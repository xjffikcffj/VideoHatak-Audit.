package com.videohatak.player.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Legacy exports kept for any external references
val SeedEmerald = Color(0xFF10B981)
val SeedCyan = Color(0xFF06B6D4)

val PrimaryDark = Color(0xFFE5B84B)
val OnPrimaryDark = Color(0xFF1C1400)
val PrimaryContainerDark = Color(0xFF3A2B08)
val OnPrimaryContainerDark = Color(0xFFFFE08A)
val SecondaryDark = Color(0xFFD4AF37)
val OnSecondaryDark = Color(0xFF1A1400)
val SecondaryContainerDark = Color(0xFF2C220A)
val OnSecondaryContainerDark = Color(0xFFFDE68A)
val TertiaryDark = Color(0xFFF59E0B)
val OnTertiaryDark = Color(0xFF1E1300)
val TertiaryContainerDark = Color(0xFF422804)
val OnTertiaryContainerDark = Color(0xFFFEF3C7)
val BackgroundDark = Color(0xFF070709)
val OnBackgroundDark = Color(0xFFFAF5EB)
val SurfaceDark = Color(0xFF111115)
val OnSurfaceDark = Color(0xFFFAF5EB)
val SurfaceVariantDark = Color(0xFF1B1B22)
val OnSurfaceVariantDark = Color(0xFFBFB8A8)
val ErrorDark = Color(0xFFF87171)
val OnErrorDark = Color(0xFF7F1D1D)
val ErrorContainerDark = Color(0xFF991B1B)
val OnErrorContainerDark = Color(0xFFFEE2E2)
val OutlineDark = Color(0xFF5A4820)
val OutlineVariantDark = Color(0xFF2D2719)

val PrimaryLight = Color(0xFFB4820A)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFF7E4B0)
val OnPrimaryContainerLight = Color(0xFF312100)
val SecondaryLight = Color(0xFF8C6308)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFEDE0C4)
val OnSecondaryContainerLight = Color(0xFF2C1E00)
val TertiaryLight = Color(0xFFA16207)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFFEF3C7)
val OnTertiaryContainerLight = Color(0xFF451A03)
val BackgroundLight = Color(0xFFFAF5EA)
val OnBackgroundLight = Color(0xFF1C160C)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF1C160C)
val SurfaceVariantLight = Color(0xFFF0E6D2)
val OnSurfaceVariantLight = Color(0xFF5D523E)
val ErrorLight = Color(0xFFDC2626)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFEE2E2)
val OnErrorContainerLight = Color(0xFF7F1D1D)
val OutlineLight = Color(0xFFCBB88B)
val OutlineVariantLight = Color(0xFFE5D8BA)

@Immutable
data class VideoHatakPalette(
    val themeId: AppThemeId,
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val accent: Color,
    val accentGlow: Color,
    val secondaryAccent: Color,
    val border: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val topBarGradientStart: Color,
    val topBarGradientEnd: Color,
    val seekBarPlayedStart: Color,
    val seekBarPlayedEnd: Color,
    val seekBarGlow: Color,
    val seekBarUnplayed: Color,
    val seekBarThumb: Color
)

val LocalVideoHatakPalette = staticCompositionLocalOf {
    paletteForTheme(AppThemeId.OBSIDIAN_ELITE, forceLight = false)
}

