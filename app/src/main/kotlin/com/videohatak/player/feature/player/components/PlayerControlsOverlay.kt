package com.videohatak.player.feature.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlayerUiState
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.thumbnail.model.ThumbnailPreviewState
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    thumbnailState: ThumbnailPreviewState,
    zoomScale: Float = 1f,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPreviousTrack: () -> Unit = {},
    onNextTrack: () -> Unit = {},
    onSeekStart: (Long, Float) -> Unit,
    onSeekProgress: (Long, Float) -> Unit,
    onSeekStop: (Long) -> Unit,
    onForward10: () -> Unit,
    onReplay10: () -> Unit,
    onToggleLock: () -> Unit,
    onRotateScreen: () -> Unit = {},
    onCaptureFrame: () -> Unit = {},
    onResetZoom: () -> Unit = {},
    isPipAllowed: Boolean = true,
    onEnterPip: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onSelectResizeMode: (VideoResizeMode) -> Unit = {},
    onOpenSubtitlesDialog: () -> Unit,
    onSpeedClick: () -> Unit,
    onSelectSpeed: (Float) -> Unit = {},
    onSelectPlaybackMode: (PlaybackMode) -> Unit = {},
    onCycleRepeatMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    var showPlaybackModeDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }

    if (showPlaybackModeDialog) {
        AlertDialog(
            onDismissRequest = { showPlaybackModeDialog = false },
            containerColor = palette.surface,
            titleContentColor = palette.onSurface,
            textContentColor = palette.onSurface,
            title = { Text("وضع التشغيل", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlaybackMode.entries.forEach { mode ->
                        val selected = uiState.playbackMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSelectPlaybackMode(mode)
                                    showPlaybackModeDialog = false
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    onSelectPlaybackMode(mode)
                                    showPlaybackModeDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = palette.accent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.titleArabic,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (selected) palette.accent else palette.onSurface
                                )
                                Text(
                                    text = mode.descriptionArabic,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.onSurfaceMuted
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaybackModeDialog = false }) {
                    Text("إغلاق", color = palette.accent)
                }
            }
        )
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = palette.surface,
            titleContentColor = palette.onSurface,
            textContentColor = palette.onSurface,
            title = { Text("سرعة التشغيل", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "السرعة الحالية: ${VideoPlayerManager.formatSpeedLabel(uiState.playbackSpeed)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent
                    )
                    Slider(
                        value = uiState.playbackSpeed.coerceIn(
                            VideoPlayerManager.MIN_PLAYBACK_SPEED,
                            VideoPlayerManager.MAX_PLAYBACK_SPEED
                        ),
                        onValueChange = { raw ->
                            val snapped = (raw * 20f).roundToInt() / 20f
                            onSelectSpeed(VideoPlayerManager.clampPlaybackSpeed(snapped))
                        },
                        valueRange = VideoPlayerManager.MIN_PLAYBACK_SPEED..VideoPlayerManager.MAX_PLAYBACK_SPEED,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.accent,
                            activeTrackColor = palette.accent,
                            inactiveTrackColor = palette.border
                        )
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoPlayerManager.SUPPORTED_PLAYBACK_SPEEDS.forEach { speedOption ->
                            val selected = kotlin.math.abs(uiState.playbackSpeed - speedOption) < 0.02f
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) palette.accent.copy(alpha = 0.22f) else palette.surfaceVariant,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (selected) palette.accent else palette.border
                                ),
                                modifier = Modifier.clickable {
                                    onSelectSpeed(speedOption)
                                    showSpeedDialog = false
                                }
                            ) {
                                Text(
                                    text = VideoPlayerManager.formatSpeedLabel(speedOption),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) palette.accent else palette.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("تم", color = palette.accent)
                }
            }
        )
    }

    if (showResizeDialog) {
        AlertDialog(
            onDismissRequest = { showResizeDialog = false },
            containerColor = palette.surface,
            titleContentColor = palette.onSurface,
            textContentColor = palette.onSurface,
            title = { Text("نسبة العرض والمقاس", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoResizeMode.entries.forEach { mode ->
                        val selected = uiState.resizeModeEnum == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSelectResizeMode(mode)
                                    showResizeDialog = false
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = mode.titleArabic,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) palette.accent else palette.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = palette.accent
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showResizeDialog = false }) {
                    Text("إغلاق", color = palette.accent)
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.isLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.TopStart
            ) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.surface.copy(alpha = 0.85f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "فتح القفل",
                        tint = palette.accent
                    )
                }
            }
            return@Box
        }

        AnimatedVisibility(
            visible = uiState.isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                palette.background.copy(alpha = 0.84f),
                                Color.Transparent,
                                palette.background.copy(alpha = 0.88f)
                            )
                        )
                    )
            ) {
                // Top Bar Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "تصغير والعودة مع إبقاء التشغيل",
                            tint = palette.onSurface
                        )
                    }

                    Text(
                        text = uiState.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = onCaptureFrame) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "التقاط صورة من الفيديو",
                            tint = palette.onSurface
                        )
                    }

                    IconButton(onClick = onRotateScreen) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "تدوير الشاشة",
                            tint = palette.onSurface
                        )
                    }

                    IconButton(onClick = onOpenSubtitlesDialog) {
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = "الترجمات",
                            tint = palette.onSurface
                        )
                    }

                    if (isPipAllowed) {
                        IconButton(onClick = onEnterPip) {
                            Icon(
                                imageVector = Icons.Default.PictureInPicture,
                                contentDescription = "صورة في صورة",
                                tint = palette.onSurface
                            )
                        }
                    }

                    IconButton(onClick = onToggleLock) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "قفل الشاشة",
                            tint = palette.onSurface
                        )
                    }
                }

                // Center Transport Controls: Prev, -10s, Play/Pause, +10s, Next
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPreviousTrack,
                        enabled = uiState.hasPreviousMediaItem,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "الفيديو السابق",
                            tint = if (uiState.hasPreviousMediaItem) palette.onSurface else palette.onSurfaceMuted.copy(alpha = 0.45f),
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    IconButton(onClick = onReplay10, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = "ترجيع 10 ثوان",
                            tint = palette.onSurface,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(18.dp))

                    IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (uiState.isPlaying) "إيقاف" else "تشغيل",
                            tint = palette.accent,
                            modifier = Modifier.size(66.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(18.dp))

                    IconButton(onClick = onForward10, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = "تقديم 10 ثوان",
                            tint = palette.onSurface,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    IconButton(
                        onClick = onNextTrack,
                        enabled = uiState.hasNextMediaItem,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "الفيديو التالي",
                            tint = if (uiState.hasNextMediaItem) palette.onSurface else palette.onSurfaceMuted.copy(alpha = 0.45f),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                // Bottom Controls + Design #4 WavySeekBar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .align(Alignment.BottomCenter)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                ) {
                    if (thumbnailState.isVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ThumbnailPreviewCard(
                                bitmap = thumbnailState.bitmap,
                                positionMs = thumbnailState.positionMs
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

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Active Playback Mode Pill Button
                        val modeIcon = when (uiState.playbackMode) {
                            PlaybackMode.STOP_AFTER_CURRENT -> Icons.Default.StopCircle
                            PlaybackMode.SHUFFLE_ALL -> Icons.Default.Shuffle
                            PlaybackMode.PLAY_ALL_IN_ORDER -> Icons.Default.PlayArrow
                            PlaybackMode.REPEAT_ONE -> Icons.Default.RepeatOne
                            PlaybackMode.REPEAT_PLAYLIST -> Icons.Default.Repeat
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = palette.surface.copy(alpha = 0.85f),
                            border = BorderStroke(0.75.dp, palette.accent.copy(alpha = 0.65f)),
                            modifier = Modifier.clickable { showPlaybackModeDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = modeIcon,
                                    contentDescription = uiState.playbackMode.titleArabic,
                                    tint = palette.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = uiState.playbackMode.titleArabic,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (zoomScale > 1.05f) {
                                TextButton(onClick = onResetZoom) {
                                    Icon(
                                        imageVector = Icons.Default.ZoomOutMap,
                                        contentDescription = "إعادة ضبط التكبير",
                                        tint = palette.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "100%",
                                        color = palette.accent,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            IconButton(onClick = { showSpeedDialog = true }) {
                                Text(
                                    text = VideoPlayerManager.formatSpeedLabel(uiState.effectivePlaybackSpeed),
                                    color = if (uiState.isTemporarySpeedActive) palette.accent else palette.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            IconButton(onClick = { showResizeDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.AspectRatio,
                                    contentDescription = uiState.resizeModeLabelArabic,
                                    tint = palette.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
