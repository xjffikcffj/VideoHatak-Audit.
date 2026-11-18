package com.videohatak.player.core.playback.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.videohatak.player.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads and caches video artwork thumbnails for playback notifications and lock-screen controls,
 * guaranteeing a valid fallback bitmap when extraction fails or when privacy masking is active.
 */
object MediaNotificationArtworkLoader {

    private const val ARTWORK_SIZE_PX = 256
    private const val MAX_CACHE_ENTRIES = 24

    private val artworkCache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {}

    @Volatile
    private var cachedFallback: Bitmap? = null

    fun getCachedArtwork(uriKey: String?): Bitmap? {
        if (uriKey.isNullOrBlank()) return null
        return artworkCache.get(uriKey)
    }

    fun getFallbackArtwork(context: Context): Bitmap {
        cachedFallback?.takeIf { !it.isRecycled }?.let { return it }

        val decoded = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.splash_logo)
                ?: BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        }.getOrNull()?.takeIf { !it.isRecycled }

        val result = decoded ?: createProgrammaticFallbackBitmap()
        cachedFallback = result
        return result
    }

    fun resolveImmediateArtwork(
        context: Context,
        uriString: String?,
        isVaultPrivate: Boolean
    ): Bitmap {
        if (isVaultPrivate || uriString.isNullOrBlank()) {
            return getFallbackArtwork(context)
        }
        return getCachedArtwork(uriString)?.takeIf { !it.isRecycled } ?: getFallbackArtwork(context)
    }

    suspend fun loadOrFallbackArtwork(
        context: Context,
        uri: Uri?,
        isVaultPrivate: Boolean
    ): Bitmap {
        if (isVaultPrivate || uri == null) {
            return getFallbackArtwork(context)
        }
        val key = uri.toString()
        getCachedArtwork(key)?.takeIf { !it.isRecycled }?.let { return it }

        val extracted = withContext(Dispatchers.IO) {
            extractArtworkBitmap(context, uri)
        }
        if (extracted != null && !extracted.isRecycled) {
            artworkCache.put(key, extracted)
            return extracted
        }
        return getFallbackArtwork(context)
    }

    internal fun createProgrammaticFallbackBitmap(sizePx: Int = 128): Bitmap {
        val safeSize = sizePx.coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(18, 18, 24))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(229, 184, 75)
            style = Paint.Style.FILL
        }
        val half = safeSize / 2f
        val radius = safeSize * 0.28f
        canvas.drawCircle(half, half, radius, paint)
        return bitmap
    }

    internal fun decodeSampledBitmap(bytes: ByteArray, maxSizePx: Int = ARTWORK_SIZE_PX): Bitmap? {
        if (bytes.isEmpty()) return null
        val target = maxSizePx.coerceAtLeast(32)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            if (bounds.outWidth > target || bounds.outHeight > target) {
                var halfW = bounds.outWidth / 2
                var halfH = bounds.outHeight / 2
                while (halfW / sampleSize >= target && halfH / sampleSize >= target) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractArtworkBitmap(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val loaded = runCatching {
                context.contentResolver.loadThumbnail(
                    uri,
                    Size(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX),
                    null
                )
            }.getOrNull()
            if (loaded != null && !loaded.isRecycled) return loaded
        }

        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content" || uri.scheme == "file") {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(uri.toString(), HashMap())
            }
            val embedded = retriever.embeddedPicture
            if (embedded != null && embedded.isNotEmpty()) {
                decodeSampledBitmap(embedded, ARTWORK_SIZE_PX)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ARTWORK_SIZE_PX,
                    ARTWORK_SIZE_PX
                ) ?: retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ARTWORK_SIZE_PX,
                    ARTWORK_SIZE_PX
                )
            } else {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    internal fun clearCacheForTesting() {
        artworkCache.evictAll()
        cachedFallback = null
    }
}
