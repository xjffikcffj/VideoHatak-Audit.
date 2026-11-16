package com.videohatak.player.core.subtitle.translation

import com.videohatak.player.core.subtitle.model.SubtitleCue
import java.util.Locale

enum class TranslationEngineMode(
    val arabicTitle: String,
    val arabicDescription: String
) {
    OFFLINE_ON_DEVICE(
        arabicTitle = "الترجمة المحلية دون إنترنت (Vosk + ML Kit)",
        arabicDescription = "تعمل بالكامل على الهاتف دون إرسال أي صوت أو نص لخادم خارجي بعد تنزيل النماذج"
    ),
    CLOUD_PROXY(
        arabicTitle = "الترجمة السحابية عبر خادم وسيط (مستقبلي/اختياري)",
        arabicDescription = "مسار منفصل يتطلب إعداد خادم HTTPS خارجي خاص ولا يُستخدم للوضع المحلي"
    )
}

enum class SubtitleTranslationLanguage(
    val code: String,
    val arabicName: String,
    val nativeName: String,
    val isRightToLeft: Boolean = false,
    val mlKitCode: String? = null,
    val supportsVoskSpeech: Boolean = false,
    val isoAliases: Set<String> = emptySet()
) {
    AUTO(
        code = "auto",
        arabicName = "تلقائي (اكتشاف اللغة)",
        nativeName = "Auto",
        isRightToLeft = false,
        mlKitCode = null,
        supportsVoskSpeech = false
    ),
    ARABIC(
        code = "ar",
        arabicName = "العربية",
        nativeName = "العربية",
        isRightToLeft = true,
        mlKitCode = "ar",
        supportsVoskSpeech = true,
        isoAliases = setOf("ar", "ara", "arb")
    ),
    ENGLISH(
        code = "en",
        arabicName = "الإنجليزية",
        nativeName = "English",
        isRightToLeft = false,
        mlKitCode = "en",
        supportsVoskSpeech = true,
        isoAliases = setOf("en", "eng")
    ),
    FRENCH(
        code = "fr",
        arabicName = "الفرنسية",
        nativeName = "Français",
        isRightToLeft = false,
        mlKitCode = "fr",
        supportsVoskSpeech = true,
        isoAliases = setOf("fr", "fra", "fre")
    ),
    SPANISH(
        code = "es",
        arabicName = "الإسبانية",
        nativeName = "Español",
        isRightToLeft = false,
        mlKitCode = "es",
        supportsVoskSpeech = true,
        isoAliases = setOf("es", "spa")
    ),
    TURKISH(
        code = "tr",
        arabicName = "التركية",
        nativeName = "Türkçe",
        isRightToLeft = false,
        mlKitCode = "tr",
        supportsVoskSpeech = true,
        isoAliases = setOf("tr", "tur")
    ),
    GERMAN(
        code = "de",
        arabicName = "الألمانية",
        nativeName = "Deutsch",
        isRightToLeft = false,
        mlKitCode = "de",
        supportsVoskSpeech = true,
        isoAliases = setOf("de", "deu", "ger")
    ),
    JAPANESE(
        code = "ja",
        arabicName = "اليابانية",
        nativeName = "日本語",
        isRightToLeft = false,
        mlKitCode = "ja",
        supportsVoskSpeech = true,
        isoAliases = setOf("ja", "jpn")
    ),
    KOREAN(
        code = "ko",
        arabicName = "الكورية",
        nativeName = "한국어",
        isRightToLeft = false,
        mlKitCode = "ko",
        supportsVoskSpeech = true,
        isoAliases = setOf("ko", "kor")
    );

    companion object {
        val SPEECH_RECOGNITION_LANGUAGES = entries.filter { it.supportsVoskSpeech }
        val TARGET_LANGUAGES = listOf(ARABIC, ENGLISH, FRENCH, SPANISH, TURKISH, GERMAN, JAPANESE, KOREAN)

        fun fromLanguageTag(rawTag: String?): SubtitleTranslationLanguage? {
            val clean = rawTag?.trim()?.lowercase(Locale.US)?.substringBefore('-')?.substringBefore('_')
                ?: return null
            if (clean.isBlank() || clean == "und" || clean == "auto") return null
            return entries.firstOrNull { lang ->
                lang != AUTO && (lang.code == clean || clean in lang.isoAliases)
            }
        }
    }
}

enum class SubtitleSourceMode(
    val arabicTitle: String,
    val arabicDescription: String
) {
    EXISTING_SUBTITLE(
        arabicTitle = "ترجمة ملف/مسار الترجمة الحالي",
        arabicDescription = "ترجمة النصوص محليًا عبر Google ML Kit مع حفظ التوقيت الدقيق"
    ),
    VIDEO_AUDIO_EXTRACTION(
        arabicTitle = "توليد وترجمة تلقائية من صوت الفيديو",
        arabicDescription = "تفريغ الكلام محليًا عبر Vosk (16kHz PCM) ثم الترجمة محليًا دون إنترنت"
    )
}

data class TranslationGlossaryEntry(
    val sourceTerm: String,
    val targetTerm: String
)

data class TranslatedCuePair(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val originalText: String,
    val translatedText: String
) {
    fun toTranslatedCue(): SubtitleCue = SubtitleCue(
        index = index,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        text = translatedText
    )
}

data class SubtitleBatchRequest(
    val batchIndex: Int,
    val totalBatches: Int,
    val sourceLanguage: SubtitleTranslationLanguage,
    val targetLanguage: SubtitleTranslationLanguage,
    val previousContext: List<String>,
    val cues: List<SubtitleCue>,
    val nextContext: List<String>,
    val glossary: List<TranslationGlossaryEntry> = emptyList()
)

data class SubtitleBatchResponse(
    val batchIndex: Int,
    val translatedTextsByIndex: Map<Int, String>
)

sealed class TranslationJobStatus {
    data object Idle : TranslationJobStatus()

    data class Running(
        val progress: Float,
        val completedBatches: Int,
        val totalBatches: Int,
        val phaseLabel: String
    ) : TranslationJobStatus()

    data class Completed(
        val pairs: List<TranslatedCuePair>,
        val targetLanguage: SubtitleTranslationLanguage,
        val summaryMessage: String
    ) : TranslationJobStatus()

    data class Cancelled(
        val partialPairs: List<TranslatedCuePair> = emptyList()
    ) : TranslationJobStatus()

    data class Error(
        val message: String,
        val retryAfterSeconds: Int? = null
    ) : TranslationJobStatus()
}
