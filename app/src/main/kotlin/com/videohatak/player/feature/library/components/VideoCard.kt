package com.videohatak.player.feature.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.thumbnail.cache.VideoThumbnailLoader

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isFavorite: Boolean = false,
    onToggleFavoriteClick: (() -> Unit)? = null,
    favoriteMenuLabel: String? = null,
    onAddToPlaylist: () -> Unit = {},
    onRenameClick: () -> Unit = {},
    onMoveToFolderClick: () -> Unit = {},
    onAddToVaultClick: () -> Unit = {},
    onMoveToTrashClick: (() -> Unit)? = null,
    onInfoClick: () -> Unit = {},
    onMoreClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = LocalVideoHatakPalette.current
    var thumbnail by remember(video.id) { mutableStateOf<Bitmap?>(VideoThumbnailLoader.getCached(video.id)) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(video.id, video.contentUri) {
        if (thumbnail == null) {
            thumbnail = VideoThumbnailLoader.loadThumbnail(context, video.id, video.contentUri)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Medium crisp thumbnail on Start (Right side in Arabic RTL)
            Box(
                modifier = Modifier
                    .size(width = 118.dp, height = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(0.75.dp, palette.border.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(palette.surfaceElevated, palette.background)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = video.safeTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.14f))
                    )
                }

                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(5.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) palette.accent else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.56f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .background(Color(0xD0000000), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = video.formattedDuration,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Clean metadata column (no progress/watch bar in library)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = video.safeTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = palette.accent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = video.resolutionTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    val folderSuffix = video.bucketDisplayName.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                    Text(
                        text = "${video.formattedSize}$folderSuffix",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            // Trailing 3-dots menu button (hidden during multi-select so taps stay clean)
            if (!isSelectionMode) {
                Box {
                    IconButton(
                        onClick = {
                            if (onMoreClick != null) {
                                onMoreClick()
                            } else {
                                menuExpanded = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "خيارات الفيديو",
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
                            if (onToggleFavoriteClick != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = favoriteMenuLabel
                                                ?: if (isFavorite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                                            color = palette.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = null,
                                            tint = palette.accent
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onToggleFavoriteClick()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("إضافة إلى القائمة", color = palette.onSurface) },
                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = palette.accent) },
                                onClick = {
                                    menuExpanded = false
                                    onAddToPlaylist()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("تغيير الاسم", color = palette.onSurface) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = palette.accent) },
                                onClick = {
                                    menuExpanded = false
                                    onRenameClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("نقل إلى مجلد", color = palette.onSurface) },
                                leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = palette.accent) },
                                onClick = {
                                    menuExpanded = false
                                    onMoveToFolderClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("نقل إلى الخزنة", color = palette.onSurface) },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = palette.accent) },
                                onClick = {
                                    menuExpanded = false
                                    onAddToVaultClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("معلومات الفيديو", color = palette.onSurface) },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = palette.accent) },
                                onClick = {
                                    menuExpanded = false
                                    onInfoClick()
                                }
                            )
                            if (onMoveToTrashClick != null) {
                                DropdownMenuItem(
                                    text = { Text("نقل إلى السلة", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onMoveToTrashClick()
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) palette.accent else palette.onSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}
