package com.videohatak.player.core.subtitle.translation

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Secure backend proxy configuration.
 * IMPORTANT: Never store or embed any AI/Gemini API secret keys inside the Android client or APK.
 * All AI provider credentials remain exclusively on the backend proxy server.
 */
data class BackendProxyConfig(
    val baseUrl: String = "",
    val sessionToken: String = "",
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 45_000
) {
    val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')

    fun isConfiguredAndSecure(): Boolean {
        val clean = normalizedBaseUrl
        if (clean.isBlank()) return false
        return clean.startsWith("https://", ignoreCase = true) ||
            clean.startsWith("http://10.0.2.2", ignoreCase = true) ||
            clean.startsWith("http://127.0.0.1", ignoreCase = true) ||
            clean.startsWith("http://localhost", ignoreCase = true)
    }

    fun translateEndpoint(): String = "$normalizedBaseUrl/v1/subtitles/translate"

    fun audioTranscribeTranslateEndpoint(): String = "$normalizedBaseUrl/v1/subtitles/transcribe-translate"
}

class TranslationProxyException(
    message: String,
    val httpStatusCode: Int = 0,
    val retryAfterSeconds: Int? = null
) : IOException(message)

interface SubtitleTranslationProvider {
    suspend fun translateBatch(request: SubtitleBatchRequest): SubtitleBatchResponse

    suspend fun transcribeAndTranslateAudio(
        audioFile: File,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ): List<TranslatedCuePair>
}

