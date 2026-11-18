package com.videohatak.player.core.playback

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

sealed interface FrameCaptureResult {
    data class Success(val message: String) : FrameCaptureResult
    data class Error(val message: String) : FrameCaptureResult
}

interface FrameOutputWriter {
    fun writePng(fileName: String, folderName: String, compressPng: (java.io.OutputStream) -> Boolean): FrameCaptureResult
}

object FrameCaptureHelper {

    private const val SCREENSHOTS_FOLDER_NAME = "لقطات فيديوهاتك"
    private const val PIXEL_COPY_TIMEOUT_MS = 2500L

    fun hasDrmProtectedTracks(playerView: PlayerView?): Boolean {
        val player = playerView?.player ?: return false
        val groups = player.currentTracks.groups
        for (i in 0 until groups.size) {
            val group = groups[i]
            for (j in 0 until group.length) {
                if (group.getTrackFormat(j).drmInitData != null) {
                    return true
                }
            }
        }
        return false
    }

    suspend fun captureAndSaveFrame(
        context: Context,
        playerView: PlayerView?,
        videoUri: Uri?,
        positionMs: Long,
        isVaultVideo: Boolean
    ): FrameCaptureResult {
        val activity = context as? Activity
        val isFlagSecure = (activity?.window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (isVaultVideo || isFlagSecure || videoUri?.scheme == "vault") {
            return FrameCaptureResult.Error("التقاط اللقطات معطّل داخل الخزنة الخاصة لحماية الخصوصية")
        }
        if (hasDrmProtectedTracks(playerView)) {
            return FrameCaptureResult.Error("التقاط اللقطات معطّل للمحتوى المحمي بحقوق النشر (DRM)")
        }

        val capturedBitmap = captureFromVideoSurface(playerView)
            ?: withContext(Dispatchers.IO) {
                extractFromMediaRetriever(context, videoUri, positionMs)
            }

        if (capturedBitmap == null) {
            return FrameCaptureResult.Error("تعذر التقاط إطار الفيديو حاليًا")
        }

        return withContext(Dispatchers.IO) {
            saveBitmapWithWriter(capturedBitmap, AndroidFrameOutputWriter(context))
        }
    }

    internal fun saveBitmapWithWriter(
        bitmap: Bitmap,
        writer: FrameOutputWriter
    ): FrameCaptureResult = saveFrameWithWriter(
        compressPng = { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) },
        writer = writer,
        onComplete = { bitmap.recycle() }
    )

