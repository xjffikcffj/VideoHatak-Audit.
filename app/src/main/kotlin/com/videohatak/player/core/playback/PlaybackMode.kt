package com.videohatak.player.core.playback

import com.videohatak.player.core.playlist.model.PlaylistRepeatMode

enum class PlaybackMode(
    val titleArabic: String,
    val descriptionArabic: String,
    val repeatModeEquivalent: PlaylistRepeatMode,
    val shuffleEnabled: Boolean,
    val stopAfterCurrent: Boolean
) {
    STOP_AFTER_CURRENT(
        titleArabic = "إيقاف بعد الفيديو الحالي",
        descriptionArabic = "يتوقف التشغيل تلقائيًا عند انتهاء المقطع الحالي",
        repeatModeEquivalent = PlaylistRepeatMode.OFF,
        shuffleEnabled = false,
        stopAfterCurrent = true
    ),
    SHUFFLE_ALL(
        titleArabic = "خلط جميع الفيديوهات",
        descriptionArabic = "تشغيل المقاطع بترتيب عشوائي متواصل",
        repeatModeEquivalent = PlaylistRepeatMode.ALL,
        shuffleEnabled = true,
        stopAfterCurrent = false
    ),
    PLAY_ALL_IN_ORDER(
        titleArabic = "تشغيل الكل بالترتيب",
        descriptionArabic = "الانتقال التلقائي للفيديو التالي بالترتيب",
        repeatModeEquivalent = PlaylistRepeatMode.OFF,
        shuffleEnabled = false,
        stopAfterCurrent = false
    ),
    REPEAT_ONE(
        titleArabic = "تكرار الفيديو الحالي",
        descriptionArabic = "إعادة تشغيل المقطع الحالي تلقائيًا بعد انتهائه",
        repeatModeEquivalent = PlaylistRepeatMode.ONE,
        shuffleEnabled = false,
        stopAfterCurrent = false
    ),
    REPEAT_PLAYLIST(
        titleArabic = "تكرار القائمة كاملة",
        descriptionArabic = "تكرار تشغيل جميع الفيديوهات أو قائمة التشغيل بشكل دائري",
        repeatModeEquivalent = PlaylistRepeatMode.ALL,
        shuffleEnabled = false,
        stopAfterCurrent = false
    )
}
