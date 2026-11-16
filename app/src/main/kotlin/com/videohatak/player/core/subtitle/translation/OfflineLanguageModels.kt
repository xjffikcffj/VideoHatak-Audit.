package com.videohatak.player.core.subtitle.translation

import java.util.Locale

enum class OfflineModelDownloadStatus(val arabicLabel: String) {
    NOT_DOWNLOADED("غير محملة"),
    DOWNLOADING("جارٍ التنزيل"),
    READY("جاهزة"),
    NEEDS_UPDATE("تحتاج تحديثًا"),
    ERROR("فشل التنزيل")
}

data class VoskSpeechModelSpec(
    val language: SubtitleTranslationLanguage,
    val modelId: String,
    val version: String,
    val archiveFileName: String,
    val downloadUrl: String,
    val approximateZipMb: Int,
    val approximateDiskExtractedMb: Int,
    val licenseName: String,
    val architectureNoteArabic: String,
    val accuracyCaveatArabic: String? = null
) {
    val expectedZipBytes: Long
        get() = approximateZipMb.toLong() * 1024L * 1024L

    val requiredFreeBytesWithMargin: Long
        get() = (approximateZipMb.toLong() + approximateDiskExtractedMb.toLong() + 120L) * 1024L * 1024L
}

object VoskModelCatalog {
    val officialModels: Map<SubtitleTranslationLanguage, VoskSpeechModelSpec> = mapOf(
        SubtitleTranslationLanguage.ENGLISH to VoskSpeechModelSpec(
            language = SubtitleTranslationLanguage.ENGLISH,
            modelId = "vosk-model-small-en-us-0.15",
            version = "0.15",
            archiveFileName = "vosk-model-small-en-us-0.15.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            approximateZipMb = 40,
            approximateDiskExtractedMb = 75,
            licenseName = "Apache-2.0",
            architectureNoteArabic = "خفيف ومناسب لمعظم الهواتف (يفضل ARM64)"
        ),
        SubtitleTranslationLanguage.ARABIC to VoskSpeechModelSpec(
            language = SubtitleTranslationLanguage.ARABIC,
            modelId = "vosk-model-ar-mgb2-0.4",
            version = "0.4",
            archiveFileName = "vosk-model-ar-mgb2-0.4.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip",
            approximateZipMb = 318,
            approximateDiskExtractedMb = 620,
            licenseName = "Apache-2.0",
            architectureNoteArabic = "حجم كبير (~318 MB مضغوط) — يوصى بجهاز ARM64 وذاكرة 4GB+ RAM",
            accuracyCaveatArabic = "مدرّب على العربية الفصحى (بيانات MGB-2 الإخبارية)، وتنخفض دقته مع اللهجات العامية والمؤثرات الصاخبة."
        ),
        SubtitleTranslationLanguage.FRENCH to VoskSpeechModelSpec(
            language = SubtitleTranslationLanguage.FRENCH,
            modelId = "vosk-model-small-fr-0.22",
            version = "0.22",
            archiveFileName = "vosk-model-small-fr-0.22.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
            approximateZipMb = 41,
            approximateDiskExtractedMb = 80,
            licenseName = "Apache-2.0",
            architectureNoteArabic = "نموذج محمول خفيف للأجهزة الذكية"
        ),
        SubtitleTranslationLanguage.SPANISH to VoskSpeechModelSpec(
            language = SubtitleTranslationLanguage.SPANISH,
            modelId = "vosk-model-small-es-0.42",
            version = "0.42",
            archiveFileName = "vosk-model-small-es-0.42.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
            approximateZipMb = 39,
            approximateDiskExtractedMb = 75,
            licenseName = "Apache-2.0",
            architectureNoteArabic = "نموذج محمول خفيف للأجهزة الذكية"
        ),
        SubtitleTranslationLanguage.TURKISH to VoskSpeechModelSpec(
            language = SubtitleTranslationLanguage.TURKISH,
            modelId = "vosk-model-small-tr-0.3",
            version = "0.3",
            archiveFileName = "vosk-model-small-tr-0.3.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip",
            approximateZipMb = 35,
            approximateDiskExtractedMb = 70,
            licenseName = "Apache-2.0",
            architectureNoteArabic = "نموذج محمول خفيف للأجهزة الذكية"
        ),
        SubtitleTranslationLanguage.GERMAN to VoskSpeechModelSpec(
            language = SubtitleTranslationLanguage.GERMAN,
            modelId = "vosk-model-small-de-0.15",
            version = "0.15",
            archiveFileName = "vosk-model-small-de-0.15.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
            approximateZipMb = 45,
            approximateDiskExtractedMb = 85,
            licenseName = "Apache-2.0",
            architectureNoteArabic = "نموذج محمول خفيف للأجهزة الذكية"
        ),
        SubtitleTranslationLanguage.JAPANESE to VoskSpeechModelSpec(
            language = SubtitleTranslationLanguage.JAPANESE,
            modelId = "vosk-model-small-ja-0.22",
            version = "0.22",
            archiveFileName = "vosk-model-small-ja-0.22.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
            approximateZipMb = 48,
            approximateDiskExtractedMb = 95,
            licenseName = "Apache-2.0",
            architectureNoteArabic = "نموذج محمول خفيف للأجهزة الذكية"
        ),
        SubtitleTranslationLanguage.KOREAN to VoskSpeechModelSpec(
            language = SubtitleTranslationLanguage.KOREAN,
            modelId = "vosk-model-small-ko-0.22",
            version = "0.22",
            archiveFileName = "vosk-model-small-ko-0.22.zip",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip",
            approximateZipMb = 82,
            approximateDiskExtractedMb = 155,
            licenseName = "Apache-2.0",
            architectureNoteArabic = "نموذج محمول متوسط الحجم"
        )
    )

