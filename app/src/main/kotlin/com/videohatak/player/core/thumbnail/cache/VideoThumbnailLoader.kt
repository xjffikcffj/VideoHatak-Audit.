package com.videohatak.player.core.thumbnail.cache

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Memory-safe LRU loader for library video card thumbnails.
 */
object VideoThumbnailLoader {

    private val memoryCache: LruCache<Long, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheKb = (maxKb / 10).coerceIn(4 * 1024, 24 * 1024)
        object : LruCache<Long, Bitmap>(cacheKb) {
            override fun sizeOf(key: Long, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    fun getCached(videoId: Long): Bitmap? = memoryCache.get(videoId)

    suspend fun loadThumbnail(context: Context, videoId: Long, contentUri: Uri): Bitmap? {
        getCached(videoId)?.let { return it }

        return withContext(Dispatchers.IO) {
            getCached(videoId)?.let { return@withContext it }

            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(contentUri, Size(320, 180), null)
                } else {
                    extractFrameFallback(context, contentUri)
                }
            } catch (_: Exception) {
                extractFrameFallback(context, contentUri)
            }

            if (bitmap != null) {
                memoryCache.put(videoId, bitmap)
            }
            bitmap
        }
    }

    private fun extractFrameFallback(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            raw
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
