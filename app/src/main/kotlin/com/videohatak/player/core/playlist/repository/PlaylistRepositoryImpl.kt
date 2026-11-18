package com.videohatak.player.core.playlist.repository

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class PlaylistRepositoryImpl(
    private val context: Context,
    private val dbHelper: VideoHatakDatabaseHelper = VideoHatakDatabaseHelper(context),
    private val videoRepository: VideoRepository = LocalVideoRepositoryImpl(context)
) : PlaylistRepository {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    override val playlists: Flow<List<Playlist>> = _playlists.asStateFlow()

    private fun findAndMigrateFavoritesLocked(): Playlist? {
        val all = dbHelper.getPlaylists()
        val exact = all.firstOrNull { it.title == PlaylistRepository.FAVORITES_PLAYLIST_TITLE }
        if (exact != null) return exact
        val legacy = all.firstOrNull { it.title.trim() == PlaylistRepository.LEGACY_FAVORITES_PLAYLIST_TITLE }
        if (legacy != null) {
            dbHelper.updatePlaylist(legacy.id, PlaylistRepository.FAVORITES_PLAYLIST_TITLE)
            return legacy.copy(title = PlaylistRepository.FAVORITES_PLAYLIST_TITLE)
        }
        return null
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        findAndMigrateFavoritesLocked()
        val list = dbHelper.getPlaylists()
        val sorted = list.sortedByDescending { PlaylistRepository.isProtectedFavoritesTitle(it.title) }
        _playlists.update { sorted }
    }

    override suspend fun createPlaylist(title: String): Long = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim().ifBlank { "قائمة جديدة" }
        if (PlaylistRepository.isProtectedFavoritesTitle(cleanTitle)) {
            val existingFav = findAndMigrateFavoritesLocked()
            if (existingFav != null) {
                refresh()
                return@withContext existingFav.id
            }
        }
        val id = dbHelper.insertPlaylist(cleanTitle)
        refresh()
        id
    }

    override suspend fun renamePlaylist(id: Long, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val current = dbHelper.getPlaylists().firstOrNull { it.id == id } ?: return@withContext false
        if (PlaylistRepository.isProtectedFavoritesTitle(current.title)) {
            return@withContext false
        }
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isBlank() || PlaylistRepository.isProtectedFavoritesTitle(cleanTitle)) {
            return@withContext false
        }
        val rows = dbHelper.updatePlaylist(id, cleanTitle)
        refresh()
        rows > 0
    }

    override suspend fun deletePlaylist(id: Long): Boolean = withContext(Dispatchers.IO) {
        val current = dbHelper.getPlaylists().firstOrNull { it.id == id } ?: return@withContext false
        if (PlaylistRepository.isProtectedFavoritesTitle(current.title)) {
            return@withContext false
        }
        val rows = dbHelper.deletePlaylist(id)
        refresh()
        rows > 0
    }

    override suspend fun getPlaylistItems(playlistId: Long): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val rawItems = dbHelper.getItemsForPlaylist(playlistId)
        rawItems.map { item ->
            val exists = isMediaAvailable(Uri.parse(item.contentUri))
            if (item.isAvailable != exists) {
                dbHelper.updateItemAvailability(item.id, exists)
            }
            item.copy(isAvailable = exists)
        }
    }

    override suspend fun getPlaylistWithItems(playlistId: Long): Pair<Playlist, List<PlaylistItem>>? = withContext(Dispatchers.IO) {
        val playlist = dbHelper.getPlaylists().find { it.id == playlistId } ?: return@withContext null
        val items = getPlaylistItems(playlistId)
        Pair(playlist, items)
    }

    override suspend fun addVideoToPlaylists(video: VideoItem, playlistIds: List<Long>) = withContext(Dispatchers.IO) {
        val uriString = video.contentUri.toString()
        for (pId in playlistIds.distinct()) {
            val existing = dbHelper.getItemsForPlaylist(pId)
            val alreadyAdded = existing.any {
                it.videoId == video.id || (uriString.isNotBlank() && it.contentUri == uriString)
            }
            if (!alreadyAdded) {
                val nextOrder = existing.size
                val item = PlaylistItem(
                    id = 0L,
                    playlistId = pId,
                    videoId = video.id,
                    orderIndex = nextOrder,
                    videoTitle = video.safeTitle,
                    durationMs = video.durationMs,
                    sizeBytes = video.sizeBytes,
                    dateAddedSec = video.dateAddedSec,
                    contentUri = uriString,
                    isAvailable = true
                )
                dbHelper.insertItem(item)
            }
        }
        refresh()
    }

    override suspend fun removeVideoFromPlaylist(itemId: Long, playlistId: Long) = withContext(Dispatchers.IO) {
        dbHelper.deleteItem(itemId, playlistId)
        val remaining = dbHelper.getItemsForPlaylist(playlistId)
        remaining.forEachIndexed { index, item ->
            if (item.orderIndex != index) {
                dbHelper.updateItemOrder(item.id, index)
            }
        }
        refresh()
    }

    override suspend fun moveItem(playlistId: Long, fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        val items = dbHelper.getItemsForPlaylist(playlistId).toMutableList()
        if (fromIndex in items.indices && toIndex in items.indices && fromIndex != toIndex) {
            val item = items.removeAt(fromIndex)
            items.add(toIndex, item)
            items.forEachIndexed { idx, itm ->
                dbHelper.updateItemOrder(itm.id, idx)
            }
            refresh()
        }
    }

    /**
     * AUD-06 & AUD-07: Relink missing items using multi-criteria matching.
     * Skips vault:// URIs safely.
     */
    override suspend fun relinkMissingItems(playlistId: Long): Int = withContext(Dispatchers.IO) {
        val items = dbHelper.getItemsForPlaylist(playlistId)
        val allVideos = videoRepository.getVideos()
        var relinkedCount = 0

        for (item in items) {
            val uri = Uri.parse(item.contentUri)
            if (uri.scheme == "vault") continue
            if (!isMediaAvailable(uri)) {
                val match = allVideos.find { candidate ->
                    val nameMatch = candidate.displayName.equals(item.videoTitle, ignoreCase = true) ||
                            candidate.safeTitle.equals(item.videoTitle, ignoreCase = true)
                    val durationMatch = abs(candidate.durationMs - item.durationMs) < 1500L
                    nameMatch && durationMatch
                } ?: allVideos.find { candidate ->
                    candidate.safeTitle.equals(item.videoTitle, ignoreCase = true) &&
                            abs(candidate.durationMs - item.durationMs) < 2500L
                }

                if (match != null) {
                    dbHelper.updateItemUri(item.id, match.contentUri.toString())
                    relinkedCount++
                }
            }
        }
        if (relinkedCount > 0) {
            refresh()
        }
        relinkedCount
    }

    override suspend fun isFavorite(video: VideoItem): Boolean = withContext(Dispatchers.IO) {
        val favoritesPlaylist = findAndMigrateFavoritesLocked() ?: return@withContext false
        val items = dbHelper.getItemsForPlaylist(favoritesPlaylist.id)
        val uriString = video.contentUri.toString()
        items.any { it.videoId == video.id || (uriString.isNotBlank() && it.contentUri == uriString) }
    }

    override suspend fun toggleFavorite(video: VideoItem): Boolean = withContext(Dispatchers.IO) {
        val existingPlaylist = findAndMigrateFavoritesLocked()
        val uriString = video.contentUri.toString()

        if (existingPlaylist != null) {
            val items = dbHelper.getItemsForPlaylist(existingPlaylist.id)
            val matchingItems = items.filter { it.videoId == video.id || (uriString.isNotBlank() && it.contentUri == uriString) }
            if (matchingItems.isNotEmpty()) {
                for (match in matchingItems) {
                    dbHelper.deleteItem(match.id, existingPlaylist.id)
                }
                val remaining = dbHelper.getItemsForPlaylist(existingPlaylist.id)
                remaining.forEachIndexed { index, item ->
                    if (item.orderIndex != index) {
                        dbHelper.updateItemOrder(item.id, index)
                    }
                }
                refresh()
                return@withContext false
            }
            val nextOrder = items.size
            dbHelper.insertItem(
                PlaylistItem(
                    id = 0L,
                    playlistId = existingPlaylist.id,
                    videoId = video.id,
                    orderIndex = nextOrder,
                    videoTitle = video.safeTitle,
                    durationMs = video.durationMs,
                    sizeBytes = video.sizeBytes,
                    dateAddedSec = video.dateAddedSec,
                    contentUri = uriString,
                    isAvailable = true
                )
            )
            refresh()
            return@withContext true
        }

        val favoritesId = dbHelper.insertPlaylist(PlaylistRepository.FAVORITES_PLAYLIST_TITLE)
        if (favoritesId > 0L) {
            dbHelper.insertItem(
                PlaylistItem(
                    id = 0L,
                    playlistId = favoritesId,
                    videoId = video.id,
                    orderIndex = 0,
                    videoTitle = video.safeTitle,
                    durationMs = video.durationMs,
                    sizeBytes = video.sizeBytes,
                    dateAddedSec = video.dateAddedSec,
                    contentUri = uriString,
                    isAvailable = true
                )
            )
        }
        refresh()
        favoritesId > 0L
    }

    override suspend fun getFavoriteVideoKeys(): Pair<Set<Long>, Set<String>> = withContext(Dispatchers.IO) {
        val favoritesPlaylist = findAndMigrateFavoritesLocked() ?: return@withContext (emptySet<Long>() to emptySet())
        val items = dbHelper.getItemsForPlaylist(favoritesPlaylist.id)
        val ids = items.map { it.videoId }.toSet()
        val uris = items.map { it.contentUri }.toSet()
        ids to uris
    }

    override suspend fun getFavoritesPlaylistWithItems(): Pair<Playlist, List<PlaylistItem>>? = withContext(Dispatchers.IO) {
        val favoritesPlaylist = findAndMigrateFavoritesLocked() ?: return@withContext null
        val items = getPlaylistItems(favoritesPlaylist.id)
        favoritesPlaylist.copy(itemCount = items.size) to items
    }

    override suspend fun addVideosToPlaylists(videos: List<VideoItem>, playlistIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (videos.isEmpty() || playlistIds.isEmpty()) return@withContext 0
        var addedCount = 0
        for (pId in playlistIds.distinct()) {
            val existing = dbHelper.getItemsForPlaylist(pId)
            var nextOrder = existing.size
            val existingIds = existing.map { it.videoId }.toHashSet()
            val existingUris = existing.mapNotNull { it.contentUri.takeIf { u -> u.isNotBlank() } }.toHashSet()
            for (video in videos) {
                val uriString = video.contentUri.toString()
                val alreadyAdded = (video.id in existingIds) || (uriString.isNotBlank() && uriString in existingUris)
                if (!alreadyAdded) {
                    val inserted = dbHelper.insertItem(
                        PlaylistItem(
                            id = 0L,
                            playlistId = pId,
                            videoId = video.id,
                            orderIndex = nextOrder++,
                            videoTitle = video.safeTitle,
                            durationMs = video.durationMs,
                            sizeBytes = video.sizeBytes,
                            dateAddedSec = video.dateAddedSec,
                            contentUri = uriString,
                            isAvailable = true
                        )
                    )
                    if (inserted > 0L) {
                        addedCount++
                        existingIds.add(video.id)
                        if (uriString.isNotBlank()) existingUris.add(uriString)
                    }
                }
            }
        }
        if (addedCount > 0) refresh()
        addedCount
    }

    override suspend fun removeVideosFromPlaylist(itemIds: List<Long>, playlistId: Long): Int = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) return@withContext 0
        var removedCount = 0
        for (itemId in itemIds.distinct()) {
            if (dbHelper.deleteItem(itemId, playlistId) > 0) {
                removedCount++
            }
        }
        val remaining = dbHelper.getItemsForPlaylist(playlistId)
        remaining.forEachIndexed { index, item ->
            if (item.orderIndex != index) {
                dbHelper.updateItemOrder(item.id, index)
            }
        }
        refresh()
        removedCount
    }

    override suspend fun addVideosToFavorites(videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        if (videos.isEmpty()) return@withContext 0
        val fav = findAndMigrateFavoritesLocked()
        val favId = fav?.id ?: dbHelper.insertPlaylist(PlaylistRepository.FAVORITES_PLAYLIST_TITLE)
        if (favId <= 0L) return@withContext 0
        addVideosToPlaylists(videos, listOf(favId))
    }

    override suspend fun removeVideosFromFavorites(videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        if (videos.isEmpty()) return@withContext 0
        val fav = findAndMigrateFavoritesLocked() ?: return@withContext 0
        val existing = dbHelper.getItemsForPlaylist(fav.id)
        val targetIds = videos.map { it.id }.toSet()
        val targetUris = videos.mapNotNull { it.contentUri.toString().takeIf { u -> u.isNotBlank() } }.toSet()
        val matchingItemIds = existing
            .filter { it.videoId in targetIds || it.contentUri in targetUris }
            .map { it.id }
        if (matchingItemIds.isEmpty()) return@withContext 0
        removeVideosFromPlaylist(matchingItemIds, fav.id)
    }

    override suspend fun removeVideoByIdOrUriFromPlaylist(
        playlistId: Long,
        videoId: Long?,
        contentUri: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val items = dbHelper.getItemsForPlaylist(playlistId)
        val matches = items.filter { item ->
            (videoId != null && item.videoId == videoId) ||
                (!contentUri.isNullOrBlank() && item.contentUri == contentUri)
        }
        if (matches.isEmpty()) return@withContext false
        for (match in matches) {
            dbHelper.deleteItem(match.id, playlistId)
        }
        val remaining = dbHelper.getItemsForPlaylist(playlistId)
        remaining.forEachIndexed { index, item ->
            if (item.orderIndex != index) {
                dbHelper.updateItemOrder(item.id, index)
            }
        }
        refresh()
        true
    }

    private fun isMediaAvailable(uri: Uri): Boolean {
        return try {
            if (uri.scheme == "vault") {
                return true
            }
            if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                return file.exists()
            }
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
