package com.videohatak.player.core.vault.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class PublicExportRequest(
    val decryptedTempFile: File,
    val title: String,
    val displayName: String,
    val mimeType: String,
    val relativePath: String?,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)

sealed interface PublicExportOutcome {
    data class Exported(
        val mediaUri: Uri,
        val displayName: String,
        val relativePath: String,
        val mimeType: String,
        val verifiedSizeBytes: Long,
        val verifiedSha256: String,
        val deduplicatedExisting: Boolean = false
    ) : PublicExportOutcome

    data class RequiresLegacyWritePermission(val permission: String) : PublicExportOutcome
    data class Failed(val reason: String) : PublicExportOutcome
}

interface PublicMediaExporter {
    suspend fun exportVideo(request: PublicExportRequest): PublicExportOutcome
}

class MediaStorePublicMediaExporter(
    private val context: Context,
    private val contentResolver: ContentResolver = context.contentResolver,
    private val cryptoManager: VaultCryptoManager = VaultCryptoManager(),
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val legacyPublicRootProvider: (String) -> File = { type ->
        Environment.getExternalStoragePublicDirectory(type)
    }
) : PublicMediaExporter {

    companion object {
        private val ILLEGAL_NAME_CHARS = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")
        private val KNOWN_VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "mts", "m2ts", "flv", "wmv", "mpeg", "mpg"
        )

        private val MIME_TO_EXTENSION = mapOf(
            "video/mp4" to "mp4",
            "video/x-matroska" to "mkv",
            "video/mkv" to "mkv",
            "video/webm" to "webm",
            "video/quicktime" to "mov",
            "video/x-msvideo" to "avi",
            "video/avi" to "avi",
            "video/3gpp" to "3gp",
            "video/3gpp2" to "3g2",
            "video/x-m4v" to "m4v",
            "video/mp2t" to "ts",
            "video/mpeg" to "mpeg",
            "video/x-flv" to "flv",
            "video/x-ms-wmv" to "wmv"
        )

        private val EXTENSION_TO_MIME = mapOf(
            "mp4" to "video/mp4",
            "m4v" to "video/mp4",
            "mkv" to "video/x-matroska",
            "webm" to "video/webm",
            "mov" to "video/quicktime",
            "avi" to "video/x-msvideo",
            "3gp" to "video/3gpp",
            "3g2" to "video/3gpp2",
            "ts" to "video/mp2t",
            "mts" to "video/mp2t",
            "m2ts" to "video/mp2t",
            "mpeg" to "video/mpeg",
            "mpg" to "video/mpeg",
            "flv" to "video/x-flv",
            "wmv" to "video/x-ms-wmv"
        )

        internal fun resolveMetadataForExport(
            rawDisplayName: String,
            rawTitle: String,
            rawMimeType: String,
            rawRelativePath: String?
        ): Triple<String, String, String> {
            val cleanedDisplay = ILLEGAL_NAME_CHARS.replace(rawDisplayName, "_").trim()
            val cleanedTitle = ILLEGAL_NAME_CHARS.replace(rawTitle, "_").trim()

            val extFromDisplay = cleanedDisplay.substringAfterLast('.', "").lowercase(Locale.US)
                .takeIf { it in KNOWN_VIDEO_EXTENSIONS }
            val extFromTitle = cleanedTitle.substringAfterLast('.', "").lowercase(Locale.US)
                .takeIf { it in KNOWN_VIDEO_EXTENSIONS }
            val normalizedRawMime = rawMimeType.trim().lowercase(Locale.US)
                .takeIf { it.startsWith("video/") && it != "video/*" }

            val extFromMime = normalizedRawMime?.let { MIME_TO_EXTENSION[it] }
            val resolvedExt = extFromDisplay ?: extFromTitle ?: extFromMime ?: "mp4"

            val resolvedMime = normalizedRawMime
                ?: EXTENSION_TO_MIME[resolvedExt]
                ?: "video/mp4"

            val rawBase = when {
                extFromDisplay != null -> cleanedDisplay.dropLast(extFromDisplay.length + 1).trim()
                cleanedDisplay.isNotBlank() -> cleanedDisplay
                extFromTitle != null -> cleanedTitle.dropLast(extFromTitle.length + 1).trim()
                cleanedTitle.isNotBlank() -> cleanedTitle
                else -> "video_${System.currentTimeMillis()}"
            }.ifBlank { "video_${System.currentTimeMillis()}" }

            val finalDisplayName = "$rawBase.$resolvedExt"
            val finalRelativePath = normalizeMediaStoreVideoRelativePath(rawRelativePath)
            return Triple(finalDisplayName, resolvedMime, finalRelativePath)
        }

        internal fun normalizeMediaStoreVideoRelativePath(rawRelativePath: String?): String {
            val raw = rawRelativePath?.trim().orEmpty()
            if (raw.isBlank() || raw.equals("vault", ignoreCase = true)) {
                return "${Environment.DIRECTORY_MOVIES}/"
            }
            val segments = LocalVideoRepositoryImpl.sanitizeFolderSegments(raw)
            if (segments.isEmpty()) {
                return "${Environment.DIRECTORY_MOVIES}/"
            }
            val firstLower = segments.first().lowercase(Locale.US)
            return when (firstLower) {
                Environment.DIRECTORY_MOVIES.lowercase(Locale.US) -> {
                    (listOf(Environment.DIRECTORY_MOVIES) + segments.drop(1)).joinToString("/") + "/"
                }
                Environment.DIRECTORY_DCIM.lowercase(Locale.US) -> {
                    (listOf(Environment.DIRECTORY_DCIM) + segments.drop(1)).joinToString("/") + "/"
                }
                else -> "${Environment.DIRECTORY_MOVIES}/"
            }
        }
    }

    override suspend fun exportVideo(request: PublicExportRequest): PublicExportOutcome {
        val tempFile = request.decryptedTempFile
        if (!tempFile.exists() || !tempFile.isFile || tempFile.length() <= 0L) {
            return PublicExportOutcome.Failed("الملف المفكوك غير صالح أو مفقود")
        }
        val expectedSize = request.expectedSizeBytes.takeIf { it > 0L } ?: tempFile.length()
        if (tempFile.length() != expectedSize) {
            return PublicExportOutcome.Failed("حجم الملف المفكوك لا يطابق الحجم الأصلي المسجل")
        }

        val (targetDisplayName, targetMimeType, targetRelativePath) = resolveMetadataForExport(
            rawDisplayName = request.displayName,
            rawTitle = request.title,
            rawMimeType = request.mimeType,
            rawRelativePath = request.relativePath
        )
        val targetTitle = targetDisplayName.substringBeforeLast('.').ifBlank { targetDisplayName }
        val sdkInt = sdkIntProvider()

        return if (sdkInt >= Build.VERSION_CODES.Q) {
            exportViaScopedMediaStore(
                request = request,
                tempFile = tempFile,
                targetDisplayName = targetDisplayName,
                targetTitle = targetTitle,
                targetMimeType = targetMimeType,
                targetRelativePath = targetRelativePath,
                expectedSize = expectedSize
            )
        } else {
            exportViaLegacyStorage(
                request = request,
                tempFile = tempFile,
                targetDisplayName = targetDisplayName,
                targetTitle = targetTitle,
                targetMimeType = targetMimeType,
                targetRelativePath = targetRelativePath,
                expectedSize = expectedSize
            )
        }
    }

    private fun exportViaScopedMediaStore(
        request: PublicExportRequest,
        tempFile: File,
        targetDisplayName: String,
        targetTitle: String,
        targetMimeType: String,
        targetRelativePath: String,
        expectedSize: Long
    ): PublicExportOutcome {
        val existingMatch = findExistingPublicMatch(
            targetDisplayName = targetDisplayName,
            expectedSize = expectedSize,
            expectedSha256 = request.expectedSha256
        )
        if (existingMatch != null) {
            return PublicExportOutcome.Exported(
                mediaUri = existingMatch.first,
                displayName = existingMatch.second,
                relativePath = targetRelativePath,
                mimeType = targetMimeType,
                verifiedSizeBytes = expectedSize,
                verifiedSha256 = request.expectedSha256,
                deduplicatedExisting = true
            )
        }

        val uniqueDisplayName = resolveNonCollidingMediaStoreName(targetDisplayName, targetRelativePath)
        val nowSec = System.currentTimeMillis() / 1000L

        var usedRelativePath = targetRelativePath
        var insertedUri = insertScopedVideoRow(
            displayName = uniqueDisplayName,
            title = targetTitle,
            mimeType = targetMimeType,
            relativePath = usedRelativePath,
            expectedSize = expectedSize,
            durationMs = request.durationMs,
            width = request.width,
            height = request.height,
            nowSec = nowSec
        )
        if (insertedUri == null && usedRelativePath != "${Environment.DIRECTORY_MOVIES}/") {
            usedRelativePath = "${Environment.DIRECTORY_MOVIES}/"
            val fallbackName = resolveNonCollidingMediaStoreName(targetDisplayName, usedRelativePath)
            insertedUri = insertScopedVideoRow(
                displayName = fallbackName,
                title = targetTitle,
                mimeType = targetMimeType,
                relativePath = usedRelativePath,
                expectedSize = expectedSize,
                durationMs = request.durationMs,
                width = request.width,
                height = request.height,
                nowSec = nowSec
            )
        }
        if (insertedUri == null) {
            return PublicExportOutcome.Failed("تعذر إنشاء سجل الفيديو في مكتبة الهاتف (MediaStore)")
        }

        return try {
            val outStream = contentResolver.openOutputStream(insertedUri, "w")
                ?: throw IOException("تعذر فتح مجرى الكتابة في مكتبة الوسائط")
            var writtenBytes = 0L
            outStream.use { out ->
                FileInputStream(tempFile).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read > 0) {
                            out.write(buffer, 0, read)
                            writtenBytes += read
                        }
                    }
                    out.flush()
                }
            }
            if (writtenBytes != expectedSize) {
                throw IOException("عدم تطابق عدد البايتات المكتوبة في MediaStore ($writtenBytes / $expectedSize)")
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            val updatedRows = contentResolver.update(insertedUri, publishValues, null, null)
            if (updatedRows <= 0) {
                throw IOException("تعذر نشر الفيديو وإزالة حالة الانتظار (IS_PENDING)")
            }

            val (verifiedSize, verifiedSha256) = verifyPublishedMediaStoreUri(
                uri = insertedUri,
                expectedSize = expectedSize,
                expectedSha256 = request.expectedSha256,
                requireNotPending = true
            )

            PublicExportOutcome.Exported(
                mediaUri = insertedUri,
                displayName = uniqueDisplayName,
                relativePath = usedRelativePath,
                mimeType = targetMimeType,
                verifiedSizeBytes = verifiedSize,
                verifiedSha256 = verifiedSha256,
                deduplicatedExisting = false
            )
        } catch (e: Exception) {
            runCatching { contentResolver.delete(insertedUri, null, null) }
            PublicExportOutcome.Failed(
                e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "تعذر كتابة أو التحقق من الفيديو المستعاد في مكتبة الهاتف"
            )
        }
    }

    private fun insertScopedVideoRow(
        displayName: String,
        title: String,
        mimeType: String,
        relativePath: String,
        expectedSize: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        nowSec: Long
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.TITLE, title)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.SIZE, expectedSize)
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.DATE_ADDED, nowSec)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowSec)
            if (durationMs > 0L) put(MediaStore.Video.Media.DURATION, durationMs)
            if (width > 0) put(MediaStore.Video.Media.WIDTH, width)
            if (height > 0) put(MediaStore.Video.Media.HEIGHT, height)
        }
        return try {
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun exportViaLegacyStorage(
        request: PublicExportRequest,
        tempFile: File,
        targetDisplayName: String,
        targetTitle: String,
        targetMimeType: String,
        targetRelativePath: String,
        expectedSize: Long
    ): PublicExportOutcome {
        val legacyPerm = PermissionHelper.getLegacyWritePermission()
        if (legacyPerm != null && !PermissionHelper.hasLegacyWritePermission(context)) {
            return PublicExportOutcome.RequiresLegacyWritePermission(legacyPerm)
        }

        val existingMatch = findExistingPublicMatch(
            targetDisplayName = targetDisplayName,
            expectedSize = expectedSize,
            expectedSha256 = request.expectedSha256
        )
        if (existingMatch != null) {
            return PublicExportOutcome.Exported(
                mediaUri = existingMatch.first,
                displayName = existingMatch.second,
                relativePath = targetRelativePath,
                mimeType = targetMimeType,
                verifiedSizeBytes = expectedSize,
                verifiedSha256 = request.expectedSha256,
                deduplicatedExisting = true
            )
        }

        val segments = LocalVideoRepositoryImpl.sanitizeFolderSegments(targetRelativePath)
        val rootType = if (segments.firstOrNull()?.equals(Environment.DIRECTORY_DCIM, ignoreCase = true) == true) {
            Environment.DIRECTORY_DCIM
        } else {
            Environment.DIRECTORY_MOVIES
        }
        val publicRoot = legacyPublicRootProvider(rootType)
        val subSegments = segments.drop(1)
        val targetDir = if (subSegments.isEmpty()) {
            publicRoot
        } else {
            File(publicRoot, subSegments.joinToString(File.separator))
        }
        val canonicalRoot = publicRoot.canonicalPath
        val canonicalTargetDir = targetDir.canonicalPath
        if (canonicalTargetDir != canonicalRoot && !canonicalTargetDir.startsWith(canonicalRoot + File.separator)) {
            return PublicExportOutcome.Failed("مسار المجلد الهدف غير صالح")
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return PublicExportOutcome.Failed("تعذر إنشاء مجلد الاستعادة العام")
        }

        // Check existing physical files in targetDir for identical SHA-256 to prevent duplicates on retry
        val existingDiskMatch = findMatchingDiskFile(targetDir, targetDisplayName, expectedSize, request.expectedSha256)
        val (finalFile, createdNewFile) = if (existingDiskMatch != null) {
            existingDiskMatch to false
        } else {
            resolveNonCollidingDiskFile(targetDir, targetDisplayName) to true
        }

        val stagedPublicTemp = File(targetDir, ".restore_${UUID.randomUUID()}.tmp")
        var insertedMediaUri: Uri? = null

        return try {
            if (createdNewFile) {
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(stagedPublicTemp).use { output ->
                        input.copyTo(output)
                        output.flush()
                        runCatching { output.fd.sync() }
                    }
                }
                if (!stagedPublicTemp.exists() || stagedPublicTemp.length() != expectedSize) {
                    throw IOException("فشل كتابة الملف المؤقت في المجلد العام")
                }
                if (!stagedPublicTemp.renameTo(finalFile)) {
                    throw IOException("تعذر تثبيت الملف المستعاد في المجلد العام")
                }
                if (!finalFile.exists() || finalFile.length() != expectedSize) {
                    throw IOException("الملف المستعاد على وحدة التخزين غير مكتمل")
                }
                val diskSha = FileInputStream(finalFile).use { cryptoManager.computeStreamSha256(it) }
                if (!diskSha.equals(request.expectedSha256, ignoreCase = true)) {
                    throw IOException("تطابق بصمة SHA-256 للملف المستعاد على وحدة التخزين فشل")
                }
            }

            val nowSec = System.currentTimeMillis() / 1000L
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, finalFile.name)
                put(MediaStore.Video.Media.TITLE, targetTitle)
                put(MediaStore.Video.Media.MIME_TYPE, targetMimeType)
                put(MediaStore.Video.Media.SIZE, expectedSize)
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, finalFile.absolutePath)
                put(MediaStore.Video.Media.DATE_ADDED, nowSec)
                put(MediaStore.Video.Media.DATE_MODIFIED, nowSec)
                if (request.durationMs > 0L) put(MediaStore.Video.Media.DURATION, request.durationMs)
                if (request.width > 0) put(MediaStore.Video.Media.WIDTH, request.width)
                if (request.height > 0) put(MediaStore.Video.Media.HEIGHT, request.height)
            }
            insertedMediaUri = runCatching {
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()

            val scannedUri = if (insertedMediaUri == null) {
                scanFileSync(finalFile, targetMimeType)
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(finalFile.absolutePath),
                    arrayOf(targetMimeType),
                    null
                )
                insertedMediaUri
            }

            val finalUri = scannedUri ?: throw IOException("تعذر تسجيل الفيديو المستعاد في مكتبة الوسائط")
            val (verifiedSize, verifiedSha) = if (finalUri.scheme == ContentResolver.SCHEME_CONTENT) {
                verifyUriOrFileReadable(finalUri, finalFile, expectedSize, request.expectedSha256)
            } else {
                val sha = FileInputStream(finalFile).use { cryptoManager.computeStreamSha256(it) }
                if (!sha.equals(request.expectedSha256, ignoreCase = true)) {
                    throw IOException("عدم تطابق SHA-256 بعد المسح الضوئي")
                }
                finalFile.length() to sha
            }

            PublicExportOutcome.Exported(
                mediaUri = finalUri,
                displayName = finalFile.name,
                relativePath = targetRelativePath,
                mimeType = targetMimeType,
                verifiedSizeBytes = verifiedSize,
                verifiedSha256 = verifiedSha,
                deduplicatedExisting = !createdNewFile
            )
        } catch (e: Exception) {
            runCatching { if (stagedPublicTemp.exists()) stagedPublicTemp.delete() }
            if (insertedMediaUri != null) {
                runCatching { contentResolver.delete(insertedMediaUri, null, null) }
            }
            if (createdNewFile) {
                runCatching { if (finalFile.exists()) finalFile.delete() }
            }
            PublicExportOutcome.Failed(
                e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "تعذر استعادة الفيديو إلى وحدة التخزين العامة"
            )
        }
    }

    private fun verifyUriOrFileReadable(
        uri: Uri,
        diskFile: File,
        expectedSize: Long,
        expectedSha256: String
    ): Pair<Long, String> {
        val stream = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        if (stream != null) {
            return verifyStreamHashAndSize(stream, expectedSize, expectedSha256)
        }
        if (!diskFile.exists() || !diskFile.canRead() || diskFile.length() != expectedSize) {
            throw IOException("تعذر الوصول إلى الفيديو المستعاد للتحقق من سلامته")
        }
        val sha = FileInputStream(diskFile).use { cryptoManager.computeStreamSha256(it) }
        if (!sha.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("فشل التحقق من بصمة SHA-256 للفيديو المستعاد")
        }
        return diskFile.length() to sha
    }

    private fun scanFileSync(file: File, mimeType: String): Uri? {
        val latch = CountDownLatch(1)
        var resultUri: Uri? = null
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { _, uri ->
                resultUri = uri
                latch.countDown()
            }
        }.onFailure {
            latch.countDown()
        }
        runCatching { latch.await(2, TimeUnit.SECONDS) }
        return resultUri ?: Uri.fromFile(file)
    }

    private fun verifyPublishedMediaStoreUri(
        uri: Uri,
        expectedSize: Long,
        expectedSha256: String,
        requireNotPending: Boolean
    ): Pair<Long, String> {
        val isQPlus = sdkIntProvider() >= Build.VERSION_CODES.Q
        val cols = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.SIZE)
            if (requireNotPending && isQPlus) {
                add(MediaStore.Video.Media.IS_PENDING)
            }
        }.toTypedArray()

        contentResolver.query(uri, cols, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                throw IOException("الفيديو المستعاد غير ظاهر في فهرس مكتبة الوسائط")
            }
            if (requireNotPending && isQPlus) {
                val pendingCol = cursor.getColumnIndex(MediaStore.Video.Media.IS_PENDING)
                if (pendingCol >= 0 && (cursor.getIntOrNull(pendingCol) ?: 0) != 0) {
                    throw IOException("لا يزال الفيديو المستعاد في وضع الانتظار (IS_PENDING)")
                }
            }
            val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            if (sizeCol >= 0) {
                val reportedSize = cursor.getLongOrNull(sizeCol) ?: -1L
                if (reportedSize > 0L && reportedSize != expectedSize) {
                    throw IOException("حجم السجل في مكتبة الوسائط ($reportedSize) لا يطابق الأصل ($expectedSize)")
                }
            }
        }

        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("تعذر فتح الفيديو المستعاد للتحقق من إمكانية الوصول")
        return verifyStreamHashAndSize(inputStream, expectedSize, expectedSha256)
    }

    private fun verifyStreamHashAndSize(
        inputStream: java.io.InputStream,
        expectedSize: Long,
        expectedSha256: String
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalRead = 0L
        inputStream.use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buf)
                if (read == -1) break
                if (read > 0) {
                    digest.update(buf, 0, read)
                    totalRead += read
                }
            }
        }
        if (totalRead != expectedSize) {
            throw IOException("حجم المحتوى القابل للقراءة ($totalRead) لا يطابق الحجم الأصلي ($expectedSize)")
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256.isNotBlank() && !actualSha.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("بصمة SHA-256 للملف المستعاد لا تطابق الأصل")
        }
        return totalRead to actualSha
    }

    private fun findExistingPublicMatch(
        targetDisplayName: String,
        expectedSize: Long,
        expectedSha256: String
    ): Pair<Uri, String>? {
        if (expectedSha256.isBlank() || expectedSize <= 0L) return null
        val isQPlus = sdkIntProvider() >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.SIZE)
            if (isQPlus) {
                add(MediaStore.Video.Media.IS_PENDING)
            }
        }.toTypedArray()

        return try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val pendingCol = if (isQPlus) {
                    cursor.getColumnIndex(MediaStore.Video.Media.IS_PENDING)
                } else -1

                while (cursor.moveToNext()) {
                    val rowPending = if (pendingCol >= 0) (cursor.getIntOrNull(pendingCol) ?: 0) else 0
                    if (rowPending != 0) continue

                    val rowId = if (idCol >= 0) cursor.getLongOrNull(idCol) else null
                    if (rowId == null || rowId <= 0L) continue

                    val rowSize = if (sizeCol >= 0) (cursor.getLongOrNull(sizeCol) ?: -1L) else -1L
                    if (rowSize > 0L && rowSize != expectedSize) continue

                    val candidateUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        rowId
                    )
                    val rowName = if (nameCol >= 0) cursor.getStringOrNull(nameCol).orEmpty() else ""

                    val match = runCatching {
                        val stream = contentResolver.openInputStream(candidateUri) ?: return@runCatching false
                        val (_, actualSha) = verifyStreamHashAndSize(stream, expectedSize, expectedSha256)
                        actualSha.equals(expectedSha256, ignoreCase = true)
                    }.getOrDefault(false)

                    if (match) {
                        return@use candidateUri to rowName.ifBlank { targetDisplayName }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveNonCollidingMediaStoreName(
        desiredDisplayName: String,
        targetRelativePath: String
    ): String {
        val existingNames = mutableSetOf<String>()
        val isQPlus = sdkIntProvider() >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Video.Media.DISPLAY_NAME)
            if (isQPlus) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        runCatching {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val relCol = if (isQPlus) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    val rowName = if (nameCol >= 0) cursor.getStringOrNull(nameCol) else null
                    val rowRel = if (relCol >= 0) cursor.getStringOrNull(relCol) else null
                    if (!rowName.isNullOrBlank()) {
                        if (rowRel.isNullOrBlank() || normalizeMediaStoreVideoRelativePath(rowRel).equals(targetRelativePath, ignoreCase = true)) {
                            existingNames.add(rowName.lowercase(Locale.US))
                        }
                    }
                }
            }
        }

        if (desiredDisplayName.lowercase(Locale.US) !in existingNames) {
            return desiredDisplayName
        }

        val dotIdx = desiredDisplayName.lastIndexOf('.')
        val base = if (dotIdx > 0) desiredDisplayName.substring(0, dotIdx) else desiredDisplayName
        val ext = if (dotIdx > 0) desiredDisplayName.substring(dotIdx) else ""
        var counter = 1
        while (counter < 10_000) {
            val candidate = "$base ($counter)$ext"
            if (candidate.lowercase(Locale.US) !in existingNames) {
                return candidate
            }
            counter++
        }
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    private fun findMatchingDiskFile(
        targetDir: File,
        desiredDisplayName: String,
        expectedSize: Long,
        expectedSha256: String
    ): File? {
        if (expectedSha256.isBlank() || expectedSize <= 0L) return null
        val dotIdx = desiredDisplayName.lastIndexOf('.')
        val base = if (dotIdx > 0) desiredDisplayName.substring(0, dotIdx) else desiredDisplayName
        val files = targetDir.listFiles()?.filter {
            it.isFile && !it.name.startsWith(".") && it.length() == expectedSize &&
                (it.name.equals(desiredDisplayName, ignoreCase = true) || it.name.startsWith("$base (", ignoreCase = true))
        }.orEmpty()
        for (file in files) {
            val sha = runCatching {
                FileInputStream(file).use { cryptoManager.computeStreamSha256(it) }
            }.getOrNull()
            if (sha != null && sha.equals(expectedSha256, ignoreCase = true)) {
                return file
            }
        }
        return null
    }

    private fun resolveNonCollidingDiskFile(targetDir: File, desiredDisplayName: String): File {
        val initial = File(targetDir, desiredDisplayName)
        if (!initial.exists()) return initial
        val dotIdx = desiredDisplayName.lastIndexOf('.')
        val base = if (dotIdx > 0) desiredDisplayName.substring(0, dotIdx) else desiredDisplayName
        val ext = if (dotIdx > 0) desiredDisplayName.substring(dotIdx) else ""
        var counter = 1
        while (counter < 10_000) {
            val candidate = File(targetDir, "$base ($counter)$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
        return File(targetDir, "${base}_${System.currentTimeMillis()}$ext")
    }
}
