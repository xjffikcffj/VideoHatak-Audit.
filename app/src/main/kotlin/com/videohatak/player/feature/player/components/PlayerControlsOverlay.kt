package com.videohatak.player.feature.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlayerOrientationMode
import com.videohatak.player.core.playback.PlayerUiState
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playback.service.MediaNotificationPrivacy
import com.videohatak.player.core.thumbnail.model.ThumbnailPreviewState
import kotlin.math.roundToInt

@Composable
fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    thumbnailState: ThumbnailPreviewState,
    zoomScale: Float,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onSeekStart: (Long, Float) -> Unit,
    onSeekProgress: (Long, Float) -> Unit,
    onSeekStop: (Long) -> Unit,
    onForward10: () -> Unit,
    onReplay10: () -> Unit,
    onToggleLock: () -> Unit,
    onRotateScreen: () -> Unit = {},
    onCycleOrientationMode: () -> Unit = onRotateScreen,
    onCaptureFrame: () -> Unit,
    onResetZoom: () -> Unit,
    isPipAllowed: Boolean = true,
    onEnterPip: () -> Unit,
    onCycleResizeMode: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    onSelectResizeMode: (VideoResizeMode) -> Unit = {},
    onOpenSubtitlesDialog: () -> Unit,
    onSpeedClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    onSelectSpeed: (Float) -> Unit = {},
    onSelectPlaybackMode: (PlaybackMode) -> Unit = {},
    isFavorite: Boolean = false,
    isPlayingFromPlaylist: Boolean = uiState.activePlaylistId != null,
    onRemoveFromPlaylist: () -> Unit = {},
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToVault: () -> Unit,
    onRenameVideo: () -> Unit,
    onMoveToTrash: () -> Unit,
    onShowDetails: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var playbackModeSubmenuExpanded by remember { mutableStateOf(false) }

    val isVaultPlayback = MediaNotificationPrivacy.isVaultUri(uiState.mediaUri)

    Box(modifier = modifier.fillMaxSize()) {
        // Locked screen mode: show only a discreet unlock pill when tapped
        if (uiState.isLocked) {
            AnimatedVisibility(
                visible = uiState.isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .safeDrawingPadding()
                    .padding(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = palette.surface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, palette.accent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { onToggleLock() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "فتح قفل الشاشة",
                            tint = palette.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "فتح القفل",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSurface
                        )
                    }
                }
            }
            return@Box
        }

        AnimatedVisibility(
            visible = uiState.isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top scrim + header bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                            )
                        )
                        .safeDrawingPadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "رجوع",
                                tint = Color.White
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.title.ifBlank { "تشغيل الفيديو" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (uiState.isAudioOnlyMode) {
                                Text(
                                    text = "وضع الاستماع فقط",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent
                                )
                            }
                        }

                        if (!isVaultPlayback) {
                            IconButton(onClick = onToggleFavorite) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = if (isFavorite) "إزالة من المفضلة" else "إضافة للمفضلة",
                                    tint = if (isFavorite) palette.accent else Color.White
                                )
                            }
                        }

                        // Strictly 3-dots menu at top trailing edge (NO standalone Vault icon button on overlay)
                        Box {
                            IconButton(onClick = { moreMenuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "خيارات المشغّل",
                                    tint = Color.White
                                )
                            }

                            MaterialTheme(
                                colorScheme = MaterialTheme.colorScheme.copy(surface = palette.surface)
                            ) {
                                DropdownMenu(
                                    expanded = moreMenuExpanded,
                                    onDismissRequest = {
                                        moreMenuExpanded = false
                                        playbackModeSubmenuExpanded = false
                                    }
                                ) {
                                    // First option when playing from inside a playlist: "إزالة من القائمة"
                                    if (isPlayingFromPlaylist) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "إزالة من القائمة",
                                                    color = palette.onSurface,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.PlaylistRemove,
                                                    contentDescription = null,
                                                    tint = palette.accent
                                                )
                                            },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onRemoveFromPlaylist()
                                            }
                                        )
                                        HorizontalDivider(color = palette.border.copy(alpha = 0.4f))
                                    }

                                    if (!isVaultPlayback) {
                                        DropdownMenuItem(
                                            text = { Text("إضافة إلى القائمة", color = palette.onSurface) },
                                            leadingIcon = {
                                                Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = palette.accent)
                                            },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onAddToPlaylist()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("نقل إلى الخزنة", color = palette.onSurface) },
                                            leadingIcon = {
                                                Icon(Icons.Default.Lock, contentDescription = null, tint = palette.accent)
                                            },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onAddToVault()
                                            }
                                        )
                                    }

                                    DropdownMenuItem(
                                        text = { Text("الترجمة والذكاء المحلي", color = palette.onSurface) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Subtitles, contentDescription = null, tint = palette.accent)
                                        },
                                        onClick = {
                                            moreMenuExpanded = false
                                            onOpenSubtitlesDialog()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "السرعة (${VideoPlayerManager.formatSpeedLabel(uiState.playbackSpeed)})",
                                                color = palette.onSurface
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Speed, contentDescription = null, tint = palette.accent)
                                        },
                                        onClick = {
                                            moreMenuExpanded = false
                                            onSpeedClick()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "وضع التشغيل: ${uiState.playbackMode.titleArabic}",
                                                color = palette.onSurface
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Repeat, contentDescription = null, tint = palette.accent)
                                        },
                                        onClick = {
                                            playbackModeSubmenuExpanded = !playbackModeSubmenuExpanded
                                        }
                                    )

                                    if (playbackModeSubmenuExpanded) {
                                        PlaybackMode.entries.forEach { mode ->
                                            val isSelectedMode = uiState.playbackMode == mode
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = if (isSelectedMode) "✓ ${mode.titleArabic}" else "   ${mode.titleArabic}",
                                                        color = if (isSelectedMode) palette.accent else palette.onSurfaceMuted,
                                                        fontWeight = if (isSelectedMode) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    playbackModeSubmenuExpanded = false
                                                    moreMenuExpanded = false
                                                    onSelectPlaybackMode(mode)
                                                }
                                            )
                                        }
                                    }

                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (uiState.isAudioOnlyMode) "العودة لعرض الفيديو" else "وضع الاستماع فقط",
                                                color = palette.onSurface
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Headphones, contentDescription = null, tint = palette.accent)
                                        },
                                        onClick = {
                                            moreMenuExpanded = false
                                            onToggleAudioOnly()
                                        }
                                    )

                                    if (isPipAllowed) {
                                        DropdownMenuItem(
                                            text = { Text("صورة في صورة", color = palette.onSurface) },
                                            leadingIcon = {
                                                Icon(Icons.Default.PictureInPictureAlt, contentDescription = null, tint = palette.accent)
                                            },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onEnterPip()
                                            }
                                        )
                                    }

                                    if (!isVaultPlayback && !uiState.isAudioOnlyMode) {
                                        DropdownMenuItem(
                                            text = { Text("التقاط إطار", color = palette.onSurface) },
                                            leadingIcon = {
                                                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = palette.accent)
                                            },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onCaptureFrame()
                                            }
                                        )
                                    }

                                    DropdownMenuItem(
                                        text = { Text("تغيير الاسم", color = palette.onSurface) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null, tint = palette.accent)
                                        },
                                        onClick = {
                                            moreMenuExpanded = false
                                            onRenameVideo()
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("معلومات الفيديو", color = palette.onSurface) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Info, contentDescription = null, tint = palette.accent)
                                        },
                                        onClick = {
                                            moreMenuExpanded = false
                                            onShowDetails()
                                        }
                                    )

                                    if (!isVaultPlayback) {
                                        DropdownMenuItem(
                                            text = { Text("نقل إلى السلة", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onMoveToTrash()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Center Transport Controls
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (uiState.hasPreviousMediaItem) {
                        IconButton(
                            onClick = onPreviousTrack,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.52f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "السابق",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onReplay10,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.52f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "ترجيع ${uiState.seekIntervalSeconds} ثوان",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = palette.accent,
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onPlayPause)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (uiState.isPlaying) "إيقاف مؤقت" else "تشغيل",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onForward10,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.52f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "تقديم ${uiState.seekIntervalSeconds} ثوان",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    if (uiState.hasNextMediaItem) {
                        IconButton(
                            onClick = onNextTrack,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.52f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "التالي",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Bottom scrim + seekbar + quick action bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                            )
                        )
                        .safeDrawingPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (thumbnailState.isVisible) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val density = LocalDensity.current
                            val cardWidthPx = with(density) { 148.dp.toPx() }
                            val maxW = with(density) { maxWidth.toPx() }
                            val xOffsetPx = ((thumbnailState.sliderFraction * maxW) - cardWidthPx / 2f)
                                .coerceIn(0f, (maxW - cardWidthPx).coerceAtLeast(0f))
                            ThumbnailPreviewCard(
                                bitmap = thumbnailState.bitmap,
                                positionMs = thumbnailState.positionMs,
                                modifier = Modifier.offset {
                                    IntOffset(x = xOffsetPx.roundToInt(), y = 0)
                                }
                            )
                        }
                    }

                    WavySeekBar(
                        currentPositionMs = uiState.currentPositionMs,
                        durationMs = uiState.durationMs,
                        onSeekStart = onSeekStart,
                        onSeekProgress = onSeekProgress,
                        onSeekStop = onSeekStop
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerQuickActionChip(
                            icon = Icons.Default.Lock,
                            label = "قفل",
                            onClick = onToggleLock
                        )

                        PlayerQuickActionChip(
                            icon = Icons.Default.Subtitles,
                            label = "الترجمة",
                            isHighlighted = uiState.primaryTrack != null || uiState.secondaryTrack != null,
                            onClick = onOpenSubtitlesDialog
                        )

                        PlayerQuickActionChip(
                            icon = Icons.Default.Speed,
                            label = VideoPlayerManager.formatSpeedLabel(uiState.playbackSpeed),
                            isHighlighted = uiState.playbackSpeed != 1.0f,
                            onClick = onSpeedClick
                        )

                        // Direct sequential aspect ratio / resize mode button (no dropdown menu)
                        PlayerQuickActionChip(
                            icon = Icons.Default.AspectRatio,
                            label = resizeShortLabel(uiState.resizeModeEnum),
                            isHighlighted = uiState.resizeModeEnum != VideoResizeMode.FIT,
                            onClick = onCycleResizeMode
                        )

                        // 3-mode sequential screen rotation button: PORTRAIT -> LANDSCAPE -> AUTO
                        PlayerQuickActionChip(
                            icon = orientationIcon(uiState.playerOrientationMode),
                            label = uiState.playerOrientationMode.arabicLabel,
                            isHighlighted = uiState.playerOrientationMode != PlayerOrientationMode.AUTO,
                            onClick = onCycleOrientationMode
                        )

                        if (isPipAllowed) {
                            PlayerQuickActionChip(
                                icon = Icons.Default.PictureInPictureAlt,
                                label = "نافذة",
                                onClick = onEnterPip
                            )
                        }

                        if (zoomScale > 1.05f) {
                            PlayerQuickActionChip(
                                icon = Icons.Default.ZoomOutMap,
                                label = "100%",
                                isHighlighted = true,
                                onClick = onResetZoom
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerQuickActionChip(
    icon: ImageVector,
    label: String,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalVideoHatakPalette.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isHighlighted) {
            palette.accent.copy(alpha = 0.24f)
        } else {
            Color.White.copy(alpha = 0.12f)
        },
        border = BorderStroke(
            width = 0.75.dp,
            color = if (isHighlighted) palette.accent else Color.White.copy(alpha = 0.22f)
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isHighlighted) palette.accent else Color.White,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                color = if (isHighlighted) palette.accent else Color.White,
                maxLines = 1
            )
        }
    }
}

private fun resizeShortLabel(mode: VideoResizeMode): String = when (mode) {
    VideoResizeMode.FIT -> "ملاءمة"
    VideoResizeMode.ZOOM -> "تكبير"
    VideoResizeMode.FILL -> "تمديد"
    VideoResizeMode.ORIGINAL_SIZE -> "أصلي"
    VideoResizeMode.RATIO_16_9 -> "16:9"
    VideoResizeMode.RATIO_4_3 -> "4:3"
}

private fun orientationIcon(mode: PlayerOrientationMode): ImageVector = when (mode) {
    PlayerOrientationMode.PORTRAIT -> Icons.Default.StayCurrentPortrait
    PlayerOrientationMode.LANDSCAPE -> Icons.Default.StayCurrentLandscape
    PlayerOrientationMode.AUTO -> Icons.Default.ScreenRotation
}
