package com.videohatak.player.core.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.videohatak.player.core.model.SortDirection
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.model.VideoSortField
import com.videohatak.player.core.model.VideoSortOrder
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VideoFileOperationResult {
    data object Success : VideoFileOperationResult
    data class RequiresPermission(
        val intentSender: IntentSender,
        val pendingOp: PendingVideoFileOp
    ) : VideoFileOperationResult
    data class RequiresLegacyWritePermission(
        val permission: String,
        val pendingOp: PendingVideoFileOp
    ) : VideoFileOperationResult
    data class Error(val message: String) : VideoFileOperationResult
}

sealed interface PendingVideoFileOp {
    data class Rename(val video: VideoItem, val newTitle: String) : PendingVideoFileOp
    data class Move(val video: VideoItem, val targetRelativePath: String, val targetFolderName: String) : PendingVideoFileOp
    data class DeletePermanent(val video: VideoItem, val trashItemId: Long? = null) : PendingVideoFileOp
}

interface VideoRepository {
    fun observeVideos(): Flow<List<VideoItem>>
    suspend fun getVideos(): List<VideoItem>
    fun filterAndSort(
        videos: List<VideoItem>,
        query: String,
        sortOrder: VideoSortOrder,
        folderId: String? = null
    ): List<VideoItem>
    fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder>

    suspend fun doesMediaExist(video: VideoItem): Boolean = true

    suspend fun renameVideo(video: VideoItem, newName: String): VideoFileOperationResult {
        return VideoFileOperationResult.Error("غير مدعوم")
    }

    suspend fun moveVideoToFolder(
        video: VideoItem,
        targetRelativePath: String,
        targetFolderName: String
    ): VideoFileOperationResult {
        return VideoFileOperationResult.Error("غير مدعوم")
    }

    suspend fun deleteVideoPermanently(
        video: VideoItem,
        trashItemId: Long? = null
    ): VideoFileOperationResult {
        return VideoFileOperationResult.Error("غير مدعوم")
    }

    suspend fun completePermanentDeleteAfterPermission(
        video: VideoItem,
        trashItemId: Long? = null
    ): VideoFileOperationResult {
        return if (!doesMediaExist(video)) {
            VideoFileOperationResult.Success
        } else {
            deleteVideoPermanently(video, trashItemId)
        }
    }
}

