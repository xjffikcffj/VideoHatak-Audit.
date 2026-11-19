package com.videohatak.player.core.trash.repository

import android.content.Context
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playlist.db.TrashDatabaseStore
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.trash.model.TrashItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TrashRepository {
    val trashItems: StateFlow<List<TrashItem>>
    val trashedUris: StateFlow<Set<String>>
    val trashError: StateFlow<String?>

    suspend fun refresh()
    suspend fun moveToTrash(video: VideoItem): TrashItem?
    suspend fun restoreFromTrash(trashId: Long): TrashItem?
    suspend fun removeRecord(trashId: Long): Boolean
    suspend fun clearAllRecords()
    fun clearError()
}

class TrashRepositoryImpl(
    private val dbStore: TrashDatabaseStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    preloadSync: Boolean = true
) : TrashRepository {

    constructor(
        context: Context,
        preloadSync: Boolean = false
    ) : this(
        dbStore = VideoHatakDatabaseHelper(context),
        ioDispatcher = Dispatchers.IO,
        preloadSync = preloadSync
    )

    private val mutex = Mutex()

    @Volatile
    private var hasLoadedInitial: Boolean = preloadSync

    private val _trashItems = MutableStateFlow<List<TrashItem>>(emptyList())
    override val trashItems: StateFlow<List<TrashItem>> = _trashItems.asStateFlow()

    private val _trashedUris = MutableStateFlow<Set<String>>(emptySet())
    override val trashedUris: StateFlow<Set<String>> = _trashedUris.asStateFlow()

    private val _trashError = MutableStateFlow<String?>(null)
    override val trashError: StateFlow<String?> = _trashError.asStateFlow()

    init {
        if (preloadSync) {
            loadFromDbSync()
        }
    }

    private fun ensureLoadedLocked() {
        if (!hasLoadedInitial) {
            loadFromDbSync()
        }
    }

    private fun loadFromDbSync() {
        try {
            val list = com.videohatak.player.core.diagnostics.PerformanceDiagnostics.measure(
                operation = "trash_db_read",
                details = { "syncInit=${!hasLoadedInitial}" }
            ) {
                dbStore.getAllTrashItems()
            }
            _trashItems.value = list
            _trashedUris.value = list.mapTo(HashSet(list.size.coerceAtLeast(1))) { it.contentUri }
            _trashError.value = null
            hasLoadedInitial = true
        } catch (e: Exception) {
            // Preserve existing in-memory state instead of silently presenting an empty Trash Bin
            _trashError.value = e.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "تعذر قراءة سجل سلة المحذوفات من قاعدة البيانات"
        }
    }

    override suspend fun refresh() = withContext(ioDispatcher) {
        mutex.withLock {
            loadFromDbSync()
        }
    }

    override fun clearError() {
        _trashError.value = null
    }

    override suspend fun moveToTrash(video: VideoItem): TrashItem? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            val uriString = video.contentUri.toString()
            if (uriString.isBlank()) {
                _trashError.value = "مسار الفيديو غير صالح للنقل إلى سلة المحذوفات"
                return@withLock null
            }
            val existing = _trashItems.value.firstOrNull { it.contentUri == uriString }
            if (existing != null) {
                return@withLock existing
            }

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
                contentUri = uriString,
                filePath = video.path,
                trashedAtEpochMs = System.currentTimeMillis()
            )
            return@withLock try {
                val insertedId = dbStore.insertTrashItem(candidate)
                if (insertedId <= 0L) {
                    _trashError.value = "تعذر حفظ الفيديو في سلة المحذوفات"
                    null
                } else {
                    _trashError.value = null
                    loadFromDbSync()
                    _trashItems.value.firstOrNull { it.contentUri == uriString }
                        ?: candidate.copy(id = insertedId)
                }
            } catch (e: Exception) {
                _trashError.value = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "فشل حفظ الفيديو في سلة المحذوفات"
                null
            }
        }
    }

    override suspend fun restoreFromTrash(trashId: Long): TrashItem? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            try {
                val existing = _trashItems.value.firstOrNull { it.id == trashId }
                    ?: dbStore.getAllTrashItems().firstOrNull { it.id == trashId }
                    ?: return@withLock null
                val deletedRows = dbStore.deleteTrashItemById(trashId)
                if (deletedRows > 0) {
                    _trashError.value = null
                    loadFromDbSync()
                    existing
                } else {
                    _trashError.value = "تعذر تحديث سجل سلة المحذوفات أثناء الاستعادة"
                    null
                }
            } catch (e: Exception) {
                _trashError.value = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "فشل استعادة السجل من سلة المحذوفات"
                null
            }
        }
    }

    override suspend fun removeRecord(trashId: Long): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            try {
                val rows = dbStore.deleteTrashItemById(trashId)
                _trashError.value = null
                loadFromDbSync()
                rows > 0
            } catch (e: Exception) {
                _trashError.value = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "تعذر إزالة السجل من سلة المحذوفات"
                false
            }
        }
    }

    override suspend fun clearAllRecords() = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                dbStore.clearAllTrashItems()
                _trashError.value = null
                loadFromDbSync()
            } catch (e: Exception) {
                _trashError.value = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "تعذر إفراغ سجل سلة المحذوفات"
            }
        }
    }
}
