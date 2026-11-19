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
import android.os.HandlerThread
import android.provider.DocumentsContract
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VideoFileOperationResult {
    data object Success : VideoFileOperationResult
    data class PartialSuccess(
        val succeededCount: Int,
        val failedCount: Int,
        val message: String
    ) : VideoFileOperationResult
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
    data class BatchMove(
        val remainingVideos: List<VideoItem>,
        val targetRelativePath: String,
        val targetFolderName: String,
        val movedCount: Int = 0,
        val failedCount: Int = 0
    ) : PendingVideoFileOp
    data class DeletePermanent(val video: VideoItem, val trashItemId: Long? = null) : PendingVideoFileOp
    data class DeleteVaultOriginals(
        val pendingUris: List<Uri>,
        val encryptedCount: Int,
        val deletedSoFar: Int = 0,
        val failedCount: Int = 0,
        val encryptedFailedCount: Int = 0
    ) : PendingVideoFileOp
}

sealed interface MediaExistenceResult {
    data object Exists : MediaExistenceResult
    data object Missing : MediaExistenceResult
    data class Unverifiable(val reason: String) : MediaExistenceResult
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

    suspend fun checkMediaExistence(video: VideoItem): MediaExistenceResult = MediaExistenceResult.Exists

    suspend fun doesMediaExist(video: VideoItem): Boolean =
        checkMediaExistence(video) is MediaExistenceResult.Exists

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
        return when (val existence = checkMediaExistence(video)) {
            MediaExistenceResult.Missing -> VideoFileOperationResult.Success
            MediaExistenceResult.Exists -> deleteVideoPermanently(video, trashItemId)
            is MediaExistenceResult.Unverifiable -> VideoFileOperationResult.Error(existence.reason)
        }
    }

    suspend fun deleteVaultOriginals(
        uris: List<Uri>,
        encryptedCount: Int = uris.size
    ): VideoFileOperationResult {
        return VideoFileOperationResult.Error("غير مدعوم")
    }

    suspend fun completeVaultOriginalsDeleteAfterPermission(
        op: PendingVideoFileOp.DeleteVaultOriginals
    ): VideoFileOperationResult {
        return VideoFileOperationResult.Success
    }
}

