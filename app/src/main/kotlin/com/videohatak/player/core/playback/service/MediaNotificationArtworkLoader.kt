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

    private data class EncodedImageDimensions(
        val width: Int,
        val height: Int
    )

    private val PLATFORM_ONLY_IMAGE_MIMES = setOf(
        "image/heic",
        "image/heif",
        "image/avif"
    )

    private fun isValidDimensions(width: Long, height: Long): Boolean {
        return width in 1L..MAX_SOURCE_DIMENSION_PX.toLong() &&
            height in 1L..MAX_SOURCE_DIMENSION_PX.toLong() &&
            width * height <= MAX_SOURCE_PIXELS
    }

    internal fun decodeSampledBitmap(bytes: ByteArray, maxSizePx: Int = ARTWORK_SIZE_PX): Bitmap? {
        if (bytes.size < 12) return null
        val target = maxSizePx.coerceAtLeast(32)
        return try {
            val parsed = parseAndValidateImageDimensions(bytes) ?: run {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val mimeType = bounds.outMimeType?.trim()?.lowercase(Locale.US).orEmpty()
                if (mimeType in PLATFORM_ONLY_IMAGE_MIMES &&
                    bytes.size >= 16 &&
                    isValidDimensions(bounds.outWidth.toLong(), bounds.outHeight.toLong())
                ) {
                    EncodedImageDimensions(bounds.outWidth, bounds.outHeight)
                } else {
                    return null
                }
            }

            if (!isValidDimensions(parsed.width.toLong(), parsed.height.toLong())) {
                return null
            }

            var sampleSize = 1
            if (parsed.width > target || parsed.height > target) {
                var halfW = parsed.width / 2
                var halfH = parsed.height / 2
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

    private fun parseAndValidateImageDimensions(bytes: ByteArray): EncodedImageDimensions? {
        return when {
            hasPngSignature(bytes) -> parseValidPngDimensions(bytes)
            hasJpegSoi(bytes) -> parseValidJpegDimensions(bytes)
            hasWebpHeader(bytes) -> parseValidWebpDimensions(bytes)
            hasGifHeader(bytes) -> parseValidGifDimensions(bytes)
            hasBmpHeader(bytes) -> parseValidBmpDimensions(bytes)
            else -> null
        }
    }

    private fun hasPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    private fun hasJpegSoi(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8
    }

    private fun hasWebpHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    }

    private fun hasGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        return header == "GIF87a" || header == "GIF89a"
    }

    private fun hasBmpHeader(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
    }

    private fun parseValidPngDimensions(bytes: ByteArray): EncodedImageDimensions? {
        if (bytes.size < 45 || !hasPngSignature(bytes)) return null
        var offset = PNG_SIGNATURE.size
        var chunkIndex = 0
        var parsedWidth = 0L
        var parsedHeight = 0L
        var sawIhdr = false
        var sawIdat = false
        var sawIend = false

        while (offset + 12 <= bytes.size) {
            val lengthLong = readUInt32BE(bytes, offset)
            if (lengthLong < 0L || lengthLong > Int.MAX_VALUE.toLong()) return null
            val totalChunkBytes = 12L + lengthLong
            if (offset.toLong() + totalChunkBytes > bytes.size.toLong()) return null
            val length = lengthLong.toInt()

            val crc32 = CRC32().apply { update(bytes, offset + 4, 4 + length) }.value
            val storedCrc = readUInt32BE(bytes, offset + 8 + length)
            if (crc32 != storedCrc) return null

            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (chunkIndex == 0) {
                if (type != "IHDR" || length != 13) return null
                parsedWidth = readUInt32BE(bytes, offset + 8)
                parsedHeight = readUInt32BE(bytes, offset + 12)
                if (!isValidDimensions(parsedWidth, parsedHeight)) return null
                sawIhdr = true
            } else if (type == "IDAT") {
                if (length > 0) {
                    sawIdat = true
                }
            } else if (type == "IEND") {
                if (length != 0) return null
                sawIend = true
                break
            }

            offset += 12 + length
            chunkIndex++
        }
        return if (sawIhdr && sawIdat && sawIend) {
            EncodedImageDimensions(parsedWidth.toInt(), parsedHeight.toInt())
        } else {
            null
        }
    }

    private fun parseValidJpegDimensions(bytes: ByteArray): EncodedImageDimensions? {
        if (bytes.size < 16 || !hasJpegSoi(bytes)) return null

        var pos = 2
        var parsedWidth = 0L
        var parsedHeight = 0L
        var sawSof = false
        var sawSos = false
        var sawEoi = false

        while (pos < bytes.size) {
            if ((bytes[pos].toInt() and 0xFF) != 0xFF) return null
            while (pos < bytes.size && (bytes[pos].toInt() and 0xFF) == 0xFF) {
                pos++
            }
            if (pos >= bytes.size) return null
            val marker = bytes[pos].toInt() and 0xFF
            pos++

            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8) {
                continue
            }
            if (marker == 0xD9) {
                sawEoi = sawSos
                break
            }
            if (pos + 2 > bytes.size) return null
            val segLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            if (segLen < 2 || pos + segLen > bytes.size) return null

            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (segLen < 8) return null
                val frameH = (((bytes[pos + 3].toInt() and 0xFF) shl 8) or (bytes[pos + 4].toInt() and 0xFF)).toLong()
                val frameW = (((bytes[pos + 5].toInt() and 0xFF) shl 8) or (bytes[pos + 6].toInt() and 0xFF)).toLong()
                if (!isValidDimensions(frameW, frameH)) return null
                parsedWidth = frameW
                parsedHeight = frameH
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
        return if (sawSof && sawSos && sawEoi) {
            EncodedImageDimensions(parsedWidth.toInt(), parsedHeight.toInt())
        } else {
            null
        }
    }

    private fun parseValidWebpDimensions(bytes: ByteArray): EncodedImageDimensions? {
        if (bytes.size < 20 || !hasWebpHeader(bytes)) return null
        val riffSize = readUInt32LE(bytes, 4)
        if (riffSize < 12L || 8L + riffSize > bytes.size.toLong()) return null
        val chunkTag = String(bytes, 12, 4, Charsets.US_ASCII)
        val chunkSize = readUInt32LE(bytes, 16)
        if (chunkSize <= 0L || 20L + chunkSize > 8L + riffSize) return null

        val (w, h) = when (chunkTag) {
            "VP8 " -> {
                if (chunkSize < 10L || bytes.size < 30) return null
                if ((bytes[23].toInt() and 0xFF) != 0x9D ||
                    (bytes[24].toInt() and 0xFF) != 0x01 ||
                    (bytes[25].toInt() and 0xFF) != 0x2A
                ) {
                    return null
                }
                val rawW = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0x3F) shl 8)
                val rawH = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0x3F) shl 8)
                rawW.toLong() to rawH.toLong()
            }
            "VP8L" -> {
                if (chunkSize < 5L || bytes.size < 25 || (bytes[20].toInt() and 0xFF) != 0x2F) {
                    return null
                }
                val bits = readUInt32LE(bytes, 21)
                val rawW = (bits and 0x3FFFL) + 1L
                val rawH = ((bits ushr 14) and 0x3FFFL) + 1L
                rawW to rawH
            }
            "VP8X" -> {
                if (chunkSize < 10L || bytes.size < 30) return null
                val rawW = (readUInt24LE(bytes, 24) + 1L)
                val rawH = (readUInt24LE(bytes, 27) + 1L)
                rawW to rawH
            }
            else -> return null
        }
        return if (isValidDimensions(w, h)) {
            EncodedImageDimensions(w.toInt(), h.toInt())
        } else {
            null
        }
    }

    private fun parseValidGifDimensions(bytes: ByteArray): EncodedImageDimensions? {
        if (bytes.size < 14 || !hasGifHeader(bytes)) return null
        val w = ((bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)).toLong()
        val h = ((bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)).toLong()
        if (!isValidDimensions(w, h)) return null
        var lastNonZero = bytes.size - 1
        while (lastNonZero > 13 && bytes[lastNonZero] == 0.toByte()) {
            lastNonZero--
        }
        return if (bytes[lastNonZero] == 0x3B.toByte()) {
            EncodedImageDimensions(w.toInt(), h.toInt())
        } else {
            null
        }
    }

    private fun parseValidBmpDimensions(bytes: ByteArray): EncodedImageDimensions? {
        if (bytes.size < 26 || !hasBmpHeader(bytes)) return null
        val declaredSize = readUInt32LE(bytes, 2)
        if (declaredSize < 26L || declaredSize > bytes.size.toLong()) return null
        val dibSize = readUInt32LE(bytes, 14)
        val (w, h) = when {
            dibSize == 12L -> {
                val rawW = ((bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)).toLong()
                val rawH = ((bytes[20].toInt() and 0xFF) or ((bytes[21].toInt() and 0xFF) shl 8)).toLong()
                rawW to rawH
            }
            dibSize >= 40L -> {
                val rawW = readInt32LE(bytes, 18).toLong()
                val rawH = kotlin.math.abs(readInt32LE(bytes, 22).toLong())
                rawW to rawH
            }
            else -> return null
        }
        return if (isValidDimensions(w, h)) {
            EncodedImageDimensions(w.toInt(), h.toInt())
        } else {
            null
        }
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

    private fun readUInt24LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16)
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
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
