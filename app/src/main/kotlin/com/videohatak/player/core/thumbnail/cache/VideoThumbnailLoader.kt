package com.videohatak.player.core.thumbnail.cache

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.LruCache
import android.util.Size
import com.videohatak.player.core.diagnostics.PerformanceDiagnostics
import com.videohatak.player.core.playback.service.MediaNotificationPrivacy
import com.videohatak.player.core.thumbnail.retriever.ThumbnailRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Memory-safe LRU loader for library video card thumbnails.
 */
object VideoThumbnailLoader {

    private val loaderSemaphore = Semaphore(2)

    private val memoryCache: LruCache<Long, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheKb = (maxKb / 10).coerceIn(4 * 1024, 24 * 1024)
        object : LruCache<Long, Bitmap>(cacheKb) {
            override fun sizeOf(key: Long, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    fun getCached(videoId: Long): Bitmap? = memoryCache.get(videoId)?.takeIf { !it.isRecycled }

    suspend fun loadThumbnail(context: Context, videoId: Long, contentUri: Uri): Bitmap? {
        if (MediaNotificationPrivacy.isVaultUri(contentUri)) {
            return null
        }
        currentCoroutineContext().ensureActive()
        getCached(videoId)?.let { return it }

        val loaded = loadThumbnailInternal(
            videoId = videoId,
            contentUri = contentUri,
            semaphore = loaderSemaphore,
            ioDispatcher = Dispatchers.IO,
            cacheLookup = ::getCached,
            cacheStore = { id, bmp -> memoryCache.put(id, bmp) },
            primaryFetcher = { uri, signal ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(320, 180), signal)
                } else {
                    extractFrameFallback(context, uri)
                }
            },
            fallbackFetcher = { uri ->
                extractFrameFallback(context, uri)
            }
        )
        return loaded
    }

    internal suspend fun loadThumbnailInternal(
        videoId: Long,
        contentUri: Uri,
        semaphore: Semaphore = loaderSemaphore,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        cacheLookup: (Long) -> Bitmap? = ::getCached,
        cacheStore: (Long, Bitmap) -> Unit = { id, bmp -> memoryCache.put(id, bmp) },
        primaryFetcher: (Uri, CancellationSignal) -> Bitmap?,
        fallbackFetcher: (Uri) -> Bitmap?
    ): Bitmap? {
        if (MediaNotificationPrivacy.isVaultUri(contentUri)) {
            return null
        }
        currentCoroutineContext().ensureActive()
        cacheLookup(videoId)?.let { return it }

        return semaphore.withPermit {
            currentCoroutineContext().ensureActive()
            withContext(ioDispatcher) {
                currentCoroutineContext().ensureActive()
                cacheLookup(videoId)?.let { return@withContext it }

                val signal = CancellationSignal()
                val jobHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) {
                        runCatching { signal.cancel() }
                    }
                }
                val bitmap = try {
                    PerformanceDiagnostics.measure(
                        operation = "thumbnail_load",
                        details = { "videoId=$videoId" }
                    ) {
                        val primary = try {
                            primaryFetcher(contentUri, signal)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (oce: OperationCanceledException) {
                            currentCoroutineContext().ensureActive()
                            throw CancellationException("Thumbnail request cancelled", oce)
                        } catch (_: Throwable) {
                            currentCoroutineContext().ensureActive()
                            fallbackFetcher(contentUri)
                        }
                        primary?.takeIf { !it.isRecycled }
                    }
                } finally {
                    jobHandle?.dispose()
                }

                currentCoroutineContext().ensureActive()
                if (bitmap != null) {
                    cacheStore(videoId, bitmap)
                }
                bitmap
            }
        }
    }

    private fun extractFrameFallback(context: Context, uri: Uri): Bitmap? {
        if (MediaNotificationPrivacy.isVaultUri(uri)) return null
        if (Thread.currentThread().isInterrupted) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { raw -> ThumbnailRetriever.downscaleIfNeeded(raw, 320, 180) }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
