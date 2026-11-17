package com.videohatak.player.core.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepository
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepositoryImpl
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playlist.db.BackupPlaylistStore
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.repository.LocalSubtitleStore
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesRepository
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesStore
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultSecurityBackupStore
import com.videohatak.player.core.vault.security.VaultSecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

data class BackupSettingsData(
    val selectedTheme: AppThemeId,
    val themeMode: ThemeMode,
    val playbackSpeed: Float,
    val resizeMode: Int,
    val playbackMode: PlaybackMode,
    val resumeMode: ResumeMode,
    val seekIntervalSeconds: Int = PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS
)

data class BackupSubtitleData(
    val primaryFontSizeSp: Float,
    val secondaryFontSizeSp: Float
)

data class BackupVaultSecurityData(
    val autoLockTimeout: VaultAutoLockTimeout
)

data class BackupPlaylistItemData(
    val videoId: Long,
    val videoTitle: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val contentUri: String,
    val isAvailable: Boolean
)

data class BackupPlaylistData(
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val items: List<BackupPlaylistItemData>
)

data class BackupPayload(
    val schemaVersion: Int = BackupManager.SCHEMA_VERSION,
    val createdAtEpochMs: Long,
    val settings: BackupSettingsData,
    val subtitles: BackupSubtitleData,
    val vaultSecurity: BackupVaultSecurityData,
    val playlists: List<BackupPlaylistData>,
    val resumePositions: Map<String, Long> = emptyMap(),
    val localSubtitles: List<LocalSubtitleStore.BackupStoredSubtitleTrack> = emptyList(),
    val sha256Checksum: String = ""
)

data class BackupExportSummary(
    val playlistCount: Int,
    val playlistItemCount: Int,
    val byteSize: Int,
    val resumePositionCount: Int = 0,
    val localSubtitleTrackCount: Int = 0
)

data class BackupRestoreSummary(
    val playlistCount: Int,
    val playlistItemCount: Int,
    val themeRestored: AppThemeId,
    val resumePositionCount: Int = 0,
    val localSubtitleTrackCount: Int = 0
)

sealed interface BackupParseResult {
    data class Success(val payload: BackupPayload) : BackupParseResult
    data class Error(val message: String) : BackupParseResult
}

/**
 * Backup & Restore manager for app settings, subtitle preferences,
 * non-secret Vault auto-lock policy, saved playback resume positions (hashed keys),
 * and user playlists.
 *
 * Integrity note: `sha256Checksum` detects file corruption or modification during storage/transfer,
 * NOT cryptographic origin authentication/digital signature.
 *
 * Security boundary (never backed up): Raw video media files, Vault encrypted videos,
 * Vault PIN hashes/salts, Android Keystore keys, or file DEKs.
 */