class ProxySubtitleTranslationProvider(
    private val configProvider: () -> BackendProxyConfig
) : SubtitleTranslationProvider {

    override suspend fun translateBatch(request: SubtitleBatchRequest): SubtitleBatchResponse =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            validateProxyConfig(config)

            val payload = JSONObject().apply {
                put("source_language", request.sourceLanguage.code)
                put("target_language", request.targetLanguage.code)
                put("batch_index", request.batchIndex)
                put("total_batches", request.totalBatches)
                put("previous_context", JSONArray(request.previousContext))
                put("next_context", JSONArray(request.nextContext))
                put("glossary", JSONArray().apply {
                    request.glossary.forEach { entry ->
                        put(JSONObject().apply {
                            put("source", entry.sourceTerm)
                            put("target", entry.targetTerm)
                        })
                    }
                })
                put("cues", JSONArray().apply {
                    request.cues.forEach { cue ->
                        put(JSONObject().apply {
                            put("index", cue.index)
                            put("start_ms", cue.startTimeMs)
                            put("end_ms", cue.endTimeMs)
                            put("text", cue.text)
                        })
                    }
                })
            }

            val responseBody = executeJsonPost(
                urlString = config.translateEndpoint(),
                jsonPayload = payload.toString(),
                config = config
            )
            val resultMap = try {
                val json = JSONObject(responseBody)
                val translatedArr = json.optJSONArray("translations")
                    ?: throw TranslationProxyException("استجابة غير مكتملة من خادم الترجمة")

                val parsedMap = mutableMapOf<Int, String>()
                for (i in 0 until translatedArr.length()) {
                    val item = translatedArr.getJSONObject(i)
                    val idx = item.getInt("index")
                    val translated = item.getString("translated_text")
                    parsedMap[idx] = translated
                }
                parsedMap
            } catch (e: TranslationProxyException) {
                throw e
            } catch (e: JSONException) {
                throw TranslationProxyException("استجابة غير صالحة (JSON) من خادم الترجمة: ${e.localizedMessage ?: "تنسيق غير متوقع"}")
            }

            SubtitleBatchResponse(
                batchIndex = request.batchIndex,
                translatedTextsByIndex = resultMap
            )
        }

    override suspend fun transcribeAndTranslateAudio(
        audioFile: File,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ): List<TranslatedCuePair> = withContext(Dispatchers.IO) {
        val config = configProvider()
        validateProxyConfig(config)

        if (!audioFile.exists() || audioFile.length() == 0L) {
            throw TranslationProxyException("لم يتم العثور على مسار صوت صالح للاستخراج والترجمة")
        }

        val responseBody = executeMultipartAudioUpload(
            urlString = config.audioTranscribeTranslateEndpoint(),
            audioFile = audioFile,
            sourceLanguage = sourceLanguage.code,
            targetLanguage = targetLanguage.code,
            config = config
        )

        try {
            val json = JSONObject(responseBody)
            val cuesArr = json.optJSONArray("cues")
                ?: throw TranslationProxyException("لم يرجع الخادم جمل ترجمة من الصوت")

            buildList {
                for (i in 0 until cuesArr.length()) {
                    val obj = cuesArr.getJSONObject(i)
                    add(
                        TranslatedCuePair(
                            index = obj.optInt("index", i + 1),
                            startTimeMs = obj.getLong("start_ms"),
                            endTimeMs = obj.getLong("end_ms"),
                            originalText = obj.optString("original_text", ""),
                            translatedText = obj.getString("translated_text")
                        )
                    )
                }
            }
        } catch (e: TranslationProxyException) {
            throw e
        } catch (e: JSONException) {
            throw TranslationProxyException("استجابة غير صالحة (JSON) من خادم النسخ الصوتي والترجمة")
        }
    }

    private fun validateProxyConfig(config: BackendProxyConfig) {
        if (config.normalizedBaseUrl.isBlank()) {
            throw TranslationProxyException(
                "خادم الترجمة الذكية (Backend Proxy) غير مضبوط بعد. يتطلب هذا الوضع ربط التطبيق بخادم وسيط خارجي لم يُنشأ بعد."
            )
        }
        if (!config.isConfiguredAndSecure()) {
            throw TranslationProxyException(
                "يجب أن يستخدم خادم الترجمة بروتوكول HTTPS الآمن للحفاظ على خصوصية البيانات."
            )
        }
        val parsedUrl = runCatching { URL(config.normalizedBaseUrl) }.getOrNull()
        if (parsedUrl == null || parsedUrl.host.isNullOrBlank()) {
            throw TranslationProxyException(
                "رابط خادم الترجمة غير صالح. يرجى التأكد من صيغة الرابط مثل: https://api.example.com"
            )
        }
    }

    private fun executeJsonPost(
        urlString: String,
        jsonPayload: String,
        config: BackendProxyConfig
    ): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                if (config.sessionToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.sessionToken.trim()}")
                }
            }
            conn.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(Charsets.UTF_8))
            }
            readHttpResponseOrThrow(conn)
        } catch (e: MalformedURLException) {
            throw TranslationProxyException("رابط خادم الترجمة غير صالح.")
        } catch (e: SocketTimeoutException) {
            throw TranslationProxyException("انتهت مهلة الاتصال بخادم الترجمة. تحقق من الشبكة وحاول مرة أخرى.")
        } catch (e: IOException) {
            if (e is TranslationProxyException) throw e
            throw TranslationProxyException("تعذر الاتصال بخادم الترجمة: ${e.localizedMessage ?: "الخادم غير متاح"}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun executeMultipartAudioUpload(
        urlString: String,
        audioFile: File,
        sourceLanguage: String,
        targetLanguage: String,
        config: BackendProxyConfig
    ): String {
        val boundary = "----VideoHatakBoundary${UUID.randomUUID()}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs.coerceAtLeast(90_000)
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                if (config.sessionToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.sessionToken.trim()}")
                }
            }

            conn.outputStream.buffered().use { out ->
                fun writeField(name: String, value: String) {
                    val header = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
                    out.write(header.toByteArray(Charsets.UTF_8))
                }
                writeField("source_language", sourceLanguage)
                writeField("target_language", targetLanguage)

                val fileHeader = "--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"${audioFile.name}\"\r\nContent-Type: audio/mp4\r\n\r\n"
                out.write(fileHeader.toByteArray(Charsets.UTF_8))
                audioFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }

            readHttpResponseOrThrow(conn)
        } catch (e: MalformedURLException) {
            throw TranslationProxyException("رابط خادم الترجمة غير صالح.")
        } catch (e: SocketTimeoutException) {
            throw TranslationProxyException("انتهت مهلة رفع ومعالجة الصوت عبر الخادم. حاول تقصير المدة أو إعادة المحاولة.")
        } catch (e: IOException) {
            if (e is TranslationProxyException) throw e
            throw TranslationProxyException("تعذر رفع الصوت إلى خادم الترجمة: ${e.localizedMessage ?: "الخادم غير متاح"}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun readHttpResponseOrThrow(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

        if (code in 200..299) return body

        val retryAfter = conn.getHeaderField("Retry-After")?.toIntOrNull()
        val serverMsg = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        val message = when (code) {
            429 -> "تم تجاوز حد الطلبات المسموح على الخادم (Rate Limit). يرجى الانتظار ${retryAfter ?: 30} ثانية."
            401, 403 -> "جلسة الاستخدام غير مصرح لها على خادم الترجمة."
            else -> serverMsg.ifBlank { "فشل طلب الترجمة الذكية (رمز الحالة: $code)." }
        }
        throw TranslationProxyException(
            message = message,
            httpStatusCode = code,
            retryAfterSeconds = retryAfter
        )
    }
}