fun paletteForTheme(themeId: AppThemeId, forceLight: Boolean = false): VideoHatakPalette {
    val effectiveId = if (forceLight && !themeId.isLightTheme) {
        // Keep accent hue while providing a crisp light surface for daytime readability
        themeId
    } else {
        themeId
    }

    if (forceLight) {
        val (accent, glow, secondary, border) = when (effectiveId) {
            AppThemeId.SAPPHIRE_PREMIUM -> QuadColor(Color(0xFF1D6FD8), Color(0xFF60A5FA), Color(0xFF0284C7), Color(0xFFBFD7F5))
            AppThemeId.EMERALD_LUXURY -> QuadColor(Color(0xFF059669), Color(0xFF34D399), Color(0xFF0D9488), Color(0xFFB5E3D0))
            AppThemeId.GALAXY_PRO -> QuadColor(Color(0xFF7E22CE), Color(0xFFC084FC), Color(0xFFA855F7), Color(0xFFDBC7F5))
            AppThemeId.TITANIUM_NOIR -> QuadColor(Color(0xFF334155), Color(0xFF64748B), Color(0xFF475569), Color(0xFFCBD5E1))
            AppThemeId.RUBY_CINEMA -> QuadColor(Color(0xFFBE123C), Color(0xFFFB7185), Color(0xFFE11D48), Color(0xFFF3C1CC))
            AppThemeId.NEON_DREAM -> QuadColor(Color(0xFF0891B2), Color(0xFF22D3EE), Color(0xFFC026D3), Color(0xFFBAE6FD))
            else -> QuadColor(Color(0xFFB4820A), Color(0xFFE5B84B), Color(0xFF8C6308), Color(0xFFD6C08D))
        }
        val isIvory = effectiveId == AppThemeId.ROYAL_IVORY
        val bg = if (isIvory) Color(0xFFFAF5EA) else Color(0xFFF6F7FB)
        val surf = Color(0xFFFFFFFF)
        val surfElev = if (isIvory) Color(0xFFF1E7D2) else Color(0xFFEBEEF5)
        return VideoHatakPalette(
            themeId = effectiveId,
            isDark = false,
            background = bg,
            surface = surf,
            surfaceElevated = surfElev,
            accent = accent,
            accentGlow = glow,
            secondaryAccent = secondary,
            border = border,
            onSurface = Color(0xFF1A1610),
            onSurfaceMuted = Color(0xFF5D5342),
            topBarGradientStart = surf,
            topBarGradientEnd = surfElev,
            seekBarPlayedStart = accent,
            seekBarPlayedEnd = glow,
            seekBarGlow = glow.copy(alpha = 0.45f),
            seekBarUnplayed = accent.copy(alpha = 0.25f),
            seekBarThumb = accent
        )
    }

    return when (effectiveId) {
        AppThemeId.OBSIDIAN_ELITE -> VideoHatakPalette(
            themeId = effectiveId,
            isDark = true,
            background = Color(0xFF070709),
            surface = Color(0xFF121216),
            surfaceElevated = Color(0xFF1C1A16),
            accent = Color(0xFFE5B84B),
            accentGlow = Color(0xFFFFD875),
            secondaryAccent = Color(0xFFC59326),
            border = Color(0xFF4A3B18),
            onSurface = Color(0xFFFAF5EB),
            onSurfaceMuted = Color(0xFFC3B8A2),
            topBarGradientStart = Color(0xFF0D0C0A),
            topBarGradientEnd = Color(0xFF18140C),
            seekBarPlayedStart = Color(0xFFC59326),
            seekBarPlayedEnd = Color(0xFFFFDF85),
            seekBarGlow = Color(0xFFFFD25A).copy(alpha = 0.55f),
            seekBarUnplayed = Color(0xFFFFE7A3).copy(alpha = 0.24f),
            seekBarThumb = Color(0xFFFFE599)
        )

        AppThemeId.SAPPHIRE_PREMIUM -> VideoHatakPalette(
            themeId = effectiveId,
            isDark = true,
            background = Color(0xFF040A16),
            surface = Color(0xFF0B1628),
            surfaceElevated = Color(0xFF12233F),
            accent = Color(0xFF4EA8FF),
            accentGlow = Color(0xFF93CCFF),
            secondaryAccent = Color(0xFFBBD7F5),
            border = Color(0xFF1E3C68),
            onSurface = Color(0xFFF0F7FF),
            onSurfaceMuted = Color(0xFF9BB4D4),
            topBarGradientStart = Color(0xFF060E1F),
            topBarGradientEnd = Color(0xFF0F203B),
            seekBarPlayedStart = Color(0xFF2563EB),
            seekBarPlayedEnd = Color(0xFF7CC4FF),
            seekBarGlow = Color(0xFF60B5FF).copy(alpha = 0.55f),
            seekBarUnplayed = Color(0xFFBFDBFE).copy(alpha = 0.24f),
            seekBarThumb = Color(0xFFDCEEFF)
        )

        AppThemeId.EMERALD_LUXURY -> VideoHatakPalette(
            themeId = effectiveId,
            isDark = true,
            background = Color(0xFF04100A),
            surface = Color(0xFF0A1D13),
            surfaceElevated = Color(0xFF122E20),
            accent = Color(0xFF2BE38A),
            accentGlow = Color(0xFF86F8C0),
            secondaryAccent = Color(0xFF14B8A6),
            border = Color(0xFF194B32),
            onSurface = Color(0xFFECFDF4),
            onSurfaceMuted = Color(0xFF98C9B0),
            topBarGradientStart = Color(0xFF06160D),
            topBarGradientEnd = Color(0xFF0D281A),
            seekBarPlayedStart = Color(0xFF059669),
            seekBarPlayedEnd = Color(0xFF5BF2AB),
            seekBarGlow = Color(0xFF34EB95).copy(alpha = 0.55f),
            seekBarUnplayed = Color(0xFFA7F3D0).copy(alpha = 0.24f),
            seekBarThumb = Color(0xFFD1FAE5)
        )

        AppThemeId.GALAXY_PRO -> VideoHatakPalette(
            themeId = effectiveId,
            isDark = true,
            background = Color(0xFF080413),
            surface = Color(0xFF130B26),
            surfaceElevated = Color(0xFF1E133A),
            accent = Color(0xFFB56BFF),
            accentGlow = Color(0xFFD8A8FF),
            secondaryAccent = Color(0xFFE879F9),
            border = Color(0xFF3D226B),
            onSurface = Color(0xFFF6F0FF),
            onSurfaceMuted = Color(0xFFB8A2DB),
            topBarGradientStart = Color(0xFF0B061A),
            topBarGradientEnd = Color(0xFF1B0F36),
            seekBarPlayedStart = Color(0xFF9333EA),
            seekBarPlayedEnd = Color(0xFFE09EFF),
            seekBarGlow = Color(0xFFC875FF).copy(alpha = 0.55f),
            seekBarUnplayed = Color(0xFFE9D5FF).copy(alpha = 0.24f),
            seekBarThumb = Color(0xFFF3E8FF)
        )

        AppThemeId.TITANIUM_NOIR -> VideoHatakPalette(
            themeId = effectiveId,
            isDark = true,
            background = Color(0xFF060608),
            surface = Color(0xFF121317),
            surfaceElevated = Color(0xFF1E2026),
            accent = Color(0xFFDCE3EC),
            accentGlow = Color(0xFFFFFFFF),
            secondaryAccent = Color(0xFF94A3B8),
            border = Color(0xFF3B404A),
            onSurface = Color(0xFFF8FAFC),
            onSurfaceMuted = Color(0xFFA3ABB8),
            topBarGradientStart = Color(0xFF0A0B0E),
            topBarGradientEnd = Color(0xFF181A20),
            seekBarPlayedStart = Color(0xFF94A3B8),
            seekBarPlayedEnd = Color(0xFFF8FAFC),
            seekBarGlow = Color(0xFFE2E8F0).copy(alpha = 0.5f),
            seekBarUnplayed = Color(0xFFCBD5E1).copy(alpha = 0.24f),
            seekBarThumb = Color(0xFFFFFFFF)
        )

        AppThemeId.RUBY_CINEMA -> VideoHatakPalette(
            themeId = effectiveId,
            isDark = true,
            background = Color(0xFF100407),
            surface = Color(0xFF1D090E),
            surfaceElevated = Color(0xFF2D0F17),
            accent = Color(0xFFFF3E54),
            accentGlow = Color(0xFFFF8A98),
            secondaryAccent = Color(0xFFFB7185),
            border = Color(0xFF5E1B28),
            onSurface = Color(0xFFFFF1F3),
            onSurfaceMuted = Color(0xFFD49EA8),
            topBarGradientStart = Color(0xFF150509),
            topBarGradientEnd = Color(0xFF270B12),
            seekBarPlayedStart = Color(0xFFDC2626),
            seekBarPlayedEnd = Color(0xFFFF6B7D),
            seekBarGlow = Color(0xFFFF4D62).copy(alpha = 0.55f),
            seekBarUnplayed = Color(0xFFFECDD3).copy(alpha = 0.24f),
            seekBarThumb = Color(0xFFFFE4E6)
        )

        AppThemeId.NEON_DREAM -> VideoHatakPalette(
            themeId = effectiveId,
            isDark = true,
            background = Color(0xFF060613),
            surface = Color(0xFF0E0E24),
            surfaceElevated = Color(0xFF171738),
            accent = Color(0xFF22E5FF),
            accentGlow = Color(0xFF8BF3FF),
            secondaryAccent = Color(0xFFE040FB),
            border = Color(0xFF2B2C66),
            onSurface = Color(0xFFECFEFF),
            onSurfaceMuted = Color(0xFF9FA7D6),
            topBarGradientStart = Color(0xFF09091B),
            topBarGradientEnd = Color(0xFF151236),
            seekBarPlayedStart = Color(0xFFE040FB),
            seekBarPlayedEnd = Color(0xFF22E5FF),
            seekBarGlow = Color(0xFF22E5FF).copy(alpha = 0.55f),
            seekBarUnplayed = Color(0xFFA5F3FC).copy(alpha = 0.24f),
            seekBarThumb = Color(0xFFE0F7FA)
        )

        AppThemeId.ROYAL_IVORY -> VideoHatakPalette(
            themeId = effectiveId,
            isDark = true,
            background = Color(0xFF14110B),
            surface = Color(0xFF1E1911),
            surfaceElevated = Color(0xFF2B2418),
            accent = Color(0xFFF0C96B),
            accentGlow = Color(0xFFFCE29E),
            secondaryAccent = Color(0xFFB4820A),
            border = Color(0xFF574728),
            onSurface = Color(0xFFFAF5EA),
            onSurfaceMuted = Color(0xFFD1C3A5),
            topBarGradientStart = Color(0xFF19150D),
            topBarGradientEnd = Color(0xFF292115),
            seekBarPlayedStart = Color(0xFFA67C2E),
            seekBarPlayedEnd = Color(0xFFF5D889),
            seekBarGlow = Color(0xFFF0C96B).copy(alpha = 0.55f),
            seekBarUnplayed = Color(0xFFFAF5EA).copy(alpha = 0.24f),
            seekBarThumb = Color(0xFFFCE7AF)
        )
    }
}

