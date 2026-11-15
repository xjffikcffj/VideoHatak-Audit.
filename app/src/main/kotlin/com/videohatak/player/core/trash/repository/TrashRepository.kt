package com.videohatak.player.core.trash.repository

import android.content.Context
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.trash.model.TrashItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

interface TrashRepository {
    val trashItems: StateFlow<List<TrashItem>>
    val trashedUris: StateFlow<Set<String>>

    suspend fun refresh()
    suspend fun moveToTrash(video: VideoItem): TrashItem?
    suspend fun restoreFromTrash(trashId: Long): TrashItem?
    suspend fun removeRecord(trashId: Long): Boolean
    suspend fun clearAllRecords()
}

class TrashRepositoryImpl(
    private val dbHelper: VideoHatakDatabaseHelper
) : TrashRepository {

    constructor(context: Context) : this(VideoHatakDatabaseHelper(context))

    private val _trashItems = MutableStateFlow<List<TrashItem>>(emptyList())
    override val trashItems: StateFlow<List<TrashItem>> = _trashItems.asStateFlow()

    private val _trashedUris = MutableStateFlow<Set<String>>(emptySet())
    override val trashedUris: StateFlow<Set<String>> = _trashedUris.asStateFlow()

    init {
        loadFromDbSync()
    }

    private fun loadFromDbSync() {
        val list = runCatching { dbHelper.getAllTrashItems() }.getOrDefault(emptyList())
        _trashItems.value = list
        _trashedUris.value = list.map { it.contentUri }.toSet()
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        loadFromDbSync()
    }

    override suspend fun moveToTrash(video: VideoItem): TrashItem? = withContext(Dispatchers.IO) {
        val candidate = TrashItem(
            id = 0L,
            videoId = video.id,
            title = video.title,
            displayName = video.displayName.ifBlank { video.safeTitle },
            durationMs = video.durationMs,
            sizeBytes = video.sizeBytes,
            mimeType = video.mimeType,
            bucketId = video.bucketId,
            bucketDisplayName = video.bucketDisplayName,
            width = video.width,
            height = video.height,
            dateAddedSec = video.dateAddedSec,
            contentUri = video.contentUri.toString(),
            filePath = video.path,
            trashedAtEpochMs = System.currentTimeMillis()
        )
        val insertedId = dbHelper.insertTrashItem(candidate)
        if (insertedId <= 0L) return@withContext null
        loadFromDbSync()
        _trashItems.value.firstOrNull { it.contentUri == candidate.contentUri } ?: candidate.copy(id = insertedId)
    }

    override suspend fun restoreFromTrash(trashId: Long): TrashItem? = withContext(Dispatchers.IO) {
        val existing = _trashItems.value.firstOrNull { it.id == trashId }
            ?: dbHelper.getAllTrashItems().firstOrNull { it.id == trashId }
            ?: return@withContext null
        val deletedRows = dbHelper.deleteTrashItemById(trashId)
        loadFromDbSync()
        if (deletedRows > 0) existing else null
    }

    override suspend fun removeRecord(trashId: Long): Boolean = withContext(Dispatchers.IO) {
        val rows = dbHelper.deleteTrashItemById(trashId)
        loadFromDbSync()
        rows > 0
    }

    override suspend fun clearAllRecords() = withContext(Dispatchers.IO) {
        dbHelper.clearAllTrashItems()
        loadFromDbSync()
    }
}
