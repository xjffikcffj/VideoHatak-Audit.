package com.videohatak.player.feature.library

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.data.repository.PendingVideoFileOp
import com.videohatak.player.core.data.repository.VideoFileOperationResult
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.SortOrder
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.permission.VideoPermissionStatus
import com.videohatak.player.core.trash.model.TrashItem
import com.videohatak.player.core.trash.model.toVideoItem
import com.videohatak.player.core.trash.repository.TrashRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryUiEvent {
    data class ShowMessage(val message: String) : LibraryUiEvent
    data class LaunchWritePermission(val intentSender: IntentSender) : LibraryUiEvent
    data class RequestLegacyWritePermission(val permission: String) : LibraryUiEvent
}

class LibraryViewModel(
    private val videoRepository: VideoRepository,
    private val trashRepository: TrashRepository? = null
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

    val searchQuery = MutableStateFlow("")
    val sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val currentTab = MutableStateFlow(LibraryTab.VIDEOS)
    val selectedFolder = MutableStateFlow<VideoFolder?>(null)

    val trashItems: StateFlow<List<TrashItem>> =
        trashRepository?.trashItems ?: MutableStateFlow(emptyList())

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
        currentTab.value = tab
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

    fun moveToTrash(video: VideoItem) {
        viewModelScope.launch {
            val saved = trashRepository?.moveToTrash(video)
            if (saved != null) {
                _events.tryEmit(LibraryUiEvent.ShowMessage("تم نقل \"${video.safeTitle}\" إلى سلة المحذوفات"))
            } else {
                _events.tryEmit(LibraryUiEvent.ShowMessage("تعذر نقل الفيديو إلى سلة المحذوفات"))
            }
        }
    }

    fun restoreFromTrash(trashItem: TrashItem) {
        viewModelScope.launch {
            val mediaExists = videoRepository.doesMediaExist(trashItem.toVideoItem())
            val restored = trashRepository?.restoreFromTrash(trashItem.id)
            if (restored != null) {
                loadVideos()
                val label = trashItem.title.ifBlank { trashItem.displayName }
                if (mediaExists) {
                    _events.tryEmit(LibraryUiEvent.ShowMessage("تمت استعادة \"$label\""))
                } else {
                    _events.tryEmit(LibraryUiEvent.ShowMessage("الملف \"$label\" لم يعد موجودًا على الجهاز؛ تمت إزالة السجل من السلة"))
                }
            }
        }
    }

    fun deleteTrashItemPermanently(trashItem: TrashItem) {
        viewModelScope.launch {
            pendingBulkTrashItems.clear()
            pendingBulkDeletedCount = 0
            val result = videoRepository.deleteVideoPermanently(trashItem.toVideoItem(), trashItem.id)
            if (result is VideoFileOperationResult.Success) {
                trashRepository?.removeRecord(trashItem.id)
            }
            handleFileOpResult(
                result = result,
                successMessage = "تم حذف الفيديو نهائيًا"
            )
        }
    }

    fun emptyTrashBin() {
        viewModelScope.launch {
            val items = trashItems.value
            pendingBulkTrashItems = items.toMutableList()
            pendingBulkDeletedCount = 0
            processNextBulkDeleteItem()
        }
    }

    private suspend fun processNextBulkDeleteItem() {
        while (pendingBulkTrashItems.isNotEmpty()) {
            val item = pendingBulkTrashItems.removeAt(0)
            val res = videoRepository.deleteVideoPermanently(item.toVideoItem(), item.id)
            when (res) {
                is VideoFileOperationResult.Success -> {
                    trashRepository?.removeRecord(item.id)
                    pendingBulkDeletedCount++
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
                    _events.tryEmit(LibraryUiEvent.ShowMessage(res.message))
                }
            }
        }
        val count = pendingBulkDeletedCount
        pendingBulkDeletedCount = 0
        loadVideos()
        _events.tryEmit(LibraryUiEvent.ShowMessage("تم حذف $count فيديو من سلة المحذوفات"))
    }

    fun onWritePermissionGranted(granted: Boolean) {
        val op = pendingFileOp
        pendingFileOp = null
        if (!granted || op == null) {
            pendingBulkTrashItems.clear()
            pendingBulkDeletedCount = 0
            if (!granted) {
                _events.tryEmit(LibraryUiEvent.ShowMessage("لم يتم منح صلاحية تعديل الملف"))
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
                is PendingVideoFileOp.DeletePermanent -> {
                    val verifyResult = videoRepository.completePermanentDeleteAfterPermission(op.video, op.trashItemId)
                    if (verifyResult is VideoFileOperationResult.Success) {
                        op.trashItemId?.let { trashRepository?.removeRecord(it) }
                        if (pendingBulkTrashItems.isNotEmpty()) {
                            pendingBulkDeletedCount++
                            processNextBulkDeleteItem()
                        } else {
                            loadVideos()
                            _events.tryEmit(LibraryUiEvent.ShowMessage("تم حذف الفيديو نهائيًا"))
                        }
                    } else {
                        pendingBulkTrashItems.clear()
                        pendingBulkDeletedCount = 0
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
            trashRepository: TrashRepository? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(videoRepository, trashRepository) as T
                }
            }
    }
}