class LocalVideoRepositoryImpl(
    private val context: Context,
    private val contentResolver: ContentResolver = context.contentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : VideoRepository {

    private val playlistDb by lazy { VideoHatakDatabaseHelper(context) }

    override fun observeVideos(): Flow<List<VideoItem>> = callbackFlow {
        trySend(queryVideosInternal())

        val refreshChannel = Channel<Unit>(Channel.CONFLATED)
        val handlerThread = HandlerThread("VideoMediaStoreObserver").apply { start() }
        val observer = object : ContentObserver(Handler(handlerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                refreshChannel.trySend(Unit)
            }
        }

        val collectorJob = launch(ioDispatcher) {
            for (ignored in refreshChannel) {
                delay(250L)
                while (refreshChannel.tryReceive().isSuccess) {
                    // Coalesce burst MediaStore notifications
                }
                trySend(queryVideosInternal())
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
            collectorJob.cancel()
            refreshChannel.close()
            handlerThread.quitSafely()
        }
    }.flowOn(ioDispatcher)

    override suspend fun getVideos(): List<VideoItem> = withContext(ioDispatcher) {
        com.videohatak.player.core.diagnostics.PerformanceDiagnostics.measure(
            operation = "mediastore_get_videos"
        ) {
            queryVideosInternal()
        }
    }

    private fun extractParentFolderName(path: String): String? {
        val trimmed = path.trimEnd('/')
        val lastSlash = trimmed.lastIndexOf('/')
        if (lastSlash <= 0) return null
        val prevSlash = trimmed.lastIndexOf('/', lastSlash - 1)
        return if (prevSlash >= 0) trimmed.substring(prevSlash + 1, lastSlash) else trimmed.substring(0, lastSlash)
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

        val selection: String? = null
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
                            extractParentFolderName(path).hashCode().toString()
                        } else "default_bucket"

                        val resolvedBucketName = when {
                            !rawBucketName.isNullOrBlank() -> rawBucketName
                            relativePath.isNotBlank() -> relativePath.trim('/').substringAfterLast('/')
                            path.isNotBlank() -> extractParentFolderName(path)?.takeIf { it.isNotBlank() }
                                ?: "مجلد الفيديوهات"
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
        withContext(ioDispatcher) {
            val cleaned = newName.trim().replace('/', '_').replace('\\', '_')
            if (cleaned.isBlank()) {
                return@withContext VideoFileOperationResult.Error("الاسم الجديد غير صالح")
            }

            val existingExt = video.displayName.substringAfterLast('.', "").trim()
            val resolved = resolveRenamedDisplayAndTitle(cleaned, existingExt)
                ?: return@withContext VideoFileOperationResult.Error("الاسم الجديد غير صالح")
            val (finalDisplayName, finalTitle) = resolved

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
    ): VideoFileOperationResult = withContext(ioDispatcher) {
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
                val safeRelativeFolder = sanitizeFolderSegments(targetFolderName).ifEmpty { listOf("VideoHatak") }
                val destDir = File(moviesRoot, safeRelativeFolder.joinToString(File.separator))
                val canonicalRoot = moviesRoot.canonicalPath
                val canonicalDest = destDir.canonicalPath
                if (canonicalDest != canonicalRoot && !canonicalDest.startsWith(canonicalRoot + File.separator)) {
                    return@withContext VideoFileOperationResult.Error("مسار المجلد الهدف غير صالح")
                }
                if (!destDir.exists()) destDir.mkdirs()

                val rawFileName = video.displayName.ifBlank { sourceFile.name }
                val fileName = File(rawFileName).name
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

    override suspend fun checkMediaExistence(video: VideoItem): MediaExistenceResult = withContext(ioDispatcher) {
        if (!PermissionHelper.hasAnyAccess(PermissionHelper.checkPermissionStatus(context))) {
            return@withContext MediaExistenceResult.Unverifiable("لا توجد صلاحية وصول للوسائط للتحقق من وجود الملف")
        }

        if (video.path.isNotBlank() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val file = File(video.path)
            if (file.exists()) return@withContext MediaExistenceResult.Exists
        }

        val storeCheckResult: MediaExistenceResult? = try {
            val found = contentResolver.query(
                video.contentUri,
                arrayOf(MediaStore.Video.Media._ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.count > 0 } ?: false
            if (found) MediaExistenceResult.Exists else null
        } catch (_: SecurityException) {
            MediaExistenceResult.Unverifiable("تعذر التحقق من الملف بسبب قيود الصلاحيات")
        } catch (e: Exception) {
            MediaExistenceResult.Unverifiable(e.localizedMessage ?: "تعذر التحقق من حالة الملف حاليًا")
        }

        if (storeCheckResult != null) return@withContext storeCheckResult

        try {
            val opened = contentResolver.openAssetFileDescriptor(video.contentUri, "r")?.use { true } ?: false
            if (opened) MediaExistenceResult.Exists else MediaExistenceResult.Missing
        } catch (_: java.io.FileNotFoundException) {
            MediaExistenceResult.Missing
        } catch (_: SecurityException) {
            MediaExistenceResult.Unverifiable("صلاحية الوصول إلى هذا الملف مقيدة")
        } catch (e: Exception) {
            MediaExistenceResult.Unverifiable(e.localizedMessage ?: "تعذر التحقق من وجود الملف")
        }
    }

    override suspend fun deleteVideoPermanently(
        video: VideoItem,
        trashItemId: Long?
    ): VideoFileOperationResult = withContext(ioDispatcher) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val deleted = try {
                    contentResolver.delete(video.contentUri, null, null)
                } catch (_: SecurityException) {
                    0
                }
                if (deleted > 0 || checkMediaExistence(video) is MediaExistenceResult.Missing) {
                    return@withContext VideoFileOperationResult.Success
                }
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(video.contentUri))
                VideoFileOperationResult.RequiresPermission(
                    pi.intentSender,
                    PendingVideoFileOp.DeletePermanent(video, trashItemId)
                )
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val deleted = contentResolver.delete(video.contentUri, null, null)
                if (deleted > 0 || checkMediaExistence(video) is MediaExistenceResult.Missing) {
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
    ): VideoFileOperationResult = withContext(ioDispatcher) {
        when (val currentExistence = checkMediaExistence(video)) {
            MediaExistenceResult.Missing -> return@withContext VideoFileOperationResult.Success
            is MediaExistenceResult.Unverifiable -> return@withContext VideoFileOperationResult.Error(currentExistence.reason)
            MediaExistenceResult.Exists -> Unit
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val deleted = contentResolver.delete(video.contentUri, null, null)
                if (deleted > 0 || checkMediaExistence(video) is MediaExistenceResult.Missing) {
                    VideoFileOperationResult.Success
                } else {
                    VideoFileOperationResult.Error("لم يكتمل حذف الملف من ذاكرة الجهاز، تم الإبقاء على السجل")
                }
            } else {
                deleteVideoPermanently(video, trashItemId)
            }
        } catch (e: Exception) {
            if (checkMediaExistence(video) is MediaExistenceResult.Missing) {
                VideoFileOperationResult.Success
            } else {
                VideoFileOperationResult.Error(e.localizedMessage ?: "تعذر حذف الملف بعد منح الإذن")
            }
        }
    }

    override suspend fun deleteVaultOriginals(
        uris: List<Uri>,
        encryptedCount: Int
    ): VideoFileOperationResult = withContext(ioDispatcher) {
        val targetUris = uris.filterNot { it.scheme.equals("vault", ignoreCase = true) }.distinct()
        if (targetUris.isEmpty()) return@withContext VideoFileOperationResult.Success

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val legacyPerm = PermissionHelper.getLegacyWritePermission()
            if (legacyPerm != null && !PermissionHelper.hasLegacyWritePermission(context)) {
                return@withContext VideoFileOperationResult.RequiresLegacyWritePermission(
                    permission = legacyPerm,
                    pendingOp = PendingVideoFileOp.DeleteVaultOriginals(
                        pendingUris = targetUris,
                        encryptedCount = encryptedCount
                    )
                )
            }
        }

        val pendingConsentUris = mutableListOf<Uri>()
        var deletedCount = 0
        var failedCount = 0
        var recoverableIntentSender: IntentSender? = null

        for (uri in targetUris) {
            val res = tryDirectDeleteUri(uri)
            when (res) {
                is UriDeleteAttempt.Deleted -> deletedCount++
                is UriDeleteAttempt.NeedsConsent -> {
                    pendingConsentUris.add(uri)
                    if (recoverableIntentSender == null && res.intentSender != null) {
                        recoverableIntentSender = res.intentSender
                    }
                }
                is UriDeleteAttempt.Failed -> failedCount++
            }
        }

        if (pendingConsentUris.isEmpty()) {
            return@withContext formatVaultOriginalsDeleteOutcome(
                deletedCount = deletedCount,
                failedCount = failedCount
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mediaUris = pendingConsentUris.filter { isMediaStoreUri(it) }
            val nonMediaFailures = pendingConsentUris.size - mediaUris.size
            if (mediaUris.isNotEmpty()) {
                val op = PendingVideoFileOp.DeleteVaultOriginals(
                    pendingUris = mediaUris,
                    encryptedCount = encryptedCount,
                    deletedSoFar = deletedCount,
                    failedCount = failedCount + nonMediaFailures
                )
                return@withContext try {
                    val pi = MediaStore.createDeleteRequest(contentResolver, mediaUris)
                    VideoFileOperationResult.RequiresPermission(pi.intentSender, op)
                } catch (e: Exception) {
                    formatVaultOriginalsDeleteOutcome(
                        deletedCount = deletedCount,
                        failedCount = failedCount + pendingConsentUris.size,
                        fallbackErrorMessage = e.localizedMessage
                            ?: "تعذر طلب إذن إزالة النسخة العامة بعد النقل للخزنة"
                    )
                }
            }
        }

        if (recoverableIntentSender != null) {
            val op = PendingVideoFileOp.DeleteVaultOriginals(
                pendingUris = pendingConsentUris,
                encryptedCount = encryptedCount,
                deletedSoFar = deletedCount,
                failedCount = failedCount
            )
            return@withContext VideoFileOperationResult.RequiresPermission(recoverableIntentSender, op)
        }

        formatVaultOriginalsDeleteOutcome(
            deletedCount = deletedCount,
            failedCount = failedCount + pendingConsentUris.size,
            fallbackErrorMessage = "بقيت النسخة العامة في موقعها"
        )
    }

    override suspend fun completeVaultOriginalsDeleteAfterPermission(
        op: PendingVideoFileOp.DeleteVaultOriginals
    ): VideoFileOperationResult = withContext(ioDispatcher) {
        var postDeleted = 0
        var postFailed = 0

        for (uri in op.pendingUris.distinct()) {
            if (!isUriStillPresent(uri)) {
                postDeleted++
                continue
            }
            when (tryDirectDeleteUri(uri)) {
                is UriDeleteAttempt.Deleted -> postDeleted++
                is UriDeleteAttempt.NeedsConsent,
                is UriDeleteAttempt.Failed -> postFailed++
            }
        }

        formatVaultOriginalsDeleteOutcome(
            deletedCount = op.deletedSoFar + postDeleted,
            failedCount = op.failedCount + postFailed
        )
    }

    private fun formatVaultOriginalsDeleteOutcome(
        deletedCount: Int,
        failedCount: Int,
        fallbackErrorMessage: String? = null
    ): VideoFileOperationResult {
        return when {
            failedCount <= 0 -> VideoFileOperationResult.Success
            deletedCount > 0 -> VideoFileOperationResult.PartialSuccess(
                succeededCount = deletedCount,
                failedCount = failedCount,
                message = "تمت إزالة $deletedCount من النسخ العامة، وتعذر إزالة $failedCount"
            )
            else -> VideoFileOperationResult.Error(
                fallbackErrorMessage ?: if (failedCount == 1) {
                    "تعذر إزالة النسخة العامة من المعرض"
                } else {
                    "تعذر إزالة النسخ العامة من المعرض ($failedCount)"
                }
            )
        }
    }

    private fun isUriStillPresent(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let { File(it) }
            return file != null && file.exists()
        }
        try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                return true
            }
        } catch (_: java.io.FileNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return true
        } catch (_: Exception) {
            return true
        }
        return try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media._ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.count > 0 } ?: false
        } catch (_: java.io.FileNotFoundException) {
            false
        } catch (_: Exception) {
            true
        }
    }

    private sealed interface UriDeleteAttempt {
        data object Deleted : UriDeleteAttempt
        data class NeedsConsent(val intentSender: IntentSender? = null) : UriDeleteAttempt
        data object Failed : UriDeleteAttempt
    }

    private fun isMediaStoreUri(uri: Uri): Boolean =
        ContentResolver.SCHEME_CONTENT == uri.scheme &&
            MediaStore.AUTHORITY.equals(uri.authority, ignoreCase = true)

    private fun tryDirectDeleteUri(uri: Uri): UriDeleteAttempt {
        try {
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val file = uri.path?.let { File(it) }
                if (file == null || !file.exists()) {
                    return UriDeleteAttempt.Deleted
                }
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                return if (deleted && !file.exists()) {
                    UriDeleteAttempt.Deleted
                } else {
                    UriDeleteAttempt.Failed
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && isMediaStoreUri(uri)) {
                val filePath = contentResolver.query(
                    uri,
                    arrayOf(@Suppress("DEPRECATION") MediaStore.Video.Media.DATA),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idx = cursor.getColumnIndex(@Suppress("DEPRECATION") MediaStore.Video.Media.DATA)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getStringOrNull(idx) else null
                }
                if (!filePath.isNullOrBlank()) {
                    val file = File(filePath)
                    if (file.exists()) {
                        runCatching { file.delete() }
                    }
                }
            }

            if (DocumentsContract.isDocumentUri(context, uri)) {
                val supportsDelete = runCatching {
                    contentResolver.query(
                        uri,
                        arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                        null,
                        null,
                        null
                    )?.use { c ->
                        val col = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                        if (col >= 0 && c.moveToFirst()) {
                            (c.getInt(col) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                        } else {
                            true
                        }
                    } ?: true
                }.getOrDefault(true)
                if (!supportsDelete) {
                    return UriDeleteAttempt.Failed
                }
                val docDeleted = runCatching {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                }.getOrDefault(false)
                if (docDeleted && !isUriStillPresent(uri)) {
                    return UriDeleteAttempt.Deleted
                }
                if (!isUriStillPresent(uri)) {
                    return UriDeleteAttempt.Deleted
                }
                return UriDeleteAttempt.Failed
            }

            val deletedRows = contentResolver.delete(uri, null, null)
            if (deletedRows > 0 && !isUriStillPresent(uri)) {
                return UriDeleteAttempt.Deleted
            }

            if (!isUriStillPresent(uri)) {
                return UriDeleteAttempt.Deleted
            }

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isMediaStoreUri(uri)) {
                UriDeleteAttempt.NeedsConsent()
            } else {
                UriDeleteAttempt.Failed
            }
        } catch (se: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isMediaStoreUri(uri)) {
                return UriDeleteAttempt.NeedsConsent()
            }
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && se is RecoverableSecurityException) {
                return UriDeleteAttempt.NeedsConsent(se.userAction.actionIntent.intentSender)
            }
            return UriDeleteAttempt.Failed
        } catch (_: Exception) {
            return UriDeleteAttempt.Failed
        }
    }

    private fun buildNormalizedRelativePath(targetRelativePath: String, targetFolderName: String): String {
        return sanitizeRelativePath(targetRelativePath, targetFolderName)
    }

    companion object {
        private val ILLEGAL_PATH_CHARS = Regex("[<>:\"|?*\\u0000-\\u001F]")

        internal fun resolveRenamedDisplayAndTitle(
            cleanedNewName: String,
            existingExtension: String
        ): Pair<String, String>? {
            val sanitizedName = cleanedNewName.trim().replace('/', '_').replace('\\', '_')
            if (sanitizedName.isBlank()) return null

            val cleanExt = existingExtension.trim().trimStart('.')
            if (cleanExt.isBlank()) {
                return sanitizedName to sanitizedName
            }

            val baseTitle = if (sanitizedName.endsWith(".$cleanExt", ignoreCase = true)) {
                sanitizedName.dropLast(cleanExt.length + 1).trim()
            } else {
                sanitizedName
            }
            if (baseTitle.isBlank()) return null
            return "$baseTitle.$cleanExt" to baseTitle
        }

        internal fun sanitizeFolderSegments(rawPath: String): List<String> {
            return rawPath
                .replace('\\', '/')
                .split('/')
                .map { segment -> ILLEGAL_PATH_CHARS.replace(segment, "").trim() }
                .filter { segment -> segment.isNotEmpty() && segment != "." && segment != ".." }
        }

        internal fun sanitizeRelativePath(targetRelativePath: String, targetFolderName: String): String {
            val allowedRoots = listOf(
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_DCIM,
                Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_PICTURES
            )
            val pathSegments = sanitizeFolderSegments(targetRelativePath)
            if (pathSegments.isNotEmpty()) {
                val firstLower = pathSegments.first().lowercase()
                val canonicalRoot = allowedRoots.firstOrNull { it.lowercase() == firstLower }
                val finalSegments = if (canonicalRoot != null) {
                    listOf(canonicalRoot) + pathSegments.drop(1)
                } else {
                    listOf(Environment.DIRECTORY_MOVIES) + pathSegments
                }
                return finalSegments.joinToString("/") + "/"
            }
            val folderSegments = sanitizeFolderSegments(targetFolderName).ifEmpty { listOf("VideoHatak") }
            return (listOf(Environment.DIRECTORY_MOVIES) + folderSegments).joinToString("/") + "/"
        }
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
        val normalizedQuery = query.trim()

        val filtered = if (folderId == null && normalizedQuery.isEmpty()) {
            videos
        } else {
            videos.filter { video ->
                val matchesFolder = folderId == null || video.bucketId == folderId
                if (!matchesFolder) return@filter false
                normalizedQuery.isEmpty() ||
                    video.safeTitle.contains(normalizedQuery, ignoreCase = true) ||
                    video.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    video.bucketDisplayName.contains(normalizedQuery, ignoreCase = true)
            }
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
                    filtered.sortedWith { a, b -> b.safeTitle.compareTo(a.safeTitle, ignoreCase = true) }
                } else {
                    filtered.sortedWith { a, b -> a.safeTitle.compareTo(b.safeTitle, ignoreCase = true) }
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
