package com.videohatak.player.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun VideoHatakTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    appTheme: AppThemeId = AppThemeId.OBSIDIAN_ELITE,
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = darkTheme ?: when (themeMode) {
        ThemeMode.SYSTEM -> if (appTheme.isLightTheme) false else isSystemInDarkTheme() || !appTheme.isLightTheme
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val palette = paletteForTheme(
        themeId = appTheme,
        forceLight = !isDark
    )
    val colorScheme = colorSchemeFromPalette(palette)

    CompositionLocalProvider(LocalVideoHatakPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
