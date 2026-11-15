package com.videohatak.player.core.designsystem.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "videohatak_settings")

sealed interface ThemeState {
    data object Loading : ThemeState
    data class Success(val themeMode: ThemeMode) : ThemeState
}

interface ThemePreferencesRepository {
    val themeModeFlow: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    val appThemeFlow: Flow<AppThemeId>
        get() = flowOf(AppThemeId.OBSIDIAN_ELITE)

    suspend fun setAppTheme(theme: AppThemeId) {}
}

class ThemePreferencesRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : ThemePreferencesRepository {

    constructor(context: Context) : this(context.themeDataStore)

    private val themeKey = stringPreferencesKey("app_theme_mode")
    private val appThemeKey = stringPreferencesKey("selected_app_theme_id")

    override val themeModeFlow: Flow<ThemeMode> = dataStore.data.map { preferences ->
        when (preferences[themeKey]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[themeKey] = mode.name
        }
    }

    override val appThemeFlow: Flow<AppThemeId> = dataStore.data.map { preferences ->
        val raw = preferences[appThemeKey]
        AppThemeId.entries.firstOrNull { it.name == raw } ?: AppThemeId.OBSIDIAN_ELITE
    }

    override suspend fun setAppTheme(theme: AppThemeId) {
        dataStore.edit { preferences ->
            preferences[appThemeKey] = theme.name
        }
    }
}

// Backward-compatible alias for direct instantiation
typealias ThemePreferencesRepositoryAlias = ThemePreferencesRepositoryImpl
