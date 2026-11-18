package com.videohatak.player.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.data.repository.MediaExistenceResult
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.model.SortOrder
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.permission.VideoPermissionStatus
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import com.videohatak.player.core.trash.model.TrashItem
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.feature.library.components.FolderCard
import com.videohatak.player.feature.library.components.MoveVideoFolderDialog
import com.videohatak.player.feature.library.components.PartialAccessBanner
import com.videohatak.player.feature.library.components.PermissionCard
import com.videohatak.player.feature.library.components.RenameVideoDialog
import com.videohatak.player.feature.library.components.SortBottomSheet
import com.videohatak.player.feature.library.components.VideoCard
import com.videohatak.player.feature.library.components.VideoInfoDialog
import com.videohatak.player.feature.playlist.components.AddToPlaylistDialog
import com.videohatak.player.feature.playlist.components.CreatePlaylistDialog
import com.videohatak.player.feature.playlist.components.RenamePlaylistDialog
import com.videohatak.player.feature.settings.BackupImportConfirmDialog
import com.videohatak.player.feature.settings.SettingsScreen
import com.videohatak.player.feature.trash.TrashScreen
import com.videohatak.player.feature.vault.VaultImportState
import com.videohatak.player.feature.vault.VaultRestoreState
import com.videohatak.player.feature.vault.VaultScreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    sortOrder: SortOrder,
    onSortOrderChanged: (SortOrder) -> Unit,
    currentTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    selectedFolder: VideoFolder?,
    onFolderSelected: (VideoFolder) -> Unit,
    clearFolderSelection: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onVideoClick: (VideoItem, List<VideoItem>) -> Unit,
    onRenameVideo: (VideoItem, String) -> Unit,
    onMoveVideoToFolder: (VideoItem, String, String) -> Unit,
    onAddVideoToVault: (VideoItem) -> Unit,
    onMoveVideoToTrash: (VideoItem) -> Unit = {},
    // Bulk operations
    onMoveVideosToFolder: (List<VideoItem>, String, String) -> Unit = { list, r, n ->
        list.forEach { onMoveVideoToFolder(it, r, n) }
    },
    onAddVideosToVault: (List<VideoItem>) -> Unit = { list ->
        list.forEach(onAddVideoToVault)
    },
    onTrashVideos: (List<VideoItem>) -> Unit = { list ->
        list.forEach(onMoveVideoToTrash)
    },
    // Trash Bin state & callbacks
    trashItems: List<TrashItem> = emptyList(),
    trashError: String? = null,
    trashExistenceMap: Map<Long, MediaExistenceResult> = emptyMap(),
    missingTrashItemPendingRemoval: TrashItem? = null,
    onRefreshTrashStatus: () -> Unit = {},
    onDismissTrashError: () -> Unit = {},
    onRestoreFromTrash: (TrashItem) -> Unit = {},
    onConfirmRemoveMissingTrashRecord: (TrashItem) -> Unit = {},
    onDismissMissingTrashRemoval: () -> Unit = {},
    onPermanentDeleteTrashItem: (TrashItem) -> Unit = {},
    onEmptyTrashBin: () -> Unit = {},
    // Settings state & callbacks
    selectedAppTheme: AppThemeId,
    persistedAppTheme: AppThemeId = selectedAppTheme,
    isThemePreviewActive: Boolean = false,
    themeMode: ThemeMode,
    playbackMode: PlaybackMode,
    resumeMode: ResumeMode,
    onSelectAppTheme: (AppThemeId) -> Unit,
    onApplyThemePreview: () -> Unit = {},
    onCancelThemePreview: () -> Unit = {},
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSelectPlaybackMode: (PlaybackMode) -> Unit,
    onSelectResumeMode: (ResumeMode) -> Unit,
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    // Playlist & Favorites Callbacks
    playlists: List<Playlist>,
    selectedPlaylistWithItems: Pair<Playlist, List<PlaylistItem>>?,
    onSelectPlaylist: (Playlist?) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRemovePlaylistItem: (Long, Long) -> Unit,
    onRemovePlaylistItems: (Set<Long>, Long) -> Unit = { ids, pid ->
        ids.forEach { onRemovePlaylistItem(it, pid) }
    },
    onMovePlaylistItemUp: (Int) -> Unit,
    onMovePlaylistItemDown: (Int) -> Unit,
    onPlayPlaylist: (Long, Long?, Boolean) -> Unit,
    onAddToPlaylists: (VideoItem, List<Long>) -> Unit,
    onAddVideosToPlaylists: (List<VideoItem>, List<Long>) -> Unit = { list, ids ->
        list.forEach { onAddToPlaylists(it, ids) }
    },
    favoritesPlaylistId: Long? = null,
    favoriteVideos: List<VideoItem> = emptyList(),
    favoriteVideoIds: Set<Long> = emptySet(),
    favoriteVideoUris: Set<String> = emptySet(),
    onToggleFavorite: (VideoItem) -> Unit = {},
    onAddVideosToFavorites: (List<VideoItem>) -> Unit = {},
    onRemoveVideosFromFavorites: (List<VideoItem>) -> Unit = {},
    // Vault Callbacks & State
    vaultIsUnlocked: Boolean,
    vaultVideos: List<VaultVideo>,
    vaultTotalCount: Int = vaultVideos.size,
    vaultImportState: VaultImportState,
    vaultBiometricError: String?,
    vaultError: String? = null,
    vaultHasPinConfigured: Boolean = false,
    vaultCanUseBiometrics: Boolean = true,
    vaultAutoLockTimeout: VaultAutoLockTimeout = VaultAutoLockTimeout.IMMEDIATE,
    onVaultAuthenticate: () -> Unit,
    onVaultUnlockWithPin: (String) -> Unit = {},
    onVaultSetupInitialPin: (String, (Boolean, String) -> Unit) -> Unit = { _, _ -> },
    onVaultChangePin: (String, String, (Boolean, String) -> Unit) -> Unit = { _, _, _ -> },
    onVaultSetPin: (String) -> Unit = {},
    onVaultRemovePin: () -> Unit = {},
    onVaultSelectAutoLockTimeout: (VaultAutoLockTimeout) -> Unit = {},
    onVaultLock: () -> Unit,
    onVaultImportRequest: () -> Unit,
    onVaultCancelImport: () -> Unit,
    onVaultDismissImportState: () -> Unit,
    onVaultDismissError: () -> Unit = {},
    onVaultVideoClick: (VaultVideo) -> Unit,
    onVaultRenameVideo: (Long, String) -> Unit,
    vaultRestoreState: VaultRestoreState = VaultRestoreState.Idle,
    onVaultRestoreVideo: (Long) -> Unit = {},
    onVaultRestoreVideos: (Set<Long>) -> Unit = { ids -> ids.forEach(onVaultRestoreVideo) },
    onVaultDeleteVideo: (Long) -> Unit,
    onVaultDeleteVideos: (Set<Long>) -> Unit = { ids -> ids.forEach(onVaultDeleteVideo) },
    onVaultUserInteraction: () -> Unit = {},
    seekIntervalSeconds: Int = PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS,
    onSelectSeekIntervalSeconds: (Int) -> Unit = {},
    videosScrollPosition: LibraryScrollPosition = LibraryScrollPosition(),
    isVideosScrollLoaded: Boolean = true,
    onSaveVideosScrollPosition: (Int, Int) -> Unit = { _, _ -> },
    foldersScrollPosition: LibraryScrollPosition = LibraryScrollPosition(),
    onSaveFoldersScrollPosition: (Int, Int) -> Unit = { _, _ -> },
    onOpenFavoritesPlaylist: () -> Unit = {
        favoritesPlaylistId?.let { id ->
            playlists.firstOrNull { it.id == id }?.let(onSelectPlaylist)
        }
    },
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current

    var isSortSheetVisible by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var videosForPlaylistAddition by remember { mutableStateOf<List<VideoItem>?>(null) }
    var videoToRename by remember { mutableStateOf<VideoItem?>(null) }
    var videosToMove by remember { mutableStateOf<List<VideoItem>?>(null) }
    var videoForInfo by remember { mutableStateOf<VideoItem?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var showBackupImportConfirmDialog by remember { mutableStateOf(false) }

    // Multi-select state for VideoCard lists (VIDEOS, FOLDERS detail, FAVORITES)
    var selectedVideoKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkMenuExpanded by remember { mutableStateOf(false) }

    // Multi-select state for Playlist detail view
    var selectedPlaylistItemIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var playlistBulkMenuExpanded by remember { mutableStateOf(false) }

    fun videoSelectionKey(video: VideoItem): String = "${video.id}|${video.contentUri}"

    fun isVideoFavorite(video: VideoItem): Boolean =
        (video.id in favoriteVideoIds) || (video.contentUri.toString() in favoriteVideoUris)

    LaunchedEffect(currentTab, selectedFolder?.bucketId, selectedPlaylistWithItems?.first?.id) {
        selectedVideoKeys = emptySet()
        selectedPlaylistItemIds = emptySet()
        bulkMenuExpanded = false
        playlistBulkMenuExpanded = false
    }

    LaunchedEffect(currentTab) {
        if (currentTab == LibraryTab.FAVORITES) {
            onTabSelected(LibraryTab.PLAYLISTS)
            onOpenFavoritesPlaylist()
        }
    }

    val latestSaveVideosScroll by rememberUpdatedState(onSaveVideosScrollPosition)
    val latestSaveFoldersScroll by rememberUpdatedState(onSaveFoldersScrollPosition)

    val isVideoSelectionActive = selectedVideoKeys.isNotEmpty()
    val isPlaylistSelectionActive = selectedPlaylistItemIds.isNotEmpty()

    // Hardware / gesture Back button handling: exit selection mode first, then nested folders/playlists/tabs
    BackHandler(
        enabled = isVideoSelectionActive ||
            isPlaylistSelectionActive ||
            selectedFolder != null ||
            selectedPlaylistWithItems != null ||
            currentTab == LibraryTab.VAULT ||
            currentTab == LibraryTab.TRASH ||
            currentTab == LibraryTab.SETTINGS ||
            (isSearchExpanded && searchQuery.isNotEmpty())
    ) {
        when {
            isVideoSelectionActive -> selectedVideoKeys = emptySet()
            isPlaylistSelectionActive -> selectedPlaylistItemIds = emptySet()
            selectedFolder != null -> clearFolderSelection()
            selectedPlaylistWithItems != null -> onSelectPlaylist(null)
            currentTab == LibraryTab.VAULT -> onTabSelected(LibraryTab.SETTINGS)
            currentTab == LibraryTab.TRASH -> onTabSelected(LibraryTab.VIDEOS)
            isSearchExpanded -> {
                isSearchExpanded = false
                onSearchQueryChanged("")
            }
            currentTab == LibraryTab.SETTINGS -> onTabSelected(LibraryTab.VIDEOS)
        }
    }

    Scaffold(
        containerColor = palette.background,
        bottomBar = {
            NavigationBar(
                containerColor = palette.surface
            ) {
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = palette.accent,
                    selectedTextColor = palette.accent,
                    indicatorColor = palette.accent.copy(alpha = 0.16f),
                    unselectedIconColor = palette.onSurfaceMuted,
                    unselectedTextColor = palette.onSurfaceMuted
                )

                NavigationBarItem(
                    selected = currentTab == LibraryTab.VIDEOS,
                    onClick = {
                        clearFolderSelection()
                        onTabSelected(LibraryTab.VIDEOS)
                    },
                    colors = itemColors,
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text("فيديوهات") }
                )
                NavigationBarItem(
                    selected = currentTab == LibraryTab.FOLDERS,
                    onClick = { onTabSelected(LibraryTab.FOLDERS) },
                    colors = itemColors,
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("مجلدات") }
                )
                NavigationBarItem(
                    selected = currentTab == LibraryTab.PLAYLISTS || currentTab == LibraryTab.FAVORITES,
                    onClick = {
                        clearFolderSelection()
                        onTabSelected(LibraryTab.PLAYLISTS)
                    },
                    colors = itemColors,
                    icon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                    label = { Text("قوائم") }
                )
                NavigationBarItem(
                    selected = currentTab == LibraryTab.SETTINGS ||
                        currentTab == LibraryTab.VAULT ||
                        currentTab == LibraryTab.TRASH,
                    onClick = {
                        clearFolderSelection()
                        onTabSelected(LibraryTab.SETTINGS)
                    },
                    colors = itemColors,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("الإعدادات") }
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentTab) {
                LibraryTab.VIDEOS, LibraryTab.FOLDERS -> {
                    val hasAccess = PermissionHelper.hasAnyAccess(uiState.permissionStatus)
                    if (!hasAccess && uiState.permissionStatus !is VideoPermissionStatus.NotRequested) {
                        val isPermanentlyDenied =
                            (uiState.permissionStatus as? VideoPermissionStatus.Denied)?.isPermanentlyDenied == true
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PermissionCard(
                                onRequestPermission = onRequestPermission,
                                onOpenSettings = onOpenSettings,
                                isPermanentlyDenied = isPermanentlyDenied
                            )
                        }
                    } else {
                        if (uiState.permissionStatus is VideoPermissionStatus.PartialAccess) {
                            PartialAccessBanner(onManageClick = onRequestPermission)
                        }

                        val showingVideoList = currentTab == LibraryTab.VIDEOS || selectedFolder != null
                        val videos = uiState.filteredVideos
                        val selectedVideos = remember(videos, selectedVideoKeys) {
                            videos.filter { videoSelectionKey(it) in selectedVideoKeys }
                        }

                        if (showingVideoList && isVideoSelectionActive) {
                            // Selection Action Top Bar for Public Library / Folder
                            Surface(
                                color = palette.surfaceElevated,
                                border = BorderStroke(0.75.dp, palette.accent.copy(alpha = 0.45f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { selectedVideoKeys = emptySet() }) {
                                        Icon(Icons.Default.Close, contentDescription = "إلغاء التحديد", tint = palette.onSurface)
                                    }
                                    Text(
                                        text = "تم تحديد ${selectedVideos.size}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.accent,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            selectedVideoKeys = if (selectedVideos.size == videos.size && videos.isNotEmpty()) {
                                                emptySet()
                                            } else {
                                                videos.map(::videoSelectionKey).toSet()
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (selectedVideos.size == videos.size && videos.isNotEmpty()) {
                                                Icons.Default.Deselect
                                            } else {
                                                Icons.Default.SelectAll
                                            },
                                            contentDescription = "تحديد الكل",
                                            tint = palette.accent
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { bulkMenuExpanded = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "إجراءات التحديد", tint = palette.accent)
                                        }
                                        MaterialTheme(
                                            colorScheme = MaterialTheme.colorScheme.copy(surface = palette.surface)
                                        ) {
                                            DropdownMenu(
                                                expanded = bulkMenuExpanded,
                                                onDismissRequest = { bulkMenuExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("إضافة إلى المفضلة", color = palette.onSurface) },
                                                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = palette.accent) },
                                                    onClick = {
                                                        bulkMenuExpanded = false
                                                        val targets = selectedVideos
                                                        selectedVideoKeys = emptySet()
                                                        onAddVideosToFavorites(targets)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("إضافة إلى القائمة", color = palette.onSurface) },
                                                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = palette.accent) },
                                                    onClick = {
                                                        bulkMenuExpanded = false
                                                        videosForPlaylistAddition = selectedVideos
                                                        selectedVideoKeys = emptySet()
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("نقل إلى مجلد", color = palette.onSurface) },
                                                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = palette.accent) },
                                                    onClick = {
                                                        bulkMenuExpanded = false
                                                        videosToMove = selectedVideos
                                                        selectedVideoKeys = emptySet()
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("نقل إلى الخزنة", color = palette.onSurface) },
                                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = palette.accent) },
                                                    onClick = {
                                                        bulkMenuExpanded = false
                                                        val targets = selectedVideos
                                                        selectedVideoKeys = emptySet()
                                                        onAddVideosToVault(targets)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("نقل إلى سلة المهملات", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Default.DeleteOutline,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                    },
                                                    onClick = {
                                                        bulkMenuExpanded = false
                                                        val targets = selectedVideos
                                                        selectedVideoKeys = emptySet()
                                                        onTrashVideos(targets)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Compact Top Control Row inside Library
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedFolder != null) {
                                    IconButton(onClick = clearFolderSelection) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع للمجلدات", tint = palette.accent)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedFolder.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.onSurface
                                        )
                                        Text(
                                            text = "${uiState.filteredVideos.size} فيديو",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.onSurfaceMuted
                                        )
                                    }
                                } else {
                                    Text(
                                        text = if (currentTab == LibraryTab.FOLDERS) {
                                            "المجلدات (${uiState.folders.size})"
                                        } else {
                                            "جميع الفيديوهات (${uiState.filteredVideos.size})"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.onSurfaceMuted,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        if (isSearchExpanded) {
                                            isSearchExpanded = false
                                            onSearchQueryChanged("")
                                        } else {
                                            isSearchExpanded = true
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                                        contentDescription = "بحث",
                                        tint = palette.accent
                                    )
                                }

                                IconButton(onClick = { isSortSheetVisible = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Sort,
                                        contentDescription = "ترتيب",
                                        tint = palette.accent
                                    )
                                }
                            }
                        }

                        // Compact Collapsible Slim Search Bar
                        AnimatedVisibility(visible = (isSearchExpanded || searchQuery.isNotEmpty()) && !isVideoSelectionActive) {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = palette.surface,
                                border = BorderStroke(0.75.dp, palette.border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp)
                                    .height(42.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = palette.onSurfaceMuted,
                                        modifier = Modifier.width(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = "ابحث عن فيديو...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = palette.onSurfaceMuted
                                            )
                                        }
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = onSearchQueryChanged,
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.onSurface),
                                            cursorBrush = SolidColor(palette.accent),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { onSearchQueryChanged("") },
                                            modifier = Modifier.width(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "مسح",
                                                tint = palette.onSurfaceMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.accent)
                            }
                        } else if (currentTab == LibraryTab.FOLDERS && selectedFolder == null) {
                            val latestFolders by rememberUpdatedState(uiState.folders)
                            val clampedFolderPos = remember(uiState.folders.size, foldersScrollPosition) {
                                foldersScrollPosition.clampForItemCount(uiState.folders.size)
                            }
                            val foldersListState = rememberLazyListState(
                                initialFirstVisibleItemIndex = clampedFolderPos.firstVisibleItemIndex,
                                initialFirstVisibleItemScrollOffset = clampedFolderPos.firstVisibleItemScrollOffset
                            )
                            LaunchedEffect(foldersListState, uiState.folders.size) {
                                snapshotFlow {
                                    Triple(
                                        foldersListState.isScrollInProgress,
                                        foldersListState.firstVisibleItemIndex,
                                        foldersListState.firstVisibleItemScrollOffset
                                    )
                                }.collect { (inProgress, idx, off) ->
                                    if (!inProgress && latestFolders.isNotEmpty()) {
                                        latestSaveFoldersScroll(idx, off)
                                    }
                                }
                            }
                            DisposableEffect(Unit) {
                                onDispose {
                                    if (latestFolders.isNotEmpty()) {
                                        latestSaveFoldersScroll(
                                            foldersListState.firstVisibleItemIndex,
                                            foldersListState.firstVisibleItemScrollOffset
                                        )
                                    }
                                }
                            }
                            LazyColumn(
                                state = foldersListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(uiState.folders, key = { it.bucketId }) { folder ->
                                    FolderCard(
                                        folder = folder,
                                        onClick = { onFolderSelected(folder) }
                                    )
                                }
                            }
                        } else {
                            if (videos.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "لا توجد نتائج مطابقة للبحث" else "لا توجد مقاطع فيديو",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = palette.onSurfaceMuted
                                    )
                                }
                            } else if (currentTab == LibraryTab.VIDEOS && selectedFolder == null) {
                                val latestVideos by rememberUpdatedState(videos)
                                val latestSearchQuery by rememberUpdatedState(searchQuery)
                                val latestVideosScrollPosition by rememberUpdatedState(videosScrollPosition)
                                val isSearchBlank = searchQuery.isBlank()

                                val initialClamped = remember(isVideosScrollLoaded, isSearchBlank) {
                                    if (isVideosScrollLoaded && isSearchBlank) {
                                        videosScrollPosition.clampForItemCount(videos.size)
                                    } else {
                                        LibraryScrollPosition()
                                    }
                                }
                                val videosListState = rememberLazyListState(
                                    initialFirstVisibleItemIndex = initialClamped.firstVisibleItemIndex,
                                    initialFirstVisibleItemScrollOffset = initialClamped.firstVisibleItemScrollOffset
                                )
                                var hasRestoredVideosScroll by remember { mutableStateOf(false) }

                                LaunchedEffect(isVideosScrollLoaded, videos.size, isSearchBlank) {
                                    if (!isSearchBlank) {
                                        hasRestoredVideosScroll = false
                                        return@LaunchedEffect
                                    }
                                    if (!hasRestoredVideosScroll && isVideosScrollLoaded && videos.isNotEmpty()) {
                                        val target = latestVideosScrollPosition.clampForItemCount(videos.size)
                                        if (!target.isAtOrigin &&
                                            (videosListState.firstVisibleItemIndex != target.firstVisibleItemIndex ||
                                                videosListState.firstVisibleItemScrollOffset != target.firstVisibleItemScrollOffset)
                                        ) {
                                            videosListState.scrollToItem(
                                                target.firstVisibleItemIndex,
                                                target.firstVisibleItemScrollOffset
                                            )
                                        }
                                        hasRestoredVideosScroll = true
                                    }
                                }

                                LaunchedEffect(videosListState, hasRestoredVideosScroll, isSearchBlank) {
                                    if (!hasRestoredVideosScroll || !isSearchBlank) return@LaunchedEffect
                                    snapshotFlow {
                                        Triple(
                                            videosListState.isScrollInProgress,
                                            videosListState.firstVisibleItemIndex,
                                            videosListState.firstVisibleItemScrollOffset
                                        )
                                    }.collect { (inProgress, idx, off) ->
                                        if (!inProgress && latestSearchQuery.isBlank() && latestVideos.isNotEmpty()) {
                                            latestSaveVideosScroll(idx, off)
                                        }
                                    }
                                }

                                DisposableEffect(Unit) {
                                    onDispose {
                                        if (hasRestoredVideosScroll &&
                                            latestSearchQuery.isBlank() &&
                                            latestVideos.isNotEmpty()
                                        ) {
                                            latestSaveVideosScroll(
                                                videosListState.firstVisibleItemIndex,
                                                videosListState.firstVisibleItemScrollOffset
                                            )
                                        }
                                    }
                                }

                                LazyColumn(
                                    state = videosListState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(videos, key = { it.id }) { video ->
                                        val key = videoSelectionKey(video)
                                        val selected = key in selectedVideoKeys
                                        VideoCard(
                                            video = video,
                                            onClick = {
                                                if (isVideoSelectionActive) {
                                                    selectedVideoKeys = if (selected) {
                                                        selectedVideoKeys - key
                                                    } else {
                                                        selectedVideoKeys + key
                                                    }
                                                } else {
                                                    if (latestSearchQuery.isBlank()) {
                                                        latestSaveVideosScroll(
                                                            videosListState.firstVisibleItemIndex,
                                                            videosListState.firstVisibleItemScrollOffset
                                                        )
                                                    }
                                                    onVideoClick(video, videos)
                                                }
                                            },
                                            onLongClick = {
                                                selectedVideoKeys = if (selected) {
                                                    selectedVideoKeys - key
                                                } else {
                                                    selectedVideoKeys + key
                                                }
                                            },
                                            isSelectionMode = isVideoSelectionActive,
                                            isSelected = selected,
                                            isFavorite = isVideoFavorite(video),
                                            onToggleFavoriteClick = { onToggleFavorite(video) },
                                            onAddToPlaylist = { videosForPlaylistAddition = listOf(video) },
                                            onRenameClick = { videoToRename = video },
                                            onMoveToFolderClick = { videosToMove = listOf(video) },
                                            onAddToVaultClick = { onAddVideoToVault(video) },
                                            onMoveToTrashClick = { onMoveVideoToTrash(video) },
                                            onInfoClick = { videoForInfo = video }
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(videos, key = { it.id }) { video ->
                                        val key = videoSelectionKey(video)
                                        val selected = key in selectedVideoKeys
                                        VideoCard(
                                            video = video,
                                            onClick = {
                                                if (isVideoSelectionActive) {
                                                    selectedVideoKeys = if (selected) {
                                                        selectedVideoKeys - key
                                                    } else {
                                                        selectedVideoKeys + key
                                                    }
                                                } else {
                                                    onVideoClick(video, videos)
                                                }
                                            },
                                            onLongClick = {
                                                selectedVideoKeys = if (selected) {
                                                    selectedVideoKeys - key
                                                } else {
                                                    selectedVideoKeys + key
                                                }
                                            },
                                            isSelectionMode = isVideoSelectionActive,
                                            isSelected = selected,
                                            isFavorite = isVideoFavorite(video),
                                            onToggleFavoriteClick = { onToggleFavorite(video) },
                                            onAddToPlaylist = { videosForPlaylistAddition = listOf(video) },
                                            onRenameClick = { videoToRename = video },
                                            onMoveToFolderClick = { videosToMove = listOf(video) },
                                            onAddToVaultClick = { onAddVideoToVault(video) },
                                            onMoveToTrashClick = { onMoveVideoToTrash(video) },
                                            onInfoClick = { videoForInfo = video }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LibraryTab.FAVORITES -> Unit

                LibraryTab.PLAYLISTS -> {
                    val currentPlaylistPair = selectedPlaylistWithItems
                    if (currentPlaylistPair != null) {
                        val playlist = currentPlaylistPair.first
                        val items = currentPlaylistPair.second
                        val selectedItems = remember(items, selectedPlaylistItemIds) {
                            items.filter { it.id in selectedPlaylistItemIds }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            if (isPlaylistSelectionActive) {
                                Surface(
                                    color = palette.surfaceElevated,
                                    border = BorderStroke(0.75.dp, palette.accent.copy(alpha = 0.45f)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = { selectedPlaylistItemIds = emptySet() }) {
                                            Icon(Icons.Default.Close, contentDescription = "إلغاء التحديد", tint = palette.onSurface)
                                        }
                                        Text(
                                            text = "تم تحديد ${selectedItems.size}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.accent,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                selectedPlaylistItemIds = if (selectedItems.size == items.size && items.isNotEmpty()) {
                                                    emptySet()
                                                } else {
                                                    items.map { it.id }.toSet()
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (selectedItems.size == items.size && items.isNotEmpty()) {
                                                    Icons.Default.Deselect
                                                } else {
                                                    Icons.Default.SelectAll
                                                },
                                                contentDescription = "تحديد الكل",
                                                tint = palette.accent
                                            )
                                        }
                                        Box {
                                            IconButton(onClick = { playlistBulkMenuExpanded = true }) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "إجراءات التحديد", tint = palette.accent)
                                            }
                                            MaterialTheme(
                                                colorScheme = MaterialTheme.colorScheme.copy(surface = palette.surface)
                                            ) {
                                                DropdownMenu(
                                                    expanded = playlistBulkMenuExpanded,
                                                    onDismissRequest = { playlistBulkMenuExpanded = false }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("إزالة من القائمة (دون حذف الملف)", color = palette.onSurface) },
                                                        leadingIcon = {
                                                            Icon(Icons.Default.PlaylistRemove, contentDescription = null, tint = palette.accent)
                                                        },
                                                        onClick = {
                                                            playlistBulkMenuExpanded = false
                                                            val targetIds = selectedPlaylistItemIds
                                                            selectedPlaylistItemIds = emptySet()
                                                            onRemovePlaylistItems(targetIds, playlist.id)
                                                        }
                                                    )
                                                    val mappedVideos = selectedItems.map { item ->
                                                        uiState.allVideos.firstOrNull {
                                                            it.id == item.videoId || it.contentUri.toString() == item.contentUri
                                                        } ?: VideoItem(
                                                            id = item.videoId,
                                                            contentUri = android.net.Uri.parse(item.contentUri),
                                                            title = item.videoTitle,
                                                            displayName = item.videoTitle,
                                                            durationMs = item.durationMs,
                                                            sizeBytes = 0L,
                                                            bucketId = "playlist",
                                                            dateAddedSec = 0L
                                                        )
                                                    }
                                                    if (!PlaylistRepository.isProtectedFavoritesTitle(playlist.title)) {
                                                        DropdownMenuItem(
                                                            text = { Text("إضافة إلى المفضلة", color = palette.onSurface) },
                                                            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = palette.accent) },
                                                            onClick = {
                                                                playlistBulkMenuExpanded = false
                                                                selectedPlaylistItemIds = emptySet()
                                                                onAddVideosToFavorites(mappedVideos)
                                                            }
                                                        )
                                                    }
                                                    DropdownMenuItem(
                                                        text = { Text("إضافة إلى قائمة أخرى", color = palette.onSurface) },
                                                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = palette.accent) },
                                                        onClick = {
                                                            playlistBulkMenuExpanded = false
                                                            videosForPlaylistAddition = mappedVideos
                                                            selectedPlaylistItemIds = emptySet()
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { onSelectPlaylist(null) }) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = palette.accent)
                                    }
                                    Text(
                                        text = playlist.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = { onPlayPlaylist(playlist.id, null, playbackMode.shuffleEnabled) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = palette.accent,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("تشغيل", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(items, key = { it.id }) { item ->
                                    val isSelected = item.id in selectedPlaylistItemIds
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = {
                                                    if (isPlaylistSelectionActive) {
                                                        selectedPlaylistItemIds = if (isSelected) {
                                                            selectedPlaylistItemIds - item.id
                                                        } else {
                                                            selectedPlaylistItemIds + item.id
                                                        }
                                                    } else {
                                                        onPlayPlaylist(playlist.id, item.videoId, playbackMode.shuffleEnabled)
                                                    }
                                                },
                                                onLongClick = {
                                                    selectedPlaylistItemIds = if (isSelected) {
                                                        selectedPlaylistItemIds - item.id
                                                    } else {
                                                        selectedPlaylistItemIds + item.id
                                                    }
                                                }
                                            ),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) palette.accent.copy(alpha = 0.14f) else palette.surface
                                        ),
                                        border = BorderStroke(
                                            width = if (isSelected) 1.5.dp else 0.75.dp,
                                            color = if (isSelected) palette.accent else palette.border.copy(alpha = 0.6f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isPlaylistSelectionActive) {
                                                Icon(
                                                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    tint = if (isSelected) palette.accent else palette.onSurfaceMuted
                                                )
                                            } else {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = palette.accent)
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.videoTitle,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.onSurface,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = if (item.isAvailable) "متاح" else "غير متاح",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = palette.onSurfaceMuted
                                                )
                                            }
                                            if (!isPlaylistSelectionActive) {
                                                IconButton(onClick = { onMovePlaylistItemUp(item.orderIndex) }) {
                                                    Icon(Icons.Default.ArrowUpward, contentDescription = "أعلى", tint = palette.onSurfaceMuted)
                                                }
                                                IconButton(onClick = { onMovePlaylistItemDown(item.orderIndex) }) {
                                                    Icon(Icons.Default.ArrowDownward, contentDescription = "أسفل", tint = palette.onSurfaceMuted)
                                                }
                                                IconButton(onClick = { onRemovePlaylistItem(item.id, playlist.id) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val hasFavoritesPlaylist = playlists.any {
                                PlaylistRepository.isProtectedFavoritesTitle(it.title)
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!hasFavoritesPlaylist) {
                                    item(key = "virtual_favorites_playlist") {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onOpenFavoritesPlaylist() },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = CardDefaults.cardColors(containerColor = palette.surface),
                                            border = BorderStroke(
                                                width = 1.25.dp,
                                                color = palette.accent.copy(alpha = 0.75f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = null,
                                                    tint = palette.accent
                                                )
                                                Spacer(modifier = Modifier.width(14.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = PlaylistRepository.FAVORITES_PLAYLIST_TITLE,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = palette.onSurface
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = palette.accent.copy(alpha = 0.16f)
                                                        ) {
                                                            Text(
                                                                text = "أساسية",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = palette.accent,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = "${favoriteVideos.size} فيديو",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = palette.onSurfaceMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                items(playlists, key = { it.id }) { playlist ->
                                    val isProtectedFavorites =
                                        PlaylistRepository.isProtectedFavoritesTitle(playlist.title)
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectPlaylist(playlist) },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                                        border = BorderStroke(
                                            width = if (isProtectedFavorites) 1.25.dp else 0.75.dp,
                                            color = if (isProtectedFavorites) palette.accent.copy(alpha = 0.75f) else palette.border.copy(alpha = 0.65f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isProtectedFavorites) Icons.Default.Star else Icons.Default.PlaylistPlay,
                                                contentDescription = null,
                                                tint = palette.accent
                                            )
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = playlist.title,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = palette.onSurface
                                                    )
                                                    if (isProtectedFavorites) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = palette.accent.copy(alpha = 0.16f)
                                                        ) {
                                                            Text(
                                                                text = "أساسية",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = palette.accent,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = "${playlist.itemCount} فيديو",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = palette.onSurfaceMuted
                                                )
                                            }
                                            if (!isProtectedFavorites) {
                                                IconButton(onClick = { playlistToRename = playlist }) {
                                                    Icon(Icons.Default.Edit, contentDescription = "إعادة تسمية", tint = palette.onSurfaceMuted)
                                                }
                                                IconButton(onClick = { onDeletePlaylist(playlist.id) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            FloatingActionButton(
                                onClick = { showCreatePlaylistDialog = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                containerColor = palette.accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "قائمة جديدة")
                            }
                        }
                    }
                }

                LibraryTab.TRASH -> {
                    TrashScreen(
                        trashItems = trashItems,
                        trashError = trashError,
                        existenceMap = trashExistenceMap,
                        missingItemPendingRemoval = missingTrashItemPendingRemoval,
                        onRefreshStatus = onRefreshTrashStatus,
                        onDismissError = onDismissTrashError,
                        onRestoreItem = onRestoreFromTrash,
                        onConfirmRemoveMissingRecord = onConfirmRemoveMissingTrashRecord,
                        onDismissMissingRemoval = onDismissMissingTrashRemoval,
                        onPermanentDeleteItem = onPermanentDeleteTrashItem,
                        onEmptyTrash = onEmptyTrashBin
                    )
                }

                LibraryTab.SETTINGS -> {
                    SettingsScreen(
                        selectedTheme = selectedAppTheme,
                        persistedTheme = persistedAppTheme,
                        isThemePreviewActive = isThemePreviewActive,
                        themeMode = themeMode,
                        playbackMode = playbackMode,
                        resumeMode = resumeMode,
                        vaultVideoCount = vaultTotalCount,
                        trashVideoCount = trashItems.size,
                        onSelectTheme = onSelectAppTheme,
                        onApplyThemePreview = onApplyThemePreview,
                        onCancelThemePreview = onCancelThemePreview,
                        onSelectThemeMode = onSelectThemeMode,
                        onSelectPlaybackMode = onSelectPlaybackMode,
                        onSelectResumeMode = onSelectResumeMode,
                        onOpenVault = { onTabSelected(LibraryTab.VAULT) },
                        onOpenTrashBin = { onTabSelected(LibraryTab.TRASH) },
                        onExportBackup = onExportBackup,
                        onImportBackup = { showBackupImportConfirmDialog = true },
                        seekIntervalSeconds = seekIntervalSeconds,
                        onSelectSeekIntervalSeconds = onSelectSeekIntervalSeconds
                    )
                }

                LibraryTab.VAULT -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.surface)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { onTabSelected(LibraryTab.SETTINGS) }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = palette.accent)
                            }
                            Icon(Icons.Default.Lock, contentDescription = null, tint = palette.accent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "العودة للإعدادات",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.onSurfaceMuted
                            )
                        }

                        VaultScreen(
                            isUnlocked = vaultIsUnlocked,
                            videos = vaultVideos,
                            importState = vaultImportState,
                            biometricError = vaultBiometricError,
                            vaultError = vaultError,
                            hasPinConfigured = vaultHasPinConfigured,
                            canUseBiometrics = vaultCanUseBiometrics,
                            autoLockTimeout = vaultAutoLockTimeout,
                            onAuthenticateClick = onVaultAuthenticate,
                            onUnlockWithPin = onVaultUnlockWithPin,
                            onSetupInitialPin = onVaultSetupInitialPin,
                            onChangePin = onVaultChangePin,
                            onSetPin = onVaultSetPin,
                            onRemovePin = onVaultRemovePin,
                            onSelectAutoLockTimeout = onVaultSelectAutoLockTimeout,
                            onLockClick = onVaultLock,
                            onImportClick = onVaultImportRequest,
                            onCancelImport = onVaultCancelImport,
                            onDismissImportState = onVaultDismissImportState,
                            onDismissError = onVaultDismissError,
                            onVideoClick = onVaultVideoClick,
                            onRenameVideo = onVaultRenameVideo,
                            restoreState = vaultRestoreState,
                            onRestoreVideo = onVaultRestoreVideo,
                            onRestoreVideos = onVaultRestoreVideos,
                            onDeleteVideo = onVaultDeleteVideo,
                            onDeleteVideos = onVaultDeleteVideos,
                            onUserInteraction = onVaultUserInteraction,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (isSortSheetVisible) {
            SortBottomSheet(
                currentSort = sortOrder,
                onSelectSort = {
                    onSortOrderChanged(it)
                    isSortSheetVisible = false
                },
                onDismiss = { isSortSheetVisible = false }
            )
        }

        videosForPlaylistAddition?.let { targetVideos ->
            AddToPlaylistDialog(
                playlists = playlists,
                onSelectPlaylist = { playlist ->
                    if (targetVideos.size == 1) {
                        onAddToPlaylists(targetVideos.first(), listOf(playlist.id))
                    } else {
                        onAddVideosToPlaylists(targetVideos, listOf(playlist.id))
                    }
                    videosForPlaylistAddition = null
                },
                onCreateNewPlaylist = {
                    showCreatePlaylistDialog = true
                },
                onDismiss = { videosForPlaylistAddition = null }
            )
        }

        videoToRename?.let { video ->
            RenameVideoDialog(
                video = video,
                onDismiss = { videoToRename = null },
                onConfirm = { newTitle ->
                    onRenameVideo(video, newTitle)
                    videoToRename = null
                }
            )
        }

        videosToMove?.let { targetVideos ->
            val anchor = targetVideos.firstOrNull()
            if (anchor != null) {
                MoveVideoFolderDialog(
                    video = anchor,
                    existingFolders = uiState.folders,
                    selectedCount = targetVideos.size,
                    onDismiss = { videosToMove = null },
                    onMoveToFolder = { relPath, folderName ->
                        if (targetVideos.size == 1) {
                            onMoveVideoToFolder(anchor, relPath, folderName)
                        } else {
                            onMoveVideosToFolder(targetVideos, relPath, folderName)
                        }
                        videosToMove = null
                    }
                )
            }
        }

        videoForInfo?.let { video ->
            VideoInfoDialog(
                video = video,
                onMoveToFolderClick = { videosToMove = listOf(video) },
                onMoveToTrashClick = { onMoveVideoToTrash(video) },
                onDismiss = { videoForInfo = null }
            )
        }

        if (showBackupImportConfirmDialog) {
            BackupImportConfirmDialog(
                onConfirm = {
                    showBackupImportConfirmDialog = false
                    onImportBackup()
                },
                onDismiss = { showBackupImportConfirmDialog = false }
            )
        }

        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onConfirm = { name ->
                    onCreatePlaylist(name)
                    showCreatePlaylistDialog = false
                },
                onDismiss = { showCreatePlaylistDialog = false }
            )
        }

        playlistToRename?.let { playlist ->
            RenamePlaylistDialog(
                currentName = playlist.title,
                onConfirm = { newName ->
                    onRenamePlaylist(playlist.id, newName)
                    playlistToRename = null
                },
                onDismiss = { playlistToRename = null }
            )
        }
    }
}
