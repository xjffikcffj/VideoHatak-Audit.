package com.videohatak.player.feature.library

enum class LibraryTab(val titleArabic: String) {
    VIDEOS("الفيديوهات"),
    FOLDERS("المجلدات"),
    FAVORITES("المفضلة"),
    PLAYLISTS("قوائم التشغيل"),
    TRASH("سلة المحذوفات"),
    SETTINGS("الإعدادات"),
    VAULT("الخزنة الخاصة");

    companion object {
        val bottomBarTabs: List<LibraryTab> = listOf(VIDEOS, FOLDERS, PLAYLISTS, SETTINGS)
    }
}
