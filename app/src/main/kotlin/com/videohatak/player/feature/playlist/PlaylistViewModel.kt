package com.videohatak.player.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.playlist.model.PlaylistPlaybackState
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.playlist.repository.PlaylistAddOutcome
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import com.videohatak.player.core.playlist.repository.ToggleFavoriteResult
import com.videohatak.player.core.vault.model.VaultHiddenMatcher
import com.videohatak.player.core.vault.model.VaultHiddenOriginal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PlaylistUiEvent {
    data class ShowMessage(val message: String) : PlaylistUiEvent
}

class PlaylistViewModel(
    private val repository: PlaylistRepository,
    private val vaultHiddenEntries: StateFlow<List<VaultHiddenOriginal>> = MutableStateFlow(emptyList())
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedPlaylistWithItems = MutableStateFlow<Pair<Playlist, List<PlaylistItem>>?>(null)
    val selectedPlaylistWithItems: StateFlow<Pair<Playlist, List<PlaylistItem>>?> = combine(
        _selectedPlaylistWithItems,
        vaultHiddenEntries
    ) { pair, hidden ->
        pair?.let { (pl, items) ->
            val visible = if (hidden.isEmpty()) items else items.filterNot {
                VaultHiddenMatcher.isPlaylistItemHidden(it, hidden)
            }
            pl.copy(itemCount = visible.size) to visible
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentPlaylistItems: StateFlow<List<PlaylistItem>> = selectedPlaylistWithItems
        .map { it?.second ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _playbackState = MutableStateFlow(PlaylistPlaybackState())
    val playbackState: StateFlow<PlaylistPlaybackState> = _playbackState.asStateFlow()

    private val _favoriteVideoIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteVideoIds: StateFlow<Set<Long>> = _favoriteVideoIds.asStateFlow()

    private val _favoriteVideoUris = MutableStateFlow<Set<String>>(emptySet())
    val favoriteVideoUris: StateFlow<Set<String>> = _favoriteVideoUris.asStateFlow()

    private val _favoritesPlaylistWithItems = MutableStateFlow<Pair<Playlist, List<PlaylistItem>>?>(null)
    val favoritesPlaylistWithItems: StateFlow<Pair<Playlist, List<PlaylistItem>>?> = combine(
        _favoritesPlaylistWithItems,
        vaultHiddenEntries
    ) { pair, hidden ->
        pair?.let { (pl, items) ->
            val visible = if (hidden.isEmpty()) items else items.filterNot {
                VaultHiddenMatcher.isPlaylistItemHidden(it, hidden)
            }
            pl.copy(itemCount = visible.size) to visible
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val favoriteItems: StateFlow<List<PlaylistItem>> = favoritesPlaylistWithItems
        .map { it?.second ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoritesPlaylistId: StateFlow<Long?> = _favoritesPlaylistWithItems
        .map { it?.first?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _events = MutableSharedFlow<PlaylistUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PlaylistUiEvent> = _events.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch {
            vaultHiddenEntries.drop(1).collect {
                refresh()
            }
        }
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
        val uriKey = video.contentUri.toString().trim()
        return (video.id > 0L && video.id in _favoriteVideoIds.value) ||
            (uriKey.isNotEmpty() && uriKey in _favoriteVideoUris.value)
    }

    fun openFavoritesPlaylist() {
        viewModelScope.launch {
            val fav = repository.ensureFavoritesPlaylist() ?: return@launch
            refreshFavoritesState()
            _selectedPlaylistWithItems.value = repository.getPlaylistWithItems(fav.id)
        }
    }

    fun toggleFavorite(video: VideoItem, onResult: ((Boolean) -> Unit)? = null) {
        toggleFavoriteWithFeedback(video) { isNowFavorite, _ ->
            onResult?.invoke(isNowFavorite)
        }
    }

    fun toggleFavoriteWithFeedback(video: VideoItem, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val outcome = repository.toggleFavoriteDetailed(video)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.let { selected ->
                if (PlaylistRepository.isProtectedFavoritesTitle(selected.title)) {
                    loadPlaylistWithItems(selected.id)
                }
            }
            val (isNowFavorite, msg) = when (outcome) {
                is ToggleFavoriteResult.Added -> true to "تمت إضافة الفيديو إلى المفضلة بنجاح"
                is ToggleFavoriteResult.Removed -> false to "تمت إزالة الفيديو من المفضلة بنجاح"
                is ToggleFavoriteResult.Failed -> outcome.isFavoriteAfterAttempt to outcome.message
            }
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
            onResult(isNowFavorite, msg)
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
            val cleanName = name.trim().ifBlank { "قائمة جديدة" }
            val newId = repository.createPlaylist(cleanName)
            if (newId > 0L) {
                loadPlaylistWithItems(newId)
                _events.tryEmit(PlaylistUiEvent.ShowMessage("تم إنشاء قائمة \"$cleanName\" بنجاح"))
            } else {
                _events.tryEmit(PlaylistUiEvent.ShowMessage("تعذر إنشاء قائمة التشغيل"))
            }
        }
    }

    fun createPlaylistAndAddVideo(name: String, video: VideoItem, onComplete: (() -> Unit)? = null) {
        createPlaylistAndAddVideoWithResult(name, video) { _, _ ->
            onComplete?.invoke()
        }
    }

    fun createPlaylistAndAddVideoWithResult(
        name: String,
        video: VideoItem,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val cleanName = name.trim().ifBlank { "قائمة جديدة" }
            val newId = repository.createPlaylist(cleanName)
            if (newId <= 0L) {
                val errMsg = "تعذر إنشاء قائمة التشغيل"
                _events.tryEmit(PlaylistUiEvent.ShowMessage(errMsg))
                onResult(false, errMsg)
                return@launch
            }
            val outcome = repository.addVideosToPlaylistsDetailed(listOf(video), listOf(newId))
            refreshFavoritesState()
            loadPlaylistWithItems(newId)
            val msg = when {
                outcome.addedCount > 0 -> "تم إنشاء \"$cleanName\" وإضافة الفيديو بنجاح"
                outcome.duplicateCount > 0 -> "الفيديو موجود بالفعل في \"$cleanName\""
                else -> "تم إنشاء \"$cleanName\" لكن تعذر إضافة الفيديو"
            }
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
            onResult(outcome.addedCount > 0, msg)
        }
    }

    fun renamePlaylist(id: Long, newName: String) {
        val current = playlists.value.firstOrNull { it.id == id }
        if (current != null && PlaylistRepository.isProtectedFavoritesTitle(current.title)) {
            _events.tryEmit(PlaylistUiEvent.ShowMessage("لا يمكن تعديل اسم قائمة المفضلة الأساسية"))
            return
        }
        viewModelScope.launch {
            val updated = repository.renamePlaylist(id, newName)
            if (updated && _selectedPlaylistWithItems.value?.first?.id == id) {
                loadPlaylistWithItems(id)
            }
            val msg = if (updated) {
                "تم تغيير اسم القائمة بنجاح"
            } else {
                "تعذر تغيير اسم القائمة"
            }
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
        }
    }

    fun deletePlaylist(id: Long) {
        val current = playlists.value.firstOrNull { it.id == id }
        if (current != null && PlaylistRepository.isProtectedFavoritesTitle(current.title)) {
            _events.tryEmit(PlaylistUiEvent.ShowMessage("لا يمكن حذف قائمة المفضلة الأساسية"))
            return
        }
        viewModelScope.launch {
            val deleted = repository.deletePlaylist(id)
            refreshFavoritesState()
            if (deleted && _selectedPlaylistWithItems.value?.first?.id == id) {
                _selectedPlaylistWithItems.value = null
            }
            val msg = if (deleted) {
                "تم حذف القائمة بنجاح"
            } else {
                "تعذر حذف القائمة"
            }
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
        }
    }

    fun addVideoToPlaylists(video: VideoItem, playlistIds: List<Long>) {
        addVideoToPlaylistsWithResult(video, playlistIds) { _, _ -> }
    }

    fun addVideoToPlaylistsWithResult(
        video: VideoItem,
        playlistIds: List<Long>,
        onResult: (Boolean, String) -> Unit
    ) {
        if (playlistIds.isEmpty()) {
            val msg = "لم يتم اختيار قائمة تشغيل"
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
            onResult(false, msg)
            return
        }
        viewModelScope.launch {
            val outcome = repository.addVideosToPlaylistsDetailed(listOf(video), playlistIds)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.id?.let {
                if (it in playlistIds) loadPlaylistWithItems(it)
            }
            val msg = formatPlaylistAddMessage(outcome, totalVideos = 1, isFavoritesTarget = false)
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
            onResult(outcome.addedCount > 0, msg)
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
            val outcome = repository.addVideosToPlaylistsDetailed(videos, playlistIds)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.id?.let {
                if (it in playlistIds) loadPlaylistWithItems(it)
            }
            val msg = formatPlaylistAddMessage(outcome, totalVideos = videos.size, isFavoritesTarget = false)
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
            onComplete(outcome.addedCount)
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
            val outcome = repository.addVideosToFavoritesDetailed(videos)
            refreshFavoritesState()
            _selectedPlaylistWithItems.value?.first?.let { selected ->
                if (PlaylistRepository.isProtectedFavoritesTitle(selected.title)) {
                    loadPlaylistWithItems(selected.id)
                }
            }
            val msg = formatPlaylistAddMessage(outcome, totalVideos = videos.size, isFavoritesTarget = true)
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
            onComplete(outcome.addedCount)
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
            val msg = when {
                removed == 1 -> "تمت إزالة الفيديو من المفضلة بنجاح"
                removed > 1 -> "تمت إزالة $removed فيديو من المفضلة بنجاح"
                else -> "الفيديو غير موجود في المفضلة"
            }
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
            onComplete(removed)
        }
    }

    fun removeItemFromPlaylist(itemId: Long, playlistId: Long) {
        viewModelScope.launch {
            val isFavPlaylist = isFavoritesPlaylistId(playlistId)
            val removed = repository.removeVideoFromPlaylistDetailed(itemId, playlistId)
            refreshFavoritesState()
            if (_selectedPlaylistWithItems.value?.first?.id == playlistId) {
                loadPlaylistWithItems(playlistId)
            }
            val msg = when {
                removed && isFavPlaylist -> "تمت إزالة الفيديو من المفضلة بنجاح"
                removed -> "تمت إزالة الفيديو من القائمة بنجاح"
                else -> "تعذر إزالة الفيديو من القائمة"
            }
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
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
            val isFavPlaylist = isFavoritesPlaylistId(playlistId)
            val removed = repository.removeVideosFromPlaylist(itemIds.toList(), playlistId)
            refreshFavoritesState()
            if (_selectedPlaylistWithItems.value?.first?.id == playlistId) {
                loadPlaylistWithItems(playlistId)
            }
            val failed = (itemIds.size - removed).coerceAtLeast(0)
            val msg = when {
                removed > 0 && failed > 0 && isFavPlaylist -> "تمت إزالة $removed من المفضلة، وتعذر إزالة $failed"
                removed > 0 && failed > 0 -> "تمت إزالة $removed من القائمة، وتعذر إزالة $failed"
                removed == 1 && isFavPlaylist -> "تمت إزالة الفيديو من المفضلة بنجاح"
                removed > 1 && isFavPlaylist -> "تمت إزالة $removed فيديو من المفضلة بنجاح"
                removed == 1 -> "تمت إزالة الفيديو من القائمة بنجاح"
                removed > 1 -> "تمت إزالة $removed فيديو من القائمة بنجاح"
                else -> "تعذر إزالة الفيديو من القائمة"
            }
            _events.tryEmit(PlaylistUiEvent.ShowMessage(msg))
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

    private fun isFavoritesPlaylistId(playlistId: Long): Boolean {
        val currentTitle = _selectedPlaylistWithItems.value?.first
            ?.takeIf { it.id == playlistId }?.title
            ?: playlists.value.firstOrNull { it.id == playlistId }?.title
            ?: _favoritesPlaylistWithItems.value?.first?.takeIf { it.id == playlistId }?.title
        return PlaylistRepository.isProtectedFavoritesTitle(currentTitle)
    }

    private fun formatPlaylistAddMessage(
        outcome: PlaylistAddOutcome,
        totalVideos: Int,
        isFavoritesTarget: Boolean
    ): String {
        val targetName = outcome.playlistTitles.singleOrNull()
        val isSingleFav = isFavoritesTarget || PlaylistRepository.isProtectedFavoritesTitle(targetName)
        return when {
            outcome.addedCount > 0 && outcome.duplicateCount == 0 && outcome.failedCount == 0 -> {
                when {
                    isSingleFav && totalVideos == 1 -> "تمت إضافة الفيديو إلى المفضلة بنجاح"
                    isSingleFav -> "تمت إضافة ${outcome.addedCount} فيديو إلى المفضلة بنجاح"
                    targetName != null && totalVideos == 1 -> "تمت إضافة الفيديو إلى \"$targetName\" بنجاح"
                    targetName != null -> "تمت إضافة ${outcome.addedCount} فيديو إلى \"$targetName\" بنجاح"
                    totalVideos == 1 -> "تمت إضافة الفيديو إلى القائمة بنجاح"
                    else -> "تمت إضافة ${outcome.addedCount} فيديو إلى القائمة بنجاح"
                }
            }
            outcome.addedCount == 0 && outcome.duplicateCount > 0 && outcome.failedCount == 0 -> {
                when {
                    isSingleFav && totalVideos == 1 -> "الفيديو موجود بالفعل في المفضلة"
                    isSingleFav -> "الفيديوهات المحددة موجودة بالفعل في المفضلة"
                    totalVideos == 1 -> "الفيديو موجود بالفعل في القائمة"
                    else -> "الفيديوهات المحددة موجودة بالفعل في القائمة"
                }
            }
            outcome.addedCount > 0 && outcome.duplicateCount > 0 && outcome.failedCount > 0 -> {
                "تمت إضافة ${outcome.addedCount} فيديو، و${outcome.duplicateCount} موجود بالفعل، وتعذر إضافة ${outcome.failedCount}"
            }
            outcome.addedCount > 0 && outcome.duplicateCount > 0 -> {
                if (isSingleFav) {
                    "تمت إضافة ${outcome.addedCount} إلى المفضلة، و${outcome.duplicateCount} موجود بالفعل"
                } else {
                    "تمت إضافة ${outcome.addedCount} فيديو، و${outcome.duplicateCount} موجود بالفعل في القائمة"
                }
            }
            outcome.addedCount > 0 && outcome.failedCount > 0 -> {
                if (isSingleFav) {
                    "تمت إضافة ${outcome.addedCount} إلى المفضلة، وتعذر إضافة ${outcome.failedCount}"
                } else {
                    "تمت إضافة ${outcome.addedCount} فيديو، وتعذر إضافة ${outcome.failedCount}"
                }
            }
            outcome.addedCount > 0 -> {
                "تمت إضافة ${outcome.addedCount} فيديو بنجاح"
            }
            outcome.duplicateCount > 0 && outcome.failedCount > 0 -> {
                if (isSingleFav) {
                    "بعض المقاطع موجودة بالفعل في المفضلة (${outcome.duplicateCount})، وتعذر إضافة ${outcome.failedCount}"
                } else {
                    "بعض المقاطع موجودة بالفعل في القائمة (${outcome.duplicateCount})، وتعذر إضافة ${outcome.failedCount}"
                }
            }
            else -> {
                if (isSingleFav) "تعذر إضافة الفيديو إلى المفضلة" else "تعذر إضافة الفيديو إلى القائمة"
            }
        }
    }

    companion object {
        fun provideFactory(
            repository: PlaylistRepository,
            vaultHiddenEntries: StateFlow<List<VaultHiddenOriginal>> = MutableStateFlow(emptyList())
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlaylistViewModel(repository, vaultHiddenEntries) as T
                }
            }
    }
}