class BackupManager internal constructor(
    private val context: Context?,
    private val themeRepository: ThemePreferencesRepository,
    private val playbackRepository: PlaybackPreferencesRepository,
    private val subtitleRepository: SubtitlePreferencesStore,
    private val vaultSecurityStore: VaultSecurityBackupStore,
    private val playlistStore: BackupPlaylistStore,
    private val subtitleStore: LocalSubtitleStore? = null
) {

    constructor(
        context: Context,
        themeRepository: ThemePreferencesRepository = ThemePreferencesRepositoryImpl(context),
        playbackRepository: PlaybackPreferencesRepository = PlaybackPreferencesRepositoryImpl(context),
        subtitleRepository: SubtitlePreferencesRepository = SubtitlePreferencesRepository(context),
        vaultSecurityPreferences: VaultSecurityPreferences = VaultSecurityPreferences(context),
        databaseHelper: VideoHatakDatabaseHelper = VideoHatakDatabaseHelper(context),
        subtitleStore: LocalSubtitleStore = LocalSubtitleStore(context)
    ) : this(
        context = context,
        themeRepository = themeRepository,
        playbackRepository = playbackRepository,
        subtitleRepository = subtitleRepository as SubtitlePreferencesStore,
        vaultSecurityStore = vaultSecurityPreferences as VaultSecurityBackupStore,
        playlistStore = databaseHelper as BackupPlaylistStore,
        subtitleStore = subtitleStore
    )

    private data class ExportUriPreWriteState(
        val wasInitiallyEmptyOrMissing: Boolean,
        val rollbackFile: File?,
        val rollbackExpectedBytes: Long = 0L,
        val rollbackSha256: ByteArray? = null
    )

    suspend fun exportToUri(uri: Uri): Result<BackupExportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val nonNullContext = requireNotNull(context) { "Context is required for exportToUri" }
            val payload = captureCurrentPayload()
            exportPayloadToUriInternal(nonNullContext, payload, uri)
        }
    }

    internal suspend fun exportPayloadToUri(
        payload: BackupPayload,
        uri: Uri
    ): Result<BackupExportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val nonNullContext = requireNotNull(context) { "Context is required for exportPayloadToUri" }
            exportPayloadToUriInternal(nonNullContext, payload, uri)
        }
    }

    @Volatile
    internal var uriStreamOpenerForTesting: ((Uri, String) -> OutputStream?)? = null

    @Volatile
    internal var uriInputStreamOpenerForTesting: ((Uri) -> InputStream?)? = null

    @Volatile
    internal var fileOutputStreamOpenerForTesting: ((File, String) -> OutputStream?)? = null

    @Volatile
    internal var fileMoveHookForTesting: ((File, File) -> Boolean)? = null

    private fun openUriOutputStream(context: Context, uri: Uri, mode: String): OutputStream? {
        return uriStreamOpenerForTesting?.invoke(uri, mode)
            ?: context.contentResolver.openOutputStream(uri, mode)
    }

    private fun openUriInputStream(context: Context, uri: Uri): InputStream? {
        return uriInputStreamOpenerForTesting?.invoke(uri)
            ?: context.contentResolver.openInputStream(uri)
    }

    private fun openFileOutputStream(file: File, role: String): OutputStream {
        return fileOutputStreamOpenerForTesting?.invoke(file, role)
            ?: FileOutputStream(file)
    }

    private fun syncOutputStream(output: OutputStream) {
        output.flush()
        if (output is FileOutputStream) {
            runCatching { output.fd.sync() }
        }
    }

    private fun computeFileDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun verifyReadableAndIntegrity(
        file: File,
        expectedBytes: Long,
        expectedFileDigest: ByteArray
    ) {
        if (!file.exists() || !file.isFile) {
            throw IOException("ملف النسخة الاحتياطية غير موجود بعد الكتابة: ${file.name}")
        }
        val actualLen = file.length()
        if (actualLen <= 0L || actualLen != expectedBytes) {
            throw IOException("حجم ملف النسخة الاحتياطية غير مطابق المتوقع ($actualLen / $expectedBytes)")
        }
        if (actualLen > MAX_BACKUP_BYTES.toLong()) {
            throw IllegalArgumentException(formatBackupSizeExceededMessage(actualLen))
        }
        val actualDigest = computeFileDigest(file)
        if (!MessageDigest.isEqual(actualDigest, expectedFileDigest)) {
            throw IOException("فشل التحقق من تطابق بصمة الملف المؤقت أو النهائي للنسخة الاحتياطية")
        }
        val rawJson = FileInputStream(file).use { input ->
            readBoundedUtf8Stream(input, MAX_BACKUP_BYTES)
        }
        when (val parsed = parseAndValidateBackupJson(rawJson)) {
            is BackupParseResult.Error -> throw IOException("ملف النسخة الاحتياطية الناتج غير قابل للتحقق: ${parsed.message}")
            is BackupParseResult.Success -> Unit
        }
    }

    private fun verifyCommittedUriBackup(
        context: Context,
        uri: Uri,
        expectedBytes: Long,
        expectedFileDigest: ByteArray
    ) {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let(::File)
                ?: throw IOException("مسار ملف التصدير غير صالح للتحقق")
            verifyReadableAndIntegrity(file, expectedBytes, expectedFileDigest)
            return
        }
        val reportedSize = queryContentUriSize(context, uri)
        if (reportedSize != null && reportedSize != expectedBytes) {
            throw IOException("حجم ملف التصدير عبر URI غير مكتمل ($reportedSize / $expectedBytes)")
        }
        val inputStream = openUriInputStream(context, uri)
            ?: throw IOException("تعذر إعادة فتح ملف التصدير للتحقق من قابليته للقراءة وسلامته")
        val rawBytes = inputStream.use { input ->
            readBoundedBytes(input, MAX_BACKUP_BYTES)
        }
        if (rawBytes.size.toLong() != expectedBytes) {
            throw IOException("طول بيانات التصدير عبر URI غير مطابق (${rawBytes.size} / $expectedBytes)")
        }
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(rawBytes)
        if (!MessageDigest.isEqual(actualDigest, expectedFileDigest)) {
            throw IOException("تلف محتوى النسخة الاحتياطية بعد التصدير عبر URI")
        }
        when (val parsed = parseAndValidateBackupJson(rawBytes.toString(Charsets.UTF_8))) {
            is BackupParseResult.Error -> throw IOException("فشل التحقق من سلامة النسخة الاحتياطية بعد التصدير: ${parsed.message}")
            is BackupParseResult.Success -> Unit
        }
    }

    private fun snapshotExistingFileForRollback(source: File, stagingDir: File): Triple<File, Long, ByteArray> {
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مجلد التراجع المؤقت للنسخة الاحتياطية")
        }
        val sourceSize = source.length()
        if (sourceSize > MAX_ROLLBACK_SNAPSHOT_BYTES) {
            throw IllegalStateException("حجم الملف الموجود يتجاوز الحد المسموح لإنشاء نسخة تراجع آمنة")
        }
        val sourceDigest = computeFileDigest(source)
        val rollback = File.createTempFile("videohatak_rollback_", ".bak.tmp", stagingDir)
        try {
            FileInputStream(source).use { input ->
                openFileOutputStream(rollback, "rollback_snapshot").use { out ->
                    input.copyTo(out)
                    syncOutputStream(out)
                }
            }
            if (!rollback.exists() || rollback.length() != sourceSize) {
                throw IOException("حجم نسخة التراجع غير مطابق للملف الأصلي")
            }
            val rollbackDigest = computeFileDigest(rollback)
            if (!MessageDigest.isEqual(rollbackDigest, sourceDigest)) {
                throw IOException("بصمة نسخة التراجع غير مطابقة للملف الأصلي")
            }
            return Triple(rollback, sourceSize, sourceDigest)
        } catch (t: Throwable) {
            runCatching { rollback.delete() }
            throw IllegalStateException("تعذر إنشاء نسخة تراجع موثقة للملف الموجود قبل الكتابة عليه", t)
        }
    }

    private fun restoreRollbackToFile(
        rollbackFile: File,
        expectedBytes: Long,
        expectedDigest: ByteArray,
        targetFile: File
    ) {
        targetFile.parentFile?.mkdirs()
        FileInputStream(rollbackFile).use { input ->
            openFileOutputStream(targetFile, "restore").use { out ->
                input.copyTo(out)
                syncOutputStream(out)
            }
        }
        if (!targetFile.exists() || targetFile.length() != expectedBytes) {
            throw IOException("حجم الملف المستعاد غير مطابق للنسخة الأصلية (${targetFile.length()} / $expectedBytes)")
        }
        val restoredDigest = computeFileDigest(targetFile)
        if (!MessageDigest.isEqual(restoredDigest, expectedDigest)) {
            throw IOException("فشل التحقق من تطابق بصمة النسخة المستعادة")
        }
    }

    private fun exportPayloadToUriInternal(
        nonNullContext: Context,
        payload: BackupPayload,
        uri: Uri
    ): BackupExportSummary {
        val stagingDir = File(nonNullContext.cacheDir, "backup_staging")
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مجلد التحضير المؤقت للنسخة الاحتياطية")
        }
        val preWriteState = inspectExportUriPreWriteState(nonNullContext, uri, stagingDir)
        var streamOpenedAndTruncated = false
        try {
            return exportPayloadViaTempFile(payload, stagingDir) { tempFile, expectedBytes, expectedFileDigest ->
                val outStream = openUriOutputStream(nonNullContext, uri, "wt")
                    ?: throw IllegalStateException("تعذر فتح ملف التصدير للكتابة")
                streamOpenedAndTruncated = true
                outStream.use { out ->
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(out)
                    }
                    syncOutputStream(out)
                }
                verifyCommittedUriBackup(nonNullContext, uri, expectedBytes, expectedFileDigest)
            }
        } catch (t: Throwable) {
            cleanupOrRestoreExportUriOnFailure(
                context = nonNullContext,
                uri = uri,
                preWriteState = preWriteState,
                streamOpenedAndTruncated = streamOpenedAndTruncated,
                originalFailure = t
            )
            throw t
        } finally {
            runCatching { preWriteState.rollbackFile?.delete() }
        }
    }

    suspend fun exportToFile(
        targetFile: File,
        tempDir: File = File(targetFile.parentFile ?: File("."), ".backup_staging")
    ): Result<BackupExportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = captureCurrentPayload()
            exportPayloadToFileInternal(payload, targetFile, tempDir)
        }
    }

    internal suspend fun exportPayloadToFile(
        payload: BackupPayload,
        targetFile: File,
        tempDir: File = File(targetFile.parentFile ?: File("."), ".backup_staging")
    ): Result<BackupExportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            exportPayloadToFileInternal(payload, targetFile, tempDir)
        }
    }

    private fun moveStageToTarget(stageDest: File, targetFile: File): Boolean {
        fileMoveHookForTesting?.let { hook ->
            return runCatching { hook(stageDest, targetFile) }.getOrDefault(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val atomicMoved = runCatching {
                Files.move(
                    stageDest.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            }.getOrDefault(false)
            if (atomicMoved) return true
        }
        if (!targetFile.exists()) {
            return runCatching { stageDest.renameTo(targetFile) }.getOrDefault(false)
        }
        return false
    }

    private fun exportPayloadToFileInternal(
        payload: BackupPayload,
        targetFile: File,
        tempDir: File
    ): BackupExportSummary {
        return exportPayloadViaTempFile(payload, tempDir) { tempFile, expectedBytes, expectedFileDigest ->
            targetFile.parentFile?.mkdirs()
            val stageDest = File(targetFile.parentFile ?: tempDir, "${targetFile.name}.partial")
            var rollbackFile: File? = null
            var rollbackSize = 0L
            var rollbackDigest: ByteArray? = null
            val hadExistingNonEmptyTarget = targetFile.exists() && targetFile.length() > 0L

            try {
                FileInputStream(tempFile).use { input ->
                    openFileOutputStream(stageDest, "stage").use { output ->
                        input.copyTo(output)
                        syncOutputStream(output)
                    }
                }
                verifyReadableAndIntegrity(stageDest, expectedBytes, expectedFileDigest)

                if (hadExistingNonEmptyTarget) {
                    val snapshot = snapshotExistingFileForRollback(targetFile, tempDir)
                    rollbackFile = snapshot.first
                    rollbackSize = snapshot.second
                    rollbackDigest = snapshot.third
                }

                try {
                    val moved = moveStageToTarget(stageDest, targetFile)
                    if (!moved) {
                        FileInputStream(stageDest).use { input ->
                            openFileOutputStream(targetFile, "dest_copy").use { output ->
                                input.copyTo(output)
                                syncOutputStream(output)
                            }
                        }
                    }
                    verifyReadableAndIntegrity(targetFile, expectedBytes, expectedFileDigest)
                } catch (commitErr: Throwable) {
                    if (hadExistingNonEmptyTarget && rollbackFile != null && rollbackDigest != null) {
                        try {
                            restoreRollbackToFile(rollbackFile, rollbackSize, rollbackDigest, targetFile)
                        } catch (restoreErr: Throwable) {
                            throw IllegalStateException(
                                "فشل تصدير النسخة الاحتياطية وفشلت استعادة الملف السابق",
                                commitErr
                            ).apply { addSuppressed(restoreErr) }
                        }
                    } else if (!hadExistingNonEmptyTarget) {
                        runCatching {
                            if (targetFile.exists()) targetFile.delete()
                        }
                    }
                    throw commitErr
                }
            } finally {
                runCatching { stageDest.delete() }
                runCatching { rollbackFile?.delete() }
            }
        }
    }

    private fun exportPayloadViaTempFile(
        payload: BackupPayload,
        tempDir: File,
        commitTempFile: (tempFile: File, byteCount: Long, expectedFileDigest: ByteArray) -> Unit
    ): BackupExportSummary {
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مجلد التحضير المؤقت للنسخة الاحتياطية")
        }
        val json = serializeBackupJson(payload)
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_BACKUP_BYTES.toLong()) {
            throw IllegalArgumentException(formatBackupSizeExceededMessage(bytes.size.toLong()))
        }
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val tempFile = File.createTempFile("videohatak_backup_", ".json.tmp", tempDir)
        try {
            openFileOutputStream(tempFile, "temp").use { rawOut ->
                BufferedOutputStream(rawOut).use { bos ->
                    bos.write(bytes)
                    bos.flush()
                }
                syncOutputStream(rawOut)
            }
            val actualBytes = tempFile.length()
            if (actualBytes > MAX_BACKUP_BYTES.toLong()) {
                throw IllegalArgumentException(formatBackupSizeExceededMessage(actualBytes))
            }
            verifyReadableAndIntegrity(tempFile, bytes.size.toLong(), expectedDigest)
            commitTempFile(tempFile, actualBytes, expectedDigest)
            return BackupExportSummary(
                playlistCount = payload.playlists.size,
                playlistItemCount = payload.playlists.sumOf { it.items.size },
                byteSize = actualBytes.toInt(),
                resumePositionCount = payload.resumePositions.size,
                localSubtitleTrackCount = payload.localSubtitles.size
            )
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun inspectExportUriPreWriteState(
        context: Context,
        uri: Uri,
        stagingDir: File
    ): ExportUriPreWriteState {
        return when (uri.scheme?.lowercase(Locale.US)) {
            "file" -> {
                val file = uri.path?.let(::File)
                if (file == null || !file.exists() || file.length() == 0L) {
                    ExportUriPreWriteState(wasInitiallyEmptyOrMissing = true, rollbackFile = null)
                } else {
                    val (rollback, size, digest) = snapshotExistingFileForRollback(file, stagingDir)
                    ExportUriPreWriteState(
                        wasInitiallyEmptyOrMissing = false,
                        rollbackFile = rollback,
                        rollbackExpectedBytes = size,
                        rollbackSha256 = digest
                    )
                }
            }
            "content" -> {
                val docSize = queryContentUriSize(context, uri)
                snapshotContentUriBeforeOverwrite(context, uri, docSize, stagingDir)
            }
            else -> throw IllegalStateException("نوع مسار التصدير غير مدعوم لحماية التراجع الآمن: ${uri.scheme}")
        }
    }

    private fun snapshotContentUriBeforeOverwrite(
        context: Context,
        uri: Uri,
        reportedSize: Long?,
        stagingDir: File
    ): ExportUriPreWriteState {
        var rollback: File? = null
        val inputStream = try {
            openUriInputStream(context, uri)
        } catch (fnf: FileNotFoundException) {
            if (reportedSize != null && reportedSize > 0L) {
                throw IllegalStateException("تعذر فتح الملف الموجود لإنشاء نسخة تراجع قبل الكتابة عليه", fnf)
            }
            return ExportUriPreWriteState(wasInitiallyEmptyOrMissing = true, rollbackFile = null)
        } catch (t: Throwable) {
            throw IllegalStateException("فشل إنشاء نسخة التراجع للملف الموجود قبل الكتابة عليه", t)
        }

        if (inputStream == null) {
            if (reportedSize == 0L) {
                return ExportUriPreWriteState(wasInitiallyEmptyOrMissing = true, rollbackFile = null)
            }
            throw IllegalStateException("تعذر قراءة الملف الموجود لإنشاء نسخة تراجع آمنة قبل الاستبدال")
        }

        return try {
            inputStream.use { input ->
                val temp = File.createTempFile("videohatak_rollback_", ".bak.tmp", stagingDir)
                rollback = temp
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8 * 1024)
                var total = 0L
                openFileOutputStream(temp, "rollback_snapshot").use { out ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_ROLLBACK_SNAPSHOT_BYTES) {
                            throw IllegalStateException("Existing file exceeds rollback snapshot cap")
                        }
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                    syncOutputStream(out)
                }
                if (reportedSize != null && reportedSize > 0L && total != reportedSize) {
                    throw IOException("نسخة التراجع غير مكتملة ($total / $reportedSize)")
                }
                if (total == 0L) {
                    runCatching { temp.delete() }
                    rollback = null
                    ExportUriPreWriteState(wasInitiallyEmptyOrMissing = true, rollbackFile = null)
                } else {
                    val expectedDigest = digest.digest()
                    if (!temp.exists() || temp.length() != total) {
                        throw IOException("حجم ملف التراجع المؤقت غير مطابق")
                    }
                    val actualTempDigest = computeFileDigest(temp)
                    if (!MessageDigest.isEqual(actualTempDigest, expectedDigest)) {
                        throw IOException("فشل التحقق من بصمة نسخة التراجع المؤقتة")
                    }
                    ExportUriPreWriteState(
                        wasInitiallyEmptyOrMissing = false,
                        rollbackFile = temp,
                        rollbackExpectedBytes = total,
                        rollbackSha256 = expectedDigest
                    )
                }
            }
        } catch (t: Throwable) {
            runCatching { rollback?.delete() }
            throw IllegalStateException("فشل إنشاء نسخة التراجع للملف الموجود قبل الكتابة عليه", t)
        }
    }

    private fun queryContentUriSize(context: Context, uri: Uri): Long? {
        var docSize: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx != -1 && !cursor.isNull(idx)) {
                        docSize = cursor.getLong(idx)
                    }
                }
            }
        }
        return docSize
    }

    private fun verifyRestoredUriMatchesRollback(
        context: Context,
        uri: Uri,
        expectedBytes: Long,
        expectedDigest: ByteArray
    ) {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val dest = uri.path?.let(::File) ?: throw IOException("مسار ملف الاستعادة غير صالح")
            if (!dest.exists() || dest.length() != expectedBytes) {
                throw IOException("حجم الملف المستعاد غير مطابق (${dest.length()} / $expectedBytes)")
            }
            val actualDigest = computeFileDigest(dest)
            if (!MessageDigest.isEqual(actualDigest, expectedDigest)) {
                throw IOException("بصمة الملف المستعاد غير مطابقة للنسخة الأصلية")
            }
            return
        }
        val reportedSize = queryContentUriSize(context, uri)
        if (reportedSize != null && reportedSize != expectedBytes) {
            throw IOException("حجم المستند المستعاد غير مطابق ($reportedSize / $expectedBytes)")
        }
        val verifyStream = openUriInputStream(context, uri)
            ?: throw IOException("تعذر التحقق من قراءة المستند المستعاد")
        val actualDigest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        verifyStream.use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
                if (total > MAX_ROLLBACK_SNAPSHOT_BYTES) {
                    throw IOException("تجاوز حجم المستند المستعاد الحد المتوقع")
                }
                actualDigest.update(buf, 0, read)
            }
        }
        if (total != expectedBytes || !MessageDigest.isEqual(actualDigest.digest(), expectedDigest)) {
            throw IOException("محتوى المستند المستعاد غير مطابق للنسخة الأصلية")
        }
    }

    private fun cleanupOrRestoreExportUriOnFailure(
        context: Context,
        uri: Uri,
        preWriteState: ExportUriPreWriteState,
        streamOpenedAndTruncated: Boolean,
        originalFailure: Throwable
    ) {
        val rollbackFile = preWriteState.rollbackFile
        if (streamOpenedAndTruncated && rollbackFile != null && rollbackFile.exists()) {
            try {
                when (uri.scheme?.lowercase(Locale.US)) {
                    "file" -> {
                        val dest = uri.path?.let(::File)
                            ?: throw IOException("مسار الملف المستهدف للاستعادة غير صالح")
                        val stage = File(dest.parentFile ?: rollbackFile.parentFile, "${dest.name}.restore.tmp")
                        try {
                            FileInputStream(rollbackFile).use { input ->
                                openFileOutputStream(stage, "restore").use { out ->
                                    input.copyTo(out)
                                    syncOutputStream(out)
                                }
                            }
                            val moved = runCatching { stage.renameTo(dest) }.getOrDefault(false)
                            if (!moved) {
                                FileInputStream(stage).use { input ->
                                    openFileOutputStream(dest, "restore").use { out ->
                                        input.copyTo(out)
                                        syncOutputStream(out)
                                    }
                                }
                            }
                        } finally {
                            runCatching { stage.delete() }
                        }
                    }
                    else -> {
                        val restoreStream = openUriOutputStream(context, uri, "wt")
                            ?: throw IOException("تعذر فتح مسار URI لاستعادة النسخة السابقة")
                        restoreStream.use { out ->
                            FileInputStream(rollbackFile).use { input ->
                                input.copyTo(out)
                            }
                            syncOutputStream(out)
                        }
                    }
                }
                val expectedDigest = preWriteState.rollbackSha256
                if (expectedDigest != null) {
                    verifyRestoredUriMatchesRollback(
                        context = context,
                        uri = uri,
                        expectedBytes = preWriteState.rollbackExpectedBytes,
                        expectedDigest = expectedDigest
                    )
                }
                return
            } catch (restoreErr: Throwable) {
                throw IllegalStateException(
                    "فشل تصدير النسخة الاحتياطية وفشلت استعادة الملف السابق",
                    originalFailure
                ).apply { addSuppressed(restoreErr) }
            }
        }

        if (!preWriteState.wasInitiallyEmptyOrMissing) {
            return
        }

        runCatching {
            when (uri.scheme?.lowercase(Locale.US)) {
                "file" -> {
                    val file = uri.path?.let(::File)
                    if (file != null && file.exists() && (streamOpenedAndTruncated || file.length() == 0L)) {
                        file.delete()
                    }
                }
                "content" -> {
                    if (!streamOpenedAndTruncated) {
                        val currentSize = queryContentUriSize(context, uri)
                        if (currentSize == 0L) {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        }
                    } else {
                        val deleted = runCatching {
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        }.getOrDefault(false)
                        if (!deleted) {
                            runCatching {
                                openUriOutputStream(context, uri, "wt")?.use { out ->
                                    out.flush()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun importFromUri(uri: Uri): Result<BackupRestoreSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val nonNullContext = requireNotNull(context) { "Context is required for importFromUri" }
            val rawJson = nonNullContext.contentResolver.openInputStream(uri)?.use { input ->
                readBoundedUtf8Stream(input, MAX_BACKUP_BYTES)
            } ?: throw IllegalStateException("تعذر قراءة ملف النسخة الاحتياطية")

            when (val parsed = parseAndValidateBackupJson(rawJson)) {
                is BackupParseResult.Error -> throw IllegalArgumentException(parsed.message)
                is BackupParseResult.Success -> {
                    applyPayload(parsed.payload)
                    BackupRestoreSummary(
                        playlistCount = parsed.payload.playlists.size,
                        playlistItemCount = parsed.payload.playlists.sumOf { it.items.size },
                        themeRestored = parsed.payload.settings.selectedTheme,
                        resumePositionCount = parsed.payload.resumePositions.size,
                        localSubtitleTrackCount = parsed.payload.localSubtitles.size
                    )
                }
            }
        }
    }

    suspend fun importFromFile(sourceFile: File): Result<BackupRestoreSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val rawJson = FileInputStream(sourceFile).use { input ->
                readBoundedUtf8Stream(input, MAX_BACKUP_BYTES)
            }
            when (val parsed = parseAndValidateBackupJson(rawJson)) {
                is BackupParseResult.Error -> throw IllegalArgumentException(parsed.message)
                is BackupParseResult.Success -> {
                    applyPayload(parsed.payload)
                    BackupRestoreSummary(
                        playlistCount = parsed.payload.playlists.size,
                        playlistItemCount = parsed.payload.playlists.sumOf { it.items.size },
                        themeRestored = parsed.payload.settings.selectedTheme,
                        resumePositionCount = parsed.payload.resumePositions.size,
                        localSubtitleTrackCount = parsed.payload.localSubtitles.size
                    )
                }
            }
        }
    }

    suspend fun captureCurrentPayload(createdAtMs: Long = System.currentTimeMillis()): BackupPayload {
        val selectedTheme = themeRepository.appThemeFlow.first()
        val themeMode = themeRepository.themeModeFlow.first()
        val playbackSpeed = VideoPlayerManager.clampPlaybackSpeed(playbackRepository.playbackSpeed.first())
        val resizeMode = VideoResizeMode.fromCode(playbackRepository.resizeMode.first()).storageCode
        val playbackMode = playbackRepository.playbackMode.first()
        val resumeMode = playbackRepository.resumeMode.first()
        val seekIntervalSeconds = PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(
            playbackRepository.seekIntervalSeconds.first()
        )

        val primarySub = subtitleRepository.primaryStyle.first()
        val secondarySub = subtitleRepository.secondaryStyle.first()
        val autoLock = vaultSecurityStore.autoLockTimeout.first()

        val playlists = playlistStore.getPlaylists().map { playlist ->
            val items = playlistStore.getItemsForPlaylist(playlist.id).map { item ->
                BackupPlaylistItemData(
                    videoId = item.videoId,
                    videoTitle = item.videoTitle,
                    durationMs = item.durationMs,
                    sizeBytes = item.sizeBytes,
                    dateAddedSec = item.dateAddedSec,
                    contentUri = item.contentUri,
                    isAvailable = item.isAvailable
                )
            }
            BackupPlaylistData(
                title = playlist.title,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
                items = items
            )
        }

        val savedPositions = playbackRepository.exportSavedPositions()
        val storedSubtitles = runCatching { subtitleStore?.exportAllStoredTracksForBackup() }.getOrNull().orEmpty()

        return BackupPayload(
            schemaVersion = SCHEMA_VERSION,
            createdAtEpochMs = createdAtMs,
            settings = BackupSettingsData(
                selectedTheme = selectedTheme,
                themeMode = themeMode,
                playbackSpeed = playbackSpeed,
                resizeMode = resizeMode,
                playbackMode = playbackMode,
                resumeMode = resumeMode,
                seekIntervalSeconds = seekIntervalSeconds
            ),
            subtitles = BackupSubtitleData(
                primaryFontSizeSp = primarySub.fontSizeSp,
                secondaryFontSizeSp = secondarySub.fontSizeSp
            ),
            vaultSecurity = BackupVaultSecurityData(
                autoLockTimeout = autoLock
            ),
            playlists = playlists,
            resumePositions = savedPositions,
            localSubtitles = storedSubtitles
        )
    }

    private fun toDatabaseEntries(playlists: List<BackupPlaylistData>): List<Pair<Playlist, List<PlaylistItem>>> {
        return playlists.map { pl ->
            val playlist = Playlist(
                id = 0L,
                title = pl.title,
                createdAt = pl.createdAt,
                updatedAt = pl.updatedAt
            )
            val items = pl.items.mapIndexed { idx, item ->
                PlaylistItem(
                    id = 0L,
                    playlistId = 0L,
                    videoId = item.videoId,
                    orderIndex = idx,
                    videoTitle = item.videoTitle,
                    durationMs = item.durationMs,
                    sizeBytes = item.sizeBytes,
                    dateAddedSec = item.dateAddedSec,
                    contentUri = item.contentUri,
                    isAvailable = item.isAvailable
                )
            }
            playlist to items
        }
    }

    suspend fun applyPayload(payload: BackupPayload) {
        val previousSnapshot = captureCurrentPayload()
        val targetDbEntries = toDatabaseEntries(payload.playlists)

        // Commit transactional DB replacement first: if SQLite fails or returns -1,
        // no DataStore preferences are touched.
        playlistStore.replaceAllPlaylistsAndItems(targetDbEntries)

        try {
            themeRepository.setAppTheme(payload.settings.selectedTheme)
            themeRepository.setThemeMode(payload.settings.themeMode)
            playbackRepository.setPlaybackSpeed(VideoPlayerManager.clampPlaybackSpeed(payload.settings.playbackSpeed))
            playbackRepository.setResizeMode(VideoResizeMode.fromCode(payload.settings.resizeMode).storageCode)
            playbackRepository.setPlaybackMode(payload.settings.playbackMode)
            playbackRepository.setResumeMode(payload.settings.resumeMode)
            playbackRepository.setSeekIntervalSeconds(
                PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(payload.settings.seekIntervalSeconds)
            )
            playbackRepository.replaceSavedPositions(payload.resumePositions)

            subtitleRepository.updatePrimaryFontSize(payload.subtitles.primaryFontSizeSp.coerceIn(12f, 36f))
            subtitleRepository.updateSecondaryFontSize(payload.subtitles.secondaryFontSizeSp.coerceIn(10f, 32f))
            vaultSecurityStore.setAutoLockTimeout(payload.vaultSecurity.autoLockTimeout)
            subtitleStore?.replaceAllStoredTracksFromBackup(payload.localSubtitles)
        } catch (e: Throwable) {
            try {
                playlistStore.replaceAllPlaylistsAndItems(toDatabaseEntries(previousSnapshot.playlists))
                themeRepository.setAppTheme(previousSnapshot.settings.selectedTheme)
                themeRepository.setThemeMode(previousSnapshot.settings.themeMode)
                playbackRepository.setPlaybackSpeed(previousSnapshot.settings.playbackSpeed)
                playbackRepository.setResizeMode(previousSnapshot.settings.resizeMode)
                playbackRepository.setPlaybackMode(previousSnapshot.settings.playbackMode)
                playbackRepository.setResumeMode(previousSnapshot.settings.resumeMode)
                playbackRepository.setSeekIntervalSeconds(previousSnapshot.settings.seekIntervalSeconds)
                playbackRepository.replaceSavedPositions(previousSnapshot.resumePositions)
                subtitleRepository.updatePrimaryFontSize(previousSnapshot.subtitles.primaryFontSizeSp)
                subtitleRepository.updateSecondaryFontSize(previousSnapshot.subtitles.secondaryFontSizeSp)
                vaultSecurityStore.setAutoLockTimeout(previousSnapshot.vaultSecurity.autoLockTimeout)
                subtitleStore?.replaceAllStoredTracksFromBackup(previousSnapshot.localSubtitles)
            } catch (rollbackError: Throwable) {
                e.addSuppressed(rollbackError)
            }
            throw e
        }
    }

    companion object {
        const val SCHEMA_VERSION = 3
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2, 3)
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        private const val MAX_ROLLBACK_SNAPSHOT_BYTES = MAX_BACKUP_BYTES * 2L
        private const val MAX_BACKUP_MB = MAX_BACKUP_BYTES / (1024 * 1024)
        private const val MAX_PLAYLISTS = 200
        private const val MAX_ITEMS_PER_PLAYLIST = 2_000
        private const val MAX_RESUME_POSITIONS = 5_000
        private const val MAX_SUBTITLE_TRACKS = 500

        internal fun formatBackupSizeExceededMessage(actualBytes: Long? = null): String {
            return if (actualBytes != null && actualBytes > MAX_BACKUP_BYTES.toLong()) {
                val actualMb = String.format(Locale.US, "%.2f", actualBytes / (1024.0 * 1024.0))
                val sizeLabel = if (actualMb == "$MAX_BACKUP_MB.00") {
                    "$actualBytes بايت"
                } else {
                    "$actualMb MB"
                }
                "حجم ملف النسخة الاحتياطية ($sizeLabel) يتجاوز الحد الأقصى المسموح به ($MAX_BACKUP_MB MB). يُرجى تقليل عدد مسارات الترجمة أو قوائم التشغيل والمحاولة مجددًا."
            } else {
                "حجم ملف النسخة الاحتياطية يتجاوز الحد الأقصى المسموح به ($MAX_BACKUP_MB MB)"
            }
        }

        private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
            val buffer = ByteArray(8 * 1024)
            val baos = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    throw IllegalArgumentException(formatBackupSizeExceededMessage(total.toLong()))
                }
                baos.write(buffer, 0, read)
            }
            return baos.toByteArray()
        }

        private fun readBoundedUtf8Stream(input: InputStream, maxBytes: Int): String {
            return readBoundedBytes(input, maxBytes).toString(Charsets.UTF_8)
        }

        internal fun createForTesting(
            themeRepository: ThemePreferencesRepository,
            playbackRepository: PlaybackPreferencesRepository,
            subtitleRepository: SubtitlePreferencesStore,
            vaultSecurityStore: VaultSecurityBackupStore,
            playlistStore: BackupPlaylistStore,
            subtitleStore: LocalSubtitleStore? = null,
            context: Context? = null
        ): BackupManager {
            return BackupManager(
                context = context,
                themeRepository = themeRepository,
                playbackRepository = playbackRepository,
                subtitleRepository = subtitleRepository,
                vaultSecurityStore = vaultSecurityStore,
                playlistStore = playlistStore,
                subtitleStore = subtitleStore
            )
        }

        fun serializeBackupJson(payload: BackupPayload): String {
            val canonicalBody = buildCanonicalBodyJson(payload)
            val checksum = sha256Hex(canonicalBody)

            return buildString {
                append("{\n")
                append("  \"schemaVersion\": ").append(payload.schemaVersion).append(",\n")
                append("  \"createdAtEpochMs\": ").append(payload.createdAtEpochMs).append(",\n")
                append("  \"sha256Checksum\": ").append(quoteJson(checksum)).append(",\n")
                append("  \"body\": ").append(canonicalBody).append("\n")
                append("}")
            }
        }

        fun parseAndValidateBackupJson(rawJson: String): BackupParseResult {
            if (rawJson.length > MAX_BACKUP_BYTES) {
                return BackupParseResult.Error(formatBackupSizeExceededMessage())
            }
            if (rawJson.length > MAX_BACKUP_BYTES / 3) {
                val utf8Size = rawJson.toByteArray(Charsets.UTF_8).size.toLong()
                if (utf8Size > MAX_BACKUP_BYTES.toLong()) {
                    return BackupParseResult.Error(formatBackupSizeExceededMessage(utf8Size))
                }
            }
            val root = try {
                MiniJsonParser(rawJson).parseValue() as? Map<*, *>
            } catch (_: Exception) {
                null
            } ?: return BackupParseResult.Error("ملف النسخة الاحتياطية تالف أو ليس بصيغة JSON صالحة")

            val version = asJsonNumber(root["schemaVersion"])?.toInt()
                ?: return BackupParseResult.Error("إصدار النسخة الاحتياطية غير محدد")
            if (version !in SUPPORTED_SCHEMA_VERSIONS) {
                return BackupParseResult.Error("إصدار النسخة الاحتياطية ($version) غير مدعوم في هذا الإصدار")
            }

            val createdAt = asJsonNumber(root["createdAtEpochMs"])?.toLong() ?: 0L
            val declaredChecksum = (root["sha256Checksum"] as? String)?.trim()?.lowercase()
                ?: return BackupParseResult.Error("البصمة الأمنية للنسخة الاحتياطية مفقودة")

            val bodyMap = root["body"] as? Map<*, *>
                ?: return BackupParseResult.Error("بيانات النسخة الاحتياطية غير مكتملة")

            // Enforce strict separation: never allow secret fields if injected
            val vaultMap = bodyMap["vaultSecurity"] as? Map<*, *>
                ?: return BackupParseResult.Error("قسم أمان الخزنة مفقود")
            val forbiddenSecretKeys = setOf("pinhash", "pinsalt", "pin", "dek", "keystorealias", "encryptedmetabase64")
            val containsForbiddenKey = listOf(root.keys, bodyMap.keys, vaultMap.keys)
                .flatten()
                .any { it.toString().lowercase() in forbiddenSecretKeys }
            if (containsForbiddenKey) {
                return BackupParseResult.Error("ملف غير صالح: يحتوي على حقول أمان محظورة")
            }

            val settingsMap = bodyMap["settings"] as? Map<*, *>
                ?: return BackupParseResult.Error("قسم الإعدادات مفقود")
            val subtitlesMap = bodyMap["subtitles"] as? Map<*, *>
                ?: return BackupParseResult.Error("قسم الترجمة مفقود")
            val playlistsRaw = bodyMap["playlists"] as? List<*>
                ?: return BackupParseResult.Error("قسم قوائم التشغيل مفقود")
            if (playlistsRaw.size > MAX_PLAYLISTS) {
                return BackupParseResult.Error("عدد قوائم التشغيل في النسخة الاحتياطية يتجاوز الحد المسموح ($MAX_PLAYLISTS)")
            }

            val themeName = settingsMap["selectedTheme"] as? String
                ?: return BackupParseResult.Error("اسم الثيم مفقود")
            val selectedTheme = AppThemeId.entries.firstOrNull { it.name == themeName }
                ?: return BackupParseResult.Error("ثيم غير معروف: $themeName")

            val themeModeName = settingsMap["themeMode"] as? String
                ?: return BackupParseResult.Error("وضع السطوع مفقود")
            val themeMode = ThemeMode.entries.firstOrNull { it.name == themeModeName }
                ?: return BackupParseResult.Error("وضع سطوع غير معروف")

            val rawSpeed = asJsonNumber(settingsMap["playbackSpeed"])?.toFloat()
                ?: return BackupParseResult.Error("سرعة التشغيل مفقودة")
            if (!rawSpeed.isFinite()) {
                return BackupParseResult.Error("قيمة سرعة التشغيل غير صالحة")
            }
            val playbackSpeed = VideoPlayerManager.clampPlaybackSpeed(rawSpeed)

            val rawResize = asJsonNumber(settingsMap["resizeMode"])?.toInt()
                ?: return BackupParseResult.Error("نمط أبعاد الشاشة مفقود")
            val resizeMode = VideoResizeMode.fromCode(rawResize).storageCode

            val playbackModeName = settingsMap["playbackMode"] as? String
                ?: return BackupParseResult.Error("وضع التشغيل مفقود")
            val playbackMode = PlaybackMode.entries.firstOrNull { it.name == playbackModeName }
                ?: return BackupParseResult.Error("وضع تشغيل غير معروف")

            val resumeModeName = settingsMap["resumeMode"] as? String
                ?: return BackupParseResult.Error("وضع الاستئناف مفقود")
            val resumeMode = when (resumeModeName) {
                "ASK_EVERY_TIME" -> ResumeMode.RESUME
                else -> ResumeMode.entries.firstOrNull { it.name == resumeModeName }
                    ?: return BackupParseResult.Error("وضع استئناف غير معروف")
            }

            val rawSeekInterval = asJsonNumber(settingsMap["seekIntervalSeconds"])?.toInt()
            val seekIntervalSeconds = PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(
                rawSeekInterval ?: PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS
            )

            val rawPrimarySub = asJsonNumber(subtitlesMap["primaryFontSizeSp"])?.toFloat() ?: 18f
            val rawSecondarySub = asJsonNumber(subtitlesMap["secondaryFontSizeSp"])?.toFloat() ?: 15f
            if (!rawPrimarySub.isFinite() || !rawSecondarySub.isFinite()) {
                return BackupParseResult.Error("حجم خط الترجمة غير صالح")
            }
            val primarySubSize = rawPrimarySub.coerceIn(12f, 36f)
            val secondarySubSize = rawSecondarySub.coerceIn(10f, 32f)

            val autoLockName = vaultMap["autoLockTimeout"] as? String ?: VaultAutoLockTimeout.IMMEDIATE.name
            val autoLock = VaultAutoLockTimeout.entries.firstOrNull { it.name == autoLockName }
                ?: VaultAutoLockTimeout.IMMEDIATE

            val numberLexemes = HashMap<String, String>()
            asJsonLexeme(settingsMap["playbackSpeed"])?.let { numberLexemes["settings.playbackSpeed"] = it }
            asJsonLexeme(settingsMap["resizeMode"])?.let { numberLexemes["settings.resizeMode"] = it }
            asJsonLexeme(settingsMap["seekIntervalSeconds"])?.let { numberLexemes["settings.seekIntervalSeconds"] = it }
            asJsonLexeme(subtitlesMap["primaryFontSizeSp"])?.let { numberLexemes["subtitles.primaryFontSizeSp"] = it }
            asJsonLexeme(subtitlesMap["secondaryFontSizeSp"])?.let { numberLexemes["subtitles.secondaryFontSizeSp"] = it }

            val playlists = playlistsRaw.mapIndexed { idx, entry ->
                val plMap = entry as? Map<*, *>
                    ?: return BackupParseResult.Error("عنصر قائمة تشغيل غير صالح عند الموضع $idx")
                val title = (plMap["title"] as? String)?.trim().orEmpty()
                if (title.isBlank()) {
                    return BackupParseResult.Error("اسم قائمة التشغيل فارغ عند الموضع $idx")
                }
                val pCreatedAt = asJsonNumber(plMap["createdAt"])?.toLong() ?: 0L
                val pUpdatedAt = asJsonNumber(plMap["updatedAt"])?.toLong() ?: 0L
                asJsonLexeme(plMap["createdAt"])?.let { numberLexemes["playlists.$idx.createdAt"] = it }
                asJsonLexeme(plMap["updatedAt"])?.let { numberLexemes["playlists.$idx.updatedAt"] = it }

                val itemsRaw = plMap["items"] as? List<*> ?: emptyList<Any>()
                if (itemsRaw.size > MAX_ITEMS_PER_PLAYLIST) {
                    return BackupParseResult.Error("عدد المقاطع داخل القائمة $title يتجاوز الحد المسموح ($MAX_ITEMS_PER_PLAYLIST)")
                }
                val items = itemsRaw.mapIndexed { itemIdx, itemAny ->
                    val itemMap = itemAny as? Map<*, *>
                        ?: return BackupParseResult.Error("عنصر فيديو غير صالح داخل القائمة $title")
                    asJsonLexeme(itemMap["videoId"])?.let { numberLexemes["playlists.$idx.items.$itemIdx.videoId"] = it }
                    asJsonLexeme(itemMap["durationMs"])?.let { numberLexemes["playlists.$idx.items.$itemIdx.durationMs"] = it }
                    asJsonLexeme(itemMap["sizeBytes"])?.let { numberLexemes["playlists.$idx.items.$itemIdx.sizeBytes"] = it }
                    asJsonLexeme(itemMap["dateAddedSec"])?.let { numberLexemes["playlists.$idx.items.$itemIdx.dateAddedSec"] = it }
                    BackupPlaylistItemData(
                        videoId = asJsonNumber(itemMap["videoId"])?.toLong() ?: 0L,
                        videoTitle = (itemMap["videoTitle"] as? String).orEmpty(),
                        durationMs = (asJsonNumber(itemMap["durationMs"])?.toLong() ?: 0L).coerceAtLeast(0L),
                        sizeBytes = (asJsonNumber(itemMap["sizeBytes"])?.toLong() ?: 0L).coerceAtLeast(0L),
                        dateAddedSec = (asJsonNumber(itemMap["dateAddedSec"])?.toLong() ?: 0L).coerceAtLeast(0L),
                        contentUri = (itemMap["contentUri"] as? String).orEmpty(),
                        isAvailable = (itemMap["isAvailable"] as? Boolean) ?: true
                    )
                }
                BackupPlaylistData(
                    title = title,
                    createdAt = pCreatedAt,
                    updatedAt = pUpdatedAt,
                    items = items
                )
            }

            val rawPositions = bodyMap["resumePositions"] as? Map<*, *>
            if (rawPositions != null && rawPositions.size > MAX_RESUME_POSITIONS) {
                return BackupParseResult.Error("عدد مواضع الاستئناف يتجاوز الحد المسموح ($MAX_RESUME_POSITIONS)")
            }
            val resumePositions = linkedMapOf<String, Long>()
            if (rawPositions != null) {
                for ((k, v) in rawPositions) {
                    val keyStr = k?.toString()?.trim().orEmpty()
                    val posMs = asJsonNumber(v)?.toLong() ?: -1L
                    if (keyStr.isNotBlank() && posMs > 0L) {
                        resumePositions[keyStr] = posMs
                        asJsonLexeme(v)?.let { numberLexemes["resumePositions.$keyStr"] = it }
                    }
                }
            }

            val rawLocalSubtitles = bodyMap["localSubtitles"] as? List<*>
            if (rawLocalSubtitles != null && rawLocalSubtitles.size > MAX_SUBTITLE_TRACKS) {
                return BackupParseResult.Error("عدد مسارات الترجمة يتجاوز الحد المسموح ($MAX_SUBTITLE_TRACKS)")
            }
            val localSubtitles = mutableListOf<LocalSubtitleStore.BackupStoredSubtitleTrack>()
            rawLocalSubtitles?.forEach { entry ->
                val map = entry as? Map<*, *> ?: return@forEach
                val hash = (map["videoKeyHash"] as? String).orEmpty()
                val trackId = (map["trackId"] as? String).orEmpty()
                val trackName = (map["trackName"] as? String).orEmpty()
                val language = (map["language"] as? String)?.takeIf { it.isNotBlank() }
                val formatName = (map["format"] as? String) ?: SubtitleFormat.SRT.name
                val format = SubtitleFormat.entries.firstOrNull { it.name == formatName } ?: SubtitleFormat.SRT
                val content = (map["content"] as? String).orEmpty()
                val updatedAtMs = asJsonNumber(map["updatedAtMs"])?.toLong() ?: 0L
                if (hash.isNotBlank() && trackId.isNotBlank() && content.isNotBlank()) {
                    val sIdx = localSubtitles.size
                    asJsonLexeme(map["updatedAtMs"])?.let { numberLexemes["localSubtitles.$sIdx.updatedAtMs"] = it }
                    localSubtitles.add(
                        LocalSubtitleStore.BackupStoredSubtitleTrack(
                            videoKeyHash = hash,
                            trackId = trackId,
                            trackName = trackName,
                            language = language,
                            format = format,
                            content = content,
                            updatedAtMs = updatedAtMs
                        )
                    )
                }
            }

            val candidate = BackupPayload(
                schemaVersion = version,
                createdAtEpochMs = createdAt,
                settings = BackupSettingsData(
                    selectedTheme = selectedTheme,
                    themeMode = themeMode,
                    playbackSpeed = rawSpeed,
                    resizeMode = rawResize,
                    playbackMode = playbackMode,
                    resumeMode = resumeMode,
                    seekIntervalSeconds = rawSeekInterval ?: PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS
                ),
                subtitles = BackupSubtitleData(
                    primaryFontSizeSp = rawPrimarySub,
                    secondaryFontSizeSp = rawSecondarySub
                ),
                vaultSecurity = BackupVaultSecurityData(autoLockTimeout = autoLock),
                playlists = playlists,
                resumePositions = resumePositions,
                localSubtitles = localSubtitles
            )

            val expectedChecksum = sha256Hex(
                buildCanonicalBodyJson(
                    payload = candidate,
                    numberLexemes = numberLexemes,
                    rawResumeModeName = resumeModeName
                )
            )
            if (!MessageDigest.isEqual(
                    expectedChecksum.toByteArray(Charsets.UTF_8),
                    declaredChecksum.toByteArray(Charsets.UTF_8)
                )
            ) {
                return BackupParseResult.Error("فشل التحقق من سلامة النسخة الاحتياطية: تم تعديل الملف أو تلفه")
            }

            return BackupParseResult.Success(
                candidate.copy(
                    settings = candidate.settings.copy(
                        playbackSpeed = playbackSpeed,
                        resizeMode = resizeMode,
                        seekIntervalSeconds = seekIntervalSeconds
                    ),
                    subtitles = BackupSubtitleData(
                        primaryFontSizeSp = primarySubSize,
                        secondaryFontSizeSp = secondarySubSize
                    ),
                    sha256Checksum = declaredChecksum
                )
            )
        }

        private fun asJsonNumber(value: Any?): Number? = when (value) {
            is JsonRawNumber -> value.number
            is Number -> value
            else -> null
        }

        private fun asJsonLexeme(value: Any?): String? = when (value) {
            is JsonRawNumber -> value.rawLexeme
            else -> null
        }

        private fun buildCanonicalBodyJson(
            payload: BackupPayload,
            numberLexemes: Map<String, String> = emptyMap(),
            rawResumeModeName: String? = null
        ): String {
            fun numToken(path: String, fallback: Any): String = numberLexemes[path] ?: fallback.toString()
            return buildString {
                append("{")
                append("\"settings\":{")
                append("\"selectedTheme\":").append(quoteJson(payload.settings.selectedTheme.name)).append(",")
                append("\"themeMode\":").append(quoteJson(payload.settings.themeMode.name)).append(",")
                append("\"playbackSpeed\":").append(numToken("settings.playbackSpeed", payload.settings.playbackSpeed)).append(",")
                append("\"resizeMode\":").append(numToken("settings.resizeMode", payload.settings.resizeMode)).append(",")
                append("\"playbackMode\":").append(quoteJson(payload.settings.playbackMode.name)).append(",")
                append("\"resumeMode\":").append(quoteJson(rawResumeModeName ?: payload.settings.resumeMode.name))
                if (payload.schemaVersion >= 3 || payload.settings.seekIntervalSeconds != PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS) {
                    append(",\"seekIntervalSeconds\":").append(numToken("settings.seekIntervalSeconds", payload.settings.seekIntervalSeconds))
                }
                append("},")
                append("\"subtitles\":{")
                append("\"primaryFontSizeSp\":").append(numToken("subtitles.primaryFontSizeSp", payload.subtitles.primaryFontSizeSp)).append(",")
                append("\"secondaryFontSizeSp\":").append(numToken("subtitles.secondaryFontSizeSp", payload.subtitles.secondaryFontSizeSp))
                append("},")
                append("\"vaultSecurity\":{")
                append("\"autoLockTimeout\":").append(quoteJson(payload.vaultSecurity.autoLockTimeout.name))
                append("},")
                append("\"playlists\":[")
                payload.playlists.forEachIndexed { pIdx, pl ->
                    if (pIdx > 0) append(",")
                    append("{")
                    append("\"title\":").append(quoteJson(pl.title)).append(",")
                    append("\"createdAt\":").append(numToken("playlists.$pIdx.createdAt", pl.createdAt)).append(",")
                    append("\"updatedAt\":").append(numToken("playlists.$pIdx.updatedAt", pl.updatedAt)).append(",")
                    append("\"items\":[")
                    pl.items.forEachIndexed { iIdx, item ->
                        if (iIdx > 0) append(",")
                        append("{")
                        append("\"videoId\":").append(numToken("playlists.$pIdx.items.$iIdx.videoId", item.videoId)).append(",")
                        append("\"videoTitle\":").append(quoteJson(item.videoTitle)).append(",")
                        append("\"durationMs\":").append(numToken("playlists.$pIdx.items.$iIdx.durationMs", item.durationMs)).append(",")
                        append("\"sizeBytes\":").append(numToken("playlists.$pIdx.items.$iIdx.sizeBytes", item.sizeBytes)).append(",")
                        append("\"dateAddedSec\":").append(numToken("playlists.$pIdx.items.$iIdx.dateAddedSec", item.dateAddedSec)).append(",")
                        append("\"contentUri\":").append(quoteJson(item.contentUri)).append(",")
                        append("\"isAvailable\":").append(item.isAvailable)
                        append("}")
                    }
                    append("]")
                    append("}")
                }
                append("]")
                if (payload.schemaVersion >= 2 || payload.resumePositions.isNotEmpty()) {
                    append(",\"resumePositions\":{")
                    val sortedEntries = payload.resumePositions.entries.sortedBy { it.key }
                    sortedEntries.forEachIndexed { idx, (key, value) ->
                        if (idx > 0) append(",")
                        append(quoteJson(key)).append(":").append(numToken("resumePositions.$key", value))
                    }
                    append("}")
                }
                if (payload.localSubtitles.isNotEmpty()) {
                    append(",\"localSubtitles\":[")
                    payload.localSubtitles.forEachIndexed { sIdx, track ->
                        if (sIdx > 0) append(",")
                        append("{")
                        append("\"videoKeyHash\":").append(quoteJson(track.videoKeyHash)).append(",")
                        append("\"trackId\":").append(quoteJson(track.trackId)).append(",")
                        append("\"trackName\":").append(quoteJson(track.trackName)).append(",")
                        append("\"language\":").append(quoteJson(track.language ?: "")).append(",")
                        append("\"format\":").append(quoteJson(track.format.name)).append(",")
                        append("\"content\":").append(quoteJson(track.content)).append(",")
                        append("\"updatedAtMs\":").append(numToken("localSubtitles.$sIdx.updatedAtMs", track.updatedAtMs))
                        append("}")
                    }
                    append("]")
                }
                append("}")
            }
        }

        private fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun quoteJson(value: String): String {
            return buildString(value.length + 2) {
                append('"')
                for (ch in value) {
                    when (ch) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (ch.code < 0x20) {
                            append("\\u%04x".format(ch.code))
                        } else {
                            append(ch)
                        }
                    }
                }
                append('"')
            }
        }
    }
}

