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
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    suspend fun renameVideo(videoId: Long, newTitle: String): Boolean
    suspend fun deleteVaultVideo(id: Long): Boolean
    fun getVideoById(id: Long): VaultVideo?
    fun resolveEncryptedFile(video: VaultVideo): File
}

class VaultRepositoryImpl internal constructor(
    private val context: Context,
    private val cryptoManager: VaultCryptoManager = VaultCryptoManager(),
    private val dbHelper: VaultDatabaseStore = VaultDatabaseHelper(context)
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

    override suspend fun refresh() = withContext(Dispatchers.IO) {
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

    private fun computeSourceFingerprint(sourceUri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceUri.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encodeMetadata(
        displayName: String,
        internalFileName: String,
        mimeType: String,
        sourceFingerprint: String = ""
    ): String {
        val json = JSONObject().apply {
            put("title", displayName)
            put("file", internalFileName)
            put("mime", mimeType)
            if (sourceFingerprint.isNotBlank()) {
                put("src", sourceFingerprint)
            }
        }
        return cryptoManager.encryptMetadata(json.toString())
    }

    private fun isDuplicateInVault(
        sourceFingerprint: String,
        resolvedName: String,
        estimatedSize: Long,
        durationMs: Long
    ): Boolean {
        val rows = dbHelper.getAllVideos()
        for (row in rows) {
            val encMeta = row.encryptedMetaBase64
            if (!encMeta.isNullOrBlank()) {
                try {
                    val json = JSONObject(cryptoManager.decryptMetadata(encMeta))
                    val storedSrc = json.optString("src")
                    if (storedSrc.isNotBlank() && storedSrc == sourceFingerprint) {
                        return true
                    }
                    val storedTitle = json.optString("title")
                    if (estimatedSize > 0L &&
                        row.originalSizeBytes == estimatedSize &&
                        row.durationMs == durationMs &&
                        storedTitle.equals(resolvedName, ignoreCase = true)
                    ) {
                        return true
                    }
                } catch (_: Exception) {
                }
            }
        }
        return false
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

    private fun verifyEncryptedOutputIntegrity(destFile: File) {
        cryptoManager.verifyEncryptedFileIntegrity(destFile)
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
    ): VaultVideo? = withContext(Dispatchers.IO) {
        val uniqueName = "vault_${UUID.randomUUID()}"
        val fileName = "$uniqueName.enc"
        val destFile = File(vaultDir, fileName)
        val stagingFile = File(vaultDir, "$fileName.stage")
        val stagingInnerTempName = "${stagingFile.name}.tmp"
        val resolvedName = displayName?.ifBlank { null } ?: queryFileName(sourceUri) ?: "$uniqueName.mp4"
        val sourceFingerprint = computeSourceFingerprint(sourceUri)

        val metadata = probeVideoMetadata(sourceUri)
        val estimatedSize = try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }

        val duplicateBeforeEncrypt = repoMutex.withLock {
            isDuplicateInVault(sourceFingerprint, resolvedName, estimatedSize, metadata.durationMs)
        }
        if (duplicateBeforeEncrypt) {
            _vaultError.value = "هذا الفيديو مضاف مسبقًا داخل الخزنة المشفرة"
            return@withContext null
        }

        if (!hasEnoughStorageForEncryption(estimatedSize)) {
            _vaultError.value = "المساحة التخزينية غير كافية لتشفير هذا الفيديو بأمان"
            return@withContext null
        }

        val inputStream: InputStream? = try {
            context.contentResolver.openInputStream(sourceUri)
        } catch (_: Exception) {
            null
        }

        if (inputStream == null) {
            _vaultError.value = "تعذر فتح ملف الفيديو للقراءة والتشفير"
            return@withContext null
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

            verifyEncryptedOutputIntegrity(stagingFile)

            val encryptedMeta = encodeMetadata(resolvedName, fileName, metadata.mimeType, sourceFingerprint)
            val ivBase64 = VaultBase64.encodeNoWrap(encResult.encryptedDek)

            repoMutex.withLock {
                if (isDuplicateInVault(sourceFingerprint, resolvedName, estimatedSize, metadata.durationMs)) {
                    stagingFile.delete()
                    _vaultError.value = "هذا الفيديو مضاف مسبقًا داخل الخزنة المشفرة"
                    return@withContext null
                }

                if (!stagingFile.renameTo(destFile)) {
                    stagingFile.copyTo(destFile, overwrite = true)
                    stagingFile.delete()
                }
                if (!destFile.exists() || destFile.length() <= 0L) {
                    throw IllegalStateException("Failed to move staged encrypted file into place")
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
                if (newId <= 0L) {
                    destFile.delete()
                    throw IllegalStateException("Failed to persist vault record")
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
                savedVideo
            }
        } catch (e: Exception) {
            stagingFile.delete()
            File(vaultDir, stagingInnerTempName).delete()
            if (!destCommittedInDb && destFile.exists()) {
                destFile.delete()
            }
            if (e !is InterruptedException) {
                _vaultError.value = "فشل تشفير الفيديو إلى الخزنة: بقي الملف الأصلي سليمًا في مكانه"
            }
            null
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

    override suspend fun renameVideo(videoId: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
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
            val existingSourceFingerprint = existing.encryptedMetaBase64
                ?.takeIf { it.isNotBlank() }
                ?.let { enc ->
                    runCatching { JSONObject(cryptoManager.decryptMetadata(enc)).optString("src") }.getOrNull()
                }
                .orEmpty()
            val rows = try {
                val newEncryptedMeta = encodeMetadata(
                    displayName = cleanTitle,
                    internalFileName = internalFile,
                    mimeType = decoded.mimeType,
                    sourceFingerprint = existingSourceFingerprint
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

    override suspend fun deleteVaultVideo(id: Long): Boolean = withContext(Dispatchers.IO) {
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
