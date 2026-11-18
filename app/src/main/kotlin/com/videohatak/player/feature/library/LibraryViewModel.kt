package com.videohatak.player.feature.library

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.data.repository.MediaExistenceResult
import com.videohatak.player.core.data.repository.PendingVideoFileOp
import com.videohatak.player.core.data.repository.VideoFileOperationResult
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.SortOrder
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.permission.VideoPermissionStatus
import com.videohatak.player.core.trash.model.TrashItem
import com.videohatak.player.core.trash.repository.TrashRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LibraryUiEvent {
    data class ShowMessage(val message: String) : LibraryUiEvent
    data class LaunchWritePermission(val intentSender: IntentSender) : LibraryUiEvent
    data class RequestLegacyWritePermission(val permission: String) : LibraryUiEvent
}

class LibraryViewModel(
    private val videoRepository: VideoRepository,
    private val trashRepository: TrashRepository? = null,
    private val scrollStore: LibraryScrollStore? = null
) : ViewModel() {

    private val _permissionStatus = MutableStateFlow<VideoPermissionStatus>(VideoPermissionStatus.NotRequested)
    private val _isLoading = MutableStateFlow(false)
    private val _rawVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _events = MutableSharedFlow<LibraryUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LibraryUiEvent> = _events.asSharedFlow()

    private var pendingFileOp: PendingVideoFileOp? = null
    private var pendingBulkTrashItems: MutableList<TrashItem> = mutableListOf()
    private var pendingBulkDeletedCount: Int = 0
    private var pendingBulkFailedCount: Int = 0

    val searchQuery = MutableStateFlow("")
    val sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val currentTab = MutableStateFlow(LibraryTab.VIDEOS)
    val selectedFolder = MutableStateFlow<VideoFolder?>(null)

    private val _videosScrollPosition = MutableStateFlow(LibraryScrollPosition())
    val videosScrollPosition: StateFlow<LibraryScrollPosition> = _videosScrollPosition.asStateFlow()

    private val _isVideosScrollLoaded = MutableStateFlow(scrollStore == null)
    val isVideosScrollLoaded: StateFlow<Boolean> = _isVideosScrollLoaded.asStateFlow()

    private val _foldersScrollPosition = MutableStateFlow(LibraryScrollPosition())
    val foldersScrollPosition: StateFlow<LibraryScrollPosition> = _foldersScrollPosition.asStateFlow()

    @Volatile
    private var hasLocalVideosScrollSave = false
    private val videosScrollSaveMutex = Mutex()

    init {
        if (scrollStore != null) {
            viewModelScope.launch {
                try {
                    val persisted = scrollStore.videosScrollPosition.first()
                    if (!hasLocalVideosScrollSave) {
                        _videosScrollPosition.value = persisted
                    }
                } finally {
                    _isVideosScrollLoaded.value = true
                }
            }
        }
    }

    val trashItems: StateFlow<List<TrashItem>> =
        trashRepository?.trashItems ?: MutableStateFlow(emptyList())

    val trashError: StateFlow<String?> =
        trashRepository?.trashError ?: MutableStateFlow(null)

    private val _trashExistenceMap = MutableStateFlow<Map<Long, MediaExistenceResult>>(emptyMap())
    val trashExistenceMap: StateFlow<Map<Long, MediaExistenceResult>> = _trashExistenceMap.asStateFlow()

    private val _missingTrashItemPendingRemoval = MutableStateFlow<TrashItem?>(null)
    val missingTrashItemPendingRemoval: StateFlow<TrashItem?> = _missingTrashItemPendingRemoval.asStateFlow()

    private val trashedUrisFlow: StateFlow<Set<String>> =
        trashRepository?.trashedUris ?: MutableStateFlow(emptySet())

    private data class FilterCriteria(
        val query: String,
        val sort: SortOrder,
        val folder: VideoFolder?,
        val trashedUris: Set<String>
    )

    private val filterCriteria = combine(
        searchQuery,
        sortOrder,
        selectedFolder,
        trashedUrisFlow
    ) { query, sort, folder, trashed ->
        FilterCriteria(query, sort, folder, trashed)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        _permissionStatus,
        _isLoading,
        _rawVideos,
        filterCriteria
    ) { status, loading, videos, criteria ->
        val visibleVideos = if (criteria.trashedUris.isEmpty()) {
            videos
        } else {
            videos.filterNot { it.contentUri.toString() in criteria.trashedUris }
        }
        val filtered = videoRepository.filterAndSort(
            videos = visibleVideos,
            query = criteria.query,
            sortOrder = criteria.sort.asVideoSortOrder,
            folderId = criteria.folder?.bucketId
        )
        val folders = videoRepository.groupIntoFolders(visibleVideos)

        LibraryUiState(
            permissionStatus = status,
            isLoading = loading,
            allVideos = visibleVideos,
            filteredVideos = filtered,
            folders = folders,
            errorMessage = _errorMessage.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(isLoading = true)
    )

    fun onPermissionStatusChanged(status: VideoPermissionStatus) {
        _permissionStatus.value = status
        if (PermissionHelper.hasAnyAccess(status)) {
            loadVideos()
        } else {
            _isLoading.value = false
        }
    }

    // Backward-compatible alias
    fun updatePermissionState(status: VideoPermissionStatus) = onPermissionStatusChanged(status)

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onSortOrderChanged(order: SortOrder) {
        sortOrder.value = order
    }

    fun onTabSelected(tab: LibraryTab) {
        currentTab.value = if (tab == LibraryTab.FAVORITES) LibraryTab.PLAYLISTS else tab
    }

    fun saveVideosScrollPosition(index: Int, offset: Int) {
        hasLocalVideosScrollSave = true
        val next = LibraryScrollPosition(
            firstVisibleItemIndex = index.coerceAtLeast(0),
            firstVisibleItemScrollOffset = offset.coerceAtLeast(0)
        )
        if (_videosScrollPosition.value != next) {
            _videosScrollPosition.value = next
        }
        if (scrollStore != null) {
            viewModelScope.launch {
                videosScrollSaveMutex.withLock {
                    scrollStore.saveVideosScrollPosition(_videosScrollPosition.value)
                }
            }
        }
    }

    fun saveFoldersScrollPosition(index: Int, offset: Int) {
        _foldersScrollPosition.value = LibraryScrollPosition(
            firstVisibleItemIndex = index.coerceAtLeast(0),
            firstVisibleItemScrollOffset = offset.coerceAtLeast(0)
        )
    }

    fun onFolderSelected(folder: VideoFolder) {
        selectedFolder.value = folder
    }

    fun clearFolderSelection() {
        selectedFolder.value = null
    }

    fun renameVideo(video: VideoItem, newTitle: String) {
        viewModelScope.launch {
            handleFileOpResult(
                result = videoRepository.renameVideo(video, newTitle),
                successMessage = "تم تغيير اسم الفيديو بنجاح"
            )
        }
    }

    fun moveVideoToFolder(video: VideoItem, targetRelativePath: String, targetFolderName: String) {
        viewModelScope.launch {
            handleFileOpResult(
                result = videoRepository.moveVideoToFolder(video, targetRelativePath, targetFolderName),
                successMessage = "تم نقل الفيديو إلى \"$targetFolderName\""
            )
        }
    }

    fun moveVideosToFolder(
        videos: List<VideoItem>,
        targetRelativePath: String,
        targetFolderName: String
    ) {
        val publicVideos = videos.filterNot {
            it.bucketId == "secure_vault" || it.contentUri.scheme.equals("vault", ignoreCase = true)
        }
        if (publicVideos.isEmpty()) return
        if (publicVideos.size == 1) {
            moveVideoToFolder(publicVideos.first(), targetRelativePath, targetFolderName)
            return
        }
        viewModelScope.launch {
            processBatchMove(
                PendingVideoFileOp.BatchMove(
                    remainingVideos = publicVideos,
                    targetRelativePath = targetRelativePath,
                    targetFolderName = targetFolderName
                )
            )
        }
    }

    private suspend fun processBatchMove(op: PendingVideoFileOp.BatchMove) {
        val remaining = op.remainingVideos.toMutableList()
        var moved = op.movedCount
        var failed = op.failedCount

        while (remaining.isNotEmpty()) {
            val video = remaining.removeAt(0)
            when (val res = videoRepository.moveVideoToFolder(video, op.targetRelativePath, op.targetFolderName)) {
                is VideoFileOperationResult.Success -> moved++
                is VideoFileOperationResult.PartialSuccess -> {
                    moved += res.succeededCount
                    failed += res.failedCount
                }
                is VideoFileOperationResult.RequiresPermission -> {
                    val resumeList = listOf(video) + remaining
                    pendingFileOp = PendingVideoFileOp.BatchMove(
                        remainingVideos = resumeList,
                        targetRelativePath = op.targetRelativePath,
                        targetFolderName = op.targetFolderName,
                        movedCount = moved,
                        failedCount = failed
                    )
                    _events.tryEmit(LibraryUiEvent.LaunchWritePermission(res.intentSender))
                    return
                }
                is VideoFileOperationResult.RequiresLegacyWritePermission -> {
                    val resumeList = listOf(video) + remaining
                    pendingFileOp = PendingVideoFileOp.BatchMove(
                        remainingVideos = resumeList,
                        targetRelativePath = op.targetRelativePath,
                        targetFolderName = op.targetFolderName,
                        movedCount = moved,
                        failedCount = failed
                    )
                    _events.tryEmit(LibraryUiEvent.RequestLegacyWritePermission(res.permission))
                    return
                }
                is VideoFileOperationResult.Error -> failed++
            }
        }

        loadVideos()
        val msg = when {
            moved > 0 && failed == 0 -> "تم نقل $moved فيديو إلى \"${op.targetFolderName}\""
            moved > 0 && failed > 0 -> "تم نقل $moved فيديو إلى \"${op.targetFolderName}\"، وتعذر نقل $failed"
            else -> "تعذر نقل الفيديوهات المحددة"
        }
        _events.tryEmit(LibraryUiEvent.ShowMessage(msg))
    }

    fun deleteMovedVaultOriginals(
        sourceUris: List<Uri>,
        encryptedCount: Int,
        encryptedFailedCount: Int = 0
    ) {
        if (encryptedCount <= 0 && encryptedFailedCount <= 0) return
        viewModelScope.launch {
            if (sourceUris.isEmpty()) {
                loadVideos()
                val msg = when {
                    encryptedFailedCount > 0 ->
                        "تمت إضافة $encryptedCount فيديو إلى الخزنة بنجاح، وتعذر نقل $encryptedFailedCount"
                    encryptedCount == 1 -> "تمت إضافة الفيديو إلى الخزنة بنجاح"
                    else -> "تمت إضافة $encryptedCount فيديو إلى الخزنة بنجاح"
                }
                _events.tryEmit(LibraryUiEvent.ShowMessage(msg))
                return@launch
            }
            when (val res = videoRepository.deleteVaultOriginals(sourceUris, encryptedCount)) {
                is VideoFileOperationResult.Success -> {
                    loadVideos()
                    val msg = when {
                        encryptedFailedCount > 0 ->
                            "تمت إضافة $encryptedCount فيديو إلى الخزنة بنجاح، وتعذر نقل $encryptedFailedCount"
                        encryptedCount == 1 -> "تمت إضافة الفيديو إلى الخزنة بنجاح"
                        else -> "تمت إضافة $encryptedCount فيديو إلى الخزنة بنجاح"
                    }
                    _events.tryEmit(LibraryUiEvent.ShowMessage(msg))
                }
                is VideoFileOperationResult.PartialSuccess -> {
                    loadVideos()
                    val msg = buildVaultPartialCleanupMessage(
                        encryptedCount = encryptedCount,
                        encryptedFailedCount = encryptedFailedCount,
                        removedPublicCount = res.succeededCount,
                        failedPublicCount = res.failedCount
                    )
                    _events.tryEmit(LibraryUiEvent.ShowMessage(msg))
                }
                is VideoFileOperationResult.RequiresPermission -> {
                    val op = res.pendingOp
                    pendingFileOp = if (op is PendingVideoFileOp.DeleteVaultOriginals) {
                        op.copy(encryptedFailedCount = encryptedFailedCount)
                    } else op
                    _events.tryEmit(LibraryUiEvent.LaunchWritePermission(res.intentSender))
                }
                is VideoFileOperationResult.RequiresLegacyWritePermission -> {
                    val op = res.pendingOp
                    pendingFileOp = if (op is PendingVideoFileOp.DeleteVaultOriginals) {
                        op.copy(encryptedFailedCount = encryptedFailedCount)
                    } else op
                    _events.tryEmit(LibraryUiEvent.RequestLegacyWritePermission(res.permission))
                }
                is VideoFileOperationResult.Error -> {
                    loadVideos()
                    val encFailSuffix = if (encryptedFailedCount > 0) "، وتعذر تشفير $encryptedFailedCount" else ""
                    _events.tryEmit(
                        LibraryUiEvent.ShowMessage(
                            "تمت إضافة النسخة المشفرة إلى الخزنة ($encryptedCount) بنجاح$encFailSuffix، لكن ${res.message}"
                        )
                    )
                }
            }
        }
    }

    private fun buildVaultPartialCleanupMessage(
        encryptedCount: Int,
        encryptedFailedCount: Int,
        removedPublicCount: Int,
        failedPublicCount: Int
    ): String {
        val encFailPart = if (encryptedFailedCount > 0) " وتعذر نقل $encryptedFailedCount،" else ""
        return "تمت إضافة $encryptedCount إلى الخزنة بنجاح،$encFailPart وأُزيلت $removedPublicCount نسخة عامة، وبقيت $failedPublicCount نسخة عامة لم تُزل"
    }

    fun moveToTrash(video: VideoItem) {
        viewModelScope.launch {
            if (video.bucketId == "secure_vault" || video.contentUri.scheme.equals("vault", ignoreCase = true)) {
                _events.tryEmit(LibraryUiEvent.ShowMessage("لا يمكن نقل مقاطع الخزنة الخاصة إلى سلة المحذوفات"))
                return@launch
            }
            if (video.contentUri.toString() in trashedUrisFlow.value) {
                _events.tryEmit(LibraryUiEvent.ShowMessage("\"${video.safeTitle}\" موجود بالفعل في سلة المحذوفات"))
                return@launch
            }
            val saved = trashRepository?.moveToTrash(video)
            if (saved != null) {
                _trashExistenceMap.value = _trashExistenceMap.value + (saved.id to MediaExistenceResult.Exists)
                _events.tryEmit(LibraryUiEvent.ShowMessage("تم نقل \"${video.safeTitle}\" إلى سلة المحذوفات"))
            } else {
                val err = trashRepository?.trashError?.value ?: "تعذر نقل الفيديو إلى سلة المحذوفات"
                _events.tryEmit(LibraryUiEvent.ShowMessage(err))
            }
        }
    }

    fun trashVideos(videos: List<VideoItem>) {
        if (videos.isEmpty()) return
        if (videos.size == 1) {
            moveToTrash(videos.first())
            return
        }
        viewModelScope.launch {
            var moved = 0
            var failed = 0
            val updates = LinkedHashMap<Long, MediaExistenceResult>()
            val alreadyTrashed = trashedUrisFlow.value
            for (video in videos) {
                if (video.bucketId == "secure_vault" || video.contentUri.scheme.equals("vault", ignoreCase = true)) {
                    failed++
                    continue
                }
                if (video.contentUri.toString() in alreadyTrashed) {
                    continue
                }
                val saved = trashRepository?.moveToTrash(video)
                if (saved != null) {
                    updates[saved.id] = MediaExistenceResult.Exists
                    moved++
                } else {
                    failed++
                }
            }
            if (updates.isNotEmpty()) {
                _trashExistenceMap.value = _trashExistenceMap.value + updates
            }
            val msg = when {
                moved > 0 && failed == 0 -> "تم نقل $moved فيديو إلى سلة المحذوفات"
                moved > 0 && failed > 0 -> "تم نقل $moved فيديو إلى السلة، وتعذر نقل $failed"
                failed > 0 -> "تعذر نقل الفيديوهات المحددة إلى سلة المحذوفات"
                else -> "الفيديوهات المحددة موجودة بالفعل في سلة المحذوفات"
            }
            _events.tryEmit(LibraryUiEvent.ShowMessage(msg))
        }
    }

    fun refreshTrashStatus() {
        viewModelScope.launch {
            trashRepository?.refresh()
            val items = trashItems.value
            val statusMap = LinkedHashMap<Long, MediaExistenceResult>(items.size)
            for (item in items) {
                statusMap[item.id] = videoRepository.checkMediaExistence(item.toVideoItem())
            }
            _trashExistenceMap.value = statusMap
        }
    }

    fun dismissTrashError() {
        trashRepository?.clearError()
    }

    fun restoreFromTrash(trashItem: TrashItem) {
        viewModelScope.launch {
            val label = trashItem.title.ifBlank { trashItem.displayName }
            when (val existence = videoRepository.checkMediaExistence(trashItem.toVideoItem())) {
                MediaExistenceResult.Exists -> {
                    _trashExistenceMap.value = _trashExistenceMap.value + (trashItem.id to MediaExistenceResult.Exists)
                    val restored = trashRepository?.restoreFromTrash(trashItem.id)
                    if (restored != null) {
                        loadVideos()
                        _events.tryEmit(LibraryUiEvent.ShowMessage("تمت استعادة \"$label\""))
                    } else {
                        val err = trashRepository?.trashError?.value ?: "تعذر استعادة \"$label\" من سلة المحذوفات"
                        _events.tryEmit(LibraryUiEvent.ShowMessage(err))
                    }
                }
                MediaExistenceResult.Missing -> {
                    _trashExistenceMap.value = _trashExistenceMap.value + (trashItem.id to MediaExistenceResult.Missing)
                    _missingTrashItemPendingRemoval.value = trashItem
                }
                is MediaExistenceResult.Unverifiable -> {
                    _trashExistenceMap.value = _trashExistenceMap.value + (trashItem.id to existence)
                    _events.tryEmit(
                        LibraryUiEvent.ShowMessage(
                            "تعذر التحقق من وجود \"$label\": ${existence.reason} — تم الإبقاء على السجل في السلة"
                        )
                    )
                }
            }
        }
    }

    fun confirmRemoveMissingTrashRecord(trashItem: TrashItem) {
        viewModelScope.launch {
            _missingTrashItemPendingRemoval.value = null
            val removed = trashRepository?.removeRecord(trashItem.id) == true
            val label = trashItem.title.ifBlank { trashItem.displayName }
            if (removed) {
                _trashExistenceMap.value = _trashExistenceMap.value - trashItem.id
                _events.tryEmit(LibraryUiEvent.ShowMessage("تمت إزالة سجل \"$label\" من سلة المحذوفات"))
            } else {
                val err = trashRepository?.trashError?.value ?: "تعذر إزالة السجل من سلة المحذوفات"
                _events.tryEmit(LibraryUiEvent.ShowMessage(err))
            }
        }
    }

    fun dismissMissingTrashRemoval() {
        _missingTrashItemPendingRemoval.value = null
    }

    fun deleteTrashItemPermanently(trashItem: TrashItem) {
        viewModelScope.launch {
            pendingBulkTrashItems.clear()
            pendingBulkDeletedCount = 0
            pendingBulkFailedCount = 0
            val label = trashItem.title.ifBlank { trashItem.displayName }
            when (val existence = videoRepository.checkMediaExistence(trashItem.toVideoItem())) {
                is MediaExistenceResult.Unverifiable -> {
                    _trashExistenceMap.value = _trashExistenceMap.value + (trashItem.id to existence)
                    _events.tryEmit(
                        LibraryUiEvent.ShowMessage("تعذر الحذف النهائي لعدم إمكانية التحقق من الملف: ${existence.reason}")
                    )
                    return@launch
                }
                MediaExistenceResult.Missing -> {
                    _trashExistenceMap.value = _trashExistenceMap.value + (trashItem.id to MediaExistenceResult.Missing)
                    val removed = trashRepository?.removeRecord(trashItem.id) == true
                    if (removed) {
                        _trashExistenceMap.value = _trashExistenceMap.value - trashItem.id
                        _events.tryEmit(LibraryUiEvent.ShowMessage("الملف مفقود بالفعل على الجهاز — تمت إزالة السجل من السلة"))
                    } else {
                        val err = trashRepository?.trashError?.value
                            ?: "الملف مفقود على الجهاز، لكن تعذر إزالة سجل \"$label\" من سلة المحذوفات"
                        _events.tryEmit(LibraryUiEvent.ShowMessage(err))
                    }
                    return@launch
                }
                MediaExistenceResult.Exists -> Unit
            }
            val result = videoRepository.deleteVideoPermanently(trashItem.toVideoItem(), trashItem.id)
            if (result is VideoFileOperationResult.Success) {
                val removed = trashRepository?.removeRecord(trashItem.id) == true
                if (removed) {
                    _trashExistenceMap.value = _trashExistenceMap.value - trashItem.id
                    handleFileOpResult(
                        result = result,
                        successMessage = "تم حذف الفيديو نهائيًا"
                    )
                } else {
                    loadVideos()
                    val err = trashRepository?.trashError?.value
                        ?: "تم حذف الملف، لكن تعذر إزالة سجل \"$label\" من سلة المحذوفات"
                    _events.tryEmit(LibraryUiEvent.ShowMessage(err))
                }
            } else {
                handleFileOpResult(
                    result = result,
                    successMessage = "تم حذف الفيديو نهائيًا"
                )
            }
        }
    }

    fun emptyTrashBin() {
        viewModelScope.launch {
            val items = trashItems.value
            pendingBulkTrashItems = items.toMutableList()
            pendingBulkDeletedCount = 0
            pendingBulkFailedCount = 0
            processNextBulkDeleteItem()
        }
    }

    private suspend fun processNextBulkDeleteItem() {
        while (pendingBulkTrashItems.isNotEmpty()) {
            val item = pendingBulkTrashItems.removeAt(0)
            val label = item.title.ifBlank { item.displayName }
            when (val existence = videoRepository.checkMediaExistence(item.toVideoItem())) {
                is MediaExistenceResult.Unverifiable -> {
                    _trashExistenceMap.value = _trashExistenceMap.value + (item.id to existence)
                    pendingBulkFailedCount++
                    _events.tryEmit(LibraryUiEvent.ShowMessage("تم تجاوز \"$label\": ${existence.reason}"))
                    continue
                }
                MediaExistenceResult.Missing -> {
                    _trashExistenceMap.value = _trashExistenceMap.value + (item.id to MediaExistenceResult.Missing)
                    val removed = trashRepository?.removeRecord(item.id) == true
                    if (removed) {
                        _trashExistenceMap.value = _trashExistenceMap.value - item.id
                        pendingBulkDeletedCount++
                    } else {
                        pendingBulkFailedCount++
                        val err = trashRepository?.trashError?.value
                            ?: "الملف مفقود، وتعذر إزالة سجل \"$label\" من سلة المحذوفات"
                        _events.tryEmit(LibraryUiEvent.ShowMessage(err))
                    }
                    continue
                }
                MediaExistenceResult.Exists -> Unit
            }
            val res = videoRepository.deleteVideoPermanently(item.toVideoItem(), item.id)
            when (res) {
                is VideoFileOperationResult.Success -> {
                    val removed = trashRepository?.removeRecord(item.id) == true
                    if (removed) {
                        _trashExistenceMap.value = _trashExistenceMap.value - item.id
                        pendingBulkDeletedCount++
                    } else {
                        pendingBulkFailedCount++
                        val err = trashRepository?.trashError?.value
                            ?: "تم حذف \"$label\"، لكن تعذر إزالة سجله من سلة المحذوفات"
                        _events.tryEmit(LibraryUiEvent.ShowMessage(err))
                    }
                }
                is VideoFileOperationResult.PartialSuccess -> {
                    pendingBulkFailedCount++
                    _events.tryEmit(LibraryUiEvent.ShowMessage(res.message))
                }
                is VideoFileOperationResult.RequiresPermission -> {
                    pendingFileOp = res.pendingOp
                    _events.tryEmit(LibraryUiEvent.LaunchWritePermission(res.intentSender))
                    return
                }
                is VideoFileOperationResult.RequiresLegacyWritePermission -> {
                    pendingFileOp = res.pendingOp
                    _events.tryEmit(LibraryUiEvent.RequestLegacyWritePermission(res.permission))
                    return
                }
                is VideoFileOperationResult.Error -> {
                    pendingBulkFailedCount++
                    _events.tryEmit(LibraryUiEvent.ShowMessage(res.message))
                }
            }
        }
        val count = pendingBulkDeletedCount
        val failed = pendingBulkFailedCount
        pendingBulkDeletedCount = 0
        pendingBulkFailedCount = 0
        loadVideos()
        val summaryMessage = when {
            count > 0 && failed == 0 -> "تم حذف $count فيديو من سلة المحذوفات"
            count > 0 && failed > 0 -> "تم حذف $count فيديو من السلة، وتعذر إكمال $failed عنصر"
            failed > 0 -> "تعذر إفراغ العناصر المتبقية في سلة المحذوفات ($failed عنصر)"
            else -> "سلة المحذوفات فارغة"
        }
        _events.tryEmit(LibraryUiEvent.ShowMessage(summaryMessage))
    }

    fun onWritePermissionGranted(granted: Boolean) {
        val op = pendingFileOp
        pendingFileOp = null
        if (!granted || op == null) {
            pendingBulkTrashItems.clear()
            pendingBulkDeletedCount = 0
            pendingBulkFailedCount = 0
            if (!granted) {
                when (op) {
                    is PendingVideoFileOp.DeleteVaultOriginals -> {
                        loadVideos()
                        val remainingCount = op.pendingUris.size + op.failedCount
                        val msg = if (op.deletedSoFar > 0) {
                            "تمت إضافة النسخة المشفرة إلى الخزنة (${op.encryptedCount}) بنجاح، وأُزيلت ${op.deletedSoFar} نسخة عامة، وبقيت $remainingCount نسخة عامة لعدم منح الإذن"
                        } else {
                            "تمت إضافة النسخة المشفرة إلى الخزنة (${op.encryptedCount}) بنجاح، وبقيت النسخة العامة في المعرض لعدم منح إذن إزالتها"
                        }
                        _events.tryEmit(LibraryUiEvent.ShowMessage(msg))
                    }
                    is PendingVideoFileOp.BatchMove -> {
                        loadVideos()
                        val msg = if (op.movedCount > 0) {
                            "تم نقل ${op.movedCount} فيديو فقط، ولم يتم منح إذن لبقية الملفات"
                        } else {
                            "لم يتم منح صلاحية نقل الملفات المحددة"
                        }
                        _events.tryEmit(LibraryUiEvent.ShowMessage(msg))
                    }
                    else -> {
                        _events.tryEmit(LibraryUiEvent.ShowMessage("لم يتم منح صلاحية تعديل الملف"))
                    }
                }
            }
            return
        }
        viewModelScope.launch {
            when (op) {
                is PendingVideoFileOp.Rename -> handleFileOpResult(
                    result = videoRepository.renameVideo(op.video, op.newTitle),
                    successMessage = "تم تغيير اسم الفيديو بنجاح"
                )
                is PendingVideoFileOp.Move -> handleFileOpResult(
                    result = videoRepository.moveVideoToFolder(op.video, op.targetRelativePath, op.targetFolderName),
                    successMessage = "تم نقل الفيديو إلى \"${op.targetFolderName}\""
                )
                is PendingVideoFileOp.BatchMove -> {
                    processBatchMove(op)
                }
                is PendingVideoFileOp.DeleteVaultOriginals -> {
                    val res = videoRepository.completeVaultOriginalsDeleteAfterPermission(op)
                    when (res) {
                        is VideoFileOperationResult.Success -> {
                            loadVideos()
                            val msg = when {
                                op.encryptedFailedCount > 0 ->
                                    "تمت إضافة ${op.encryptedCount} فيديو إلى الخزنة بنجاح، وتعذر نقل ${op.encryptedFailedCount}"
                                op.encryptedCount == 1 -> "تمت إضافة الفيديو إلى الخزنة بنجاح"
                                else -> "تمت إضافة ${op.encryptedCount} فيديو إلى الخزنة بنجاح"
                            }
                            _events.tryEmit(LibraryUiEvent.ShowMessage(msg))
                        }
                        is VideoFileOperationResult.PartialSuccess -> {
                            loadVideos()
                            _events.tryEmit(
                                LibraryUiEvent.ShowMessage(
                                    buildVaultPartialCleanupMessage(
                                        encryptedCount = op.encryptedCount,
                                        encryptedFailedCount = op.encryptedFailedCount,
                                        removedPublicCount = res.succeededCount,
                                        failedPublicCount = res.failedCount
                                    )
                                )
                            )
                        }
                        is VideoFileOperationResult.RequiresPermission -> {
                            val nextOp = res.pendingOp
                            pendingFileOp = if (nextOp is PendingVideoFileOp.DeleteVaultOriginals) {
                                nextOp.copy(encryptedFailedCount = op.encryptedFailedCount)
                            } else nextOp
                            _events.tryEmit(LibraryUiEvent.LaunchWritePermission(res.intentSender))
                        }
                        is VideoFileOperationResult.RequiresLegacyWritePermission -> {
                            val nextOp = res.pendingOp
                            pendingFileOp = if (nextOp is PendingVideoFileOp.DeleteVaultOriginals) {
                                nextOp.copy(encryptedFailedCount = op.encryptedFailedCount)
                            } else nextOp
                            _events.tryEmit(LibraryUiEvent.RequestLegacyWritePermission(res.permission))
                        }
                        is VideoFileOperationResult.Error -> {
                            loadVideos()
                            val encFailSuffix = if (op.encryptedFailedCount > 0) "، وتعذر تشفير ${op.encryptedFailedCount}" else ""
                            _events.tryEmit(
                                LibraryUiEvent.ShowMessage(
                                    "تمت إضافة النسخة المشفرة (${op.encryptedCount}) إلى الخزنة بنجاح$encFailSuffix، لكن ${res.message}"
                                )
                            )
                        }
                    }
                }
                is PendingVideoFileOp.DeletePermanent -> {
                    val verifyResult = videoRepository.completePermanentDeleteAfterPermission(op.video, op.trashItemId)
                    if (verifyResult is VideoFileOperationResult.Success) {
                        val trashId = op.trashItemId
                        val removed = if (trashId != null) {
                            trashRepository?.removeRecord(trashId) == true
                        } else {
                            true
                        }
                        if (removed && trashId != null) {
                            _trashExistenceMap.value = _trashExistenceMap.value - trashId
                        }
                        if (pendingBulkTrashItems.isNotEmpty()) {
                            if (removed) {
                                pendingBulkDeletedCount++
                            } else {
                                pendingBulkFailedCount++
                                val err = trashRepository?.trashError?.value
                                    ?: "تعذر إزالة السجل من سلة المحذوفات"
                                _events.tryEmit(LibraryUiEvent.ShowMessage(err))
                            }
                            processNextBulkDeleteItem()
                        } else {
                            loadVideos()
                            if (removed) {
                                _events.tryEmit(LibraryUiEvent.ShowMessage("تم حذف الفيديو نهائيًا"))
                            } else {
                                val err = trashRepository?.trashError?.value
                                    ?: "تم حذف الملف، لكن تعذر إزالة السجل من سلة المحذوفات"
                                _events.tryEmit(LibraryUiEvent.ShowMessage(err))
                            }
                        }
                    } else {
                        pendingBulkTrashItems.clear()
                        pendingBulkDeletedCount = 0
                        pendingBulkFailedCount = 0
                        handleFileOpResult(
                            result = verifyResult,
                            successMessage = "تم حذف الفيديو نهائيًا"
                        )
                    }
                }
            }
        }
    }

    private fun handleFileOpResult(result: VideoFileOperationResult, successMessage: String) {
        when (result) {
            is VideoFileOperationResult.Success -> {
                loadVideos()
                _events.tryEmit(LibraryUiEvent.ShowMessage(successMessage))
            }
            is VideoFileOperationResult.PartialSuccess -> {
                loadVideos()
                _events.tryEmit(LibraryUiEvent.ShowMessage(result.message))
            }
            is VideoFileOperationResult.RequiresPermission -> {
                pendingFileOp = result.pendingOp
                _events.tryEmit(LibraryUiEvent.LaunchWritePermission(result.intentSender))
            }
            is VideoFileOperationResult.RequiresLegacyWritePermission -> {
                pendingFileOp = result.pendingOp
                _events.tryEmit(LibraryUiEvent.RequestLegacyWritePermission(result.permission))
            }
            is VideoFileOperationResult.Error -> {
                _events.tryEmit(LibraryUiEvent.ShowMessage(result.message))
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            trashRepository?.refresh()
        }
        if (PermissionHelper.hasAnyAccess(_permissionStatus.value)) {
            loadVideos()
        }
    }

    private fun loadVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                trashRepository?.refresh()
                val videos = videoRepository.getVideos()
                _rawVideos.value = videos
                val visible = videos.filterNot { it.contentUri.toString() in trashedUrisFlow.value }
                val activeFolder = selectedFolder.value
                if (activeFolder != null) {
                    selectedFolder.value = videoRepository.groupIntoFolders(visible)
                        .firstOrNull { it.bucketId == activeFolder.bucketId }
                }
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "فشل في قراءة الفيديوهات"
            } finally {
                _isLoading.value = false
            }
        }
    }

    companion object {
        fun provideFactory(
            videoRepository: VideoRepository,
            trashRepository: TrashRepository? = null,
            scrollStore: LibraryScrollStore? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(videoRepository, trashRepository, scrollStore) as T
                }
            }
    }
}
