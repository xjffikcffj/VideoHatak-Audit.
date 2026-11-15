package com.videohatak.player.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
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
import com.videohatak.player.feature.vault.VaultScreen

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
    // Playlist Callbacks
    playlists: List<Playlist>,
    selectedPlaylistWithItems: Pair<Playlist, List<PlaylistItem>>?,
    onSelectPlaylist: (Playlist?) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRemovePlaylistItem: (Long, Long) -> Unit,
    onMovePlaylistItemUp: (Int) -> Unit,
    onMovePlaylistItemDown: (Int) -> Unit,
    onPlayPlaylist: (Long, Long?, Boolean) -> Unit,
    onAddToPlaylists: (VideoItem, List<Long>) -> Unit,
    // Vault Callbacks & State
    vaultIsUnlocked: Boolean,
    vaultVideos: List<VaultVideo>,
    vaultImportState: VaultImportState,
    vaultBiometricError: String?,
    vaultError: String? = null,
    vaultHasPinConfigured: Boolean = false,
    vaultCanUseBiometrics: Boolean = true,
    vaultAutoLockTimeout: VaultAutoLockTimeout = VaultAutoLockTimeout.IMMEDIATE,
    onVaultAuthenticate: () -> Unit,
    onVaultUnlockWithPin: (String) -> Unit = {},
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
    onVaultDeleteVideo: (Long) -> Unit,
    onVaultUserInteraction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current

    var isSortSheetVisible by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var videoForPlaylistAddition by remember { mutableStateOf<VideoItem?>(null) }
    var videoToRename by remember { mutableStateOf<VideoItem?>(null) }
    var videoToMove by remember { mutableStateOf<VideoItem?>(null) }
    var videoForInfo by remember { mutableStateOf<VideoItem?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var showBackupImportConfirmDialog by remember { mutableStateOf(false) }

    // Hardware / gesture Back button handling inside nested folders, playlists, vault, trash, search, and settings
    BackHandler(
        enabled = selectedFolder != null ||
            selectedPlaylistWithItems != null ||
            currentTab == LibraryTab.VAULT ||
            currentTab == LibraryTab.TRASH ||
            currentTab == LibraryTab.SETTINGS ||
            (isSearchExpanded && searchQuery.isNotEmpty())
    ) {
        when {
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
                    selected = currentTab == LibraryTab.PLAYLISTS,
                    onClick = {
                        clearFolderSelection()
                        onTabSelected(LibraryTab.PLAYLISTS)
                    },
                    colors = itemColors,
                    icon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                    label = { Text("قوائم") }
                )
                NavigationBarItem(
                    selected = currentTab == LibraryTab.TRASH,
                    onClick = {
                        clearFolderSelection()
                        onTabSelected(LibraryTab.TRASH)
                    },
                    colors = itemColors,
                    icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                    label = { Text("السلة") }
                )
                NavigationBarItem(
                    selected = currentTab == LibraryTab.SETTINGS || currentTab == LibraryTab.VAULT,
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

                        // Compact Collapsible Slim Search Bar
                        AnimatedVisibility(visible = isSearchExpanded || searchQuery.isNotEmpty()) {
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
                            LazyColumn(
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
                            val videos = uiState.filteredVideos
                            if (videos.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "لا توجد نتائج مطابقة للبحث" else "لا توجد مقاطع فيديو",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = palette.onSurfaceMuted
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(videos, key = { it.id }) { video ->
                                        VideoCard(
                                            video = video,
                                            onClick = { onVideoClick(video, videos) },
                                            onAddToPlaylist = { videoForPlaylistAddition = video },
                                            onRenameClick = { videoToRename = video },
                                            onMoveToFolderClick = { videoToMove = video },
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

                LibraryTab.PLAYLISTS -> {
                    val currentPlaylistPair = selectedPlaylistWithItems
                    if (currentPlaylistPair != null) {
                        val playlist = currentPlaylistPair.first
                        val items = currentPlaylistPair.second

                        Column(modifier = Modifier.fillMaxSize()) {
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
                                    onClick = { onPlayPlaylist(playlist.id, null, false) },
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

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(items, key = { it.id }) { item ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onPlayPlaylist(playlist.id, item.videoId, false) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                                        border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.6f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = palette.accent)
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
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (playlists.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "لا توجد قوائم تشغيل حتى الآن",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = palette.onSurfaceMuted
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(playlists, key = { it.id }) { playlist ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onSelectPlaylist(playlist) },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = CardDefaults.cardColors(containerColor = palette.surface),
                                            border = BorderStroke(0.75.dp, palette.border.copy(alpha = 0.65f))
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = palette.accent)
                                                Spacer(modifier = Modifier.width(14.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = playlist.title,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = palette.onSurface
                                                    )
                                                    Text(
                                                        text = "${playlist.itemCount} فيديو",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = palette.onSurfaceMuted
                                                    )
                                                }
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
                        vaultVideoCount = vaultVideos.size,
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
                        onImportBackup = { showBackupImportConfirmDialog = true }
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
                            onDeleteVideo = onVaultDeleteVideo,
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

        videoForPlaylistAddition?.let { video ->
            AddToPlaylistDialog(
                playlists = playlists,
                onSelectPlaylist = { playlist ->
                    onAddToPlaylists(video, listOf(playlist.id))
                    videoForPlaylistAddition = null
                },
                onCreateNewPlaylist = {
                    showCreatePlaylistDialog = true
                },
                onDismiss = { videoForPlaylistAddition = null }
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

        videoToMove?.let { video ->
            MoveVideoFolderDialog(
                video = video,
                existingFolders = uiState.folders,
                onDismiss = { videoToMove = null },
                onMoveToFolder = { relPath, folderName ->
                    onMoveVideoToFolder(video, relPath, folderName)
                    videoToMove = null
                }
            )
        }

        videoForInfo?.let { video ->
            VideoInfoDialog(
                video = video,
                onMoveToFolderClick = { videoToMove = video },
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