    private class ByteCountingOutputStream(
        private val delegate: java.io.OutputStream
    ) : java.io.OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1L
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            bytesWritten += b.size.toLong()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len.toLong()
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    internal fun saveFrameWithWriter(
        compressPng: (java.io.OutputStream) -> Boolean,
        writer: FrameOutputWriter,
        onComplete: () -> Unit = {}
    ): FrameCaptureResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VideoHatak_$timestamp.png"
        var totalBytesWritten = 0L
        var compressedOk = false
        return try {
            val result = writer.writePng(fileName, SCREENSHOTS_FOLDER_NAME) { out ->
                val countingOut = ByteCountingOutputStream(out)
                val ok = compressPng(countingOut)
                if (ok && countingOut.bytesWritten > 0L) {
                    countingOut.flush()
                    compressedOk = true
                    totalBytesWritten = countingOut.bytesWritten
                    true
                } else {
                    compressedOk = false
                    totalBytesWritten = countingOut.bytesWritten
                    false
                }
            }
            if (result is FrameCaptureResult.Success && (!compressedOk || totalBytesWritten <= 0L)) {
                FrameCaptureResult.Error("تعذر حفظ اللقطة لأن بيانات الصورة فارغة")
            } else {
                result
            }
        } catch (t: Throwable) {
            FrameCaptureResult.Error(t.localizedMessage ?: "تعذر حفظ لقطة الفيديو")
        } finally {
            runCatching { onComplete() }
        }
    }

    private suspend fun captureFromVideoSurface(playerView: PlayerView?): Bitmap? {
        val videoView = playerView?.videoSurfaceView ?: return null
        if (videoView is TextureView) {
            return withContext(Dispatchers.Main) {
                if (videoView.isAvailable && videoView.width > 0 && videoView.height > 0) {
                    runCatching { videoView.bitmap }.getOrNull()
                } else {
                    null
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val surfaceView = videoView as? SurfaceView ?: return null
        if (!surfaceView.holder.surface.isValid || surfaceView.width <= 0 || surfaceView.height <= 0) {
            return null
        }

        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(PIXEL_COPY_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    var allocatedBitmap: Bitmap? = null
                    cont.invokeOnCancellation {
                        allocatedBitmap?.let { bmp ->
                            if (!bmp.isRecycled) bmp.recycle()
                        }
                    }
                    try {
                        val bitmap = Bitmap.createBitmap(
                            surfaceView.width,
                            surfaceView.height,
                            Bitmap.Config.ARGB_8888
                        )
                        allocatedBitmap = bitmap
                        PixelCopy.request(
                            surfaceView,
                            bitmap,
                            { copyResult ->
                                if (cont.isActive) {
                                    if (copyResult == PixelCopy.SUCCESS) {
                                        cont.resume(bitmap)
                                    } else {
                                        if (!bitmap.isRecycled) bitmap.recycle()
                                        cont.resume(null)
                                    }
                                } else {
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                }
                            },
                            Handler(Looper.getMainLooper())
                        )
                    } catch (_: Throwable) {
                        allocatedBitmap?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() }
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    private fun extractFromMediaRetriever(context: Context, uri: Uri?, positionMs: Long): Bitmap? {
        if (uri == null) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val timeUs = (positionMs.coerceAtLeast(0L)) * 1000L
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?.takeIf { !it.isRecycled }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private class AndroidFrameOutputWriter(
        private val context: Context
    ) : FrameOutputWriter {
        override fun writePng(
            fileName: String,
            folderName: String,
            compressPng: (java.io.OutputStream) -> Boolean
        ): FrameCaptureResult {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$folderName"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return FrameCaptureResult.Error("تعذر إنشاء ملف الصورة")

                try {
                    val outputStream = resolver.openOutputStream(uri)
                    if (outputStream == null) {
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                        return FrameCaptureResult.Error("تعذر فتح مسار حفظ الصورة")
                    }
                    val countingOut = ByteCountingOutputStream(outputStream)
                    val saved = countingOut.use { out -> compressPng(out) }
                    if (!saved || countingOut.bytesWritten <= 0L) {
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                        return FrameCaptureResult.Error("فشل ضغط وحفظ الصورة")
                    }

                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    val updatedRows = resolver.update(uri, values, null, null)
                    if (updatedRows <= 0) {
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                        return FrameCaptureResult.Error("تعذر إتمام حفظ الصورة في معرض الصور")
                    }
                    FrameCaptureResult.Success("تم حفظ اللقطة في: $folderName")
                } catch (e: Exception) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    FrameCaptureResult.Error(e.localizedMessage ?: "تعذر حفظ لقطة الفيديو")
                }
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val targetDir = File(picturesDir, folderName)
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    return FrameCaptureResult.Error("تعذر إنشاء مجلد حفظ اللقطات")
                }

                val targetFile = File(targetDir, fileName)
                try {
                    val countingOut = ByteCountingOutputStream(FileOutputStream(targetFile))
                    val saved = countingOut.use { out -> compressPng(out) }
                    if (!saved || countingOut.bytesWritten <= 0L || !targetFile.exists() || targetFile.length() <= 0L) {
                        if (targetFile.exists()) targetFile.delete()
                        return FrameCaptureResult.Error("فشل حفظ ملف الصورة")
                    }
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        arrayOf("image/png"),
                        null
                    )
                    FrameCaptureResult.Success("تم حفظ اللقطة في: $folderName")
                } catch (e: Exception) {
                    if (targetFile.exists()) targetFile.delete()
                    FrameCaptureResult.Error(e.localizedMessage ?: "تعذر حفظ لقطة الفيديو")
                }
            }
        }
    }
}
