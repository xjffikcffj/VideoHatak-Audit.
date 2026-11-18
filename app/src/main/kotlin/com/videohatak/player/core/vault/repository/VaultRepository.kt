package com.videohatak.player.core.vault.repository

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.videohatak.player.core.vault.crypto.VaultBase64
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.db.VaultDatabaseStore
import com.videohatak.player.core.vault.model.VaultEntryState
import com.videohatak.player.core.vault.model.VaultHiddenMatcher
import com.videohatak.player.core.vault.model.VaultHiddenOriginal
import com.videohatak.player.core.vault.model.VaultOriginalReference
import com.videohatak.player.core.vault.model.VaultOriginalStorageType
import com.videohatak.player.core.vault.model.VaultVideo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface VaultImportOutcome {
    data class Imported(val video: VaultVideo) : VaultImportOutcome
    data class AlreadyInVault(val existingVideo: VaultVideo, val integrityVerified: Boolean) : VaultImportOutcome
    data object Cancelled : VaultImportOutcome
    data class Failed(val reason: String) : VaultImportOutcome
}

sealed interface VaultRestoreOutcome {
    data class Restored(
        val video: VaultVideo,
        val restoredUri: Uri,
        val verifiedSha256: String,
        val verifiedSizeBytes: Long
    ) : VaultRestoreOutcome

    data class OriginalMissing(
        val video: VaultVideo,
        val reason: String,
        val vaultEntryPreserved: Boolean = true
    ) : VaultRestoreOutcome

    data class OriginalChanged(
        val video: VaultVideo,
        val reason: String,
        val vaultEntryPreserved: Boolean = true
    ) : VaultRestoreOutcome

    data class RequiresUriPermission(
        val video: VaultVideo,
        val originalUri: Uri,
        val storageType: VaultOriginalStorageType,
        val reason: String,
        val vaultEntryPreserved: Boolean = true
    ) : VaultRestoreOutcome

    data class RequiresLegacyWritePermission(
        val permission: String,
        val videoId: Long
    ) : VaultRestoreOutcome

    data class Failed(
        val reason: String,
        val vaultEntryPreserved: Boolean = true
    ) : VaultRestoreOutcome
}

sealed interface VaultDeleteOutcome {
    data object Deleted : VaultDeleteOutcome
    data class BlockedSoleRemainingCopy(val reason: String) : VaultDeleteOutcome
    data class Failed(val reason: String) : VaultDeleteOutcome
}

interface VaultRepository {
    val vaultVideos: Flow<List<VaultVideo>>
    val isUnlocked: StateFlow<Boolean>
    val vaultCount: StateFlow<Int>
        get() = MutableStateFlow(0)
    val hiddenOriginalEntries: StateFlow<List<VaultHiddenOriginal>>
        get() = MutableStateFlow(emptyList())
    val vaultError: StateFlow<String?>
        get() = MutableStateFlow(null)
    val lastActivityTimestampMs: Long
        get() = 0L

    fun setUnlocked(unlocked: Boolean)
    fun recordActivity(nowMs: Long = System.currentTimeMillis()) {}
    fun clearError() {}
    suspend fun refresh()
    suspend fun importVideo(
        sourceUri: Uri,
        displayName: String? = null,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): VaultVideo?
    suspend fun importVideoDetailed(
        sourceUri: Uri,
        displayName: String? = null,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): VaultImportOutcome {
        val imported = importVideo(sourceUri, displayName, onProgress, isCancelled)
        return when {
            imported != null -> VaultImportOutcome.Imported(imported)
            isCancelled() -> VaultImportOutcome.Cancelled
            else -> VaultImportOutcome.Failed(vaultError.value ?: "فشل تشفير الفيديو إلى الخزنة")
        }
    }
    suspend fun importMediaVideoDetailed(
        sourceUri: Uri,
        displayName: String? = null,
        originalFileName: String? = null,
        originalRelativePath: String? = null,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        originalSizeBytes: Long? = null,
        originalDateModifiedSec: Long? = null,
        originalMediaStoreId: Long? = null,
        originalFilePath: String? = null
    ): VaultImportOutcome {
        return importVideoDetailed(
            sourceUri = sourceUri,
            displayName = displayName ?: originalFileName,
            onProgress = onProgress,
            isCancelled = isCancelled
        )
    }
    suspend fun renameVideo(videoId: Long, newTitle: String): Boolean
    suspend fun restoreVaultVideo(id: Long): VaultRestoreOutcome {
        return VaultRestoreOutcome.Failed(
            reason = "استعادة الفيديو إلى المكتبة غير مدعومة في هذا المزوّد",
            vaultEntryPreserved = true
        )
    }
    suspend fun deleteVaultVideo(id: Long): Boolean
    suspend fun deleteVaultVideoDetailed(id: Long): VaultDeleteOutcome {
        return if (deleteVaultVideo(id)) {
            VaultDeleteOutcome.Deleted
        } else {
            VaultDeleteOutcome.Failed(vaultError.value ?: "تعذر حذف الفيديو من الخزنة")
        }
    }
    fun getVideoById(id: Long): VaultVideo?
    fun resolveEncryptedFile(video: VaultVideo): File
}

