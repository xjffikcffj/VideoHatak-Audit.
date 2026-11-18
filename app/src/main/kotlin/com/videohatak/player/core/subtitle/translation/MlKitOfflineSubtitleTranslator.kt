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
                    translateSingleTurnOffline(
                        translator = activeTranslator,
                        rawCleanText = turns[0].cleanText,
                        sourceLanguage = resolvedSource,
                        targetLanguage = targetLanguage,
                        glossary = glossary,
                        fallbackText = cue.text
                    )
                } else {
                    val translatedTurns = buildList(turns.size) {
                        for (segment in turns) {
                            coroutineContext.ensureActive()
                            val translatedSegment = translateSingleTurnOffline(
                                translator = activeTranslator,
                                rawCleanText = segment.cleanText,
                                sourceLanguage = resolvedSource,
                                targetLanguage = targetLanguage,
                                glossary = glossary,
                                fallbackText = segment.cleanText
                            )
                            add("${segment.prefix}$translatedSegment".trim())
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

    private suspend fun translateSingleTurnOffline(
        translator: Translator,
        rawCleanText: String,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage,
        glossary: List<TranslationGlossaryEntry>,
        fallbackText: String
    ): String {
        val protected = SubtitleTurnAndGlossaryHelper.protectGlossaryTerms(rawCleanText, glossary)
        if (protected.tokenToTarget.isEmpty()) {
            OfflineTranslationTextProcessor.lookupNaturalConversationalRescue(
                sourceText = rawCleanText,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )?.let { rescued ->
                return ContextAwareDialogueTranslator.formatSubtitleLines(rescued).ifBlank { fallbackText }
            }
        }

        val normalizedInput = OfflineTranslationTextProcessor.prepareSourceForTranslation(
            text = protected.maskedText,
            sourceLanguage = sourceLanguage
        )
        val rawTranslated = translator.translate(normalizedInput).awaitTaskResult()
        val restored = SubtitleTurnAndGlossaryHelper.restoreGlossaryTerms(rawTranslated, protected)
        val postProcessed = OfflineTranslationTextProcessor.postProcessTranslation(
            translatedText = restored,
            sourceText = rawCleanText,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
        return ContextAwareDialogueTranslator.formatSubtitleLines(postProcessed).ifBlank { fallbackText }
    }
}

/**
 * Pure offline preprocessing & post-processing helper for spoken ASR / SRT dialogue.
 * Fixes common quantized NMT failure modes (lowercase/unpunctuated Vosk phrases such as "i knew",
 * missing apostrophes in spoken contractions, and degenerate single-token mistranslations).
 */
object OfflineTranslationTextProcessor {

    private val STANDALONE_I_REGEX = Regex("\\bi\\b")
    private val I_CONTRACTION_REGEX = Regex("\\bi(['’](?:m|ve|ll|d))\\b", RegexOption.IGNORE_CASE)
    private val TERMINATES_SENTENCE_REGEX = Regex("[.!?؟…:;]$")
    private val ENGLISH_QUESTION_PREFIX_REGEX = Regex(
        "^(who|what|where|when|why|how|did|do|does|is|are|was|were|can|could|will|would|have|has|had|should|may|might)\\b",
        RegexOption.IGNORE_CASE
    )
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val PUNCT_STRIP_REGEX = Regex("[\\p{Punct}؟،؛]+")

    private val ASR_CONTRACTION_FIXES: List<Pair<Regex, String>> = listOf(
        Regex("\\bim\\b", RegexOption.IGNORE_CASE) to "I'm",
        Regex("\\bive\\b", RegexOption.IGNORE_CASE) to "I've",
        Regex("\\bdont\\b", RegexOption.IGNORE_CASE) to "don't",
        Regex("\\bcant\\b", RegexOption.IGNORE_CASE) to "can't",
        Regex("\\bwont\\b", RegexOption.IGNORE_CASE) to "won't",
        Regex("\\bdidnt\\b", RegexOption.IGNORE_CASE) to "didn't",
        Regex("\\bdoesnt\\b", RegexOption.IGNORE_CASE) to "doesn't",
        Regex("\\bisnt\\b", RegexOption.IGNORE_CASE) to "isn't",
        Regex("\\barent\\b", RegexOption.IGNORE_CASE) to "aren't",
        Regex("\\bwasnt\\b", RegexOption.IGNORE_CASE) to "wasn't",
        Regex("\\bwerent\\b", RegexOption.IGNORE_CASE) to "weren't",
        Regex("\\bhavent\\b", RegexOption.IGNORE_CASE) to "haven't",
        Regex("\\bhasnt\\b", RegexOption.IGNORE_CASE) to "hasn't",
        Regex("\\bshouldnt\\b", RegexOption.IGNORE_CASE) to "shouldn't",
        Regex("\\bwouldnt\\b", RegexOption.IGNORE_CASE) to "wouldn't",
        Regex("\\bcouldnt\\b", RegexOption.IGNORE_CASE) to "couldn't",
        Regex("\\byoure\\b", RegexOption.IGNORE_CASE) to "you're",
        Regex("\\btheyre\\b", RegexOption.IGNORE_CASE) to "they're",
        Regex("\\bthats\\b", RegexOption.IGNORE_CASE) to "that's",
        Regex("\\bwhats\\b", RegexOption.IGNORE_CASE) to "what's"
    )

    private val EN_TO_AR_CONVERSATIONAL_RESCUES: Map<String, String> = mapOf(
        "i knew" to "كنت أعلم",
        "i knew it" to "كنت أعلم ذلك",
        "i knew that" to "كنت أعلم ذلك",
        "i thought so" to "ظننت ذلك",
        "i see" to "فهمت",
        "i got it" to "فهمت الأمر",
        "got it" to "فهمت",
        "never mind" to "لا بأس",
        "nevermind" to "لا بأس",
        "of course" to "بالطبع",
        "no way" to "مستحيل",
        "hold on" to "انتظر لحظة",
        "hang on" to "انتظر قليلًا",
        "come on" to "هيا بنا",
        "take care" to "اعتنِ بنفسك",
        "my bad" to "خطئي، أعتذر",
        "what's up" to "ما الأخبار؟",
        "whats up" to "ما الأخبار؟",
        "calm down" to "اهدأ",
        "watch out" to "انتبه!",
        "excuse me" to "عفوًا",
        "you bet" to "بالتأكيد",
        "right now" to "الآن"
    )

    fun prepareSourceForTranslation(
        text: String,
        sourceLanguage: SubtitleTranslationLanguage
    ): String {
        val collapsed = text.replace(MULTI_SPACE_REGEX, " ").trim()
        if (collapsed.isEmpty()) return ""

        if (sourceLanguage != SubtitleTranslationLanguage.ENGLISH) {
            return collapsed
        }

        var normalized = collapsed
        ASR_CONTRACTION_FIXES.forEach { (pattern, replacement) ->
            normalized = pattern.replace(normalized, replacement)
        }
        normalized = STANDALONE_I_REGEX.replace(normalized, "I")
        normalized = I_CONTRACTION_REGEX.replace(normalized) { match ->
            "I" + match.groupValues[1].lowercase(java.util.Locale.US)
        }

        // Capitalize leading English character for NMT sentence-mode stability
        val firstChar = normalized.firstOrNull()
        if (firstChar != null && firstChar in 'a'..'z') {
            normalized = firstChar.uppercaseChar() + normalized.substring(1)
        }

        // Add terminal punctuation if missing so short ASR clauses aren't truncated/mismapped
        if (!TERMINATES_SENTENCE_REGEX.containsMatchIn(normalized)) {
            val isQuestion = ENGLISH_QUESTION_PREFIX_REGEX.containsMatchIn(normalized)
            normalized += if (isQuestion) "?" else "."
        }
        return normalized
    }

    fun lookupNaturalConversationalRescue(
        sourceText: String,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ): String? {
        if (sourceLanguage != SubtitleTranslationLanguage.ENGLISH ||
            targetLanguage != SubtitleTranslationLanguage.ARABIC
        ) {
            return null
        }
        val key = sourceText
            .replace(PUNCT_STRIP_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .lowercase(java.util.Locale.US)
        return EN_TO_AR_CONVERSATIONAL_RESCUES[key]
    }

    fun postProcessTranslation(
        translatedText: String,
        sourceText: String,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ): String {
        val clean = translatedText.replace(MULTI_SPACE_REGEX, " ").trim()
        if (clean.isEmpty()) return sourceText

        if (targetLanguage == SubtitleTranslationLanguage.ARABIC) {
            // Check known corrupted short ML Kit artifact where past/cognitive EN line yields pure "الآن"
            val rescued = lookupNaturalConversationalRescue(sourceText, sourceLanguage, targetLanguage)
            if (rescued != null && (clean == "الآن" || clean == "الآن.")) {
                return rescued
            }
            return clean
                .replace(" ?", "؟")
                .replace("?", "؟")
                .replace(" ,", "،")
                .replace(" .", ".")
                .replace("..", ".")
                .trim()
        }
        return clean
    }
}
