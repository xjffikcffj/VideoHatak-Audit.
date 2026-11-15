package com.videohatak.player.feature.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.PlayerUiState

@Composable
fun MiniPlayerBar(
    video: VideoItem,
    uiState: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit = {},
    onNextTrack: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surfaceElevated)
            .clickable(onClick = onExpand)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.border)
        )
        LinearProgressIndicator(
            progress = { uiState.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = palette.accent,
            trackColor = palette.surface
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.safeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${uiState.formattedCurrentTime} / ${uiState.formattedTotalDuration}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceMuted
                )
            }

            if (uiState.hasPreviousMediaItem) {
                IconButton(onClick = onPreviousTrack) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "السابق",
                        tint = palette.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "إيقاف مؤقت" else "تشغيل",
                    tint = palette.accent,
                    modifier = Modifier.size(30.dp)
                )
            }

            if (uiState.hasNextMediaItem) {
                IconButton(onClick = onNextTrack) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "التالي",
                        tint = palette.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "إغلاق",
                    tint = palette.onSurfaceMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