    fun specFor(language: SubtitleTranslationLanguage): VoskSpeechModelSpec? = officialModels[language]
}

data class LanguageModelResourceState(
    val language: SubtitleTranslationLanguage,
    val speechSpec: VoskSpeechModelSpec? = VoskModelCatalog.specFor(language),
    val speechStatus: OfflineModelDownloadStatus = OfflineModelDownloadStatus.NOT_DOWNLOADED,
    val speechDownloadProgress: Float = 0f,
    val speechDownloadedBytes: Long = 0L,
    val speechTotalBytes: Long = speechSpec?.expectedZipBytes ?: 0L,
    val speechInstalledVersion: String? = null,
    val speechDiskBytes: Long = 0L,
    val speechErrorMessage: String? = null,
    val isSpeechInUse: Boolean = false,
    val translationStatus: OfflineModelDownloadStatus = if (language == SubtitleTranslationLanguage.ENGLISH) {
        OfflineModelDownloadStatus.READY
    } else {
        OfflineModelDownloadStatus.NOT_DOWNLOADED
    },
    val isBuiltInTranslationLanguage: Boolean = (language == SubtitleTranslationLanguage.ENGLISH),
    val translationApproximateMb: Int = if (language == SubtitleTranslationLanguage.ENGLISH) 0 else 30,
    val translationErrorMessage: String? = null,
    val isTranslationInUse: Boolean = false
) {
    val formattedSpeechSizeLabel: String
        get() = speechSpec?.let { spec ->
            if (speechStatus == OfflineModelDownloadStatus.READY && speechDiskBytes > 0L) {
                String.format(Locale.US, "%.0f MB مثبّت", speechDiskBytes / (1024.0 * 1024.0))
            } else {
                "~${spec.approximateZipMb} MB"
            }
        } ?: "غير متوفر"

    val formattedTranslationSizeLabel: String
        get() = if (isBuiltInTranslationLanguage) {
            "مدمجة (~0 MB)"
        } else {
            "~${translationApproximateMb} MB"
        }
}

data class TranslationPairReadiness(
    val isSpeechRequired: Boolean,
    val speechLanguage: SubtitleTranslationLanguage?,
    val isSpeechReady: Boolean,
    val missingMlKitLanguages: List<SubtitleTranslationLanguage>,
    val estimatedDownloadMb: Int
) {
    val isFullyReadyOffline: Boolean
        get() = (!isSpeechRequired || isSpeechReady) && missingMlKitLanguages.isEmpty()
}
