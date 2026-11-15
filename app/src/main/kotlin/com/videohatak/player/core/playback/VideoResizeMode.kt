package com.videohatak.player.core.playback

import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Supported player viewport resize & aspect ratio modes.
 * Numeric [storageCode] values preserve backward compatibility with Media3
 * AspectRatioFrameLayout constants (0 = FIT, 3 = FILL, 4 = ZOOM) while adding
 * ORIGINAL_SIZE (10), RATIO_16_9 (11), and RATIO_4_3 (12).
 */
enum class VideoResizeMode(
    val storageCode: Int,
    val titleArabic: String,
    val playerViewResizeMode: Int
) {
    FIT(
        storageCode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        titleArabic = "ملاءمة (Fit)",
        playerViewResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    ),
    ZOOM(
        storageCode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        titleArabic = "تكبير (Zoom)",
        playerViewResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    ),
    FILL(
        storageCode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
        titleArabic = "تمديد (Stretch)",
        playerViewResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    ),
    ORIGINAL_SIZE(
        storageCode = 10,
        titleArabic = "الحجم الأصلي (Original)",
        playerViewResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    ),
    RATIO_16_9(
        storageCode = 11,
        titleArabic = "نسبة 16:9",
        playerViewResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    ),
    RATIO_4_3(
        storageCode = 12,
        titleArabic = "نسبة 4:3",
        playerViewResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    );

    fun next(): VideoResizeMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromCode(code: Int): VideoResizeMode {
            return entries.firstOrNull { it.storageCode == code } ?: FIT
        }
    }
}
