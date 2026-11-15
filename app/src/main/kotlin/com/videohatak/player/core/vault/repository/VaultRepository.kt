package com.videohatak.player.core.vault.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

interface VaultRepository {
    val vaultVideos: Flow<List<VaultVideo>>
    val isUnlocked: StateFlow<Boolean>
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

    private val _vaultVideos = MutableStateFlow<List<VaultVideo>>(emptyList())
    override val vaultVideos: Flow<List<VaultVideo>> = _vaultVideos.asStateFlow()

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
        if (!_isUnlocked.value) {
            _vaultVideos.value = emptyList()
            return@withContext
        }
        val rawList = dbHelper.getAllVideos()
        var firstDecodeError: String? = null
        val decoded = rawList.mapNotNull { row ->
            val result = decodeVaultRow(row)
            if (result == null && firstDecodeError == null) {
                firstDecodeError = "تعذر فك تشفير بيانات أحد المقاطع المحفوظة في الخزنة"
            }
            result
        }
        _vaultError.value = firstDecodeError
        _vaultVideos.update { decoded }
    }

    private fun encodeMetadata(displayName: String, internalFileName: String, mimeType: String): String {
        val json = JSONObject().apply {
            put("title", displayName)
            put("file", internalFileName)
            put("mime", mimeType)
        }
        return cryptoManager.encryptMetadata(json.toString())
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
        val resolvedName = displayName?.ifBlank { null } ?: queryFileName(sourceUri) ?: "$uniqueName.mp4"

        val metadata = probeVideoMetadata(sourceUri)

        val inputStream: InputStream? = try {
            context.contentResolver.openInputStream(sourceUri)
        } catch (_: Exception) {
            null
        }

        if (inputStream == null) return@withContext null

        val estimatedSize = try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }

        try {
            val encResult = inputStream.use { input ->
                cryptoManager.encryptStream(
                    inputStream = input,
                    destinationFile = destFile,
                    totalBytesEstimated = estimatedSize,
                    onProgress = onProgress,
                    isCancelled = isCancelled
                )
            }

            if (!destFile.exists() || destFile.length() <= 0L) {
                throw IllegalStateException("Encrypted vault file verification failed")
            }

            val encryptedMeta = encodeMetadata(resolvedName, fileName, metadata.mimeType)
            val ivBase64 = VaultBase64.encodeNoWrap(encResult.encryptedDek)
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

            val newId = dbHelper.insertVideo(dbRow)
            if (newId <= 0L) {
                throw IllegalStateException("Failed to persist vault record")
            }
            val savedVideo = dbRow.copy(
                id = newId,
                displayName = resolvedName,
                mimeType = metadata.mimeType,
                relativeEncryptedPath = destFile.absolutePath
            )
            recordActivity()
            refresh()
            savedVideo
        } catch (_: Exception) {
            if (destFile.exists()) {
                destFile.delete()
            }
            null
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
        if (!_isUnlocked.value) return@withContext false
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isBlank()) return@withContext false
        val existing = dbHelper.getVideoById(videoId) ?: return@withContext false
        val decoded = decodeVaultRow(existing) ?: return@withContext false
        val internalFile = File(decoded.relativeEncryptedPath).name
        val rows = try {
            val newEncryptedMeta = encodeMetadata(cleanTitle, internalFile, decoded.mimeType)
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
            recordActivity()
            refresh()
            true
        } else false
    }

    override suspend fun deleteVaultVideo(id: Long): Boolean = withContext(Dispatchers.IO) {
        if (!_isUnlocked.value) return@withContext false
        val raw = dbHelper.getVideoById(id) ?: return@withContext false
        val resolved = resolveEncryptedFile(raw)
        if (resolved.exists()) {
            val fileRemoved = runCatching { resolved.delete() }.getOrDefault(false) && !resolved.exists()
            if (!fileRemoved) {
                _vaultError.value = "تعذر حذف الملف المشفر من الذاكرة، لم تتم إزالة السجل"
                return@withContext false
            }
        }
        val rows = dbHelper.deleteVideo(id)
        if (rows <= 0) {
            _vaultError.value = "تعذر إزالة سجل الفيديو من قاعدة بيانات الخزنة"
            return@withContext false
        }
        recordActivity()
        refresh()
        true
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
