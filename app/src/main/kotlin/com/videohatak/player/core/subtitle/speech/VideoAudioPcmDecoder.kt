package com.videohatak.player.core.subtitle.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.vault.datasource.EncryptedVaultDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

data class VideoAudioTrackMetadata(
    val hasAudioTrack: Boolean,
    val detectedTrackLanguage: SubtitleTranslationLanguage? = null,
    val rawLanguageTag: String? = null,
    val durationMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0
)

data class PcmDecodeSummary(
    val durationMs: Long,
    val totalOutputBytes: Long,
    val sourceSampleRateHz: Int,
    val sourceChannelCount: Int
)

class VideoAudioDecodeException(message: String) : IOException(message)

@OptIn(UnstableApi::class)
private class VaultMediaDataSource(
    private val context: Context,
    private val uri: Uri
) : MediaDataSource() {

    private var dataSource: EncryptedVaultDataSource? = null
    private var currentPos: Long = 0L
    private var totalSize: Long = -1L

    @Synchronized
    private fun ensureOpenedAt(position: Long) {
        val ds = dataSource
        if (ds != null && currentPos == position) return

        runCatching { ds?.close() }
        val nextDs = EncryptedVaultDataSource(context)
        val length = nextDs.open(
            DataSpec.Builder()
                .setUri(uri)
                .setPosition(position)
                .build()
        )
        dataSource = nextDs
        currentPos = position
        if (totalSize < 0L) {
            totalSize = if (position == 0L && length != C.LENGTH_UNSET.toLong() && length >= 0L) {
                length
            } else if (length != C.LENGTH_UNSET.toLong() && length >= 0L) {
                position + length
            } else {
                -1L
            }
        }
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (totalSize in 0L..position) return -1

        ensureOpenedAt(position)
        val ds = dataSource ?: return -1
        var totalRead = 0
        while (totalRead < size) {
            val read = ds.read(buffer, offset + totalRead, size - totalRead)
            if (read == C.RESULT_END_OF_INPUT || read <= 0) break
            totalRead += read
            currentPos += read
        }
        return if (totalRead == 0) -1 else totalRead
    }

    @Synchronized
    override fun getSize(): Long {
        if (totalSize < 0L) {
            ensureOpenedAt(0L)
        }
        return totalSize
    }

    @Synchronized
    override fun close() {
        runCatching { dataSource?.close() }
        dataSource = null
    }
}

object VideoAudioPcmDecoder {
    const val TARGET_SAMPLE_RATE_HZ = 16_000
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val STREAM_CHUNK_BYTES = 16_000 // 0.5 sec @ 16kHz 16-bit mono

