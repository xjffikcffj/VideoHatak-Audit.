package com.videohatak.player.core.subtitle.speech

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.translation.ContextAwareDialogueTranslator
import com.videohatak.player.core.subtitle.translation.OfflineModelManager
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class VoskWordToken(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 1.0f
)

class VoskRecognitionException(message: String) : IOException(message)

object VoskResultCueSegmenter {
    private const val SILENCE_SPLIT_GAP_MS = 450L
    private const val MAX_CUE_DURATION_MS = 4_500L
    private const val MAX_CUE_CHARS = 78
    private const val MIN_CUE_DISPLAY_MS = 900L
    private val IGNORED_TOKENS = setOf("[unk]", "<unk>", "[noise]", "<noise>", "[laughter]", "")

    fun parseVoskResultWords(jsonText: String): List<VoskWordToken> {
        if (jsonText.isBlank()) return emptyList()
        return try {
            val json = JSONObject(jsonText)
            val wordsArr = json.optJSONArray("result") ?: return emptyList()
            buildList {
                for (i in 0 until wordsArr.length()) {
                    val obj = wordsArr.optJSONObject(i) ?: continue
                    val rawWord = obj.optString("word", "").trim()
                    if (rawWord.lowercase() in IGNORED_TOKENS) continue
                    val startSec = obj.optDouble("start", -1.0)
                    val endSec = obj.optDouble("end", -1.0)
                    if (startSec < 0.0 || endSec < 0.0) continue
                    val startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L)
                    val endMs = (endSec * 1000.0).toLong().coerceAtLeast(startMs + 80L)
                    val conf = obj.optDouble("conf", 1.0).toFloat()
                    add(VoskWordToken(word = rawWord, startMs = startMs, endMs = endMs, confidence = conf))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun segmentWordsIntoCues(
        words: List<VoskWordToken>,
        language: SubtitleTranslationLanguage
    ): List<SubtitleCue> {
        if (words.isEmpty()) return emptyList()

        val sorted = words.sortedWith(compareBy<VoskWordToken> { it.startMs }.thenBy { it.endMs })
        val rawGroups = mutableListOf<List<VoskWordToken>>()
        val currentGroup = mutableListOf<VoskWordToken>()

        for (token in sorted) {
            if (currentGroup.isEmpty()) {
                currentGroup.add(token)
                continue
            }

            val groupStartMs = currentGroup.first().startMs
            val prevEndMs = currentGroup.last().endMs
            val silenceGap = token.startMs - prevEndMs
            val projectedDuration = token.endMs - groupStartMs
            val projectedText = joinTokens(currentGroup + token, language)

            val shouldSplit = silenceGap >= SILENCE_SPLIT_GAP_MS ||
                projectedDuration > MAX_CUE_DURATION_MS ||
                projectedText.length > MAX_CUE_CHARS

            if (shouldSplit) {
                rawGroups.add(currentGroup.toList())
                currentGroup.clear()
            }
            currentGroup.add(token)
        }
        if (currentGroup.isNotEmpty()) {
            rawGroups.add(currentGroup.toList())
        }

        val provisionalCues = rawGroups.mapIndexedNotNull { idx, group ->
            val joined = joinTokens(group, language).trim()
            if (joined.isBlank()) return@mapIndexedNotNull null
            val startMs = group.first().startMs
            val rawEndMs = group.last().endMs
            val minExtendedEndMs = maxOf(rawEndMs, startMs + MIN_CUE_DISPLAY_MS)
            SubtitleCue(
                index = idx + 1,
                startTimeMs = startMs,
                endTimeMs = minExtendedEndMs,
                text = ContextAwareDialogueTranslator.formatSubtitleLines(joined)
            )
        }

        if (provisionalCues.isEmpty()) return emptyList()

        // Enforce strictly monotonic, non-overlapping timestamps
        val normalized = ArrayList<SubtitleCue>(provisionalCues.size)
        for (i in provisionalCues.indices) {
            val current = provisionalCues[i]
            val prevEnd = normalized.lastOrNull()?.endTimeMs ?: 0L
            val safeStart = maxOf(current.startTimeMs, prevEnd)
            val nextStart = provisionalCues.getOrNull(i + 1)?.startTimeMs
            val boundedEnd = if (nextStart != null && current.endTimeMs > nextStart) {
                maxOf(safeStart + 250L, nextStart - 20L)
            } else {
                maxOf(current.endTimeMs, safeStart + 250L)
            }
            normalized.add(
                current.copy(
                    index = normalized.size + 1,
                    startTimeMs = safeStart,
                    endTimeMs = boundedEnd
                )
            )
        }
        return normalized
    }

    private fun joinTokens(
        tokens: List<VoskWordToken>,
        language: SubtitleTranslationLanguage
    ): String {
        if (language == SubtitleTranslationLanguage.JAPANESE) {
            val sb = StringBuilder()
            for (i in tokens.indices) {
                val w = tokens[i].word
                if (w.isEmpty()) continue
                if (i > 0) {
                    val prevLast = tokens[i - 1].word.lastOrNull()
                    val currFirst = w.firstOrNull()
                    if (prevLast != null && currFirst != null &&
                        prevLast.code < 128 && currFirst.code < 128
                    ) {
                        sb.append(' ')
                    }
                }
                sb.append(w)
            }
            return sb.toString()
        }
        return tokens.joinToString(" ") { it.word }
    }
}

class VoskSpeechToSubtitleEngine(
    private val modelManager: OfflineModelManager
) {
    companion object {
        private val recognitionMutex = Mutex()
    }

    suspend fun transcribeVideoAudioToCues(
        context: Context,
        videoUri: Uri,
        sourceLanguage: SubtitleTranslationLanguage,
        onProgress: (progressFraction: Float, phaseLabel: String) -> Unit
    ): List<SubtitleCue> = withContext(Dispatchers.IO) {
        if (sourceLanguage == SubtitleTranslationLanguage.AUTO || !sourceLanguage.supportsVoskSpeech) {
            throw VoskRecognitionException(
                "يرجى تحديد لغة الفيديو الأصلية (المصدر) لكي يتمكن محرك التفريغ الصوتي المحلي من استخدام نموذجها."
            )
        }

        val modelDir = modelManager.getVerifiedSpeechModelDirectory(sourceLanguage)
            ?: throw VoskRecognitionException(
                "نموذج التفريغ الصوتي للغة ${sourceLanguage.arabicName} غير محمل أو غير مكتمل. يرجى تنزيله أولًا."
            )

        recognitionMutex.withLock {
            modelManager.markSpeechInUse(sourceLanguage, true)
            var voskModel: Model? = null
            var recognizer: Recognizer? = null

            try {
                coroutineContext.ensureActive()
                onProgress(0.05f, "جاري تحميل نموذج التعرف على الكلام (${sourceLanguage.arabicName}) محليًا...")

                voskModel = try {
                    Model(modelDir.absolutePath)
                } catch (oom: OutOfMemoryError) {
                    throw VoskRecognitionException(
                        "ذاكرة الجهاز غير كافية لتحميل نموذج ${sourceLanguage.arabicName} الصوتي."
                    )
                } catch (linkErr: UnsatisfiedLinkError) {
                    throw VoskRecognitionException(
                        "تعذر تشغيل مكتبة التفريغ الصوتي الأصلية على معمارية هذا الجهاز."
                    )
                }

                recognizer = Recognizer(
                    voskModel,
                    VideoAudioPcmDecoder.TARGET_SAMPLE_RATE_HZ.toFloat()
                ).apply {
                    setWords(true)
                }

                val collectedWords = mutableListOf<VoskWordToken>()
                val activeRecognizer = recognizer

                onProgress(0.10f, "جاري فك ترميز الصوت محليًا إلى 16kHz وتفريغ الكلام (${sourceLanguage.arabicName})...")

                VideoAudioPcmDecoder.decodeTo16kMonoPcmStream(
                    context = context,
                    videoUri = videoUri
                ) { pcmBytes, length, decodeProgress ->
                    coroutineContext.ensureActive()
                    val isUtteranceComplete = activeRecognizer.acceptWaveForm(pcmBytes, length)
                    if (isUtteranceComplete) {
                        collectedWords += VoskResultCueSegmenter.parseVoskResultWords(activeRecognizer.result)
                    }
                    val mappedProgress = 0.10f + (decodeProgress.coerceIn(0f, 1f) * 0.80f)
                    val pct = (mappedProgress * 100f).toInt()
                    onProgress(mappedProgress, "جاري التفريغ الصوتي المحلي دون إنترنت ($pct%)...")
                }

                coroutineContext.ensureActive()
                collectedWords += VoskResultCueSegmenter.parseVoskResultWords(activeRecognizer.finalResult)

                val cues = VoskResultCueSegmenter.segmentWordsIntoCues(collectedWords, sourceLanguage)
                if (cues.isEmpty()) {
                    throw VoskRecognitionException(
                        "لم يتم اكتشاف كلام واضح باللغة ${sourceLanguage.arabicName} في المسار الصوتي لهذا الفيديو."
                    )
                }
                cues
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: VoskRecognitionException) {
                throw e
            } catch (e: VideoAudioDecodeException) {
                throw VoskRecognitionException(e.message ?: "تعذر فك ترميز مسار الصوت محليًا.")
            } catch (e: Exception) {
                throw VoskRecognitionException(
                    "فشل التفريغ الصوتي المحلي: ${e.localizedMessage ?: "خطأ غير متوقع"}"
                )
            } finally {
                runCatching { recognizer?.close() }
                runCatching { voskModel?.close() }
                modelManager.markSpeechInUse(sourceLanguage, false)
            }
        }
    }
}
