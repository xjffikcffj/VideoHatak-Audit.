package com.videohatak.player.core.vault.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.videohatak.player.core.vault.crypto.VaultBase64
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.db.VaultDatabaseStore
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
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface VaultImportOutcome {
    data class Imported(val video: VaultVideo) : VaultImportOutcome
    data class AlreadyInVault(val existingVideo: VaultVideo, val integrityVerified: Boolean) : VaultImportOutcome
    data object Cancelled : VaultImportOutcome
    data class Failed(val reason: String) : VaultImportOutcome
}

interface VaultRepository {
    val vaultVideos: Flow<List<VaultVideo>>
    val isUnlocked: StateFlow<Boolean>
    val vaultCount: StateFlow<Int>
        get() = MutableStateFlow(0)
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
    suspend fun renameVideo(videoId: Long, newTitle: String): Boolean
    suspend fun deleteVaultVideo(id: Long): Boolean
    fun getVideoById(id: Long): VaultVideo?
    fun resolveEncryptedFile(video: VaultVideo): File
}

class VaultRepositoryImpl internal constructor(
    private val context: Context,
    private val cryptoManager: VaultCryptoManager = VaultCryptoManager(),
    private val dbHelper: VaultDatabaseStore = VaultDatabaseHelper(context),
    private val contentSha256Computer: ((InputStream) -> String)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : VaultRepository {

    companion object {
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

    internal var beforeDeleteHook: (suspend (Long) -> Unit)? = null

    @Volatile
    var lastPreservedOrphanCount: Int = 0
        private set

    private val _vaultVideos = MutableStateFlow<List<VaultVideo>>(emptyList())
    override val vaultVideos: Flow<List<VaultVideo>> = _vaultVideos.asStateFlow()

    private val _vaultCount = MutableStateFlow(
        runCatching { dbHelper.getVideoCount() }.getOrDefault(0)
    )
    override val vaultCount: StateFlow<Int> = _vaultCount.asStateFlow()

    private val vaultDir: File by lazy {
        File(context.filesDir, "vault_encrypted").apply {
            if (!exists()) mkdirs()
        }
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
        _vaultCount.value = runCatching { dbHelper.getVideoCount() }.getOrDefault(_vaultCount.value)
        if (!_isUnlocked.value) {
            _vaultVideos.value = emptyList()
            return
        }
        val rawList = dbHelper.getAllVideos()
        reconcileVaultStorageLocked(rawList)
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

    private fun reconcileVaultStorageLocked(rows: List<VaultVideo>) {
        val activeFileNames = buildSet {
            rows.forEach { row ->
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

    private fun computeSourceContentSha256(sourceUri: Uri): String {
        val stream = context.contentResolver.openInputStream(sourceUri)
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

    private fun encodeMetadata(
        displayName: String,
        internalFileName: String,
        mimeType: String,
        contentSha256: String = "",
        legacySourceFingerprint: String = ""
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
            val resolvedFile = resolveEncryptedFile(row)
            if (!resolvedFile.exists() || !resolvedFile.isFile || resolvedFile.length() <= 0L) {
                continue
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

            // Legacy entries without contentSha256 in metadata must only match via actual decrypted SHA-256,
            // never by URI string or (title, size, duration) alone.
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

    private fun decodeVaultRow(row: VaultVideo): VaultVideo? {
        val encMeta = row.encryptedMetaBase64
        if (encMeta.isNullOrBlank()) {
            // Migrate legacy plaintext row in-place; NEVER expose unencrypted row if migration fails
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
                mimeType = decodedMime
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
    ): VaultImportOutcome = withContext(ioDispatcher) {
        val uniqueName = "vault_${UUID.randomUUID()}"
        val fileName = "$uniqueName.enc"
        val destFile = File(vaultDir, fileName)
        val stagingFile = File(vaultDir, "$fileName.stage")
        val stagingInnerTempName = "${stagingFile.name}.tmp"
        val resolvedName = displayName?.ifBlank { null } ?: queryFileName(sourceUri) ?: "$uniqueName.mp4"
        val estimatedSize = try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }

        val sourceContentSha256 = try {
            computeSourceContentSha256(sourceUri)
        } catch (e: Exception) {
            if (isCancelled() || e is InterruptedException) {
                return@withContext VaultImportOutcome.Cancelled
            }
            val errMsg = "تعذر قراءة الفيديو أو التحقق من بصمة محتواه، بقي الملف الأصلي سليمًا في مكانه"
            _vaultError.value = errMsg
            return@withContext VaultImportOutcome.Failed(errMsg)
        }

        val metadata = probeVideoMetadata(sourceUri)

        val existingBeforeEncrypt = repoMutex.withLock {
            findExistingVaultMatchByContent(sourceContentSha256, estimatedSize)
        }
        if (existingBeforeEncrypt != null) {
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
            context.contentResolver.openInputStream(sourceUri)
        } catch (_: Exception) {
            null
        }

        if (inputStream == null) {
            val errMsg = "تعذر فتح ملف الفيديو للقراءة والتشفير، بقي الملف الأصلي سليمًا في مكانه"
            _vaultError.value = errMsg
            return@withContext VaultImportOutcome.Failed(errMsg)
        }

        activeImportTempNames.add(stagingFile.name)
        activeImportTempNames.add(stagingInnerTempName)
        var destCommittedInDb = false
        try {
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

            // Mandatory end-to-end cryptographic & decrypted SHA-256 verification before touching original
            verifyEncryptedOutputIntegrity(stagingFile, expectedContentSha256 = sourceContentSha256)

            val encryptedMeta = encodeMetadata(
                displayName = resolvedName,
                internalFileName = fileName,
                mimeType = metadata.mimeType,
                contentSha256 = sourceContentSha256
            )
            val ivBase64 = VaultBase64.encodeNoWrap(encResult.encryptedDek)

            repoMutex.withLock {
                val duplicateLocked = findExistingVaultMatchByContent(
                    sourceContentSha256 = sourceContentSha256,
                    sourceSizeBytes = encResult.plaintextSizeBytes
                )
                if (duplicateLocked != null) {
                    stagingFile.delete()
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
                    createdAtEpochMs = System.currentTimeMillis(),
                    ivBase64 = ivBase64,
                    encryptedMetaBase64 = encryptedMeta
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

                _vaultError.value = null
                val savedVideo = dbRow.copy(
                    id = newId,
                    displayName = resolvedName,
                    mimeType = metadata.mimeType,
                    relativeEncryptedPath = destFile.absolutePath
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
                val errMsg = "فشل تشفير الفيديو إلى الخزنة: بقي الملف الأصلي سليمًا في مكانه"
                _vaultError.value = errMsg
                VaultImportOutcome.Failed(errMsg)
            }
        } finally {
            activeImportTempNames.remove(stagingFile.name)
            activeImportTempNames.remove(stagingInnerTempName)
        }
    }

    private data class ProbedMetadata(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val mimeType: String = "video/mp4"
    )

    private fun probeVideoMetadata(uri: Uri): ProbedMetadata {
        val resolvedMime = try {
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
            val existingContentSha256 = existingJson?.optString("contentSha256").orEmpty()
            val existingLegacySrc = existingJson?.optString("src").orEmpty()
            val rows = try {
                val newEncryptedMeta = encodeMetadata(
                    displayName = cleanTitle,
                    internalFileName = internalFile,
                    mimeType = decoded.mimeType,
                    contentSha256 = existingContentSha256,
                    legacySourceFingerprint = existingLegacySrc
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

    override suspend fun deleteVaultVideo(id: Long): Boolean = withContext(ioDispatcher) {
        try {
            beforeDeleteHook?.invoke(id)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
        repoMutex.withLock {
            if (!_isUnlocked.value) return@withContext false
            val raw = dbHelper.getVideoById(id) ?: return@withContext false
            val resolved = resolveEncryptedFile(raw)
            var stagedFile: File? = null
            if (resolved.exists()) {
                val candidate = File(
                    resolved.parentFile ?: vaultDir,
                    "${resolved.name}.${System.currentTimeMillis()}.pending_delete"
                )
                val stagedOk = runCatching { resolved.renameTo(candidate) }.getOrDefault(false)
                if (!stagedOk) {
                    _vaultError.value = "تعذر عزل الملف المشفر مؤقتًا قبل الحذف، لم تتم إزالة السجل"
                    return@withContext false
                }
                stagedFile = candidate
            }

            val rows = runCatching { dbHelper.deleteVideo(id) }.getOrDefault(0)
            if (rows <= 0) {
                if (stagedFile != null && stagedFile.exists() && !resolved.exists()) {
                    runCatching { stagedFile.renameTo(resolved) }
                }
                _vaultError.value = "تعذر إزالة سجل الفيديو من قاعدة بيانات الخزنة، تم الحفاظ على الملف بأمان"
                return@withContext false
            }

            if (stagedFile != null && stagedFile.exists()) {
                runCatching { stagedFile.delete() }
            }
            _vaultError.value = null
            recordActivity()
            refreshLocked()
            true
        }
    }

    override fun getVideoById(id: Long): VaultVideo? {
        if (!_isUnlocked.value) return null
        val row = dbHelper.getVideoById(id) ?: return null
        return decodeVaultRow(row)
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
