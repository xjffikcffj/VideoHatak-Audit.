package com.videohatak.player.core.designsystem.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ThemeViewModel(private val repository: ThemePreferencesRepository) : ViewModel() {

    val themeState: StateFlow<ThemeState> = repository.themeModeFlow
        .map<ThemeMode, ThemeState> { ThemeState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeState.Loading
        )

    val themeMode: StateFlow<ThemeMode> = repository.themeModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.SYSTEM
        )

    val appTheme: StateFlow<AppThemeId> = repository.appThemeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppThemeId.OBSIDIAN_ELITE
        )

    private val _previewAppTheme = MutableStateFlow<AppThemeId?>(null)
    val previewAppTheme: StateFlow<AppThemeId?> = _previewAppTheme.asStateFlow()

    private val _previewThemeMode = MutableStateFlow<ThemeMode?>(null)
    val previewThemeMode: StateFlow<ThemeMode?> = _previewThemeMode.asStateFlow()

    val isPreviewActive: StateFlow<Boolean> = combine(
        _previewAppTheme,
        _previewThemeMode
    ) { themePreview, modePreview ->
        themePreview != null || modePreview != null
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    val effectiveAppTheme: StateFlow<AppThemeId> = combine(
        appTheme,
        _previewAppTheme
    ) { saved, preview ->
        preview ?: saved
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppThemeId.OBSIDIAN_ELITE
    )

    val effectiveThemeMode: StateFlow<ThemeMode> = combine(
        themeMode,
        _previewThemeMode
    ) { saved, preview ->
        preview ?: saved
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM
    )

    /**
     * Previews a theme live across the UI without writing it to DataStore until [applyPreview] is called.
     */
    fun startPreviewTheme(theme: AppThemeId) {
        if (theme == appTheme.value && _previewAppTheme.value == null) return
        _previewAppTheme.value = theme
        _previewThemeMode.value = if (theme.isLightTheme) ThemeMode.LIGHT else ThemeMode.DARK
    }

    fun startPreviewThemeMode(mode: ThemeMode) {
        _previewThemeMode.value = mode
    }

    fun applyPreview() {
        val targetTheme = _previewAppTheme.value
        val targetMode = _previewThemeMode.value
        _previewAppTheme.value = null
        _previewThemeMode.value = null
        viewModelScope.launch {
            if (targetTheme != null) {
                repository.setAppTheme(targetTheme)
            }
            if (targetMode != null) {
                repository.setThemeMode(targetMode)
            } else if (targetTheme != null) {
                repository.setThemeMode(if (targetTheme.isLightTheme) ThemeMode.LIGHT else ThemeMode.DARK)
            }
        }
    }

    fun cancelPreview() {
        _previewAppTheme.value = null
        _previewThemeMode.value = null
    }

    fun setThemeMode(mode: ThemeMode) {
        _previewThemeMode.value = null
        viewModelScope.launch {
            repository.setThemeMode(mode)
        }
    }

    fun setAppTheme(theme: AppThemeId) {
        _previewAppTheme.value = null
        _previewThemeMode.value = null
        viewModelScope.launch {
            repository.setAppTheme(theme)
            repository.setThemeMode(if (theme.isLightTheme) ThemeMode.LIGHT else ThemeMode.DARK)
        }
    }

    companion object {
        fun provideFactory(repository: ThemePreferencesRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ThemeViewModel(repository) as T
                }
            }
    }
}
