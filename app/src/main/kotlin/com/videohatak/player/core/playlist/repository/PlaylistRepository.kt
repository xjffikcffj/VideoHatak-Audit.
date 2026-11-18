package com.videohatak.player.core.playlist.repository

import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import kotlinx.coroutines.flow.Flow

data class PlaylistAddOutcome(
    val targetPlaylistCount: Int = 0,
    val addedCount: Int = 0,
    val duplicateCount: Int = 0,
    val failedCount: Int = 0,
    val playlistTitles: List<String> = emptyList()
)

sealed interface ToggleFavoriteResult {
    data class Added(val playlistId: Long, val itemId: Long) : ToggleFavoriteResult
    data class Removed(val playlistId: Long) : ToggleFavoriteResult
    data class Failed(
        val message: String,
        val isFavoriteAfterAttempt: Boolean
    ) : ToggleFavoriteResult
}

interface PlaylistRepository {
    companion object {
        const val FAVORITES_PLAYLIST_TITLE = "المفضلة ⭐"
        const val LEGACY_FAVORITES_PLAYLIST_TITLE = "المفضلة"

        fun isProtectedFavoritesTitle(title: String?): Boolean {
            val clean = title?.trim().orEmpty()
            return clean == FAVORITES_PLAYLIST_TITLE || clean == LEGACY_FAVORITES_PLAYLIST_TITLE
        }
    }

    val playlists: Flow<List<Playlist>>

    suspend fun refresh()
    suspend fun createPlaylist(title: String): Long
    suspend fun renamePlaylist(id: Long, newTitle: String): Boolean
    suspend fun deletePlaylist(id: Long): Boolean
    suspend fun getPlaylistItems(playlistId: Long): List<PlaylistItem>
    suspend fun getPlaylistWithItems(playlistId: Long): Pair<Playlist, List<PlaylistItem>>?
    suspend fun ensureFavoritesPlaylist(): Playlist? = null
    suspend fun addVideoToPlaylists(video: VideoItem, playlistIds: List<Long>)
    suspend fun removeVideoFromPlaylist(itemId: Long, playlistId: Long)
    suspend fun removeVideoFromPlaylistDetailed(itemId: Long, playlistId: Long): Boolean {
        removeVideoFromPlaylist(itemId, playlistId)
        return true
    }
    suspend fun moveItem(playlistId: Long, fromIndex: Int, toIndex: Int)
    suspend fun relinkMissingItems(playlistId: Long): Int
    suspend fun isFavorite(video: VideoItem): Boolean = false
    suspend fun toggleFavorite(video: VideoItem): Boolean = false
    suspend fun toggleFavoriteDetailed(video: VideoItem): ToggleFavoriteResult {
        val wasFavorite = isFavorite(video)
        val isNowFavorite = toggleFavorite(video)
        return if (isNowFavorite != wasFavorite) {
            if (isNowFavorite) ToggleFavoriteResult.Added(0L, 0L) else ToggleFavoriteResult.Removed(0L)
        } else {
            ToggleFavoriteResult.Failed(
                message = if (wasFavorite) "تعذر إزالة الفيديو من المفضلة" else "تعذر إضافة الفيديو إلى المفضلة",
                isFavoriteAfterAttempt = isNowFavorite
            )
        }
    }
    suspend fun getFavoriteVideoKeys(): Pair<Set<Long>, Set<String>> = emptySet<Long>() to emptySet()
    suspend fun getFavoritesPlaylistWithItems(): Pair<Playlist, List<PlaylistItem>>? = null
    suspend fun addVideosToPlaylists(videos: List<VideoItem>, playlistIds: List<Long>): Int = 0
    suspend fun addVideosToPlaylistsDetailed(
        videos: List<VideoItem>,
        playlistIds: List<Long>
    ): PlaylistAddOutcome {
        val added = addVideosToPlaylists(videos, playlistIds)
        return PlaylistAddOutcome(
            targetPlaylistCount = playlistIds.distinct().size,
            addedCount = added
        )
    }
    suspend fun removeVideosFromPlaylist(itemIds: List<Long>, playlistId: Long): Int = 0
    suspend fun addVideosToFavorites(videos: List<VideoItem>): Int = 0
    suspend fun addVideosToFavoritesDetailed(videos: List<VideoItem>): PlaylistAddOutcome {
        val added = addVideosToFavorites(videos)
        return PlaylistAddOutcome(
            targetPlaylistCount = 1,
            addedCount = added
        )
    }
    suspend fun removeVideosFromFavorites(videos: List<VideoItem>): Int = 0
    suspend fun removeVideoByIdOrUriFromPlaylist(
        playlistId: Long,
        videoId: Long?,
        contentUri: String?
    ): Boolean = false
}
