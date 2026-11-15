package com.videohatak.player.core.designsystem.theme

enum class AppThemeId(
    val displayName: String,
    val arabicTagline: String,
    val isLightTheme: Boolean = false
) {
    OBSIDIAN_ELITE(
        displayName = "Obsidian Elite",
        arabicTagline = "الفخامة المطلقة",
        isLightTheme = false
    ),
    SAPPHIRE_PREMIUM(
        displayName = "Sapphire Premium",
        arabicTagline = "الأناقة العصرية",
        isLightTheme = false
    ),
    EMERALD_LUXURY(
        displayName = "Emerald Luxury",
        arabicTagline = "الفخامة الهادئة",
        isLightTheme = false
    ),
    GALAXY_PRO(
        displayName = "Galaxy Pro",
        arabicTagline = "عالم من الإبداع",
        isLightTheme = false
    ),
    ROYAL_IVORY(
        displayName = "Royal Ivory",
        arabicTagline = "أناقة خالدة",
        isLightTheme = true
    ),
    TITANIUM_NOIR(
        displayName = "Titanium Noir",
        arabicTagline = "قوة التميز",
        isLightTheme = false
    ),
    RUBY_CINEMA(
        displayName = "Ruby Cinema",
        arabicTagline = "شغف المشاهدة",
        isLightTheme = false
    ),
    NEON_DREAM(
        displayName = "Neon Dream",
        arabicTagline = "مستقبل الترفيه",
        isLightTheme = false
    )
}
