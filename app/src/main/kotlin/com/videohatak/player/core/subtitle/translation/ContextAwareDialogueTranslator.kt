package com.videohatak.player.core.subtitle.translation

import com.videohatak.player.core.subtitle.model.SubtitleCue
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Context-aware dialogue chunker & subtitle line formatter that preserves original timestamps,
 * keeps surrounding scene context across batches, and formats readable 1-2 line subtitles.
 */
object ContextAwareDialogueTranslator {

    const val DEFAULT_BATCH_SIZE = 16
    const val DEFAULT_CONTEXT_WINDOW = 2
    const val DEFAULT_MAX_LINE_CHARS = 42

    private fun hasUniquePositiveIndices(cues: List<SubtitleCue>): Boolean {
        if (cues.isEmpty()) return true
        val seen = HashSet<Int>(cues.size)
        for (cue in cues) {
            if (cue.index <= 0 || !seen.add(cue.index)) return false
        }
        return true
    }

    fun createContextBatches(
        cues: List<SubtitleCue>,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage,
        glossary: List<TranslationGlossaryEntry> = emptyList(),
        batchSize: Int = DEFAULT_BATCH_SIZE,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW
    ): List<SubtitleBatchRequest> {
        if (cues.isEmpty()) return emptyList()
        val normalizedCues = if (hasUniquePositiveIndices(cues)) {
            cues
        } else {
            cues.mapIndexed { idx, cue -> cue.copy(index = idx + 1) }
        }
        val safeBatchSize = batchSize.coerceIn(1, 30)
        val safeWindow = contextWindow.coerceIn(0, 5)
        val chunked = normalizedCues.chunked(safeBatchSize)
        val total = chunked.size

        return chunked.mapIndexed { batchIdx, batchCues ->
            val startGlobalIdx = batchIdx * safeBatchSize
            val endGlobalIdx = startGlobalIdx + batchCues.size

            val prevStart = (startGlobalIdx - safeWindow).coerceAtLeast(0)
            val prevContext = if (startGlobalIdx > prevStart) {
                normalizedCues.subList(prevStart, startGlobalIdx).map { it.text }
            } else {
                emptyList()
            }

            val nextEnd = (endGlobalIdx + safeWindow).coerceAtMost(normalizedCues.size)
            val nextContext = if (nextEnd > endGlobalIdx) {
                normalizedCues.subList(endGlobalIdx, nextEnd).map { it.text }
            } else {
                emptyList()
            }

            SubtitleBatchRequest(
                batchIndex = batchIdx,
                totalBatches = total,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                previousContext = prevContext,
                cues = batchCues,
                nextContext = nextContext,
                glossary = glossary
            )
        }
    }

    fun formatSubtitleLines(
        rawText: String,
        maxCharsPerLine: Int = DEFAULT_MAX_LINE_CHARS,
        maxLines: Int = 2
    ): String {
        val singleSpace = rawText.replace(Regex("\\s+"), " ").trim()
        if (singleSpace.isEmpty()) return ""
        if (singleSpace.length <= maxCharsPerLine || maxLines <= 1) return singleSpace

        val words = singleSpace.split(" ")
        if (words.size <= 1) return singleSpace

        // Split into balanced 2 lines near midpoint
        val targetMid = singleSpace.length / 2
        var bestSplitWordIdx = 1
        var bestDiff = Int.MAX_VALUE
        var accLen = 0

        for (i in 0 until words.size - 1) {
            accLen += words[i].length + (if (i > 0) 1 else 0)
            val diff = kotlin.math.abs(accLen - targetMid)
            if (diff < bestDiff) {
                bestDiff = diff
                bestSplitWordIdx = i + 1
            }
        }

        val line1 = words.subList(0, bestSplitWordIdx).joinToString(" ")
        val line2 = words.subList(bestSplitWordIdx, words.size).joinToString(" ")
        return "$line1\n$line2"
    }

    fun mergeBatchResults(
        originalCues: List<SubtitleCue>,
        responses: List<SubtitleBatchResponse>
    ): List<TranslatedCuePair> {
        val mergedByIndex = mutableMapOf<Int, String>()
        responses.forEach { response ->
            response.translatedTextsByIndex.forEach { (idx, translated) ->
                mergedByIndex[idx] = formatSubtitleLines(translated)
            }
        }

        val useOriginalIndices = hasUniquePositiveIndices(originalCues)
        return originalCues.mapIndexed { position, cue ->
            val lookupKey = if (useOriginalIndices) cue.index else position + 1
            val translated = mergedByIndex[lookupKey]?.takeIf { it.isNotBlank() }
                ?: if (useOriginalIndices) {
                    mergedByIndex[cue.index]?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                ?: cue.text

            TranslatedCuePair(
                index = lookupKey,
                startTimeMs = cue.startTimeMs,
                endTimeMs = cue.endTimeMs,
                originalText = cue.text,
                translatedText = translated
            )
        }
    }

    suspend fun translateAllCues(
        cues: List<SubtitleCue>,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage,
        provider: SubtitleTranslationProvider,
        glossary: List<TranslationGlossaryEntry> = emptyList(),
        batchSize: Int = DEFAULT_BATCH_SIZE,
        maxRetriesPerBatch: Int = 2,
        onProgress: (completedBatches: Int, totalBatches: Int, partialPairs: List<TranslatedCuePair>) -> Unit = { _, _, _ -> }
    ): List<TranslatedCuePair> {
        val batches = createContextBatches(
            cues = cues,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            glossary = glossary,
            batchSize = batchSize
        )
        if (batches.isEmpty()) return emptyList()

        val responses = mutableListOf<SubtitleBatchResponse>()
        for (batch in batches) {
            coroutineContext.ensureActive()
            var attempt = 0
            var lastError: Throwable? = null
            var batchResponse: SubtitleBatchResponse? = null

            while (attempt <= maxRetriesPerBatch && batchResponse == null) {
                coroutineContext.ensureActive()
                try {
                    batchResponse = provider.translateBatch(batch)
                } catch (e: TranslationProxyException) {
                    lastError = e
                    // Do not silently retry client/auth/unconfigured errors
                    if (e.httpStatusCode in listOf(0, 400, 401, 403, 404)) {
                        throw e
                    }
                    attempt++
                    if (attempt <= maxRetriesPerBatch) {
                        val backoffMs = ((e.retryAfterSeconds ?: 2) * 1000L).coerceAtMost(8000L)
                        delay(backoffMs)
                    }
                }
            }

            val resolved = batchResponse ?: throw (lastError ?: TranslationProxyException("فشل ترجمة الدفعة"))
            responses.add(resolved)
            val partial = mergeBatchResults(cues, responses)
            onProgress(batch.batchIndex + 1, batches.size, partial)
        }

        return mergeBatchResults(cues, responses)
    }
}