fun colorSchemeFromPalette(palette: VideoHatakPalette): ColorScheme {
    return if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = if (palette.themeId == AppThemeId.TITANIUM_NOIR) Color(0xFF0A0B0E) else Color(0xFF09090B),
            primaryContainer = palette.surfaceElevated,
            onPrimaryContainer = palette.accentGlow,
            secondary = palette.secondaryAccent,
            onSecondary = Color(0xFF09090B),
            secondaryContainer = palette.surfaceElevated,
            onSecondaryContainer = palette.onSurface,
            tertiary = palette.accentGlow,
            onTertiary = Color(0xFF09090B),
            background = palette.background,
            onBackground = palette.onSurface,
            surface = palette.surface,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.onSurfaceMuted,
            error = ErrorDark,
            onError = OnErrorDark,
            errorContainer = ErrorContainerDark,
            onErrorContainer = OnErrorContainerDark,
            outline = palette.border,
            outlineVariant = palette.border.copy(alpha = 0.6f)
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.surfaceElevated,
            onPrimaryContainer = palette.onSurface,
            secondary = palette.secondaryAccent,
            onSecondary = Color.White,
            secondaryContainer = palette.surfaceElevated,
            onSecondaryContainer = palette.onSurface,
            tertiary = palette.accentGlow,
            onTertiary = Color(0xFF1A1610),
            background = palette.background,
            onBackground = palette.onSurface,
            surface = palette.surface,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.onSurfaceMuted,
            error = ErrorLight,
            onError = OnErrorLight,
            errorContainer = ErrorContainerLight,
            onErrorContainer = OnErrorContainerLight,
            outline = palette.border,
            outlineVariant = palette.border.copy(alpha = 0.6f)
        )
    }
}

private data class QuadColor(
    val accent: Color,
    val glow: Color,
    val secondary: Color,
    val border: Color
)
