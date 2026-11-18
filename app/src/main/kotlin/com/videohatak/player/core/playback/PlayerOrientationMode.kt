package com.videohatak.player.core.playback

import android.content.pm.ActivityInfo

/**
 * 3-state sequential player screen orientation modes:
 * 1. PORTRAIT -> forced sensor-portrait orientation
 * 2. LANDSCAPE -> forced sensor-landscape orientation
 * 3. AUTO -> automatic / sensor-mixed orientation following device rotation
 */
enum class PlayerOrientationMode(
    val storageCode: String,
    val arabicLabel: String,
    val activityOrientation: Int
) {
    PORTRAIT(
        storageCode = "PORTRAIT",
        arabicLabel = "عمودي",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    ),
    LANDSCAPE(
        storageCode = "LANDSCAPE",
        arabicLabel = "أفقي",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    ),
    AUTO(
        storageCode = "AUTO",
        arabicLabel = "تلقائي",
        activityOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    );

    fun next(): PlayerOrientationMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        val DEFAULT: PlayerOrientationMode = AUTO

        fun fromStorageCode(code: String?): PlayerOrientationMode {
            if (code.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.storageCode.equals(code.trim(), ignoreCase = true) }
                ?: DEFAULT
        }
    }
}
