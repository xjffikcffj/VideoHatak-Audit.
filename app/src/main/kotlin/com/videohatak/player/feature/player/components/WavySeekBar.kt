package com.videohatak.player.feature.player.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import kotlin.math.PI
import kotlin.math.sin

/**
 * Design #4 Wavy Progress Seekbar:
 * - Fine, dense, smooth sine wave along full bar length
 * - Soft rounded curves with subtle peak-to-peak height
 * - Played segment glows dynamically in the active theme's accent colors
 * - Smooth draggable circular thumb & clean LTR playback timeline
 */
@Composable
fun WavySeekBar(
    currentPositionMs: Long,
    durationMs: Long,
    onSeekStart: (Long, Float) -> Unit,
    onSeekProgress: (Long, Float) -> Unit,
    onSeekStop: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVideoHatakPalette.current
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val baseFraction = (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val activeFraction = if (isDragging) dragFraction else baseFraction
    val displayedPositionMs = if (isDragging) {
        (dragFraction * safeDuration).toLong()
    } else {
        currentPositionMs.coerceAtLeast(0L)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val targetMs = (frac * safeDuration).toLong()
                            onSeekStart(targetMs, frac)
                            onSeekStop(targetMs)
                        }
                    }
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                dragFraction = frac
                                onSeekStart((frac * safeDuration).toLong(), frac)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                dragFraction = frac
                                onSeekProgress((frac * safeDuration).toLong(), frac)
                            },
                            onDragEnd = {
                                val targetMs = (dragFraction * safeDuration).toLong()
                                isDragging = false
                                onSeekStop(targetMs)
                            },
                            onDragCancel = {
                                isDragging = false
                            }
                        )
                    }
            ) {
                val width = size.width
                val midY = size.height / 2f
                val amplitude = 3.2.dp.toPx()
                val wavelengthPx = 22.dp.toPx()
                val thumbX = (activeFraction * width).coerceIn(0f, width)
                val thumbWaveY = midY + sin((thumbX / wavelengthPx) * 2f * PI).toFloat() * amplitude

                val playedPath = Path()
                val unplayedPath = Path()

                val stepPx = 2.5f
                var x = 0f
                while (x <= width) {
                    val y = midY + sin((x / wavelengthPx) * 2f * PI).toFloat() * amplitude
                    if (x <= thumbX) {
                        if (playedPath.isEmpty) playedPath.moveTo(x, y) else playedPath.lineTo(x, y)
                    } else {
                        if (unplayedPath.isEmpty) {
                            unplayedPath.moveTo(thumbX, thumbWaveY)
                        }
                        unplayedPath.lineTo(x, y)
                    }
                    x += stepPx
                }

                // Exact join point at thumbX for played curve
                if (thumbX > 0f) {
                    playedPath.lineTo(thumbX, thumbWaveY)
                }

                // Unplayed subtle wave
                if (!unplayedPath.isEmpty) {
                    drawPath(
                        path = unplayedPath,
                        color = palette.seekBarUnplayed,
                        style = Stroke(
                            width = 2.2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Played segment: soft outer theme glow + crisp inner theme gradient
                if (!playedPath.isEmpty) {
                    drawPath(
                        path = playedPath,
                        color = palette.seekBarGlow,
                        style = Stroke(
                            width = 6.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    drawPath(
                        path = playedPath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(palette.seekBarPlayedStart, palette.seekBarPlayedEnd),
                            startX = 0f,
                            endX = thumbX.coerceAtLeast(1f)
                        ),
                        style = Stroke(
                            width = 2.8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Smooth draggable circular thumb with active theme halo
                val thumbRadius = if (isDragging) 9.dp.toPx() else 7.dp.toPx()
                drawCircle(
                    color = palette.seekBarGlow,
                    radius = thumbRadius * 1.65f,
                    center = Offset(thumbX, thumbWaveY)
                )
                drawCircle(
                    color = palette.seekBarThumb,
                    radius = thumbRadius,
                    center = Offset(thumbX, thumbWaveY)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatSeekTime(displayedPositionMs),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onSurface
                )
                Text(
                    text = formatSeekTime(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.onSurfaceMuted
                )
            }
        }
    }
}

private fun formatSeekTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