private class MiniJsonParser(private val src: String) {
    private var pos = 0
    private var depth = 0
    private var totalElements = 0

    companion object {
        private const val MAX_JSON_DEPTH = 32
        private const val MAX_JSON_ELEMENTS = 50_000
        private const val MAX_JSON_STRING_LEN = 2 * 1024 * 1024
    }

    fun parseValue(): Any? {
        skipWs()
        if (pos >= src.length) throw IllegalArgumentException("Unexpected EOF")
        return when (src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> expectLiteral("true", true)
            'f' -> expectLiteral("false", false)
            'n' -> expectLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Unexpected char at $pos")
        }
    }

    private fun enterContainer() {
        depth++
        if (depth > MAX_JSON_DEPTH) {
            throw IllegalArgumentException("JSON nesting depth exceeds $MAX_JSON_DEPTH")
        }
    }

    private fun countElement() {
        totalElements++
        if (totalElements > MAX_JSON_ELEMENTS) {
            throw IllegalArgumentException("JSON element count exceeds $MAX_JSON_ELEMENTS")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        enterContainer()
        try {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= src.length || src[pos] != '"') throw IllegalArgumentException("Expected string key")
                countElement()
                val key = parseString()
                skipWs()
                if (pos >= src.length || src[pos] != ':') throw IllegalArgumentException("Expected ':'")
                pos++
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos >= src.length) throw IllegalArgumentException("Unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
        } finally {
            depth--
        }
    }

