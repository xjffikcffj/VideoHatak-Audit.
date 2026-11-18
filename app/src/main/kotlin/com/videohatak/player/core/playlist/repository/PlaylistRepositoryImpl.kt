package com.videohatak.player.core.playlist.repository

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.db.PlaylistDatabaseStore
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
    private val dbHelper: PlaylistDatabaseStore = VideoHatakDatabaseHelper(context),
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

    internal fun normalizeVideoUriKey(rawUri: String?): String =
        rawUri?.trim().orEmpty()

    internal fun isSamePlaylistVideo(
        existingVideoId: Long,
        existingContentUri: String,
        candidateVideoId: Long,
        candidateContentUri: String
    ): Boolean {
        val normExistingUri = normalizeVideoUriKey(existingContentUri)
        val normCandidateUri = normalizeVideoUriKey(candidateContentUri)
        if (normExistingUri.isNotEmpty() && normCandidateUri.isNotEmpty() && normExistingUri == normCandidateUri) {
            return true
        }
        if (existingVideoId > 0L && candidateVideoId > 0L && existingVideoId == candidateVideoId) {
            return true
        }
        return false
    }

    override suspend fun ensureFavoritesPlaylist(): Playlist? = withContext(Dispatchers.IO) {
        val existing = findAndMigrateFavoritesLocked()
        if (existing != null) {
            refresh()
            return@withContext existing
        }
        val favId = dbHelper.insertPlaylist(PlaylistRepository.FAVORITES_PLAYLIST_TITLE)
        refresh()
        if (favId > 0L) dbHelper.getPlaylists().firstOrNull { it.id == favId } else null
    }

    override suspend fun addVideoToPlaylists(video: VideoItem, playlistIds: List<Long>) {
        addVideosToPlaylistsDetailed(listOf(video), playlistIds)
    }

    override suspend fun removeVideoFromPlaylist(itemId: Long, playlistId: Long) {
        removeVideoFromPlaylistDetailed(itemId, playlistId)
    }

    override suspend fun removeVideoFromPlaylistDetailed(itemId: Long, playlistId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val deletedRows = dbHelper.deleteItem(itemId, playlistId)
            val remaining = dbHelper.getItemsForPlaylist(playlistId)
            remaining.forEachIndexed { index, item ->
                if (item.orderIndex != index) {
                    dbHelper.updateItemOrder(item.id, index)
                }
            }
            refresh()
            deletedRows > 0
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
        val uriString = normalizeVideoUriKey(video.contentUri.toString())
        items.any { isSamePlaylistVideo(it.videoId, it.contentUri, video.id, uriString) }
    }

    override suspend fun toggleFavorite(video: VideoItem): Boolean =
        when (val result = toggleFavoriteDetailed(video)) {
            is ToggleFavoriteResult.Added -> true
            is ToggleFavoriteResult.Removed -> false
            is ToggleFavoriteResult.Failed -> result.isFavoriteAfterAttempt
        }

    override suspend fun toggleFavoriteDetailed(video: VideoItem): ToggleFavoriteResult = withContext(Dispatchers.IO) {
        val uriString = normalizeVideoUriKey(video.contentUri.toString())
        if (video.id <= 0L && uriString.isEmpty()) {
            return@withContext ToggleFavoriteResult.Failed(
                message = "تعذر إضافة الفيديو إلى المفضلة لعدم توفر معرّف صالح",
                isFavoriteAfterAttempt = false
            )
        }

        val existingPlaylist = try {
            findAndMigrateFavoritesLocked()
        } catch (_: Exception) {
            return@withContext ToggleFavoriteResult.Failed(
                message = "تعذر الوصول إلى قائمة المفضلة في قاعدة البيانات",
                isFavoriteAfterAttempt = false
            )
        }

        if (existingPlaylist != null) {
            val items = try {
                dbHelper.getItemsForPlaylist(existingPlaylist.id)
            } catch (_: Exception) {
                return@withContext ToggleFavoriteResult.Failed(
                    message = "تعذر قراءة عناصر المفضلة من قاعدة البيانات",
                    isFavoriteAfterAttempt = false
                )
            }

            val matchingItems = items.filter {
                isSamePlaylistVideo(it.videoId, it.contentUri, video.id, uriString)
            }
            if (matchingItems.isNotEmpty()) {
                var anyDeleteFailed = false
                for (match in matchingItems) {
                    val deletedRows = try {
                        dbHelper.deleteItem(match.id, existingPlaylist.id)
                    } catch (_: Exception) {
                        0
                    }
                    if (deletedRows <= 0) {
                        anyDeleteFailed = true
                    }
                }
                val remaining = try {
                    dbHelper.getItemsForPlaylist(existingPlaylist.id)
                } catch (_: Exception) {
                    items
                }
                val stillPresent = remaining.any {
                    isSamePlaylistVideo(it.videoId, it.contentUri, video.id, uriString)
                }
                if (!anyDeleteFailed && !stillPresent) {
                    remaining.forEachIndexed { index, item ->
                        if (item.orderIndex != index) {
                            runCatching { dbHelper.updateItemOrder(item.id, index) }
                        }
                    }
                    runCatching { refresh() }
                    return@withContext ToggleFavoriteResult.Removed(existingPlaylist.id)
                }
                runCatching { refresh() }
                return@withContext ToggleFavoriteResult.Failed(
                    message = "تعذر إزالة الفيديو من المفضلة",
                    isFavoriteAfterAttempt = stillPresent
                )
            }

            val nextOrder = (items.maxOfOrNull { it.orderIndex } ?: -1) + 1
            val insertedId = try {
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
            } catch (_: Exception) {
                -1L
            }
            val afterItems = try {
                dbHelper.getItemsForPlaylist(existingPlaylist.id)
            } catch (_: Exception) {
                items
            }
            val isNowPresent = afterItems.any {
                (insertedId > 0L && it.id == insertedId) ||
                    isSamePlaylistVideo(it.videoId, it.contentUri, video.id, uriString)
            }
            runCatching { refresh() }
            return@withContext if (insertedId > 0L && isNowPresent) {
                ToggleFavoriteResult.Added(existingPlaylist.id, insertedId)
            } else {
                ToggleFavoriteResult.Failed(
                    message = "تعذر إضافة الفيديو إلى المفضلة",
                    isFavoriteAfterAttempt = isNowPresent
                )
            }
        }

        val favoritesId = try {
            dbHelper.insertPlaylist(PlaylistRepository.FAVORITES_PLAYLIST_TITLE)
        } catch (_: Exception) {
            -1L
        }
        if (favoritesId <= 0L) {
            runCatching { refresh() }
            return@withContext ToggleFavoriteResult.Failed(
                message = "تعذر إنشاء قائمة المفضلة",
                isFavoriteAfterAttempt = false
            )
        }

        val insertedId = try {
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
        } catch (_: Exception) {
            -1L
        }
        val afterItems = try {
            dbHelper.getItemsForPlaylist(favoritesId)
        } catch (_: Exception) {
            emptyList()
        }
        val isNowPresent = afterItems.any {
            (insertedId > 0L && it.id == insertedId) ||
                isSamePlaylistVideo(it.videoId, it.contentUri, video.id, uriString)
        }
        runCatching { refresh() }
        if (insertedId > 0L && isNowPresent) {
            ToggleFavoriteResult.Added(favoritesId, insertedId)
        } else {
            ToggleFavoriteResult.Failed(
                message = "تعذر إضافة الفيديو إلى المفضلة",
                isFavoriteAfterAttempt = isNowPresent
            )
        }
    }

    override suspend fun getFavoriteVideoKeys(): Pair<Set<Long>, Set<String>> = withContext(Dispatchers.IO) {
        val favoritesPlaylist = findAndMigrateFavoritesLocked() ?: return@withContext (emptySet<Long>() to emptySet())
        val items = dbHelper.getItemsForPlaylist(favoritesPlaylist.id)
        val ids = items.mapNotNull { it.videoId.takeIf { id -> id > 0L } }.toSet()
        val uris = items.mapNotNull { normalizeVideoUriKey(it.contentUri).takeIf { u -> u.isNotEmpty() } }.toSet()
        ids to uris
    }

    override suspend fun getFavoritesPlaylistWithItems(): Pair<Playlist, List<PlaylistItem>>? = withContext(Dispatchers.IO) {
        val favoritesPlaylist = findAndMigrateFavoritesLocked() ?: return@withContext null
        val items = getPlaylistItems(favoritesPlaylist.id)
        favoritesPlaylist.copy(itemCount = items.size) to items
    }

    override suspend fun addVideosToPlaylists(videos: List<VideoItem>, playlistIds: List<Long>): Int =
        addVideosToPlaylistsDetailed(videos, playlistIds).addedCount

    override suspend fun addVideosToPlaylistsDetailed(
        videos: List<VideoItem>,
        playlistIds: List<Long>
    ): PlaylistAddOutcome = withContext(Dispatchers.IO) {
        val distinctIds = playlistIds.distinct()
        if (videos.isEmpty() || distinctIds.isEmpty()) {
            return@withContext PlaylistAddOutcome(
                targetPlaylistCount = distinctIds.size,
                failedCount = if (distinctIds.isEmpty()) videos.size else 0
            )
        }
        val availablePlaylists = dbHelper.getPlaylists().associateBy { it.id }
        var addedCount = 0
        var duplicateCount = 0
        var failedCount = 0
        val targetTitles = mutableListOf<String>()

        for (pId in distinctIds) {
            val playlist = availablePlaylists[pId]
            if (playlist == null) {
                failedCount += videos.size
                continue
            }
            targetTitles.add(playlist.title)
            val existing = dbHelper.getItemsForPlaylist(pId)
            var nextOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
            val currentItems = existing.toMutableList()

            for (video in videos) {
                val uriString = normalizeVideoUriKey(video.contentUri.toString())
                if (video.id <= 0L && uriString.isEmpty()) {
                    failedCount++
                    continue
                }
                val alreadyAdded = currentItems.any { existingItem ->
                    isSamePlaylistVideo(
                        existingVideoId = existingItem.videoId,
                        existingContentUri = existingItem.contentUri,
                        candidateVideoId = video.id,
                        candidateContentUri = uriString
                    )
                }
                if (alreadyAdded) {
                    duplicateCount++
                } else {
                    val newItem = PlaylistItem(
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
                    val inserted = dbHelper.insertItem(newItem)
                    if (inserted > 0L) {
                        addedCount++
                        nextOrder++
                        currentItems.add(newItem.copy(id = inserted))
                    } else {
                        failedCount++
                    }
                }
            }
        }
        if (addedCount > 0) refresh()
        PlaylistAddOutcome(
            targetPlaylistCount = distinctIds.size,
            addedCount = addedCount,
            duplicateCount = duplicateCount,
            failedCount = failedCount,
            playlistTitles = targetTitles
        )
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

    override suspend fun addVideosToFavorites(videos: List<VideoItem>): Int =
        addVideosToFavoritesDetailed(videos).addedCount

    override suspend fun addVideosToFavoritesDetailed(videos: List<VideoItem>): PlaylistAddOutcome =
        withContext(Dispatchers.IO) {
            if (videos.isEmpty()) return@withContext PlaylistAddOutcome(targetPlaylistCount = 1)
            val fav = findAndMigrateFavoritesLocked()
            val favId = fav?.id ?: dbHelper.insertPlaylist(PlaylistRepository.FAVORITES_PLAYLIST_TITLE)
            if (favId <= 0L) {
                return@withContext PlaylistAddOutcome(
                    targetPlaylistCount = 1,
                    failedCount = videos.size
                )
            }
            addVideosToPlaylistsDetailed(videos, listOf(favId))
        }

    override suspend fun removeVideosFromFavorites(videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        if (videos.isEmpty()) return@withContext 0
        val fav = findAndMigrateFavoritesLocked() ?: return@withContext 0
        val existing = dbHelper.getItemsForPlaylist(fav.id)
        val matchingItemIds = existing.filter { existingItem ->
            videos.any { candidate ->
                isSamePlaylistVideo(
                    existingVideoId = existingItem.videoId,
                    existingContentUri = existingItem.contentUri,
                    candidateVideoId = candidate.id,
                    candidateContentUri = candidate.contentUri.toString()
                )
            }
        }.map { it.id }
        if (matchingItemIds.isEmpty()) return@withContext 0
        removeVideosFromPlaylist(matchingItemIds, fav.id)
    }

    override suspend fun removeVideoByIdOrUriFromPlaylist(
        playlistId: Long,
        videoId: Long?,
        contentUri: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val items = dbHelper.getItemsForPlaylist(playlistId)
        val normalizedUri = normalizeVideoUriKey(contentUri)
        val matches = items.filter { item ->
            isSamePlaylistVideo(
                existingVideoId = item.videoId,
                existingContentUri = item.contentUri,
                candidateVideoId = videoId ?: -1L,
                candidateContentUri = normalizedUri
            )
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