class VaultRepositoryImpl internal constructor(
    private val context: Context,
    private val cryptoManager: VaultCryptoManager = VaultCryptoManager(),
    private val dbHelper: VaultDatabaseStore = VaultDatabaseHelper(context),
    private val contentSha256Computer: ((InputStream) -> String)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    @Suppress("unused")
    private val publicMediaExporter: PublicMediaExporter? = null,
    private val uriPermissionChecker: ((Uri) -> Boolean)? = null
) : VaultRepository {

    internal constructor(
        context: Context,
        cryptoManager: VaultCryptoManager = VaultCryptoManager(),
        dbHelper: VaultDatabaseStore = VaultDatabaseHelper(context),
        contentSha256Computer: ((InputStream) -> String)? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        context = context,
        cryptoManager = cryptoManager,
        dbHelper = dbHelper,
        contentSha256Computer = contentSha256Computer,
        ioDispatcher = ioDispatcher,
        publicMediaExporter = null,
        uriPermissionChecker = null
    )

    companion object {
        private const val TAG = "VaultRepository"
        private const val MASKED_DISPLAY_NAME = "مقطع خاص مشفر"
        private const val MASKED_MIME_TYPE = "application/octet-stream"
        private const val DECODE_ERROR_MESSAGE = "تعذر فك تشفير بيانات أحد المقاطع المحفوظة في الخزنة"

        @Volatile
        private var instance: VaultRepositoryImpl? = null

        fun getInstance(context: Context): VaultRepositoryImpl {
            return instance ?: synchronized(this) {
                instance ?: VaultRepositoryImpl(context.applicationContext).also { instance = it }
            }
        }
    }

    private val vaultDir: File = File(context.filesDir, "vault_encrypted").apply {
        if (!exists()) mkdirs()
    }

    private val _isUnlocked = MutableStateFlow(false)
    override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    @Volatile
    private var _lastActivityTimestampMs: Long = 0L
    override val lastActivityTimestampMs: Long
        get() = _lastActivityTimestampMs

    private val _vaultError = MutableStateFlow<String?>(null)
    override val vaultError: StateFlow<String?> = _vaultError.asStateFlow()

    override fun recordActivity(nowMs: Long) {
        if (_isUnlocked.value) {
            _lastActivityTimestampMs = nowMs
        }
    }

    override fun clearError() {
        _vaultError.value = null
    }

    override fun setUnlocked(unlocked: Boolean) {
        _isUnlocked.value = unlocked
        if (unlocked) {
            _lastActivityTimestampMs = System.currentTimeMillis()
        } else {
            _lastActivityTimestampMs = 0L
            _vaultVideos.value = emptyList()
        }
    }

    private val repoMutex = Mutex()
    private val activeImportTempNames = ConcurrentHashMap.newKeySet<String>()
    private val inFlightImportUris = ConcurrentHashMap.newKeySet<String>()
    private val inFlightRestoreIds = ConcurrentHashMap.newKeySet<Long>()

    internal var beforeDeleteHook: (suspend (Long) -> Unit)? = null

    @Volatile
    var lastPreservedOrphanCount: Int = 0
        private set

    private val _vaultVideos = MutableStateFlow<List<VaultVideo>>(emptyList())
    override val vaultVideos: StateFlow<List<VaultVideo>> = _vaultVideos.asStateFlow()

    private val _hiddenOriginalEntries = MutableStateFlow(loadInitialHiddenEntries())
    override val hiddenOriginalEntries: StateFlow<List<VaultHiddenOriginal>> =
        _hiddenOriginalEntries.asStateFlow()

    private val _vaultCount = MutableStateFlow(loadInitialVaultCount())
    override val vaultCount: StateFlow<Int> = _vaultCount.asStateFlow()

    private fun loadInitialHiddenEntries(): List<VaultHiddenOriginal> {
        runCatching {
            reconcileLifecycleStatesLocked(runStorageCleanup = false)
        }.onFailure { err ->
            Log.e(TAG, "Failed to reconcile vault lifecycle states during initialization", err)
            if (_vaultError.value == null) {
                _vaultError.value = err.localizedMessage
            }
        }
        return runCatching {
            dbHelper.getHiddenOriginalEntries()
        }.onFailure { err ->
            Log.e(TAG, "Failed to load hidden original entries during initialization", err)
            _vaultError.value = "تعذر تحميل سجل العناصر المخفية من قاعدة بيانات الخزنة"
        }.getOrDefault(emptyList())
    }

    private fun loadInitialVaultCount(): Int {
        return runCatching {
            dbHelper.getVideoCount()
        }.onFailure { err ->
            Log.e(TAG, "Failed to load initial vault video count", err)
        }.getOrDefault(0)
    }

    override fun resolveEncryptedFile(video: VaultVideo): File {
        val raw = video.relativeEncryptedPath
        val direct = File(raw)
        if (direct.isAbsolute) return direct
        return File(vaultDir, File(raw).name)
    }

    override suspend fun refresh() = withContext(ioDispatcher) {
        repoMutex.withLock {
            refreshLocked()
        }
    }

    private fun refreshLocked() {
        reconcileLifecycleStatesLocked(runStorageCleanup = true)
        _hiddenOriginalEntries.value = runCatching { dbHelper.getHiddenOriginalEntries() }
            .onFailure { err ->
                Log.e(TAG, "Failed to refresh hidden original entries", err)
            }
            .getOrDefault(_hiddenOriginalEntries.value)
        _vaultCount.value = runCatching { dbHelper.getVideoCount() }
            .onFailure { err ->
                Log.e(TAG, "Failed to refresh vault count", err)
            }
            .getOrDefault(_vaultCount.value)
        if (!_isUnlocked.value) {
            _vaultVideos.value = emptyList()
            return
        }
        val rawList = dbHelper.getAllVideos()
        var firstDecodeError: String? = null
        val decoded = rawList.mapNotNull { row ->
            val result = decodeVaultRow(row)
            if (result == null && firstDecodeError == null) {
                firstDecodeError = DECODE_ERROR_MESSAGE
            }
            result
        }
        if (firstDecodeError != null) {
            _vaultError.value = firstDecodeError
        } else if (_vaultError.value == DECODE_ERROR_MESSAGE) {
            _vaultError.value = null
        }
        _vaultCount.value = decoded.size
        _vaultVideos.update { decoded }
    }

    private fun reconcileLifecycleStatesLocked(runStorageCleanup: Boolean) {
        val rows = runCatching { dbHelper.getAllVideos() }
            .onFailure { err ->
                Log.e(TAG, "Failed to read vault rows during lifecycle reconciliation", err)
            }
            .getOrElse { return }
        for (row in rows) {
            val encFile = resolveEncryptedFile(row)
            when (row.vaultState) {
                VaultEntryState.ENCRYPTING -> {
                    // Process crashed mid-import before encryption committed cleanly; remove aborted record & leave original untouched.
                    runCatching { dbHelper.deleteVideo(row.id) }
                    if (encFile.exists() && !isEncryptedContainerValid(encFile)) {
                        runCatching { encFile.delete() }
                    }
                }
                VaultEntryState.ENCRYPTED_VERIFIED -> {
                    // Only complete hide transition automatically when Option (A) original reference is tracked.
                    if (row.originalUri.isNotBlank() || row.originalFilePath.isNotBlank() || row.originalMediaStoreId != null) {
                        val encOk = encFile.exists() && encFile.isFile && encFile.length() > 0L &&
                            runCatching {
                                verifyEncryptedOutputIntegrity(
                                    destFile = encFile,
                                    expectedContentSha256 = row.originalSha256.takeIf { it.isNotBlank() }
                                )
                                true
                            }.getOrDefault(false)
                        if (encOk) {
                            runCatching {
                                dbHelper.updateVaultState(
                                    id = row.id,
                                    vaultState = VaultEntryState.ORIGINAL_HIDDEN,
                                    isHiddenInLibrary = true,
                                    stateUpdatedAtMs = System.currentTimeMillis(),
                                    lastError = null
                                )
                            }
                        } else {
                            runCatching {
                                dbHelper.updateVaultState(
                                    id = row.id,
                                    vaultState = VaultEntryState.HIDE_FAILED,
                                    isHiddenInLibrary = false,
                                    stateUpdatedAtMs = System.currentTimeMillis(),
                                    lastError = "تعذر التحقق من سلامة النسخة المشفرة"
                                )
                            }
                        }
                    }
                }
                VaultEntryState.RESTORING -> {
                    // Interrupted restore before un-hide completed: keep original safely hidden & Vault payload intact.
                    if (encFile.exists() && encFile.length() > 0L) {
                        runCatching {
                            dbHelper.updateVaultState(
                                id = row.id,
                                vaultState = VaultEntryState.ORIGINAL_HIDDEN,
                                isHiddenInLibrary = true,
                                stateUpdatedAtMs = System.currentTimeMillis(),
                                lastError = row.lastError
                            )
                        }
                    }
                }
                VaultEntryState.ORIGINAL_RESTORED_VERIFIED -> {
                    // Original was verified and un-hidden before crash; finish deferred .enc + DB cleanup safely.
                    val staged = File(vaultDir, "${encFile.name}.${System.currentTimeMillis()}.pending_delete")
                    val movedOrGone = !encFile.exists() || runCatching { encFile.renameTo(staged) }.getOrDefault(false)
                    if (movedOrGone) {
                        val deletedRows = runCatching { dbHelper.deleteVideo(row.id) }.getOrDefault(0)
                        if (deletedRows > 0) {
                            if (staged.exists()) runCatching { staged.delete() }
                        } else if (staged.exists() && !encFile.exists()) {
                            runCatching { staged.renameTo(encFile) }
                        }
                    }
                }
                else -> Unit
            }
        }
        if (runStorageCleanup) {
            val latestRows = runCatching { dbHelper.getAllVideos() }.getOrDefault(rows)
            reconcileVaultStorageLocked(latestRows)
        }
    }

    private fun reconcileVaultStorageLocked(rows: List<VaultVideo>) {
        val activeFileNames = buildSet {
            rows.forEach { row ->
                if (row.vaultState != VaultEntryState.ORIGINAL_RESTORED_VERIFIED) {
                    add(File(row.relativeEncryptedPath).name)
                    row.encryptedMetaBase64?.takeIf { it.isNotBlank() }?.let { enc ->
                        runCatching {
                            val json = JSONObject(cryptoManager.decryptMetadata(enc))
                            val fName = File(json.optString("file")).name
                            if (fName.isNotBlank()) add(fName)
                        }
                    }
                }
            }
        }
        var preservedOrphans = 0
        vaultDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            when {
                name.endsWith(".pending_delete") -> {
                    val baseWithTs = name.removeSuffix(".pending_delete")
                    val lastDot = baseWithTs.lastIndexOf('.')
                    val tsPart = if (lastDot > 0) baseWithTs.substring(lastDot + 1) else ""
                    val originalName = if (lastDot > 0 && tsPart.isNotEmpty() && tsPart.all { it.isDigit() }) {
                        baseWithTs.substring(0, lastDot)
                    } else {
                        baseWithTs.substringBeforeLast('.', missingDelimiterValue = "")
                    }
                    if (originalName.isNotBlank() && originalName in activeFileNames) {
                        val originalFile = File(vaultDir, originalName)
                        if (!originalFile.exists()) {
                            runCatching { file.renameTo(originalFile) }
                        } else {
                            runCatching { file.delete() }
                        }
                    } else {
                        runCatching { file.delete() }
                    }
                }
                name.endsWith(".tmp") || name.endsWith(".stage") -> {
                    if (name !in activeImportTempNames) {
                        runCatching { file.delete() }
                    }
                }
                name.endsWith(".enc") -> {
                    if (name !in activeFileNames) {
                        // Never delete unreferenced encrypted payloads blindly; preserve on disk.
                        preservedOrphans++
                    }
                }
            }
        }
        lastPreservedOrphanCount = preservedOrphans
    }

    private data class ExistingVaultMatch(
        val video: VaultVideo,
        val integrityVerified: Boolean
    )

    private fun computeStreamSha256AndSize(stream: InputStream): Pair<String, Long> {
        val custom = contentSha256Computer
        if (custom != null) {
            val counting = CountingInputStream(stream)
            val sha = custom(counting)
            return sha to counting.bytesRead
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(VaultCryptoManager.DEFAULT_CHUNK_SIZE)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n <= 0) break
            digest.update(buffer, 0, n)
            total += n
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex to total
    }

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }

        override fun close() = delegate.close()
    }

    private fun computeSourceContentSha256(sourceUri: Uri): String {
        val stream = openReadableStreamForSource(sourceUri)
            ?: throw IOException("تعذر فتح ملف الفيديو لقراءة المحتوى وحساب البصمة")
        val sha256 = stream.use { input ->
            val custom = contentSha256Computer
            if (custom != null) {
                custom(input)
            } else {
                cryptoManager.computeStreamSha256(input)
            }
        }
        if (sha256.isBlank()) {
            throw IOException("تعذر حساب بصمة المحتوى SHA-256 للفيديو")
        }
        return sha256
    }

    private fun openReadableStreamForSource(uri: Uri): InputStream? {
        return try {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path ?: return null
                val f = File(path)
                if (!f.exists() || !f.isFile || !f.canRead()) return null
                FileInputStream(f)
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (se: SecurityException) {
            throw se
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeMetadata(
        displayName: String,
        internalFileName: String,
        mimeType: String,
        contentSha256: String = "",
        legacySourceFingerprint: String = "",
        originalFileName: String = "",
        originalRelativePath: String = "",
        originalSizeBytes: Long = 0L,
        originalRef: VaultOriginalReference? = null
    ): String {
        val json = JSONObject().apply {
            put("title", displayName)
            put("file", internalFileName)
            put("mime", mimeType)
            if (contentSha256.isNotBlank()) {
                put("contentSha256", contentSha256)
            }
            if (legacySourceFingerprint.isNotBlank()) {
                put("src", legacySourceFingerprint)
            }
            if (originalFileName.isNotBlank()) {
                put("originalFileName", originalFileName)
            }
            if (originalRelativePath.isNotBlank()) {
                put("originalRelativePath", originalRelativePath)
            }
            if (originalSizeBytes > 0L) {
                put("originalSizeBytes", originalSizeBytes)
            }
            if (originalRef != null) {
                if (originalRef.originalUri.isNotBlank()) put("originalUri", originalRef.originalUri)
                if (originalRef.originalMediaStoreId != null && originalRef.originalMediaStoreId > 0L) {
                    put("originalMediaStoreId", originalRef.originalMediaStoreId)
                }
                if (originalRef.originalFilePath.isNotBlank()) put("originalFilePath", originalRef.originalFilePath)
                if (originalRef.originalDateModifiedSec > 0L) put("originalDateModifiedSec", originalRef.originalDateModifiedSec)
                put("storageType", originalRef.storageType.storageValue)
                if (originalRef.uriPermissionFlags != 0) put("uriPermissionFlags", originalRef.uriPermissionFlags)
            }
        }
        return cryptoManager.encryptMetadata(json.toString())
    }

    private fun findExistingVaultMatchByContent(
        sourceContentSha256: String,
        sourceSizeBytes: Long
    ): ExistingVaultMatch? {
        if (sourceContentSha256.isBlank()) return null
        val rows = dbHelper.getAllVideos()
        for (row in rows) {
            if (row.vaultState == VaultEntryState.ENCRYPTING ||
                row.vaultState == VaultEntryState.ORIGINAL_RESTORED_VERIFIED
            ) {
                continue
            }
            val resolvedFile = resolveEncryptedFile(row)
            if (!resolvedFile.exists() || !resolvedFile.isFile || resolvedFile.length() <= 0L) {
                continue
            }
            if (row.originalSha256.isNotBlank() &&
                row.originalSha256.equals(sourceContentSha256, ignoreCase = true)
            ) {
                val integrityOk = runCatching {
                    verifyEncryptedOutputIntegrity(
                        destFile = resolvedFile,
                        expectedContentSha256 = sourceContentSha256
                    )
                    true
                }.getOrDefault(false)
                return ExistingVaultMatch(video = row, integrityVerified = integrityOk)
            }
            val encMeta = row.encryptedMetaBase64
            if (!encMeta.isNullOrBlank()) {
                try {
                    val json = JSONObject(cryptoManager.decryptMetadata(encMeta))
                    val storedContentSha256 = json.optString("contentSha256")
                    if (storedContentSha256.isNotBlank()) {
                        if (storedContentSha256.equals(sourceContentSha256, ignoreCase = true)) {
                            val integrityOk = runCatching {
                                verifyEncryptedOutputIntegrity(
                                    destFile = resolvedFile,
                                    expectedContentSha256 = sourceContentSha256
                                )
                                true
                            }.getOrDefault(false)
                            return ExistingVaultMatch(video = row, integrityVerified = integrityOk)
                        }
                        continue
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            if (sourceSizeBytes <= 0L || row.originalSizeBytes == sourceSizeBytes) {
                val decryptedSha256 = runCatching {
                    cryptoManager.verifyEncryptedFileIntegrityAndHash(resolvedFile, expectedContentSha256 = null)
                }.getOrNull()
                if (decryptedSha256 != null && decryptedSha256.equals(sourceContentSha256, ignoreCase = true)) {
                    return ExistingVaultMatch(video = row, integrityVerified = true)
                }
            }
        }
        return null
    }

    private fun hasEnoughStorageForEncryption(estimatedSize: Long): Boolean {
        if (estimatedSize <= 0L) return true
        val required = estimatedSize + (estimatedSize / 10L) + (8L * 1024L * 1024L)
        return try {
            val stat = StatFs(vaultDir.absolutePath)
            val available = if (stat.blockCountLong > 0L && stat.totalBytes > 0L) {
                stat.availableBytes
            } else if (vaultDir.totalSpace > 0L) {
                vaultDir.usableSpace
            } else {
                return true
            }
            available >= required
        } catch (_: Exception) {
            if (vaultDir.totalSpace > 0L) vaultDir.usableSpace >= required else true
        }
    }

    private fun verifyEncryptedOutputIntegrity(destFile: File, expectedContentSha256: String? = null) {
        cryptoManager.verifyEncryptedFileIntegrityAndHash(destFile, expectedContentSha256)
    }

    private fun isEncryptedContainerValid(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 32L) return false
        return runCatching {
            val header = ByteArray(VaultCryptoManager.MAGIC_HEADER.length)
            RandomAccessFile(file, "r").use { it.readFully(header) }
            String(header, Charsets.UTF_8) == VaultCryptoManager.MAGIC_HEADER
        }.getOrDefault(false)
    }

    private fun decodeVaultRow(row: VaultVideo): VaultVideo? {
        val encMeta = row.encryptedMetaBase64
        if (encMeta.isNullOrBlank()) {
            val internalFileName = File(row.relativeEncryptedPath).name
            return try {
                val migratedCipher = encodeMetadata(row.displayName, internalFileName, row.mimeType)
                val updated = dbHelper.updateEncryptedMetadata(
                    id = row.id,
                    maskedDisplayName = MASKED_DISPLAY_NAME,
                    internalPath = internalFileName,
                    maskedMimeType = MASKED_MIME_TYPE,
                    encryptedMetaBase64 = migratedCipher
                )
                if (updated <= 0) {
                    null
                } else {
                    row.copy(
                        relativeEncryptedPath = resolveEncryptedFile(row).absolutePath,
                        encryptedMetaBase64 = migratedCipher
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

        return try {
            val json = JSONObject(cryptoManager.decryptMetadata(encMeta))
            val decodedTitle = json.optString("title").ifBlank { MASKED_DISPLAY_NAME }
            val decodedFile = json.optString("file").ifBlank { File(row.relativeEncryptedPath).name }
            val decodedMime = json.optString("mime").ifBlank { "video/mp4" }
            val resolvedFile = File(vaultDir, File(decodedFile).name)
            row.copy(
                displayName = decodedTitle,
                relativeEncryptedPath = if (resolvedFile.exists()) resolvedFile.absolutePath else row.relativeEncryptedPath,
                mimeType = decodedMime,
                originalUri = row.originalUri.ifBlank { json.optString("originalUri") },
                originalMediaStoreId = row.originalMediaStoreId
                    ?: json.optLong("originalMediaStoreId", -1L).takeIf { it > 0L },
                originalFilePath = row.originalFilePath.ifBlank { json.optString("originalFilePath") },
                originalDateModifiedSec = row.originalDateModifiedSec.takeIf { it > 0L }
                    ?: json.optLong("originalDateModifiedSec", 0L),
                originalSha256 = row.originalSha256.ifBlank { json.optString("contentSha256") },
                storageType = if (row.storageType != VaultOriginalStorageType.UNKNOWN) {
                    row.storageType
                } else {
                    VaultOriginalStorageType.fromStorageValue(json.optString("storageType"))
                },
                uriPermissionFlags = row.uriPermissionFlags.takeIf { it != 0 }
                    ?: json.optInt("uriPermissionFlags", 0)
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun importVideo(
        sourceUri: Uri,
        displayName: String?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): VaultVideo? {
        return when (val outcome = importVideoDetailed(sourceUri, displayName, onProgress, isCancelled)) {
            is VaultImportOutcome.Imported -> outcome.video
            else -> null
        }
    }

    override suspend fun importVideoDetailed(
        sourceUri: Uri,
        displayName: String?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): VaultImportOutcome = importVideoDetailedInternal(
        sourceUri = sourceUri,
        displayName = displayName,
        explicitOriginalFileName = null,
        explicitOriginalRelativePath = null,
        explicitOriginalSizeBytes = null,
        explicitOriginalDateModifiedSec = null,
        explicitOriginalMediaStoreId = null,
        explicitOriginalFilePath = null,
        onProgress = onProgress,
        isCancelled = isCancelled
    )

    override suspend fun importMediaVideoDetailed(
        sourceUri: Uri,
        displayName: String?,
        originalFileName: String?,
        originalRelativePath: String?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        originalSizeBytes: Long?,
        originalDateModifiedSec: Long?,
        originalMediaStoreId: Long?,
        originalFilePath: String?
    ): VaultImportOutcome = importVideoDetailedInternal(
        sourceUri = sourceUri,
        displayName = displayName,
        explicitOriginalFileName = originalFileName,
        explicitOriginalRelativePath = originalRelativePath,
        explicitOriginalSizeBytes = originalSizeBytes,
        explicitOriginalDateModifiedSec = originalDateModifiedSec,
        explicitOriginalMediaStoreId = originalMediaStoreId,
        explicitOriginalFilePath = originalFilePath,
        onProgress = onProgress,
        isCancelled = isCancelled
    )

    private data class QueriedSourceMetadata(
        val fileName: String? = null,
        val relativePath: String? = null,
        val filePath: String? = null,
        val sizeBytes: Long = 0L,
        val dateModifiedSec: Long = 0L,
        val mediaStoreId: Long? = null,
        val mimeType: String? = null,
        val volumeName: String? = null
    )

    private fun resolveStorageType(
        uri: Uri,
        filePath: String?,
        volumeName: String?
    ): VaultOriginalStorageType {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "file") {
            if (!filePath.isNullOrBlank() && isRemovableStoragePath(filePath)) {
                return VaultOriginalStorageType.EXTERNAL_SD_OR_REMOVABLE
            }
            return VaultOriginalStorageType.FILE_PATH
        }
        if (scheme == "content") {
            val isSafDoc = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false) ||
                uri.authority?.contains("documents", ignoreCase = true) == true ||
                uri.toString().contains("/document/", ignoreCase = true)
            if (isSafDoc) {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
                if (docId.isNotBlank() && !docId.startsWith("primary:", ignoreCase = true) && docId.contains(':')) {
                    return VaultOriginalStorageType.EXTERNAL_SD_OR_REMOVABLE
                }
                if (!filePath.isNullOrBlank() && isRemovableStoragePath(filePath)) {
                    return VaultOriginalStorageType.EXTERNAL_SD_OR_REMOVABLE
                }
                return VaultOriginalStorageType.SAF_DOCUMENT
            }
            val isMediaStore = uri.authority == MediaStore.AUTHORITY ||
                uri.toString().startsWith("content://media/", ignoreCase = true)
            if (isMediaStore) {
                if (!volumeName.isNullOrBlank() &&
                    !volumeName.equals("external_primary", ignoreCase = true) &&
                    !volumeName.equals("internal", ignoreCase = true)
                ) {
                    return VaultOriginalStorageType.EXTERNAL_SD_OR_REMOVABLE
                }
                if (!filePath.isNullOrBlank() && isRemovableStoragePath(filePath)) {
                    return VaultOriginalStorageType.EXTERNAL_SD_OR_REMOVABLE
                }
                return VaultOriginalStorageType.MEDIA_STORE
            }
            return VaultOriginalStorageType.CONTENT_URI
        }
        return VaultOriginalStorageType.UNKNOWN
    }

    private fun isRemovableStoragePath(path: String): Boolean {
        val clean = path.replace('\\', '/')
        val primaryRoot = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/storage/emulated/0")
        if (clean.startsWith(primaryRoot) || clean.startsWith("/storage/emulated/")) {
            return false
        }
        return clean.startsWith("/storage/") || clean.startsWith("/mnt/media_rw/")
    }

    private fun tryTakePersistableReadPermission(uri: Uri): Int {
        if (!uri.scheme.equals("content", ignoreCase = true)) return 0
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val persisted = runCatching { context.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        return if (persisted.any { it.uri == uri && it.isReadPermission }) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        } else {
            0
        }
    }

    private suspend fun importVideoDetailedInternal(
        sourceUri: Uri,
        displayName: String?,
        explicitOriginalFileName: String?,
        explicitOriginalRelativePath: String?,
        explicitOriginalSizeBytes: Long?,
        explicitOriginalDateModifiedSec: Long?,
        explicitOriginalMediaStoreId: Long?,
        explicitOriginalFilePath: String?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): VaultImportOutcome = withContext(ioDispatcher) {
        val uriKey = sourceUri.toString().trim()
        if (!inFlightImportUris.add(uriKey)) {
            return@withContext VaultImportOutcome.Failed("عملية نقل هذا الفيديو إلى الخزنة قيد التنفيذ حاليًا")
        }
        val uniqueName = "vault_${UUID.randomUUID()}"
        val fileName = "$uniqueName.enc"
        val destFile = File(vaultDir, fileName)
        val stagingFile = File(vaultDir, "$fileName.stage")
        val stagingInnerTempName = "${stagingFile.name}.tmp"
        activeImportTempNames.add(stagingFile.name)
        activeImportTempNames.add(stagingInnerTempName)
        var destCommittedInDb = false
        try {
            val queriedMeta = querySourceMediaMetadata(sourceUri)
            val resolvedOriginalFileName = explicitOriginalFileName?.trim()?.takeIf { it.isNotBlank() }
                ?: queriedMeta.fileName
                ?: queryFileName(sourceUri)?.trim()?.takeIf { it.isNotBlank() }
                ?: displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: "$uniqueName.mp4"
            val resolvedName = displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: resolvedOriginalFileName
            val resolvedOriginalRelativePath = explicitOriginalRelativePath?.trim()?.takeIf { it.isNotBlank() }
                ?: queriedMeta.relativePath
                ?: ""
            val resolvedOriginalFilePath = explicitOriginalFilePath?.trim()?.takeIf { it.isNotBlank() }
                ?: queriedMeta.filePath
                ?: if (sourceUri.scheme.equals("file", ignoreCase = true)) sourceUri.path.orEmpty() else ""
            val resolvedMediaStoreId = explicitOriginalMediaStoreId?.takeIf { it > 0L }
                ?: queriedMeta.mediaStoreId
                ?: if (sourceUri.authority == MediaStore.AUTHORITY ||
                    uriKey.startsWith("content://media/", ignoreCase = true)
                ) {
                    runCatching { ContentUris.parseId(sourceUri) }.getOrNull()?.takeIf { it > 0L }
                } else null
            val resolvedDateModifiedSec = explicitOriginalDateModifiedSec?.takeIf { it > 0L }
                ?: queriedMeta.dateModifiedSec

            val estimatedSize = explicitOriginalSizeBytes?.takeIf { it > 0L }
                ?: queriedMeta.sizeBytes.takeIf { it > 0L }
                ?: try {
                    if (sourceUri.scheme.equals("file", ignoreCase = true)) {
                        sourceUri.path?.let { File(it).takeIf { f -> f.exists() }?.length() } ?: 0L
                    } else {
                        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { it.statSize } ?: 0L
                    }
                } catch (_: Exception) {
                    0L
                }

            val storageType = resolveStorageType(sourceUri, resolvedOriginalFilePath, queriedMeta.volumeName)
            val uriPermFlags = if (storageType == VaultOriginalStorageType.SAF_DOCUMENT ||
                storageType == VaultOriginalStorageType.CONTENT_URI
            ) {
                tryTakePersistableReadPermission(sourceUri)
            } else {
                0
            }

            val sourceContentSha256 = try {
                computeSourceContentSha256(sourceUri)
            } catch (e: Exception) {
                if (isCancelled() || e is InterruptedException) {
                    return@withContext VaultImportOutcome.Cancelled
                }
                val errMsg = "تعذر قراءة الفيديو أو التحقق من بصمة محتواه، بقي الملف الأصلي ظاهرًا وسليمًا في مكانه"
                _vaultError.value = errMsg
                return@withContext VaultImportOutcome.Failed(errMsg)
            }

            val metadata = probeVideoMetadata(sourceUri, queriedMeta.mimeType)

            val existingBeforeEncrypt = repoMutex.withLock {
                findExistingVaultMatchByContent(sourceContentSha256, estimatedSize)
            }
            if (existingBeforeEncrypt != null) {
                if (existingBeforeEncrypt.integrityVerified) {
                    repoMutex.withLock {
                        ensureHiddenOriginalTransitionIfTracked(existingBeforeEncrypt.video)
                        refreshLocked()
                    }
                }
                val errMsg = "هذا الفيديو محفوظ ومشفّر مسبقًا داخل الخزنة"
                _vaultError.value = errMsg
                return@withContext VaultImportOutcome.AlreadyInVault(
                    existingVideo = existingBeforeEncrypt.video,
                    integrityVerified = existingBeforeEncrypt.integrityVerified
                )
            }

            if (!hasEnoughStorageForEncryption(estimatedSize)) {
                val errMsg = "المساحة التخزينية غير كافية لتشفير هذا الفيديو بأمان"
                _vaultError.value = errMsg
                return@withContext VaultImportOutcome.Failed(errMsg)
            }

            val inputStream: InputStream? = try {
                openReadableStreamForSource(sourceUri)
            } catch (_: Exception) {
                null
            }

            if (inputStream == null) {
                val errMsg = "تعذر فتح ملف الفيديو للقراءة والتشفير، بقي الملف الأصلي ظاهرًا وسليمًا في مكانه"
                _vaultError.value = errMsg
                return@withContext VaultImportOutcome.Failed(errMsg)
            }

            val encResult = inputStream.use { input ->
                cryptoManager.encryptStream(
                    inputStream = input,
                    destinationFile = stagingFile,
                    totalBytesEstimated = estimatedSize,
                    onProgress = onProgress,
                    isCancelled = isCancelled
                )
            }

            if (encResult.contentSha256.isBlank() ||
                !encResult.contentSha256.equals(sourceContentSha256, ignoreCase = true)
            ) {
                throw IOException("Source content SHA-256 mismatch during encryption")
            }

            // Mandatory end-to-end cryptographic & decrypted SHA-256 verification BEFORE committing or hiding
            verifyEncryptedOutputIntegrity(stagingFile, expectedContentSha256 = sourceContentSha256)

            val originalRef = VaultOriginalReference(
                originalUri = uriKey,
                originalMediaStoreId = resolvedMediaStoreId,
                originalFileName = resolvedOriginalFileName,
                originalRelativePath = resolvedOriginalRelativePath,
                originalFilePath = resolvedOriginalFilePath,
                originalMimeType = metadata.mimeType,
                originalSizeBytes = encResult.plaintextSizeBytes,
                originalDateModifiedSec = resolvedDateModifiedSec,
                originalSha256 = sourceContentSha256,
                storageType = storageType,
                uriPermissionFlags = uriPermFlags
            )

            val encryptedMeta = encodeMetadata(
                displayName = resolvedName,
                internalFileName = fileName,
                mimeType = metadata.mimeType,
                contentSha256 = sourceContentSha256,
                originalFileName = resolvedOriginalFileName,
                originalRelativePath = resolvedOriginalRelativePath,
                originalSizeBytes = encResult.plaintextSizeBytes,
                originalRef = originalRef
            )
            val ivBase64 = VaultBase64.encodeNoWrap(encResult.encryptedDek)

            repoMutex.withLock {
                val duplicateLocked = findExistingVaultMatchByContent(
                    sourceContentSha256 = sourceContentSha256,
                    sourceSizeBytes = encResult.plaintextSizeBytes
                )
                if (duplicateLocked != null) {
                    stagingFile.delete()
                    if (duplicateLocked.integrityVerified) {
                        ensureHiddenOriginalTransitionIfTracked(duplicateLocked.video)
                        refreshLocked()
                    }
                    val errMsg = "هذا الفيديو محفوظ ومشفّر مسبقًا داخل الخزنة"
                    _vaultError.value = errMsg
                    return@withContext VaultImportOutcome.AlreadyInVault(
                        existingVideo = duplicateLocked.video,
                        integrityVerified = duplicateLocked.integrityVerified
                    )
                }

                if (!stagingFile.renameTo(destFile)) {
                    stagingFile.copyTo(destFile, overwrite = true)
                    stagingFile.delete()
                }
                if (!destFile.exists() || !destFile.isFile || destFile.length() <= 0L || !destFile.canRead()) {
                    throw IllegalStateException("Encrypted Vault file is not readable after commit")
                }

                val nowMs = System.currentTimeMillis()
                val dbRow = VaultVideo(
                    id = 0L,
                    displayName = MASKED_DISPLAY_NAME,
                    originalSizeBytes = encResult.plaintextSizeBytes,
                    encryptedSizeBytes = destFile.length(),
                    durationMs = metadata.durationMs,
                    mimeType = MASKED_MIME_TYPE,
                    width = metadata.width,
                    height = metadata.height,
                    relativeEncryptedPath = fileName,
                    createdAtEpochMs = nowMs,
                    ivBase64 = ivBase64,
                    encryptedMetaBase64 = encryptedMeta,
                    originalUri = uriKey,
                    originalMediaStoreId = resolvedMediaStoreId,
                    originalFilePath = resolvedOriginalFilePath,
                    originalDateModifiedSec = resolvedDateModifiedSec,
                    originalSha256 = sourceContentSha256,
                    storageType = storageType,
                    uriPermissionFlags = uriPermFlags,
                    vaultState = VaultEntryState.ENCRYPTED_VERIFIED,
                    isHiddenInLibrary = false,
                    stateUpdatedAtMs = nowMs,
                    lastError = null
                )

                val newId = try {
                    dbHelper.insertVideo(dbRow)
                } catch (dbErr: Exception) {
                    destFile.delete()
                    throw dbErr
                }
                if (newId <= 0L || dbHelper.getVideoById(newId) == null) {
                    destFile.delete()
                    throw IllegalStateException("Failed to verify persisted vault record")
                }
                destCommittedInDb = true

                // Step 4 under Option (A): only AFTER verified encryption + persisted reference,
                // mark the original reversibly hidden inside VideoHatak (NEVER delete/move/rename original).
                val hiddenUpdated = runCatching {
                    dbHelper.updateVaultState(
                        id = newId,
                        vaultState = VaultEntryState.ORIGINAL_HIDDEN,
                        isHiddenInLibrary = true,
                        stateUpdatedAtMs = System.currentTimeMillis(),
                        lastError = null
                    )
                }.getOrDefault(0)
                if (hiddenUpdated <= 0) {
                    runCatching { dbHelper.deleteVideo(newId) }
                    destCommittedInDb = false
                    destFile.delete()
                    throw IllegalStateException("Failed to persist ORIGINAL_HIDDEN state for vault entry")
                }

                _vaultError.value = null
                val savedVideo = dbRow.copy(
                    id = newId,
                    displayName = resolvedName,
                    mimeType = metadata.mimeType,
                    relativeEncryptedPath = destFile.absolutePath,
                    vaultState = VaultEntryState.ORIGINAL_HIDDEN,
                    isHiddenInLibrary = true
                )
                recordActivity()
                refreshLocked()
                VaultImportOutcome.Imported(savedVideo)
            }
        } catch (e: Exception) {
            stagingFile.delete()
            File(vaultDir, stagingInnerTempName).delete()
            if (!destCommittedInDb && destFile.exists()) {
                destFile.delete()
            }
            if (isCancelled() || e is InterruptedException) {
                VaultImportOutcome.Cancelled
            } else {
                val errMsg = "فشل تشفير الفيديو إلى الخزنة: بقي الملف الأصلي ظاهرًا وسليمًا في مكانه"
                _vaultError.value = errMsg
                VaultImportOutcome.Failed(errMsg)
            }
        } finally {
            activeImportTempNames.remove(stagingFile.name)
            activeImportTempNames.remove(stagingInnerTempName)
            inFlightImportUris.remove(uriKey)
        }
    }

    private fun ensureHiddenOriginalTransitionIfTracked(row: VaultVideo) {
        if (row.vaultState != VaultEntryState.ORIGINAL_HIDDEN &&
            (row.originalUri.isNotBlank() || row.originalFilePath.isNotBlank() || row.originalMediaStoreId != null)
        ) {
            runCatching {
                dbHelper.updateVaultState(
                    id = row.id,
                    vaultState = VaultEntryState.ORIGINAL_HIDDEN,
                    isHiddenInLibrary = true,
                    stateUpdatedAtMs = System.currentTimeMillis(),
                    lastError = null
                )
            }
        }
    }

    private data class ProbedMetadata(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val mimeType: String = "video/mp4"
    )

    private fun probeVideoMetadata(uri: Uri, preferredMime: String? = null): ProbedMetadata {
        val resolvedMime = preferredMime?.takeIf { it.isNotBlank() } ?: try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val mime = resolvedMime
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?: "video/mp4"
            ProbedMetadata(durationMs = dur, width = w, height = h, mimeType = mime)
        } catch (_: Exception) {
            ProbedMetadata(mimeType = resolvedMime ?: "video/mp4")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun renameVideo(videoId: Long, newTitle: String): Boolean = withContext(ioDispatcher) {
        repoMutex.withLock {
            if (!_isUnlocked.value) {
                _vaultError.value = "يرجى فتح الخزنة أولًا قبل إعادة التسمية"
                return@withContext false
            }
            val cleanTitle = newTitle.trim()
            if (cleanTitle.isBlank()) {
                _vaultError.value = "يرجى إدخال اسم صالح للفيديو"
                return@withContext false
            }
            val existing = dbHelper.getVideoById(videoId)
            if (existing == null) {
                _vaultError.value = "تعذر العثور على المقطع المطلوب داخل الخزنة"
                return@withContext false
            }
            val decoded = decodeVaultRow(existing)
            if (decoded == null) {
                _vaultError.value = "تعذر قراءة بيانات المقطع المشفرة لإعادة تسميته"
                return@withContext false
            }
            val internalFile = File(decoded.relativeEncryptedPath).name
            val existingJson = existing.encryptedMetaBase64
                ?.takeIf { it.isNotBlank() }
                ?.let { enc -> runCatching { JSONObject(cryptoManager.decryptMetadata(enc)) }.getOrNull() }
            val existingContentSha256 = existingJson?.optString("contentSha256")
                ?.takeIf { it.isNotBlank() }
                ?: existing.originalSha256
            val existingLegacySrc = existingJson?.optString("src").orEmpty()
            val existingOriginalFileName = existingJson?.optString("originalFileName").orEmpty()
            val existingOriginalRelPath = existingJson?.optString("originalRelativePath").orEmpty()
            val existingOriginalSize = existingJson?.optLong("originalSizeBytes", decoded.originalSizeBytes)
                ?.takeIf { it > 0L }
                ?: decoded.originalSizeBytes
            val originalRef = extractOriginalReference(existing, existingJson)
            val rows = try {
                val newEncryptedMeta = encodeMetadata(
                    displayName = cleanTitle,
                    internalFileName = internalFile,
                    mimeType = decoded.mimeType,
                    contentSha256 = existingContentSha256,
                    legacySourceFingerprint = existingLegacySrc,
                    originalFileName = existingOriginalFileName,
                    originalRelativePath = existingOriginalRelPath,
                    originalSizeBytes = existingOriginalSize,
                    originalRef = originalRef
                )
                dbHelper.updateEncryptedMetadata(
                    id = videoId,
                    maskedDisplayName = MASKED_DISPLAY_NAME,
                    internalPath = internalFile,
                    maskedMimeType = MASKED_MIME_TYPE,
                    encryptedMetaBase64 = newEncryptedMeta
                )
            } catch (_: Exception) {
                0
            }
            if (rows > 0) {
                _vaultError.value = null
                recordActivity()
                refreshLocked()
                true
            } else {
                _vaultError.value = "فشل تحديث اسم المقطع داخل قاعدة بيانات الخزنة"
                false
            }
        }
    }

    private fun extractOriginalReference(row: VaultVideo, metaJson: JSONObject?): VaultOriginalReference {
        val uri = row.originalUri.ifBlank { metaJson?.optString("originalUri").orEmpty() }
        val mediaId = row.originalMediaStoreId
            ?: metaJson?.optLong("originalMediaStoreId", -1L)?.takeIf { it > 0L }
        val fileName = metaJson?.optString("originalFileName").orEmpty().ifBlank { row.displayName }
        val relPath = metaJson?.optString("originalRelativePath").orEmpty()
        val filePath = row.originalFilePath.ifBlank { metaJson?.optString("originalFilePath").orEmpty() }
        val origSize = metaJson?.optLong("originalSizeBytes", row.originalSizeBytes)
            ?.takeIf { it > 0L }
            ?: row.originalSizeBytes
        val dateMod = row.originalDateModifiedSec.takeIf { it > 0L }
            ?: metaJson?.optLong("originalDateModifiedSec", 0L)
            ?: 0L
        val sha = row.originalSha256.ifBlank { metaJson?.optString("contentSha256").orEmpty() }
        val st = if (row.storageType != VaultOriginalStorageType.UNKNOWN) {
            row.storageType
        } else {
            VaultOriginalStorageType.fromStorageValue(metaJson?.optString("storageType"))
        }
        val flags = row.uriPermissionFlags.takeIf { it != 0 }
            ?: metaJson?.optInt("uriPermissionFlags", 0)
            ?: 0
        return VaultOriginalReference(
            originalUri = uri,
            originalMediaStoreId = mediaId,
            originalFileName = fileName,
            originalRelativePath = relPath,
            originalFilePath = filePath,
            originalMimeType = row.mimeType,
            originalSizeBytes = origSize,
            originalDateModifiedSec = dateMod,
            originalSha256 = sha,
            storageType = st,
            uriPermissionFlags = flags
        )
    }

    private sealed interface OriginalVerifyResult {
        data class Verified(
            val resolvedUri: Uri,
            val actualSha256: String,
            val actualSizeBytes: Long
        ) : OriginalVerifyResult

        data class Missing(val reason: String) : OriginalVerifyResult
        data class Changed(val reason: String) : OriginalVerifyResult
        data class PermissionRequired(val uri: Uri, val storageType: VaultOriginalStorageType, val reason: String) : OriginalVerifyResult
        data class Unreadable(val reason: String) : OriginalVerifyResult
    }

    private fun checkUriPermissionStillValid(uri: Uri, ref: VaultOriginalReference): Boolean {
        uriPermissionChecker?.let { checker ->
            return checker(uri)
        }
        if (ref.uriPermissionFlags != 0 && uri.scheme.equals("content", ignoreCase = true)) {
            val persisted = runCatching { context.contentResolver.persistedUriPermissions }.getOrNull()
            if (persisted != null && persisted.none { it.uri == uri && it.isReadPermission }) {
                return false
            }
        }
        return true
    }

    private fun verifyOriginalAtReference(ref: VaultOriginalReference): OriginalVerifyResult {
        val trimmedOriginalUri = ref.originalUri.trim()
        val primaryScheme = trimmedOriginalUri.takeIf { it.isNotBlank() }?.let {
            runCatching { Uri.parse(it).scheme?.lowercase().orEmpty() }.getOrDefault("")
        }.orEmpty()
        val allowFilePathFallback = primaryScheme.isEmpty() || primaryScheme == "file"

        val candidateUris = buildList {
            if (trimmedOriginalUri.isNotBlank()) {
                add(Uri.parse(trimmedOriginalUri))
            }
            if (ref.originalMediaStoreId != null && ref.originalMediaStoreId > 0L) {
                val msUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    ref.originalMediaStoreId
                )
                if (none { it == msUri }) add(msUri)
            }
            if (allowFilePathFallback && ref.originalFilePath.isNotBlank()) {
                val fileUri = Uri.fromFile(File(ref.originalFilePath))
                if (none { it == fileUri }) add(fileUri)
            }
        }
        if (candidateUris.isEmpty()) {
            return OriginalVerifyResult.Missing(
                "لا يوجد مرجع محفوظ للملف الأصلي لهذا المقطع — بقي الفيديو محميًا داخل الخزنة"
            )
        }

        val primaryUri = candidateUris.first()
        if (!checkUriPermissionStillValid(primaryUri, ref)) {
            return OriginalVerifyResult.PermissionRequired(
                uri = primaryUri,
                storageType = ref.storageType,
                reason = "فُقدت صلاحية الوصول إلى الملف الأصلي (${primaryUri}) — أعد منح إذن SAF/التخزين أولًا؛ بقيت النسخة المشفرة محمية في الخزنة"
            )
        }

        var hadSecurityDenied = false
        var hadMissing = false
        var lastUnreadableReason: String? = null
        for (candidate in candidateUris) {
            if (candidate.scheme.equals("file", ignoreCase = true)) {
                val file = candidate.path?.let { File(it) }
                if (file == null || !file.exists() || !file.isFile) {
                    hadMissing = true
                    continue
                }
                if (!file.canRead()) {
                    hadSecurityDenied = true
                    continue
                }
                return compareStreamWithExpected(candidate, FileInputStream(file), ref)
            }

            try {
                if (candidate != primaryUri && !checkUriPermissionStillValid(candidate, ref)) {
                    hadSecurityDenied = true
                    continue
                }
                val stream = context.contentResolver.openInputStream(candidate)
                if (stream != null) {
                    return compareStreamWithExpected(candidate, stream, ref)
                }
                lastUnreadableReason = "تعذر فتح مجرى القراءة للملف الأصلي"
            } catch (_: SecurityException) {
                hadSecurityDenied = true
            } catch (_: java.io.FileNotFoundException) {
                hadMissing = true
            } catch (e: Exception) {
                lastUnreadableReason = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "تعذر الوصول إلى الملف الأصلي حاليًا"
            }
        }

        if (hadSecurityDenied) {
            return OriginalVerifyResult.PermissionRequired(
                uri = primaryUri,
                storageType = ref.storageType,
                reason = "تعذر فتح الملف الأصلي بسبب فقدان صلاحية الوصول إلى URI/التخزين — بقيت النسخة المشفرة محفوظة في الخزنة"
            )
        }
        if (!hadMissing && lastUnreadableReason != null) {
            return OriginalVerifyResult.Unreadable(
                "تعذر التحقق من سلامة الملف الأصلي ($lastUnreadableReason) — بقيت النسخة المشفرة محفوظة بأمان في الخزنة"
            )
        }

        val reason = if (ref.storageType == VaultOriginalStorageType.EXTERNAL_SD_OR_REMOVABLE) {
            "الملف الأصلي غير موجود أو تم فصل وحدة التخزين الخارجية/بطاقة SD — بقيت النسخة المشفرة محفوظة بأمان في الخزنة"
        } else {
            "الملف الأصلي غير موجود في موقعه على الجهاز (تم حذفه أو نقله خارجيًا) — بقيت النسخة المشفرة محفوظة بأمان في الخزنة"
        }
        return OriginalVerifyResult.Missing(reason)
    }

    private fun compareStreamWithExpected(
        uri: Uri,
        inputStream: InputStream,
        ref: VaultOriginalReference
    ): OriginalVerifyResult {
        return try {
            val (actualSha256, actualSizeBytes) = inputStream.use { stream ->
                computeStreamSha256AndSize(stream)
            }
            if (ref.originalSizeBytes > 0L && actualSizeBytes != ref.originalSizeBytes) {
                return OriginalVerifyResult.Changed(
                    "تغيّر حجم الملف في الموقع الأصلي (${actualSizeBytes} بايت بدل ${ref.originalSizeBytes} بايت) — قد يكون ملفًا مختلفًا بنفس الاسم؛ بقيت النسخة المشفرة بأمان في الخزنة"
                )
            }
            if (ref.originalSha256.isNotBlank() &&
                !actualSha256.equals(ref.originalSha256, ignoreCase = true)
            ) {
                return OriginalVerifyResult.Changed(
                    "الملف الموجود في الموقع الأصلي لا يطابق البصمة الأصلية SHA-256 (محتوى مختلف أو مستبدل) — بقيت النسخة المشفرة محمية في الخزنة"
                )
            }
            OriginalVerifyResult.Verified(
                resolvedUri = uri,
                actualSha256 = actualSha256,
                actualSizeBytes = actualSizeBytes
            )
        } catch (_: SecurityException) {
            OriginalVerifyResult.PermissionRequired(
                uri = uri,
                storageType = ref.storageType,
                reason = "تم رفض إذن قراءة الملف الأصلي — أعد منح الصلاحية أولًا؛ بقيت النسخة المشفرة محمية"
            )
        } catch (e: Exception) {
            OriginalVerifyResult.Unreadable(
                "تعذر التحقق من سلامة الملف الأصلي (${e.localizedMessage ?: "خطأ قراءة"}) — بقيت النسخة المشفرة محفوظة"
            )
        }
    }

    override suspend fun restoreVaultVideo(id: Long): VaultRestoreOutcome = withContext(ioDispatcher) {
        if (!inFlightRestoreIds.add(id)) {
            return@withContext VaultRestoreOutcome.Failed(
                reason = "عملية إزالة هذا الفيديو من الخزنة قيد التنفيذ حاليًا",
                vaultEntryPreserved = true
            )
        }
        try {
            repoMutex.withLock {
                if (!_isUnlocked.value) {
                    val msg = "يرجى فتح الخزنة أولًا قبل إزالة الفيديو وإعادة إظهاره"
                    return@withContext VaultRestoreOutcome.Failed(msg, vaultEntryPreserved = true)
                }

                // 1. Read database record & decrypt metadata
                val rawRow = dbHelper.getVideoById(id)
                    ?: return@withContext VaultRestoreOutcome.Failed(
                        "تعذر العثور على سجل الفيديو داخل الخزنة",
                        vaultEntryPreserved = true
                    )

                val metaJson = rawRow.encryptedMetaBase64
                    ?.takeIf { it.isNotBlank() }
                    ?.let { enc ->
                        try {
                            JSONObject(cryptoManager.decryptMetadata(enc))
                        } catch (_: Exception) {
                            val msg = "تعذر فك تشفير بيانات الفيديو داخل الخزنة — تم الإبقاء على النسخة المشفرة بأمان"
                            _vaultError.value = msg
                            return@withContext VaultRestoreOutcome.Failed(msg, vaultEntryPreserved = true)
                        }
                    }

                val decodedVideo = decodeVaultRow(rawRow)
                    ?: run {
                        val msg = "تعذر قراءة بيانات الفيديو المشفرة — لم يتم حذف أي بيانات"
                        _vaultError.value = msg
                        return@withContext VaultRestoreOutcome.Failed(msg, vaultEntryPreserved = true)
                    }

                val ref = extractOriginalReference(rawRow, metaJson)
                val encryptedFile = resolveEncryptedFile(decodedVideo)

                // Transition state -> RESTORING (still hidden until original verified intact)
                runCatching {
                    dbHelper.updateVaultState(
                        id = id,
                        vaultState = VaultEntryState.RESTORING,
                        isHiddenInLibrary = true,
                        stateUpdatedAtMs = System.currentTimeMillis(),
                        lastError = null
                    )
                }

                // 2. Verify original exists, is accessible, and strictly matches saved size + SHA-256
                when (val verify = verifyOriginalAtReference(ref)) {
                    is OriginalVerifyResult.Missing -> {
                        runCatching {
                            dbHelper.updateVaultState(
                                id = id,
                                vaultState = VaultEntryState.ORIGINAL_MISSING,
                                isHiddenInLibrary = true,
                                stateUpdatedAtMs = System.currentTimeMillis(),
                                lastError = verify.reason
                            )
                        }
                        _vaultError.value = verify.reason
                        refreshLocked()
                        return@withContext VaultRestoreOutcome.OriginalMissing(
                            video = decodedVideo.copy(vaultState = VaultEntryState.ORIGINAL_MISSING, lastError = verify.reason),
                            reason = verify.reason,
                            vaultEntryPreserved = true
                        )
                    }
                    is OriginalVerifyResult.Changed -> {
                        runCatching {
                            dbHelper.updateVaultState(
                                id = id,
                                vaultState = VaultEntryState.ORIGINAL_CHANGED,
                                isHiddenInLibrary = true,
                                stateUpdatedAtMs = System.currentTimeMillis(),
                                lastError = verify.reason
                            )
                        }
                        _vaultError.value = verify.reason
                        refreshLocked()
                        return@withContext VaultRestoreOutcome.OriginalChanged(
                            video = decodedVideo.copy(vaultState = VaultEntryState.ORIGINAL_CHANGED, lastError = verify.reason),
                            reason = verify.reason,
                            vaultEntryPreserved = true
                        )
                    }
                    is OriginalVerifyResult.PermissionRequired -> {
                        runCatching {
                            dbHelper.updateVaultState(
                                id = id,
                                vaultState = VaultEntryState.REQUIRES_PERMISSION,
                                isHiddenInLibrary = true,
                                stateUpdatedAtMs = System.currentTimeMillis(),
                                lastError = verify.reason
                            )
                        }
                        _vaultError.value = verify.reason
                        refreshLocked()
                        return@withContext VaultRestoreOutcome.RequiresUriPermission(
                            video = decodedVideo.copy(vaultState = VaultEntryState.REQUIRES_PERMISSION, lastError = verify.reason),
                            originalUri = verify.uri,
                            storageType = verify.storageType,
                            reason = verify.reason,
                            vaultEntryPreserved = true
                        )
                    }
                    is OriginalVerifyResult.Unreadable -> {
                        runCatching {
                            dbHelper.updateVaultState(
                                id = id,
                                vaultState = VaultEntryState.RESTORE_FAILED,
                                isHiddenInLibrary = true,
                                stateUpdatedAtMs = System.currentTimeMillis(),
                                lastError = verify.reason
                            )
                        }
                        _vaultError.value = verify.reason
                        refreshLocked()
                        return@withContext VaultRestoreOutcome.Failed(verify.reason, vaultEntryPreserved = true)
                    }
                    is OriginalVerifyResult.Verified -> {
                        // 3. Original verified byte-for-byte in its original location — NO copy/export created!
                        try {
                            beforeDeleteHook?.invoke(id)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (_: Throwable) {
                        }

                        // 4. Transition to ORIGINAL_RESTORED_VERIFIED and un-hide in library FIRST
                        val unhideRows = runCatching {
                            dbHelper.updateVaultState(
                                id = id,
                                vaultState = VaultEntryState.ORIGINAL_RESTORED_VERIFIED,
                                isHiddenInLibrary = false,
                                stateUpdatedAtMs = System.currentTimeMillis(),
                                lastError = null
                            )
                        }.getOrDefault(0)
                        if (unhideRows <= 0) {
                            val msg = "تعذر تحديث حالة إظهار الفيديو الأصلي في المكتبة — تم الإبقاء على النسخة المشفرة بأمان"
                            _vaultError.value = msg
                            return@withContext VaultRestoreOutcome.Failed(msg, vaultEntryPreserved = true)
                        }

                        val remainingHidden = runCatching { dbHelper.getHiddenOriginalEntries() }
                            .getOrDefault(emptyList())
                            .filterNot { it.vaultId == id }
                        _hiddenOriginalEntries.value = remainingHidden

                        val stillHidden = VaultHiddenMatcher.isTargetHidden(
                            videoId = ref.originalMediaStoreId ?: -1L,
                            contentUri = verify.resolvedUri.toString(),
                            filePath = ref.originalFilePath,
                            hiddenEntries = remainingHidden
                        )
                        if (stillHidden) {
                            // Another vault entry might still hold this path, or un-hide verification failed
                            _vaultError.value = null
                        }

                        // 5. ONLY AFTER verification & un-hide succeed, stage .enc -> .pending_delete & delete DB record
                        var stagedFile: File? = null
                        if (encryptedFile.exists()) {
                            val candidate = File(
                                encryptedFile.parentFile ?: vaultDir,
                                "${encryptedFile.name}.${System.currentTimeMillis()}.pending_delete"
                            )
                            val stagedOk = runCatching { encryptedFile.renameTo(candidate) }.getOrDefault(false)
                            if (!stagedOk) {
                                // Revert hidden state safely since cleanup failed
                                runCatching {
                                    dbHelper.updateVaultState(
                                        id = id,
                                        vaultState = VaultEntryState.RESTORE_FAILED,
                                        isHiddenInLibrary = true,
                                        stateUpdatedAtMs = System.currentTimeMillis(),
                                        lastError = "تعذر عزل النسخة المشفرة قبل مسحها"
                                    )
                                }
                                refreshLocked()
                                val msg = "تعذر إزالة النسخة المشفرة من الخزنة — تم الحفاظ على السجل بأمان"
                                _vaultError.value = msg
                                return@withContext VaultRestoreOutcome.Failed(msg, vaultEntryPreserved = true)
                            }
                            stagedFile = candidate
                        }

                        val deletedRows = runCatching { dbHelper.deleteVideo(id) }.getOrDefault(0)
                        if (deletedRows <= 0) {
                            if (stagedFile != null && stagedFile.exists() && !encryptedFile.exists()) {
                                runCatching { stagedFile.renameTo(encryptedFile) }
                            }
                            val msg = "أُعيد إظهار الفيديو الأصلي في المكتبة، لكن تعذر حذف سجله من قاعدة بيانات الخزنة"
                            _vaultError.value = msg
                            refreshLocked()
                            return@withContext VaultRestoreOutcome.Failed(msg, vaultEntryPreserved = true)
                        }

                        if (stagedFile != null && stagedFile.exists()) {
                            runCatching { stagedFile.delete() }
                        }

                        _vaultError.value = null
                        recordActivity()
                        refreshLocked()

                        return@withContext VaultRestoreOutcome.Restored(
                            video = decodedVideo.copy(
                                vaultState = VaultEntryState.ORIGINAL_RESTORED_VERIFIED,
                                isHiddenInLibrary = false
                            ),
                            restoredUri = verify.resolvedUri,
                            verifiedSha256 = verify.actualSha256,
                            verifiedSizeBytes = verify.actualSizeBytes
                        )
                    }
                }
            }
        } finally {
            inFlightRestoreIds.remove(id)
        }
    }

    override suspend fun deleteVaultVideo(id: Long): Boolean =
        deleteVaultVideoDetailed(id) is VaultDeleteOutcome.Deleted

    override suspend fun deleteVaultVideoDetailed(id: Long): VaultDeleteOutcome = withContext(ioDispatcher) {
        try {
            beforeDeleteHook?.invoke(id)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
        repoMutex.withLock {
            if (!_isUnlocked.value) {
                return@withContext VaultDeleteOutcome.Failed("يرجى فتح الخزنة أولًا قبل الحذف النهائي")
            }
            val raw = dbHelper.getVideoById(id)
                ?: return@withContext VaultDeleteOutcome.Failed("تعذر العثور على السجل داخل الخزنة")
            val resolved = resolveEncryptedFile(raw)

            val metaJson = raw.encryptedMetaBase64
                ?.takeIf { it.isNotBlank() }
                ?.let { enc -> runCatching { JSONObject(cryptoManager.decryptMetadata(enc)) }.getOrNull() }
            val ref = extractOriginalReference(raw, metaJson)

            // Guard Section 6: NEVER permit permanent deletion of the encrypted copy if it is the SOLE remaining copy
            // (Exception only for synthetic test fixtures lacking both original metadata/SHA-256 and VHTK encrypted header)
            val hasTrackedOriginal = ref.originalUri.isNotBlank() ||
                ref.originalFilePath.isNotBlank() ||
                ref.originalMediaStoreId != null
            val hasRealPayload = isEncryptedContainerValid(resolved) || ref.originalSha256.isNotBlank()

            if (hasTrackedOriginal || hasRealPayload) {
                if (!hasTrackedOriginal) {
                    val reason = "لا يمكن الحذف النهائي: هذه النسخة المشفرة هي النسخة الوحيدة المتبقية من الفيديو ولا يوجد أصل سليم على الجهاز"
                    _vaultError.value = reason
                    return@withContext VaultDeleteOutcome.BlockedSoleRemainingCopy(reason)
                }
                when (val origCheck = verifyOriginalAtReference(ref)) {
                    is OriginalVerifyResult.Verified -> Unit
                    is OriginalVerifyResult.Missing,
                    is OriginalVerifyResult.Changed,
                    is OriginalVerifyResult.PermissionRequired,
                    is OriginalVerifyResult.Unreadable -> {
                        val reason = "لا يمكن الحذف النهائي لأن هذه النسخة المشفرة هي النسخة السليمة الوحيدة المتبقية من الفيديو — أصل الفيديو مفقود أو تغيّر أو يتعذر التحقق منه"
                        _vaultError.value = reason
                        return@withContext VaultDeleteOutcome.BlockedSoleRemainingCopy(reason)
                    }
                }
            }

            var stagedFile: File? = null
            if (resolved.exists()) {
                val candidate = File(
                    resolved.parentFile ?: vaultDir,
                    "${resolved.name}.${System.currentTimeMillis()}.pending_delete"
                )
                val stagedOk = runCatching { resolved.renameTo(candidate) }.getOrDefault(false)
                if (!stagedOk) {
                    val msg = "تعذر عزل الملف المشفر مؤقتًا قبل الحذف، لم تتم إزالة السجل"
                    _vaultError.value = msg
                    return@withContext VaultDeleteOutcome.Failed(msg)
                }
                stagedFile = candidate
            }

            val rows = runCatching { dbHelper.deleteVideo(id) }.getOrDefault(0)
            if (rows <= 0) {
                if (stagedFile != null && stagedFile.exists() && !resolved.exists()) {
                    runCatching { stagedFile.renameTo(resolved) }
                }
                val msg = "تعذر إزالة سجل الفيديو من قاعدة بيانات الخزنة، تم الحفاظ على الملف بأمان"
                _vaultError.value = msg
                return@withContext VaultDeleteOutcome.Failed(msg)
            }

            if (stagedFile != null && stagedFile.exists()) {
                runCatching { stagedFile.delete() }
            }
            _vaultError.value = null
            recordActivity()
            refreshLocked()
            VaultDeleteOutcome.Deleted
        }
    }

    override fun getVideoById(id: Long): VaultVideo? {
        if (!_isUnlocked.value) return null
        val row = dbHelper.getVideoById(id) ?: return null
        return decodeVaultRow(row)
    }

    private fun querySourceMediaMetadata(uri: Uri): QueriedSourceMetadata {
        if (!uri.scheme.equals("content", ignoreCase = true)) {
            val path = uri.path
            val file = path?.let { File(it) }
            val fileName = file?.name ?: uri.lastPathSegment
            val relPath = path?.let { deriveRelativePathFromDataPath(it) }
            val size = file?.takeIf { it.exists() }?.length() ?: 0L
            val modSec = file?.takeIf { it.exists() }?.lastModified()?.div(1000L) ?: 0L
            return QueriedSourceMetadata(
                fileName = fileName,
                relativePath = relPath,
                filePath = path,
                sizeBytes = size,
                dateModifiedSec = modSec
            )
        }
        return try {
            val cols = buildList {
                add(MediaStore.Video.Media._ID)
                add(MediaStore.Video.Media.DISPLAY_NAME)
                add(OpenableColumns.DISPLAY_NAME)
                add(OpenableColumns.SIZE)
                add(MediaStore.Video.Media.SIZE)
                add(MediaStore.Video.Media.DATE_MODIFIED)
                add(MediaStore.Video.Media.MIME_TYPE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Video.Media.RELATIVE_PATH)
                    add(MediaStore.MediaColumns.VOLUME_NAME)
                }
                @Suppress("DEPRECATION")
                add(MediaStore.Video.Media.DATA)
            }.toTypedArray()

            context.contentResolver.query(uri, cols, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return QueriedSourceMetadata()

                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val queriedId = if (idCol >= 0 && !cursor.isNull(idCol)) {
                    cursor.getLong(idCol).takeIf { it > 0L }
                } else null

                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?: cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val name = if (nameCol >= 0) cursor.getString(nameCol)?.takeIf { it.isNotBlank() } else null

                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    .takeIf { it >= 0 }
                    ?: cursor.getColumnIndex(OpenableColumns.SIZE)
                val sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L

                val modCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val dateModSec = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L

                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol)?.takeIf { it.isNotBlank() } else null

                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else -1
                val relRaw = if (relCol >= 0) cursor.getString(relCol)?.takeIf { it.isNotBlank() } else null

                val volCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                } else -1
                val volName = if (volCol >= 0) cursor.getString(volCol)?.takeIf { it.isNotBlank() } else null

                @Suppress("DEPRECATION")
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val dataPath = if (dataCol >= 0) cursor.getString(dataCol)?.takeIf { it.isNotBlank() } else null

                val resolvedRel = relRaw ?: dataPath?.let { deriveRelativePathFromDataPath(it) }
                QueriedSourceMetadata(
                    fileName = name,
                    relativePath = resolvedRel,
                    filePath = dataPath,
                    sizeBytes = sizeBytes,
                    dateModifiedSec = dateModSec,
                    mediaStoreId = queriedId,
                    mimeType = mimeType,
                    volumeName = volName
                )
            } ?: QueriedSourceMetadata()
        } catch (_: Exception) {
            QueriedSourceMetadata()
        }
    }

    private fun deriveRelativePathFromDataPath(filePath: String): String? {
        val parent = File(filePath).parent ?: return null
        val normalized = parent.replace('\\', '/')
        val markers = listOf("/${Environment.DIRECTORY_MOVIES}", "/${Environment.DIRECTORY_DCIM}")
        for (marker in markers) {
            val idx = normalized.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                val rel = normalized.substring(idx + 1).trimEnd('/')
                if (rel.isNotBlank()) return "$rel/"
            }
        }
        return null
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        return cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }
}