    private fun parseArray(): List<Any?> {
        enterContainer()
        try {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                countElement()
                list.add(parseValue())
                skipWs()
                if (pos >= src.length) throw IllegalArgumentException("Unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
        } finally {
            depth--
        }
    }

    private fun parseString(): String {
        pos++ // '"'
        val sb = StringBuilder()
        while (pos < src.length) {
            if (sb.length >= MAX_JSON_STRING_LEN) {
                throw IllegalArgumentException("JSON string length exceeds $MAX_JSON_STRING_LEN")
            }
            val ch = src[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) throw IllegalArgumentException("Bad escape")
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) throw IllegalArgumentException("Bad unicode")
                            val code = src.substring(pos, pos + 4).toInt(16)
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Unknown escape $esc")
                    }
                }
                else -> sb.append(ch)
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun parseNumber(): JsonRawNumber {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        var isFloat = false
        if (pos < src.length && src[pos] == '.') {
            isFloat = true
            pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val token = src.substring(start, pos)
        val parsed: Number = if (isFloat) token.toDouble() else token.toLong()
        return JsonRawNumber(parsed, token)
    }

    private fun <T> expectLiteral(lit: String, result: T): T {
        if (!src.startsWith(lit, pos)) throw IllegalArgumentException("Expected $lit")
        pos += lit.length
        return result
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }
}

private data class JsonRawNumber(
    val number: Number,
    val rawLexeme: String
)
