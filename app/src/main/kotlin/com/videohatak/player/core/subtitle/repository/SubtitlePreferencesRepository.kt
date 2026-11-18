package com.videohatak.player.core.subtitle.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.subtitleDataStore by preferencesDataStore(name = "videohatak_subtitles")

interface SubtitlePreferencesStore {
    val primaryStyle: Flow<SubtitleStyleConfig>
    val secondaryStyle: Flow<SubtitleStyleConfig>
    val showSourceTextWithTranslation: Flow<Boolean>
        get() = flowOf(false)

    suspend fun updatePrimaryFontSize(sizeSp: Float)
    suspend fun updateSecondaryFontSize(sizeSp: Float)
    suspend fun setShowSourceTextWithTranslation(show: Boolean) {}
}

class SubtitlePreferencesRepository(private val context: Context) : SubtitlePreferencesStore {
    private val primarySizeKey = floatPreferencesKey("sub_primary_size")
    private val secondarySizeKey = floatPreferencesKey("sub_secondary_size")
    private val showSourceWithTranslationKey = booleanPreferencesKey("sub_show_source_with_translation")

    override val primaryStyle: Flow<SubtitleStyleConfig> = context.subtitleDataStore.data.map { prefs ->
        SubtitleStyleConfig(
            fontSizeSp = prefs[primarySizeKey] ?: 18f,
            bottomPaddingDp = 48f
        )
    }

    override val secondaryStyle: Flow<SubtitleStyleConfig> = context.subtitleDataStore.data.map { prefs ->
        SubtitleStyleConfig(
            fontSizeSp = prefs[secondarySizeKey] ?: 15f,
            bottomPaddingDp = 88f
        )
    }

    override val showSourceTextWithTranslation: Flow<Boolean> = context.subtitleDataStore.data.map { prefs ->
        prefs[showSourceWithTranslationKey] ?: false
    }

    override suspend fun updatePrimaryFontSize(sizeSp: Float) {
        context.subtitleDataStore.edit { prefs ->
            prefs[primarySizeKey] = sizeSp
        }
    }

    override suspend fun updateSecondaryFontSize(sizeSp: Float) {
        context.subtitleDataStore.edit { prefs ->
            prefs[secondarySizeKey] = sizeSp
        }
    }

    override suspend fun setShowSourceTextWithTranslation(show: Boolean) {
        context.subtitleDataStore.edit { prefs ->
            prefs[showSourceWithTranslationKey] = show
        }
    }
}
