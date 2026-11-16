package com.videohatak.player.feature.playlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videohatak.player.core.designsystem.component.VideoHatakTopBar
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import com.videohatak.player.feature.playlist.components.CreatePlaylistDialog
import com.videohatak.player.feature.playlist.components.RenamePlaylistDialog

@Composable
fun PlaylistsScreen(
    viewModel: PlaylistViewModel,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            VideoHatakTopBar(title = "قوائم التشغيل")
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = palette.accent,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "إنشاء قائمة")
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PlaylistPlay,
                            contentDescription = null,
                            tint = palette.onSurfaceMuted,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "لا توجد قوائم تشغيل حتى الآن",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.onSurfaceMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistItemCard(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist) },
                            onRename = { playlistToRename = playlist },
                            onDelete = { viewModel.deletePlaylist(playlist.id) },
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreatePlaylistDialog(
                onConfirm = { name ->
                    viewModel.createPlaylist(name)
                    showCreateDialog = false
                },
                onDismiss = { showCreateDialog = false }
            )
        }

        playlistToRename?.let { target ->
            RenamePlaylistDialog(
                currentName = target.name,
                onConfirm = { newName ->
                    viewModel.renamePlaylist(target.id, newName)
                    playlistToRename = null
                },
                onDismiss = { playlistToRename = null }
            )
        }
    }
}

@Composable
private fun PlaylistItemCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    val isProtectedFavorites = PlaylistRepository.isProtectedFavoritesTitle(playlist.title)
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        border = BorderStroke(
            width = if (isProtectedFavorites) 1.dp else 0.75.dp,
            color = if (isProtectedFavorites) palette.accent.copy(alpha = 0.65f) else palette.border.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isProtectedFavorites) Icons.Default.Star else Icons.Default.PlaylistPlay,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(34.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isProtectedFavorites) "قائمة النظام المحمية • ${playlist.itemCount} فيديو" else "${playlist.itemCount} فيديو",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted
                )
            }

            if (isProtectedFavorites) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = palette.accent.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "أساسية",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "خيارات",
                            tint = palette.onSurfaceMuted
                        )
                    }
                    MaterialTheme(
                        colorScheme = MaterialTheme.colorScheme.copy(surface = palette.surface)
                    ) {
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("إعادة تسمية", color = palette.onSurface) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = palette.accent) },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("حذف القائمة", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
