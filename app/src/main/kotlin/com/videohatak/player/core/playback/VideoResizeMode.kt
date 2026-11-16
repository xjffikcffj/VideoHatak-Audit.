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

        /**
         * Computes pixel dimensions constrained to both viewport width and height so neither portrait
         * nor landscape aspect ratios ever overflow and push controls off-screen.
         * Returns null when the PlayerView should occupy the full viewport (`fillMaxSize`).
         */
        fun computeFittedVideoSize(
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            resizeMode: VideoResizeMode,
            videoWidth: Int = 0,
            videoHeight: Int = 0
        ): Pair<Int, Int>? {
            if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return null
            val targetRatio = when (resizeMode) {
                RATIO_16_9 -> 16f / 9f
                RATIO_4_3 -> 4f / 3f
                ORIGINAL_SIZE -> {
                    if (videoWidth <= 0 || videoHeight <= 0) return null
                    val scale = minOf(
                        viewportWidthPx.toFloat() / videoWidth.toFloat(),
                        viewportHeightPx.toFloat() / videoHeight.toFloat(),
                        1f
                    )
                    return ((videoWidth * scale).toInt().coerceAtLeast(1)) to
                        ((videoHeight * scale).toInt().coerceAtLeast(1))
                }
                else -> return null
            }

            val viewportRatio = viewportWidthPx.toFloat() / viewportHeightPx.toFloat()
            return if (viewportRatio > targetRatio) {
                val width = (viewportHeightPx * targetRatio).toInt().coerceIn(1, viewportWidthPx)
                width to viewportHeightPx
            } else {
                val height = (viewportWidthPx / targetRatio).toInt().coerceIn(1, viewportHeightPx)
                viewportWidthPx to height
            }
        }
    }
}