    suspend fun inspectAudioTrack(
        context: Context,
        videoUri: Uri
    ): VideoAudioTrackMetadata = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var vaultSource: VaultMediaDataSource? = null
        try {
            vaultSource = configureExtractorDataSource(context, extractor, videoUri)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val rawLang = runCatching { format.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull()
                    val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                    val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
                    val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
                    return@withContext VideoAudioTrackMetadata(
                        hasAudioTrack = true,
                        detectedTrackLanguage = SubtitleTranslationLanguage.fromLanguageTag(rawLang),
                        rawLanguageTag = rawLang,
                        durationMs = (durationUs / 1000L).coerceAtLeast(0L),
                        sampleRateHz = sampleRate,
                        channelCount = channels
                    )
                }
            }
            VideoAudioTrackMetadata(hasAudioTrack = false)
        } catch (secEx: SecurityException) {
            throw VideoAudioDecodeException(
                "الخزنة الخاصة مقفلة. افتح قفل الخزنة أولًا لتفريغ الصوت محليًا."
            )
        } catch (_: Exception) {
            VideoAudioTrackMetadata(hasAudioTrack = false)
        } finally {
            runCatching { extractor.release() }
            runCatching { vaultSource?.close() }
        }
    }

    suspend fun decodeTo16kMonoPcmStream(
        context: Context,
        videoUri: Uri,
        onPcmChunk: suspend (pcmBytes: ByteArray, length: Int, progressFraction: Float) -> Unit
    ): PcmDecodeSummary = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var vaultSource: VaultMediaDataSource? = null
        var decoder: MediaCodec? = null

        try {
            vaultSource = configureExtractorDataSource(context, extractor, videoUri)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                throw VideoAudioDecodeException("لا يحتوي هذا الفيديو على مسار صوتي قابل للتفريغ.")
            }

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)
                ?: throw VideoAudioDecodeException("نوع المسار الصوتي غير معروف.")
            val durationUs = runCatching { audioFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            var currentSampleRate = runCatching { audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(44_100)
                .coerceAtLeast(8_000)
            var currentChannels = runCatching { audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1)
                .coerceAtLeast(1)
            var currentEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                audioFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ) {
                audioFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            extractor.selectTrack(audioTrackIndex)
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalOutputBytes = 0L

            val resampler = StreamingPcmResampler()
            val emitBuffer = ByteArray(STREAM_CHUNK_BYTES)
            var emitOffset = 0
            var lastEmittedPtsUs = 0L

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            inBuf.clear()
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                lastEmittedPtsUs = pts
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = decoder.outputFormat
                        currentSampleRate = runCatching { newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                            .getOrDefault(currentSampleRate)
                            .coerceAtLeast(8_000)
                        currentChannels = runCatching { newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(currentChannels)
                            .coerceAtLeast(1)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            currentEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }

                    outIndex >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val monoSamples = decodeBufferToMonoSamples(
                                buffer = outBuf,
                                byteSize = bufferInfo.size,
                                channelCount = currentChannels,
                                pcmEncoding = currentEncoding
                            )
                            val resampled16k = resampler.resampleTo16k(
                                monoInput = monoSamples,
                                sourceSampleRate = currentSampleRate
                            )
                            val progress = if (durationUs > 0L) {
                                val pts = bufferInfo.presentationTimeUs.takeIf { it > 0L } ?: lastEmittedPtsUs
                                (pts.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0.5f
                            }

                            var idx = 0
                            while (idx < resampled16k.size) {
                                val s = resampled16k[idx++]
                                emitBuffer[emitOffset++] = (s.toInt() and 0xFF).toByte()
                                emitBuffer[emitOffset++] = ((s.toInt() shr 8) and 0xFF).toByte()
                                if (emitOffset >= emitBuffer.size) {
                                    onPcmChunk(emitBuffer, emitOffset, progress)
                                    totalOutputBytes += emitOffset
                                    emitOffset = 0
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (emitOffset > 0) {
                onPcmChunk(emitBuffer, emitOffset, 1f)
                totalOutputBytes += emitOffset
            }

            PcmDecodeSummary(
                durationMs = (durationUs / 1000L).coerceAtLeast(0L),
                totalOutputBytes = totalOutputBytes,
                sourceSampleRateHz = currentSampleRate,
                sourceChannelCount = currentChannels
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (secEx: SecurityException) {
            throw VideoAudioDecodeException(
                "تعذر فك تشفير الصوت لأن الخزنة الخاصة مقفلة. افتح الخزنة ثم حاول مجددًا."
            )
        } catch (e: VideoAudioDecodeException) {
            throw e
        } catch (e: Exception) {
            throw VideoAudioDecodeException(
                "فشل فك ترميز مسار الصوت محليًا: ${e.localizedMessage ?: "تنسيق غير مدعوم"}"
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            runCatching { vaultSource?.close() }
        }
    }

    private fun configureExtractorDataSource(
        context: Context,
        extractor: MediaExtractor,
        videoUri: Uri
    ): VaultMediaDataSource? {
        return if (videoUri.scheme.equals("vault", ignoreCase = true)) {
            val vds = VaultMediaDataSource(context, videoUri)
            // Eagerly check Vault unlock state before passing to native MediaExtractor
            if (vds.size < -1L) {
                vds.close()
            }
            extractor.setDataSource(vds)
            vds
        } else {
            extractor.setDataSource(context, videoUri, null)
            null
        }
    }

    private fun decodeBufferToMonoSamples(
        buffer: ByteBuffer,
        byteSize: Int,
        channelCount: Int,
        pcmEncoding: Int
    ): ShortArray {
        val safeChannels = channelCount.coerceAtLeast(1)
        val ordered = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        return if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floatCount = byteSize / 4
            val frameCount = floatCount / safeChannels
            val mono = ShortArray(frameCount)
            for (f in 0 until frameCount) {
                var sum = 0f
                for (c in 0 until safeChannels) {
                    sum += ordered.float
                }
                val avg = (sum / safeChannels).coerceIn(-1f, 1f)
                mono[f] = (avg * Short.MAX_VALUE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            mono
        } else {
            val shortCount = byteSize / 2
            val frameCount = shortCount / safeChannels
            val mono = ShortArray(frameCount)
            if (safeChannels == 1) {
                ordered.asShortBuffer().get(mono)
            } else {
                for (f in 0 until frameCount) {
                    var sum = 0
                    for (c in 0 until safeChannels) {
                        sum += ordered.short.toInt()
                    }
                    mono[f] = (sum / safeChannels)
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }
            mono
        }
    }
}

internal class StreamingPcmResampler {
    private var prevSample: Float = 0f
    private var hasPrev: Boolean = false
    private var fracPos: Double = 0.0

    fun resampleTo16k(monoInput: ShortArray, sourceSampleRate: Int): ShortArray {
        if (monoInput.isEmpty()) return ShortArray(0)
        if (sourceSampleRate == VideoAudioPcmDecoder.TARGET_SAMPLE_RATE_HZ) {
            prevSample = monoInput.last().toFloat()
            hasPrev = true
            return monoInput
        }

        val step = sourceSampleRate.toDouble() / VideoAudioPcmDecoder.TARGET_SAMPLE_RATE_HZ.toDouble()
        val maxEstimate = ((monoInput.size / step) + 4).toInt().coerceAtLeast(1)
        val out = ShortArray(maxEstimate)
        var outCount = 0

        var cursor = fracPos
        while (cursor < monoInput.size.toDouble()) {
            val idx0 = kotlin.math.floor(cursor).toInt()
            val alpha = (cursor - idx0).toFloat()
            val s0 = if (idx0 < 0) {
                if (hasPrev) prevSample else monoInput[0].toFloat()
            } else {
                monoInput[idx0].toFloat()
            }
            val idx1 = idx0 + 1
            val s1 = if (idx1 < monoInput.size) {
                monoInput[idx1].toFloat()
            } else {
                monoInput[monoInput.size - 1].toFloat()
            }
            val interpolated = s0 + (s1 - s0) * alpha
            if (outCount >= out.size) break
            out[outCount++] = interpolated.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            cursor += step
        }

        fracPos = cursor - monoInput.size.toDouble()
        prevSample = monoInput.last().toFloat()
        hasPrev = true

        return if (outCount == out.size) out else out.copyOf(outCount)
    }
}
