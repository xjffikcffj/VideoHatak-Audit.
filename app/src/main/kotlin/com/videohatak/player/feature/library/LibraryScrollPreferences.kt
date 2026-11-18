package com.videohatak.player.feature.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.libraryScrollDataStore: DataStore<Preferences> by preferencesDataStore(name = "videohatak_library_scroll")

data class LibraryScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
) {
    val isAtOrigin: Boolean
        get() = firstVisibleItemIndex <= 0 && firstVisibleItemScrollOffset <= 0

    fun clampForItemCount(itemCount: Int): LibraryScrollPosition {
        if (itemCount <= 0) return LibraryScrollPosition(0, 0)
        val clampedIndex = firstVisibleItemIndex.coerceIn(0, itemCount - 1)
        val clampedOffset = firstVisibleItemScrollOffset.coerceAtLeast(0)
        return LibraryScrollPosition(clampedIndex, clampedOffset)
    }
}

interface LibraryScrollStore {
    val videosScrollPosition: Flow<LibraryScrollPosition>
    suspend fun saveVideosScrollPosition(position: LibraryScrollPosition)
}

class DataStoreLibraryScrollStore(
    private val dataStore: DataStore<Preferences>
) : LibraryScrollStore {

    constructor(context: Context) : this(context.libraryScrollDataStore)

    private val videosIndexKey = intPreferencesKey("videos_first_visible_index")
    private val videosOffsetKey = intPreferencesKey("videos_first_visible_offset")

    override val videosScrollPosition: Flow<LibraryScrollPosition> = dataStore.data.map { prefs ->
        val index = (prefs[videosIndexKey] ?: 0).coerceAtLeast(0)
        val offset = (prefs[videosOffsetKey] ?: 0).coerceAtLeast(0)
        LibraryScrollPosition(
            firstVisibleItemIndex = index,
            firstVisibleItemScrollOffset = offset
        )
    }

    override suspend fun saveVideosScrollPosition(position: LibraryScrollPosition) {
        val safeIndex = position.firstVisibleItemIndex.coerceAtLeast(0)
        val safeOffset = position.firstVisibleItemScrollOffset.coerceAtLeast(0)
        dataStore.edit { prefs ->
            if (prefs[videosIndexKey] != safeIndex || prefs[videosOffsetKey] != safeOffset) {
                prefs[videosIndexKey] = safeIndex
                prefs[videosOffsetKey] = safeOffset
            }
        }
    }
}
