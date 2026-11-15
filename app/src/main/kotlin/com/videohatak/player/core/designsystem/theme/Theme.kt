package com.videohatak.player.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

fun resolveIsDarkTheme(
    themeMode: ThemeMode,
    systemIsDark: Boolean
): Boolean = when (themeMode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemIsDark
}

@Composable
fun VideoHatakTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    appTheme: AppThemeId = AppThemeId.OBSIDIAN_ELITE,
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemIsDark = isSystemInDarkTheme()
    val isDark = darkTheme ?: resolveIsDarkTheme(themeMode, systemIsDark)

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
