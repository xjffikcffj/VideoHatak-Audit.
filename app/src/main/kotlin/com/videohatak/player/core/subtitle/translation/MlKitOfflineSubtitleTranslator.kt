package com.videohatak.player.core.subtitle.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.videohatak.player.core.subtitle.model.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

class OfflineTranslationException(message: String) : IOException(message)

internal data class SpeakerTurnSegment(
    val prefix: String,
    val cleanText: String
)

internal data class ProtectedGlossaryText(
    val maskedText: String,
    val tokenToTarget: Map<String, String>,
    val sourceToTarget: List<TranslationGlossaryEntry>
)

internal object SubtitleTurnAndGlossaryHelper {
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val ASS_TAG_REGEX = Regex("\\{[^}]+\\}")
    private val SPEAKER_LINE_REGEX = Regex("^(\\s*[-–—]+\\s*)(.+)$")

    fun cleanSubtitleMarkup(raw: String): String {
        return raw
            .replace(ASS_TAG_REGEX, "")
            .replace(HTML_TAG_REGEX, "")
            .trim()
    }

    fun splitSpeakerTurns(rawCueText: String): List<SpeakerTurnSegment> {
        val cleaned = cleanSubtitleMarkup(rawCueText)
        if (cleaned.isBlank()) return emptyList()

        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1 && lines.count { SPEAKER_LINE_REGEX.matches(it) } >= 2) {
            return lines.mapNotNull { line ->
                val match = SPEAKER_LINE_REGEX.matchEntire(line)
                if (match != null) {
                    val body = match.groupValues[2].trim()
                    if (body.isNotEmpty()) SpeakerTurnSegment("- ", body) else null
                } else {
                    SpeakerTurnSegment("", line)
                }
            }
        }

        // Single speaker cue: collapse mid-sentence line breaks into spaces for better NMT phrasing
        val singleTurn = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return if (singleTurn.isEmpty()) emptyList() else listOf(SpeakerTurnSegment("", singleTurn))
    }

    fun protectGlossaryTerms(
        text: String,
        glossary: List<TranslationGlossaryEntry>
    ): ProtectedGlossaryText {
        if (glossary.isEmpty() || text.isBlank()) {
            return ProtectedGlossaryText(text, emptyMap(), glossary)
        }
        var current = text
        val tokenMap = linkedMapOf<String, String>()
        val sortedEntries = glossary
            .filter { it.sourceTerm.isNotBlank() && it.targetTerm.isNotBlank() }
            .sortedByDescending { it.sourceTerm.length }

        sortedEntries.forEachIndexed { idx, entry ->
            val escaped = Regex.escape(entry.sourceTerm)
            val pattern = if (entry.sourceTerm.all { it.isLetterOrDigit() }) {
                Regex("\\b$escaped\\b", RegexOption.IGNORE_CASE)
            } else {
                Regex(escaped, RegexOption.IGNORE_CASE)
            }
            if (pattern.containsMatchIn(current)) {
                val token = "VHTERM${idx}Z"
                current = pattern.replace(current, token)
                tokenMap[token] = entry.targetTerm
            }
        }
        return ProtectedGlossaryText(
            maskedText = current,
            tokenToTarget = tokenMap,
            sourceToTarget = sortedEntries
        )
    }

    fun restoreGlossaryTerms(
        translatedText: String,
        protected: ProtectedGlossaryText
    ): String {
        var result = translatedText
        protected.tokenToTarget.forEach { (token, targetTerm) ->
            result = result.replace(token, targetTerm, ignoreCase = true)
        }
        protected.sourceToTarget.forEach { entry ->
            if (result.contains(entry.sourceTerm, ignoreCase = true)) {
                result = result.replace(entry.sourceTerm, entry.targetTerm, ignoreCase = true)
            }
        }
        return result.trim()
    }
}