data class ExtractedAudioResult(
    val file: File,
    val wasTruncatedAtSizeCap: Boolean,
    val extractedBytes: Long
)

object AudioTrackExtractor {
    const val MAX_AUDIO_EXTRACT_BYTES = 18L * 1024L * 1024L

    internal fun resolveAudioTrackIndex(
        trackMimes: List<String>,
        selectedTrackIndex: Int?
    ): Int {
        if (selectedTrackIndex != null) {
            if (selectedTrackIndex !in trackMimes.indices ||
                !trackMimes[selectedTrackIndex].startsWith("audio/")
            ) {
                throw TranslationProxyException(
                    "المسار الصوتي المحدد غير صالح أو غير موجود داخل الفيديو."
                )
            }
            return selectedTrackIndex
        }
        val firstAudioIndex = trackMimes.indexOfFirst { it.startsWith("audio/") }
        if (firstAudioIndex < 0) {
            throw TranslationProxyException("لا يحتوي هذا الفيديو على مسار صوتي قابل للاستخراج.")
        }
        return firstAudioIndex
    }

    suspend fun extractAudioToTempM4a(
        context: Context,
        videoUri: Uri,
        selectedTrackIndex: Int? = null
    ): File = extractAudioWithMetadataToTempM4a(context, videoUri, selectedTrackIndex).file

    suspend fun extractAudioWithMetadataToTempM4a(
        context: Context,
        videoUri: Uri,
        selectedTrackIndex: Int? = null
    ): ExtractedAudioResult = withContext(Dispatchers.IO) {
        if (videoUri.scheme.equals("vault", ignoreCase = true)) {
            throw TranslationProxyException(
                "حماية الخزنة تمنع رفع صوت المقاطع المشفرة إلى السحابة. يمكنك ترجمة ملف ترجمة خارجي محلي لهذا الفيديو."
            )
        }

        val outFile = File(context.cacheDir, "vh_audio_extract_${System.currentTimeMillis()}.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context, videoUri, null)
            val trackMimes = (0 until extractor.trackCount).map { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            val audioTrackIndex = resolveAudioTrackIndex(trackMimes, selectedTrackIndex)
            val audioFormat = extractor.getTrackFormat(audioTrackIndex)

            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrackIdx = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var totalBytes = 0L
            var reachedSizeCap = false

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                if (totalBytes + sampleSize > MAX_AUDIO_EXTRACT_BYTES) {
                    reachedSizeCap = true
                    break
                }
                totalBytes += sampleSize

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxTrackIdx, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (!outFile.exists() || outFile.length() == 0L) {
                throw TranslationProxyException("تعذر استخراج مسار الصوت من الفيديو.")
            }
            ExtractedAudioResult(
                file = outFile,
                wasTruncatedAtSizeCap = reachedSizeCap,
                extractedBytes = outFile.length()
            )
        } catch (e: TranslationProxyException) {
            outFile.delete()
            throw e
        } catch (e: Exception) {
            outFile.delete()
            throw TranslationProxyException(
                "فشل استخراج الصوت من الفيديو: ${e.localizedMessage ?: "صيغة غير مدعومة"}"
            )
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