class LocalVideoRepositoryImpl(
    private val context: Context,
    private val contentResolver: ContentResolver = context.contentResolver
) : VideoRepository {

    private val playlistDb by lazy { VideoHatakDatabaseHelper(context) }

    override fun observeVideos(): Flow<List<VideoItem>> = callbackFlow {
        trySend(getVideos())

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val updatedVideos = queryVideosInternal()
                trySend(updatedVideos)
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        queryVideosInternal()
    }

    private fun queryVideosInternal(): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()

        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Video.Media.RELATIVE_PATH)
        }

        val selection = "${MediaStore.Video.Media.DURATION} > 0 AND ${MediaStore.Video.Media.SIZE} > 0"
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                @Suppress("DEPRECATION")
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val bucketIdCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    try {
                        val id = cursor.getLongOrNull(idCol) ?: continue
                        val displayName = cursor.getStringOrNull(nameCol) ?: ""
                        val title = cursor.getStringOrNull(titleCol) ?: ""
                        val durationMs = cursor.getLongOrNull(durationCol) ?: 0L
                        val sizeBytes = cursor.getLongOrNull(sizeCol) ?: 0L
                        val dateAddedSec = cursor.getLongOrNull(dateAddedCol) ?: 0L
                        val dateModifiedSec = cursor.getLongOrNull(dateModifiedCol) ?: 0L
                        val width = cursor.getIntOrNull(widthCol) ?: 0
                        val height = cursor.getIntOrNull(heightCol) ?: 0
                        val mimeType = cursor.getStringOrNull(mimeCol) ?: "video/*"
                        val path = cursor.getStringOrNull(dataCol) ?: ""
                        val rawBucketId = cursor.getStringOrNull(bucketIdCol)
                        val rawBucketName = cursor.getStringOrNull(bucketNameCol)
                        val relativePath = if (relativePathCol >= 0) {
                            cursor.getStringOrNull(relativePathCol) ?: ""
                        } else ""

                        val resolvedBucketId = rawBucketId ?: if (path.isNotBlank()) {
                            File(path).parentFile?.name.hashCode().toString()
                        } else "default_bucket"

                        val resolvedBucketName = when {
                            !rawBucketName.isNullOrBlank() -> rawBucketName
                            relativePath.isNotBlank() -> relativePath.trim('/').substringAfterLast('/')
                            path.isNotBlank() -> File(path).parentFile?.name ?: "مجلد الفيديوهات"
                            else -> "مجلد الفيديوهات"
                        }

                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        videos.add(
                            VideoItem(
                                id = id,
                                contentUri = contentUri,
                                title = title,
                                displayName = displayName,
                                durationMs = durationMs,
                                sizeBytes = sizeBytes,
                                dateAddedSec = dateAddedSec,
                                dateModifiedSec = dateModifiedSec,
                                width = width,
                                height = height,
                                mimeType = mimeType,
                                path = path,
                                relativePath = relativePath,
                                bucketId = resolvedBucketId,
                                bucketDisplayName = resolvedBucketName
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    override suspend fun renameVideo(video: VideoItem, newName: String): VideoFileOperationResult =
        withContext(Dispatchers.IO) {
            val cleaned = newName.trim().replace('/', '_').replace('\\', '_')
            if (cleaned.isBlank()) {
                return@withContext VideoFileOperationResult.Error("الاسم الجديد غير صالح")
            }

            val existingExt = video.displayName.substringAfterLast('.', "")
            val hasExt = cleaned.contains('.') && cleaned.substringAfterLast('.', "").length in 2..5
            val finalDisplayName = if (hasExt || existingExt.isBlank()) cleaned else "$cleaned.$existingExt"
            val finalTitle = finalDisplayName.substringBeforeLast('.')

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, finalDisplayName)
                        put(MediaStore.Video.Media.TITLE, finalTitle)
                    }
                    val updated = contentResolver.update(video.contentUri, values, null, null)
                    if (updated > 0) {
                        runCatching { playlistDb.updateItemsTitleForVideo(video.id, finalTitle) }
                        return@withContext VideoFileOperationResult.Success
                    }
                    // On API 30+, some devices return 0 instead of throwing SecurityException when write consent is needed
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pi = MediaStore.createWriteRequest(contentResolver, listOf(video.contentUri))
                        return@withContext VideoFileOperationResult.RequiresPermission(
                            pi.intentSender,
                            PendingVideoFileOp.Rename(video, newName)
                        )
                    }
                    VideoFileOperationResult.Error("تعذر تعديل اسم الفيديو")
                } else {
                    val legacyPerm = PermissionHelper.getLegacyWritePermission()
                    if (legacyPerm != null && !PermissionHelper.hasLegacyWritePermission(context)) {
                        return@withContext VideoFileOperationResult.RequiresLegacyWritePermission(
                            permission = legacyPerm,
                            pendingOp = PendingVideoFileOp.Rename(video, newName)
                        )
                    }
                    val sourceFile = File(video.path)
                    if (sourceFile.exists() && sourceFile.parentFile != null) {
                        val targetFile = File(sourceFile.parentFile, finalDisplayName)
                        if (targetFile.exists()) {
                            return@withContext VideoFileOperationResult.Error("يوجد ملف بنفس الاسم بالفعل")
                        }
                        if (sourceFile.renameTo(targetFile)) {
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, finalDisplayName)
                                put(MediaStore.Video.Media.TITLE, finalTitle)
                                @Suppress("DEPRECATION")
                                put(MediaStore.Video.Media.DATA, targetFile.absolutePath)
                            }
                            contentResolver.update(video.contentUri, values, null, null)
                            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                            runCatching { playlistDb.updateItemsTitleForVideo(video.id, finalTitle) }
                            VideoFileOperationResult.Success
                        } else {
                            VideoFileOperationResult.Error("لم يتم منح صلاحية الكتابة على وحدة التخزين")
                        }
                    } else {
                        VideoFileOperationResult.Error("تعذر العثور على مسار الملف")
                    }
                }
            } catch (e: SecurityException) {
                buildWritePermissionRequest(e, PendingVideoFileOp.Rename(video, newName), video.contentUri)
            } catch (e: Exception) {
                VideoFileOperationResult.Error(e.localizedMessage ?: "فشل إعادة التسمية")
            }
        }

    override suspend fun moveVideoToFolder(
        video: VideoItem,
        targetRelativePath: String,
        targetFolderName: String
    ): VideoFileOperationResult = withContext(Dispatchers.IO) {
        val normalizedRelPath = buildNormalizedRelativePath(targetRelativePath, targetFolderName)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.RELATIVE_PATH, normalizedRelPath)
                }
                val updated = contentResolver.update(video.contentUri, values, null, null)
                if (updated > 0) {
                    return@withContext VideoFileOperationResult.Success
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pi = MediaStore.createWriteRequest(contentResolver, listOf(video.contentUri))
                    return@withContext VideoFileOperationResult.RequiresPermission(
                        pi.intentSender,
                        PendingVideoFileOp.Move(video, normalizedRelPath, targetFolderName)
                    )
                }
                VideoFileOperationResult.Error("تعذر نقل الفيديو إلى المجلد المحدد")
            } else {
                val legacyPerm = PermissionHelper.getLegacyWritePermission()
                if (legacyPerm != null && !PermissionHelper.hasLegacyWritePermission(context)) {
                    return@withContext VideoFileOperationResult.RequiresLegacyWritePermission(
                        permission = legacyPerm,
                        pendingOp = PendingVideoFileOp.Move(video, normalizedRelPath, targetFolderName)
                    )
                }
                val sourceFile = File(video.path)
                if (!sourceFile.exists()) {
                    return@withContext VideoFileOperationResult.Error("تعذر العثور على الملف الأصلي")
                }
                val moviesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val destDir = File(moviesRoot, targetFolderName.trim().ifBlank { "VideoHatak" })
                if (!destDir.exists()) destDir.mkdirs()

                val fileName = video.displayName.ifBlank { sourceFile.name }
                val destFile = File(destDir, fileName)
                if (destFile.absolutePath == sourceFile.absolutePath) {
                    return@withContext VideoFileOperationResult.Success
                }
                if (destFile.exists()) {
                    return@withContext VideoFileOperationResult.Error("يوجد ملف بنفس الاسم داخل المجلد الهدف")
                }

                val moved = sourceFile.renameTo(destFile) || run {
                    sourceFile.inputStream().use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    sourceFile.delete()
                }
                if (moved) {
                    val values = ContentValues().apply {
                        @Suppress("DEPRECATION")
                        put(MediaStore.Video.Media.DATA, destFile.absolutePath)
                    }
                    contentResolver.update(video.contentUri, values, null, null)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(sourceFile.absolutePath, destFile.absolutePath),
                        null,
                        null
                    )
                    VideoFileOperationResult.Success
                } else {
                    VideoFileOperationResult.Error("فشل نقل الملف")
                }
            }
        } catch (e: SecurityException) {
            buildWritePermissionRequest(
                e,
                PendingVideoFileOp.Move(video, normalizedRelPath, targetFolderName),
                video.contentUri
            )
        } catch (e: Exception) {
            VideoFileOperationResult.Error(e.localizedMessage ?: "تعذر نقل الفيديو")
        }
    }

    override suspend fun doesMediaExist(video: VideoItem): Boolean = withContext(Dispatchers.IO) {
        if (video.path.isNotBlank() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val file = File(video.path)
            if (file.exists()) return@withContext true
        }
        val existsInStore = try {
            contentResolver.query(
                video.contentUri,
                arrayOf(MediaStore.Video.Media._ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
        if (existsInStore) return@withContext true
        try {
            contentResolver.openAssetFileDescriptor(video.contentUri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun deleteVideoPermanently(
        video: VideoItem,
        trashItemId: Long?
    ): VideoFileOperationResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val deleted = try {
                    contentResolver.delete(video.contentUri, null, null)
                } catch (_: SecurityException) {
                    0
                }
                if (deleted > 0 || !doesMediaExist(video)) {
                    return@withContext VideoFileOperationResult.Success
                }
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(video.contentUri))
                VideoFileOperationResult.RequiresPermission(
                    pi.intentSender,
                    PendingVideoFileOp.DeletePermanent(video, trashItemId)
                )
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val deleted = contentResolver.delete(video.contentUri, null, null)
                if (deleted > 0 || !doesMediaExist(video)) {
                    VideoFileOperationResult.Success
                } else {
                    VideoFileOperationResult.Error("تعذر حذف ملف الفيديو نهائيًا")
                }
            } else {
                val legacyPerm = PermissionHelper.getLegacyWritePermission()
                if (legacyPerm != null && !PermissionHelper.hasLegacyWritePermission(context)) {
                    return@withContext VideoFileOperationResult.RequiresLegacyWritePermission(
                        permission = legacyPerm,
                        pendingOp = PendingVideoFileOp.DeletePermanent(video, trashItemId)
                    )
                }
                val file = if (video.path.isNotBlank()) File(video.path) else null
                val fileDeleted = file == null || !file.exists() || file.delete()
                val mediaDeleted = contentResolver.delete(video.contentUri, null, null)
                if (fileDeleted && (mediaDeleted > 0 || file != null)) {
                    if (file != null) {
                        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                    }
                    VideoFileOperationResult.Success
                } else {
                    VideoFileOperationResult.Error("تعذر حذف الملف من وحدة التخزين")
                }
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(video.contentUri))
                VideoFileOperationResult.RequiresPermission(
                    pi.intentSender,
                    PendingVideoFileOp.DeletePermanent(video, trashItemId)
                )
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                VideoFileOperationResult.RequiresPermission(
                    e.userAction.actionIntent.intentSender,
                    PendingVideoFileOp.DeletePermanent(video, trashItemId)
                )
            } else {
                VideoFileOperationResult.Error("يرجى منح صلاحية حذف الملف")
            }
        } catch (e: Exception) {
            VideoFileOperationResult.Error(e.localizedMessage ?: "فشل حذف الفيديو نهائيًا")
        }
    }

    override suspend fun completePermanentDeleteAfterPermission(
        video: VideoItem,
        trashItemId: Long?
    ): VideoFileOperationResult = withContext(Dispatchers.IO) {
        if (!doesMediaExist(video)) {
            return@withContext VideoFileOperationResult.Success
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val deleted = contentResolver.delete(video.contentUri, null, null)
                if (deleted > 0 || !doesMediaExist(video)) {
                    VideoFileOperationResult.Success
                } else {
                    VideoFileOperationResult.Error("لم يكتمل حذف الملف من ذاكرة الجهاز، تم الإبقاء على السجل")
                }
            } else {
                deleteVideoPermanently(video, trashItemId)
            }
        } catch (e: Exception) {
            if (!doesMediaExist(video)) {
                VideoFileOperationResult.Success
            } else {
                VideoFileOperationResult.Error(e.localizedMessage ?: "تعذر حذف الملف بعد منح الإذن")
            }
        }
    }

    private fun buildNormalizedRelativePath(targetRelativePath: String, targetFolderName: String): String {
        val raw = targetRelativePath.trim().trim('/')
        if (raw.isNotEmpty()) {
            val validRoot = raw.startsWith(Environment.DIRECTORY_MOVIES) ||
                raw.startsWith(Environment.DIRECTORY_DCIM) ||
                raw.startsWith(Environment.DIRECTORY_DOWNLOADS) ||
                raw.startsWith(Environment.DIRECTORY_PICTURES)
            return if (validRoot) "$raw/" else "${Environment.DIRECTORY_MOVIES}/$raw/"
        }
        val cleanName = targetFolderName.trim().trim('/').ifBlank { "VideoHatak" }
        return "${Environment.DIRECTORY_MOVIES}/$cleanName/"
    }

    private fun buildWritePermissionRequest(
        securityException: SecurityException,
        pendingOp: PendingVideoFileOp,
        uri: Uri
    ): VideoFileOperationResult {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val pi = MediaStore.createWriteRequest(contentResolver, listOf(uri))
                VideoFileOperationResult.RequiresPermission(pi.intentSender, pendingOp)
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && securityException is RecoverableSecurityException -> {
                VideoFileOperationResult.RequiresPermission(
                    securityException.userAction.actionIntent.intentSender,
                    pendingOp
                )
            }
            else -> VideoFileOperationResult.Error("يرجى منح صلاحية تعديل الملفات")
        }
    }

    override fun filterAndSort(
        videos: List<VideoItem>,
        query: String,
        sortOrder: VideoSortOrder,
        folderId: String?
    ): List<VideoItem> {
        val normalizedQuery = query.trim().lowercase()

        val filtered = videos.filter { video ->
            val matchesFolder = folderId == null || video.bucketId == folderId
            val matchesQuery = normalizedQuery.isEmpty() ||
                video.safeTitle.lowercase().contains(normalizedQuery) ||
                video.displayName.lowercase().contains(normalizedQuery) ||
                video.bucketDisplayName.lowercase().contains(normalizedQuery)

            matchesFolder && matchesQuery
        }

        return when (sortOrder.field) {
            VideoSortField.DATE_ADDED -> {
                if (sortOrder.direction == SortDirection.DESCENDING) {
                    filtered.sortedByDescending { it.dateAddedSec }
                } else {
                    filtered.sortedBy { it.dateAddedSec }
                }
            }
            VideoSortField.NAME -> {
                if (sortOrder.direction == SortDirection.DESCENDING) {
                    filtered.sortedByDescending { it.safeTitle.lowercase() }
                } else {
                    filtered.sortedBy { it.safeTitle.lowercase() }
                }
            }
            VideoSortField.DURATION -> {
                if (sortOrder.direction == SortDirection.DESCENDING) {
                    filtered.sortedByDescending { it.durationMs }
                } else {
                    filtered.sortedBy { it.durationMs }
                }
            }
            VideoSortField.SIZE -> {
                if (sortOrder.direction == SortDirection.DESCENDING) {
                    filtered.sortedByDescending { it.sizeBytes }
                } else {
                    filtered.sortedBy { it.sizeBytes }
                }
            }
        }
    }

    override fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder> {
        return videos
            .groupBy { it.bucketId }
            .map { (bucketId, itemsInFolder) ->
                val firstItem = itemsInFolder.firstOrNull()
                val displayName = firstItem?.bucketDisplayName ?: "مجلد الفيديوهات"
                val totalSize = itemsInFolder.sumOf { it.sizeBytes }

                VideoFolder(
                    bucketId = bucketId,
                    displayName = displayName,
                    videoCount = itemsInFolder.size,
                    totalSizeBytes = totalSize,
                    firstVideoItem = firstItem
                )
            }
            .sortedByDescending { it.videoCount }
    }
}