object MlKitLanguageDetector {
    suspend fun detectLanguageFromCues(cues: List<SubtitleCue>): SubtitleTranslationLanguage? =
        withContext(Dispatchers.IO) {
            val sampleText = cues.asSequence()
                .map { SubtitleTurnAndGlossaryHelper.cleanSubtitleMarkup(it.text) }
                .filter { it.length >= 3 }
                .take(8)
                .joinToString(" ")
                .take(800)
                .trim()

            if (sampleText.length < 4) return@withContext null

            val identifier = LanguageIdentification.getClient(
                LanguageIdentificationOptions.Builder()
                    .setConfidenceThreshold(0.45f)
                    .build()
            )
            try {
                val tag = identifier.identifyLanguage(sampleText).awaitTaskResult()
                SubtitleTranslationLanguage.fromLanguageTag(tag)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            } finally {
                runCatching { identifier.close() }
            }
        }
}

data class OfflineTranslationExecutionResult(
    val pairs: List<TranslatedCuePair>,
    val resolvedSourceLanguage: SubtitleTranslationLanguage,
    val usedTranslationEngine: Boolean
)

class MlKitOfflineSubtitleTranslator(
    private val modelManager: OfflineModelManager
) {
    suspend fun translateCuesOffline(
        cues: List<SubtitleCue>,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage,
        glossary: List<TranslationGlossaryEntry> = emptyList(),
        downloadMissingModels: Boolean = true,
        onProgress: (completedCues: Int, totalCues: Int, phaseLabel: String) -> Unit
    ): OfflineTranslationExecutionResult = withContext(Dispatchers.IO) {
        if (cues.isEmpty()) {
            throw OfflineTranslationException("لا توجد جمل ترجمة للمعالجة.")
        }
        if (targetLanguage == SubtitleTranslationLanguage.AUTO) {
            throw OfflineTranslationException("يرجى اختيار لغة ترجمة هدف محددة.")
        }

        val resolvedSource = if (sourceLanguage == SubtitleTranslationLanguage.AUTO) {
            onProgress(0, cues.size, "جاري التعرف تلقائيًا على لغة النص الأصلية دون إنترنت...")
            MlKitLanguageDetector.detectLanguageFromCues(cues)
                ?: throw OfflineTranslationException(
                    "تعذر تحديد لغة الترجمة تلقائيًا من النص. يرجى اختيار لغة المصدر يدويًا ثم إعادة المحاولة."
                )
        } else {
            sourceLanguage
        }

        // Fast bypass when source audio/subtitle matches target language (e.g., Arabic -> Arabic)
        if (resolvedSource == targetLanguage) {
            val directPairs = cues.mapIndexed { idx, cue ->
                val formatted = ContextAwareDialogueTranslator.formatSubtitleLines(
                    SubtitleTurnAndGlossaryHelper.cleanSubtitleMarkup(cue.text)
                )
                TranslatedCuePair(
                    index = cue.index.takeIf { it > 0 } ?: (idx + 1),
                    startTimeMs = cue.startTimeMs,
                    endTimeMs = cue.endTimeMs,
                    originalText = cue.text,
                    translatedText = formatted.ifBlank { cue.text }
                )
            }
            return@withContext OfflineTranslationExecutionResult(
                pairs = directPairs,
                resolvedSourceLanguage = resolvedSource,
                usedTranslationEngine = false
            )
        }

        val mlKitSrc = resolvedSource.mlKitCode?.let { TranslateLanguage.fromLanguageTag(it) }
            ?: throw OfflineTranslationException("لغة المصدر (${resolvedSource.arabicName}) غير مدعومة في الترجمة المحلية.")
        val mlKitDst = targetLanguage.mlKitCode?.let { TranslateLanguage.fromLanguageTag(it) }
            ?: throw OfflineTranslationException("لغة الهدف (${targetLanguage.arabicName}) غير مدعومة في الترجمة المحلية.")

        if (downloadMissingModels) {
            onProgress(0, cues.size, "التحقق من توفر نماذج الترجمة المحلية (${resolvedSource.arabicName} ← ${targetLanguage.arabicName})...")
            val readyResult = modelManager.ensureTranslationPairReady(resolvedSource, targetLanguage)
            if (!readyResult.success) {
                throw OfflineTranslationException(readyResult.message)
            }
        } else {
            modelManager.refreshAllStates()
            val readiness = modelManager.computePairReadiness(
                sourceMode = SubtitleSourceMode.EXISTING_SUBTITLE,
                sourceLanguage = resolvedSource,
                targetLanguage = targetLanguage
            )
            if (readiness.missingMlKitLanguages.isNotEmpty()) {
                val names = readiness.missingMlKitLanguages.joinToString("، ") { it.arabicName }
                throw OfflineTranslationException(
                    "يلزم تنزيل حزمة الترجمة المحلية للغة ($names) للعمل دون إنترنت."
                )
            }
        }

        modelManager.markTranslationInUse(resolvedSource, true)
        modelManager.markTranslationInUse(targetLanguage, true)

        var translator: Translator? = null
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitSrc)
                .setTargetLanguage(mlKitDst)
                .build()
            translator = Translation.getClient(options)

            if (downloadMissingModels) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTaskResult()
            }

            val activeTranslator = translator
            val results = ArrayList<TranslatedCuePair>(cues.size)
            val total = cues.size

            onProgress(0, total, "بدء الترجمة المحلية دون إنترنت (${resolvedSource.arabicName} ← ${targetLanguage.arabicName})...")

            for (i in cues.indices) {
                coroutineContext.ensureActive()
                val cue = cues[i]
                val turns = SubtitleTurnAndGlossaryHelper.splitSpeakerTurns(cue.text)

                val translatedText = if (turns.isEmpty()) {
                    cue.text
                } else if (turns.size == 1 && turns[0].prefix.isEmpty()) {
                    val protected = SubtitleTurnAndGlossaryHelper.protectGlossaryTerms(turns[0].cleanText, glossary)
                    val rawTranslated = activeTranslator.translate(protected.maskedText).awaitTaskResult()
                    val restored = SubtitleTurnAndGlossaryHelper.restoreGlossaryTerms(rawTranslated, protected)
                    ContextAwareDialogueTranslator.formatSubtitleLines(restored).ifBlank { cue.text }
                } else {
                    val translatedTurns = buildList(turns.size) {
                        for (segment in turns) {
                            coroutineContext.ensureActive()
                            val protected = SubtitleTurnAndGlossaryHelper.protectGlossaryTerms(segment.cleanText, glossary)
                            val rawTranslated = activeTranslator.translate(protected.maskedText).awaitTaskResult()
                            val restored = SubtitleTurnAndGlossaryHelper.restoreGlossaryTerms(rawTranslated, protected)
                            add("${segment.prefix}$restored".trim())
                        }
                    }
                    translatedTurns.joinToString("\n")
                }

                results.add(
                    TranslatedCuePair(
                        index = cue.index.takeIf { it > 0 } ?: (i + 1),
                        startTimeMs = cue.startTimeMs,
                        endTimeMs = cue.endTimeMs,
                        originalText = cue.text,
                        translatedText = translatedText
                    )
                )

                val doneCount = i + 1
                if (doneCount == total || doneCount % 3 == 0) {
                    onProgress(
                        doneCount,
                        total,
                        "ترجمة محلية دون إنترنت ($doneCount من $total جملة)..."
                    )
                }
            }

            OfflineTranslationExecutionResult(
                pairs = results,
                resolvedSourceLanguage = resolvedSource,
                usedTranslationEngine = true
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: OfflineTranslationException) {
            throw e
        } catch (e: Exception) {
            throw OfflineTranslationException(
                "فشلت الترجمة المحلية عبر ML Kit: ${e.localizedMessage ?: "تأكد من اكتمال نماذج اللغات"}"
            )
        } finally {
            runCatching { translator?.close() }
            modelManager.markTranslationInUse(resolvedSource, false)
            modelManager.markTranslationInUse(targetLanguage, false)
        }
    }
}
