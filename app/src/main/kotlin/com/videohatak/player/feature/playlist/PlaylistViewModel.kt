package com.videohatak.player.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.playlist.model.PlaylistPlaybackState
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlaylistViewModel(
    private val repository: PlaylistRepository
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPlaylistWithItems = MutableStateFlow<Pair<Playlist, List<PlaylistItem>>?>(null)
    val selectedPlaylistWithItems: StateFlow<Pair<Playlist, List<PlaylistItem>>?> = _selectedPlaylistWithItems.asStateFlow()

    val currentPlaylistItems: StateFlow<List<PlaylistItem>> = _selectedPlaylistWithItems
        .map { it?.second ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _playbackState = MutableStateFlow(PlaylistPlaybackState())
    val playbackState: StateFlow<PlaylistPlaybackState> = _playbackState.asStateFlow()

    private val _favoriteVideoIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteVideoIds: StateFlow<Set<Long>> = _favoriteVideoIds.asStateFlow()

    private val _favoriteVideoUris = MutableStateFlow<Set<String>>(emptySet())
    val favoriteVideoUris: StateFlow<Set<String>> = _favoriteVideoUris.asStateFlow()

    private val _favoritesPlaylistWithItems = MutableStateFlow<Pair<Playlist, List<PlaylistItem>>?>(null)
    val favoritesPlaylistWithItems: StateFlow<Pair<Playlist, List<PlaylistItem>>?> =
        _favoritesPlaylistWithItems.asStateFlow()

    val favoriteItems: StateFlow<List<PlaylistItem>> = _favoritesPlaylistWithItems
        .map { it?.second ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoritesPlaylistId: StateFlow<Long?> = _favoritesPlaylistWithItems
        .map { it?.first?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.id?.let { pId ->
                loadPlaylistWithItems(pId)
            }
        }
    }

    private suspend fun refreshFavoritesState() {
        val (ids, uris) = repository.getFavoriteVideoKeys()
        _favoriteVideoIds.value = ids
        _favoriteVideoUris.value = uris
        _favoritesPlaylistWithItems.value = repository.getFavoritesPlaylistWithItems()
    }

    fun isFavorite(video: VideoItem): Boolean {
        return video.id in _favoriteVideoIds.value || video.contentUri.toString() in _favoriteVideoUris.value
    }

    fun toggleFavorite(video: VideoItem, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val isNowFavorite = repository.toggleFavorite(video)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.let { selected ->
                if (PlaylistRepository.isProtectedFavoritesTitle(selected.title)) {
                    loadPlaylistWithItems(selected.id)
                }
            }
            onResult?.invoke(isNowFavorite)
        }
    }

    fun selectPlaylist(playlist: Playlist?) {
        if (playlist == null) {
            _selectedPlaylistWithItems.value = null
        } else {
            loadPlaylistWithItems(playlist.id)
        }
    }

    fun loadPlaylistWithItems(playlistId: Long) {
        viewModelScope.launch {
            val pair = repository.getPlaylistWithItems(playlistId)
            _selectedPlaylistWithItems.value = pair
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val newId = repository.createPlaylist(name)
            loadPlaylistWithItems(newId)
        }
    }

    fun createPlaylistAndAddVideo(name: String, video: VideoItem, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            val newId = repository.createPlaylist(name)
            if (newId > 0L) {
                repository.addVideoToPlaylists(video, listOf(newId))
                refreshFavoritesState()
                loadPlaylistWithItems(newId)
            }
            onComplete?.invoke()
        }
    }

    fun renamePlaylist(id: Long, newName: String) {
        val current = playlists.value.firstOrNull { it.id == id }
        if (current != null && PlaylistRepository.isProtectedFavoritesTitle(current.title)) {
            return
        }
        viewModelScope.launch {
            val updated = repository.renamePlaylist(id, newName)
            if (updated && _selectedPlaylistWithItems.value?.first?.id == id) {
                loadPlaylistWithItems(id)
            }
        }
    }

    fun deletePlaylist(id: Long) {
        val current = playlists.value.firstOrNull { it.id == id }
        if (current != null && PlaylistRepository.isProtectedFavoritesTitle(current.title)) {
            return
        }
        viewModelScope.launch {
            val deleted = repository.deletePlaylist(id)
            refreshFavoritesState()
            if (deleted && _selectedPlaylistWithItems.value?.first?.id == id) {
                _selectedPlaylistWithItems.value = null
            }
        }
    }

    fun addVideoToPlaylists(video: VideoItem, playlistIds: List<Long>) {
        viewModelScope.launch {
            repository.addVideoToPlaylists(video, playlistIds)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.id?.let {
                if (it in playlistIds) loadPlaylistWithItems(it)
            }
        }
    }

    fun addVideosToPlaylists(
        videos: List<VideoItem>,
        playlistIds: List<Long>,
        onComplete: (Int) -> Unit = {}
    ) {
        if (videos.isEmpty() || playlistIds.isEmpty()) {
            onComplete(0)
            return
        }
        viewModelScope.launch {
            val added = repository.addVideosToPlaylists(videos, playlistIds)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.id?.let {
                if (it in playlistIds) loadPlaylistWithItems(it)
            }
            onComplete(added)
        }
    }

    fun addVideosToFavorites(
        videos: List<VideoItem>,
        onComplete: (Int) -> Unit = {}
    ) {
        if (videos.isEmpty()) {
            onComplete(0)
            return
        }
        viewModelScope.launch {
            val added = repository.addVideosToFavorites(videos)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.let { selected ->
                if (PlaylistRepository.isProtectedFavoritesTitle(selected.title)) {
                    loadPlaylistWithItems(selected.id)
                }
            }
            onComplete(added)
        }
    }

    fun removeVideosFromFavorites(
        videos: List<VideoItem>,
        onComplete: (Int) -> Unit = {}
    ) {
        if (videos.isEmpty()) {
            onComplete(0)
            return
        }
        viewModelScope.launch {
            val removed = repository.removeVideosFromFavorites(videos)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.let { selected ->
                if (PlaylistRepository.isProtectedFavoritesTitle(selected.title)) {
                    loadPlaylistWithItems(selected.id)
                }
            }
            onComplete(removed)
        }
    }

    fun removeItemFromPlaylist(itemId: Long, playlistId: Long) {
        viewModelScope.launch {
            repository.removeVideoFromPlaylist(itemId, playlistId)
            refreshFavoritesState()
            loadPlaylistWithItems(playlistId)
        }
    }

    fun removeItemsFromPlaylist(
        itemIds: Set<Long>,
        playlistId: Long,
        onComplete: (Int) -> Unit = {}
    ) {
        if (itemIds.isEmpty()) {
            onComplete(0)
            return
        }
        viewModelScope.launch {
            val removed = repository.removeVideosFromPlaylist(itemIds.toList(), playlistId)
            refreshFavoritesState()
            if (_selectedPlaylistWithItems.value?.first?.id == playlistId) {
                loadPlaylistWithItems(playlistId)
            }
            onComplete(removed)
        }
    }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        val current = _selectedPlaylistWithItems.value ?: return
        viewModelScope.launch {
            repository.moveItem(current.first.id, fromIndex, toIndex)
            loadPlaylistWithItems(current.first.id)
        }
    }

    fun relinkMissingItems(playlistId: Long) {
        viewModelScope.launch {
            repository.relinkMissingItems(playlistId)
            loadPlaylistWithItems(playlistId)
        }
    }

    fun toggleShuffle() {
        _playbackState.update { it.copy(isShuffle = !it.isShuffle) }
    }

    fun cycleRepeatMode() {
        _playbackState.update {
            val next = when (it.repeatMode) {
                PlaylistRepeatMode.OFF -> PlaylistRepeatMode.ALL
                PlaylistRepeatMode.ALL -> PlaylistRepeatMode.ONE
                PlaylistRepeatMode.ONE -> PlaylistRepeatMode.OFF
            }
            it.copy(repeatMode = next)
        }
    }

    companion object {
        fun provideFactory(repository: PlaylistRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlaylistViewModel(repository) as T
                }
            }
    }
}
