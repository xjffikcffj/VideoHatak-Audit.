package com.videohatak.player.core.thumbnail.retriever

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.videohatak.player.core.playback.service.MediaNotificationPrivacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class ThumbnailRetriever(private val context: Context) {

    companion object {
        private val retrieverSemaphore = Semaphore(2)

        internal fun computeScaledDimensions(
            srcWidth: Int,
            srcHeight: Int,
            maxWidth: Int,
            maxHeight: Int
        ): Pair<Int, Int> {
            if (srcWidth <= 0 || srcHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
                return maxWidth.coerceAtLeast(1) to maxHeight.coerceAtLeast(1)
            }
            if (srcWidth <= maxWidth && srcHeight <= maxHeight) {
                return srcWidth to srcHeight
            }
            val scale = minOf(maxWidth.toFloat() / srcWidth, maxHeight.toFloat() / srcHeight)
            val w = (srcWidth * scale).toInt().coerceAtLeast(1)
            val h = (srcHeight * scale).toInt().coerceAtLeast(1)
            return w to h
        }

        internal fun downscaleIfNeeded(raw: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
            if (raw.width <= targetWidth && raw.height <= targetHeight) {
                return raw
            }
            val (scaledW, scaledH) = computeScaledDimensions(raw.width, raw.height, targetWidth, targetHeight)
            val scaled = Bitmap.createScaledBitmap(raw, scaledW, scaledH, true)
            if (scaled !== raw) {
                raw.recycle()
            }
            return scaled
        }
    }

    suspend fun extractFrame(videoUri: Uri, positionMs: Long, targetWidth: Int = 160, targetHeight: Int = 90): Bitmap? {
        if (MediaNotificationPrivacy.isVaultUri(videoUri)) {
            return null
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return retrieverSemaphore.withPermit {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val retriever = MediaMetadataRetriever()
                try {
                    if (videoUri.scheme == "content" || videoUri.scheme == "file") {
                        retriever.setDataSource(context, videoUri)
                    } else {
                        retriever.setDataSource(videoUri.toString(), HashMap())
                    }

                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val timeUs = positionMs * 1000L
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetWidth,
                            targetHeight
                        )
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { raw -> downscaleIfNeeded(raw, targetWidth, targetHeight) }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Throwable) { }
                }
            }
        }
    }
}

