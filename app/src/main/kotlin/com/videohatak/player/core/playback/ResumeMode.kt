package com.videohatak.player.core.playback

enum class ResumeMode(
    val titleArabic: String,
    val descriptionArabic: String
) {
    RESUME(
        titleArabic = "استئناف من آخر موضع",
        descriptionArabic = "يكمل الفيديو تلقائيًا من الثانية التي توقفت عندها"
    ),
    START_FROM_BEGINNING(
        titleArabic = "البدء من البداية دائمًا",
        descriptionArabic = "يبدأ أي فيديو من الثانية 00:00 في كل مرة"
    ),
    ASK_EVERY_TIME(
        titleArabic = "السؤال عند كل فيديو",
        descriptionArabic = "يسألك إن كنت تريد الاستئناف أو البدء من البداية إذا سبق تشغيله"
    )
}

data class ResumePromptState(
    val videoTitle: String,
    val targetUri: String,
    val savedPositionMs: Long,
    val durationMs: Long,
    val targetGeneration: Long
)
