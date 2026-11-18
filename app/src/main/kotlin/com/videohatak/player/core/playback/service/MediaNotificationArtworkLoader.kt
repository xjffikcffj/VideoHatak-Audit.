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
import java.util.Locale
import java.util.zip.CRC32

/**
 * Loads and caches video artwork thumbnails for playback notifications and lock-screen controls,
 * guaranteeing a valid fallback bitmap when extraction fails or when privacy masking is active.
 */
object MediaNotificationArtworkLoader {

    private const val ARTWORK_SIZE_PX = 256
    private const val MAX_CACHE_ENTRIES = 24
    private const val MAX_SOURCE_DIMENSION_PX = 16_384
    private const val MAX_SOURCE_PIXELS = 64_000_000L

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

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
        if (bytes.size < 12) return null
        val target = maxSizePx.coerceAtLeast(32)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0 ||
                width > MAX_SOURCE_DIMENSION_PX ||
                height > MAX_SOURCE_DIMENSION_PX ||
                width.toLong() * height.toLong() > MAX_SOURCE_PIXELS
            ) {
                return null
            }

            val mimeType = bounds.outMimeType?.trim()?.lowercase(Locale.US).orEmpty()
            if (!mimeType.startsWith("image/")) {
                return null
            }

            if (!isStructurallyValidEncodedImage(bytes, mimeType, width, height)) {
                return null
            }

            var sampleSize = 1
            if (width > target || height > target) {
                var halfW = width / 2
                var halfH = height / 2
                while (halfW / sampleSize >= target && halfH / sampleSize >= target) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                ?: return null
            if (decoded.isRecycled || decoded.width <= 0 || decoded.height <= 0) {
                runCatching { if (!decoded.isRecycled) decoded.recycle() }
                return null
            }
            decoded
        } catch (_: Throwable) {
            null
        }
    }

    private fun isStructurallyValidEncodedImage(
        bytes: ByteArray,
        mimeType: String,
        expectedWidth: Int,
        expectedHeight: Int
    ): Boolean {
        return when (mimeType) {
            "image/png" -> isValidPngStructure(bytes, expectedWidth, expectedHeight)
            "image/jpeg", "image/jpg" -> isValidJpegStructure(bytes, expectedWidth, expectedHeight)
            "image/webp" -> isValidWebpStructure(bytes)
            "image/gif" -> isValidGifStructure(bytes, expectedWidth, expectedHeight)
            "image/bmp", "image/x-ms-bmp" -> isValidBmpStructure(bytes, expectedWidth, expectedHeight)
            else -> bytes.size >= 16 && expectedWidth > 0 && expectedHeight > 0
        }
    }

    private fun isValidPngStructure(
        bytes: ByteArray,
        expectedWidth: Int,
        expectedHeight: Int
    ): Boolean {
        if (bytes.size < 45) return false
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        var offset = PNG_SIGNATURE.size
        var chunkIndex = 0
        var sawIhdr = false
        var sawIdat = false
        var sawIend = false

        while (offset + 12 <= bytes.size) {
            val lengthLong = readUInt32BE(bytes, offset)
            if (lengthLong < 0L || lengthLong > Int.MAX_VALUE.toLong()) return false
            val totalChunkBytes = 12L + lengthLong
            if (offset.toLong() + totalChunkBytes > bytes.size.toLong()) return false
            val length = lengthLong.toInt()

            val crc32 = CRC32().apply { update(bytes, offset + 4, 4 + length) }.value
            val storedCrc = readUInt32BE(bytes, offset + 8 + length)
            if (crc32 != storedCrc) return false

            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (chunkIndex == 0) {
                if (type != "IHDR" || length != 13) return false
                val ihdrW = readUInt32BE(bytes, offset + 8).toInt()
                val ihdrH = readUInt32BE(bytes, offset + 12).toInt()
                if (ihdrW <= 0 || ihdrH <= 0 || ihdrW != expectedWidth || ihdrH != expectedHeight) {
                    return false
                }
                sawIhdr = true
            } else if (type == "IDAT") {
                sawIdat = true
            } else if (type == "IEND") {
                if (length != 0) return false
                sawIend = true
                break
            }

            offset += 12 + length
            chunkIndex++
        }
        return sawIhdr && sawIdat && sawIend
    }

    private fun isValidJpegStructure(
        bytes: ByteArray,
        expectedWidth: Int,
        expectedHeight: Int
    ): Boolean {
        if (bytes.size < 16) return false
        if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return false

        var pos = 2
        var sawSof = false
        var sawSos = false
        var sawEoi = false

        while (pos < bytes.size) {
            if ((bytes[pos].toInt() and 0xFF) != 0xFF) return false
            while (pos < bytes.size && (bytes[pos].toInt() and 0xFF) == 0xFF) {
                pos++
            }
            if (pos >= bytes.size) return false
            val marker = bytes[pos].toInt() and 0xFF
            pos++

            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8) {
                continue
            }
            if (marker == 0xD9) {
                sawEoi = sawSos
                break
            }
            if (pos + 2 > bytes.size) return false
            val segLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            if (segLen < 2 || pos + segLen > bytes.size) return false

            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (segLen < 8) return false
                val frameH = ((bytes[pos + 3].toInt() and 0xFF) shl 8) or (bytes[pos + 4].toInt() and 0xFF)
                val frameW = ((bytes[pos + 5].toInt() and 0xFF) shl 8) or (bytes[pos + 6].toInt() and 0xFF)
                if (frameW <= 0 || frameH <= 0 || frameW != expectedWidth || frameH != expectedHeight) {
                    return false
                }
                sawSof = true
            }

            pos += segLen
            if (marker == 0xDA) {
                sawSos = true
                var foundEndOrNextMarker = false
                while (pos < bytes.size - 1) {
                    val b = bytes[pos].toInt() and 0xFF
                    if (b == 0xFF) {
                        var nextPos = pos + 1
                        while (nextPos < bytes.size && (bytes[nextPos].toInt() and 0xFF) == 0xFF) {
                            nextPos++
                        }
                        if (nextPos >= bytes.size) break
                        val nextMarker = bytes[nextPos].toInt() and 0xFF
                        when {
                            nextMarker == 0x00 || nextMarker in 0xD0..0xD7 -> {
                                pos = nextPos + 1
                            }
                            nextMarker == 0xD9 -> {
                                sawEoi = true
                                foundEndOrNextMarker = true
                                pos = nextPos + 1
                                break
                            }
                            else -> {
                                pos = nextPos - 1
                                foundEndOrNextMarker = true
                                break
                            }
                        }
                    } else {
                        pos++
                    }
                }
                if (!foundEndOrNextMarker || sawEoi) break
            }
        }
        return sawSof && sawSos && sawEoi
    }

    private fun isValidWebpStructure(bytes: ByteArray): Boolean {
        if (bytes.size < 20) return false
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val webp = String(bytes, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || webp != "WEBP") return false
        val riffSize = readUInt32LE(bytes, 4)
        if (riffSize < 12L || 8L + riffSize > bytes.size.toLong()) return false
        val chunkTag = String(bytes, 12, 4, Charsets.US_ASCII)
        if (chunkTag != "VP8 " && chunkTag != "VP8L" && chunkTag != "VP8X") return false
        val chunkSize = readUInt32LE(bytes, 16)
        return chunkSize > 0L && 20L + chunkSize <= 8L + riffSize
    }

    private fun isValidGifStructure(
        bytes: ByteArray,
        expectedWidth: Int,
        expectedHeight: Int
    ): Boolean {
        if (bytes.size < 14) return false
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        if (header != "GIF87a" && header != "GIF89a") return false
        val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        if (w <= 0 || h <= 0 || w != expectedWidth || h != expectedHeight) return false
        var lastNonZero = bytes.size - 1
        while (lastNonZero > 13 && bytes[lastNonZero] == 0.toByte()) {
            lastNonZero--
        }
        return bytes[lastNonZero] == 0x3B.toByte()
    }

    private fun isValidBmpStructure(
        bytes: ByteArray,
        expectedWidth: Int,
        expectedHeight: Int
    ): Boolean {
        if (bytes.size < 26) return false
        if (bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) return false
        val declaredSize = readUInt32LE(bytes, 2)
        if (declaredSize < 26L || declaredSize > bytes.size.toLong()) return false
        val dibSize = readUInt32LE(bytes, 14)
        return dibSize >= 12L && expectedWidth > 0 && expectedHeight > 0
    }

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
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
