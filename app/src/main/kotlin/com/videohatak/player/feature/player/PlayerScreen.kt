package com.videohatak.player.feature.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.playback.FrameCaptureHelper
import com.videohatak.player.core.playback.FrameCaptureResult
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playback.pip.PipHelper
import com.videohatak.player.feature.player.components.DualSubtitlesOverlay
import com.videohatak.player.feature.player.components.PlayerControlsOverlay
import com.videohatak.player.feature.player.components.SubtitleManagerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class ActiveDragAxis { NONE, SEEK, VOLUME, BRIGHTNESS }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    video: VideoItem,
    contextVideos: List<VideoItem> = emptyList(),
    viewModel: PlayerViewModel,
    isInPipMode: Boolean = false,
    onUserInteraction: () -> Unit = {},
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val palette = LocalVideoHatakPalette.current
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val thumbnailState by viewModel.thumbnailPreviewState.collectAsStateWithLifecycle()
    val primaryStyle by viewModel.primarySubtitleStyle.collectAsStateWithLifecycle()
    val secondaryStyle by viewModel.secondarySubtitleStyle.collectAsStateWithLifecycle()
    val pendingResumePrompt by viewModel.pendingResumePrompt.collectAsStateWithLifecycle()

    var isSubtitlesDialogVisible by remember { mutableStateOf(false) }
    val pipHelper = remember { PipHelper(context) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Manual screen rotation state
    var isLandscape by remember { mutableStateOf(true) }

    // Pinch-to-Zoom + Pan state
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize(1080, 720)) }

    // Gesture feedback HUD states
    var statusBannerText by remember { mutableStateOf<String?>(null) }
    var activeFeedbackText by remember { mutableStateOf<String?>(null) }
    var activeFeedbackIcon by remember { mutableStateOf<String?>(null) }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // Track horizontal/vertical drag states
    var activeDragAxis by remember { mutableStateOf(ActiveDragAxis.NONE) }
    var dragSeekTargetMs by remember { mutableStateOf(0L) }
    var dragStartBrightness by remember { mutableFloatStateOf(0.5f) }
    var dragStartVolume by remember { mutableFloatStateOf(0.5f) }
    var isMultiTouchActive by remember { mutableStateOf(false) }

    LaunchedEffect(statusBannerText) {
        if (statusBannerText != null) {
            delay(2600L)
            statusBannerText = null
        }
    }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف ترجمة خارجي"
            viewModel.importSubtitle(context, uri, name)
        }
    }

    val isVaultVideo = video.bucketId == "secure_vault" ||
        video.contentUri.scheme == "vault" ||
        uiState.mediaUri?.scheme == "vault"

    fun performFrameCapture() {
        val currentUri = uiState.mediaUri ?: video.contentUri
        val vaultProtected = isVaultVideo || currentUri.scheme == "vault"
        scope.launch {
            when (
                val res = FrameCaptureHelper.captureAndSaveFrame(
                    context = context,
                    playerView = playerViewRef,
                    videoUri = currentUri,
                    positionMs = uiState.currentPositionMs,
                    isVaultVideo = vaultProtected
                )
            ) {
                is FrameCaptureResult.Success -> statusBannerText = res.message
                is FrameCaptureResult.Error -> statusBannerText = res.message
            }
        }
    }

    val legacyWritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            performFrameCapture()
        } else {
            statusBannerText = "يرجى منح صلاحية التخزين لحفظ لقطات الفيديو"
        }
    }

    LaunchedEffect(video.contentUri) {
        if (uiState.mediaUri != video.contentUri) {
            if (contextVideos.size > 1) {
                viewModel.playVideoInContext(video, contextVideos)
            } else {
                viewModel.playVideo(video)
            }
        }
    }

    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val originalBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            viewModel.clearTemporaryPlaybackSpeed()
            activity?.requestedOrientation = originalOrientation
            activity?.window?.let { win ->
                val attrs = win.attributes
                attrs.screenBrightness = originalBrightness
                win.attributes = attrs
            }
        }
    }

    // Minimize/Back keeps video playing smoothly in background + MiniPlayerBar + notification
    BackHandler {
        onBackClick()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewportSize = it }
            // Track user interaction & release temporary 2.0x speed hold when finger lifts anywhere
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            onUserInteraction()
                        }
                        if (uiState.isTemporarySpeedActive && event.changes.none { it.pressed }) {
                            viewModel.clearTemporaryPlaybackSpeed()
                        }
                        isMultiTouchActive = event.changes.count { it.pressed } >= 2
                    }
                }
            }
            // Two-finger Pinch-to-Zoom & Pan (1.0f .. 3.0f) — never fights 1-finger gestures
            .pointerInput(uiState.isLocked) {
                if (uiState.isLocked) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (zoomScale * zoomChange).coerceIn(1.0f, 3.0f)
                            zoomScale = newScale
                            if (newScale > 1.02f) {
                                val maxOffsetX = (viewportSize.width * (newScale - 1f)) / 2f
                                val maxOffsetY = (viewportSize.height * (newScale - 1f)) / 2f
                                val updatedX = (panOffset.x + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                val updatedY = (panOffset.y + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                panOffset = Offset(updatedX, updatedY)
                                activeFeedbackIcon = "ZOOM"
                                activeFeedbackText = "${(newScale * 100).roundToInt()}%"
                            } else {
                                panOffset = Offset.Zero
                                activeFeedbackText = null
                            }
                            event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                        } else if (event.changes.none { it.pressed }) {
                            if (activeFeedbackIcon == "ZOOM") {
                                activeFeedbackText = null
                                activeFeedbackIcon = null
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Tap, Double-Tap (Left -10s, Center Play/Pause, Right +10s), Long-Press (2.0x hold)
            .pointerInput(uiState.isLocked, viewportSize) {
                detectTapGestures(
                    onTap = {
                        viewModel.toggleControls()
                    },
                    onDoubleTap = { offset ->
                        if (uiState.isLocked) return@detectTapGestures
                        val width = viewportSize.width.toFloat().coerceAtLeast(1f)
                        when {
                            offset.x < width * 0.35f -> {
                                val target = (uiState.currentPositionMs - 10_000L).coerceAtLeast(0L)
                                viewModel.seekTo(target)
                                statusBannerText = "⏪ -10 ثوان"
                            }
                            offset.x > width * 0.65f -> {
                                val maxDur = if (uiState.durationMs > 0L) uiState.durationMs else Long.MAX_VALUE
                                val target = (uiState.currentPositionMs + 10_000L).coerceAtMost(maxDur)
                                viewModel.seekTo(target)
                                statusBannerText = "⏩ +10 ثوان"
                            }
                            else -> {
                                if (uiState.isPlaying) {
                                    viewModel.pause()
                                    statusBannerText = "إيقاف مؤقت"
                                } else {
                                    viewModel.play()
                                    statusBannerText = "تشغيل"
                                }
                            }
                        }
                    },
                    onLongPress = {
                        if (!uiState.isLocked) {
                            viewModel.setTemporaryPlaybackSpeed(2.0f)
                        }
                    }
                )
            }
            // Single-finger Swipes: Horizontal = Seek, Vertical Right = Volume, Vertical Left = Brightness
            .pointerInput(uiState.isLocked, viewportSize, uiState.durationMs) {
                if (uiState.isLocked) return@pointerInput
                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (isMultiTouchActive) return@detectDragGestures
                        activeDragAxis = ActiveDragAxis.NONE
                        dragSeekTargetMs = uiState.currentPositionMs
                        val winBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                        dragStartBrightness = if (winBrightness in 0f..1f) winBrightness else 0.5f
                        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
                        val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (maxVol / 2)
                        dragStartVolume = curVol.toFloat() / maxVol.toFloat()
                    },
                    onDrag = { change, dragAmount ->
                        if (isMultiTouchActive) return@detectDragGestures
                        change.consume()

                        if (activeDragAxis == ActiveDragAxis.NONE) {
                            val width = viewportSize.width.toFloat().coerceAtLeast(1f)
                            activeDragAxis = if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                ActiveDragAxis.SEEK
                            } else if (change.position.x > width * 0.5f) {
                                ActiveDragAxis.VOLUME
                            } else {
                                ActiveDragAxis.BRIGHTNESS
                            }
                        }

                        when (activeDragAxis) {
                            ActiveDragAxis.SEEK -> {
                                val duration = uiState.durationMs.coerceAtLeast(1L)
                                val deltaMs = (dragAmount.x / viewportSize.width.coerceAtLeast(1)) * 90_000f
                                dragSeekTargetMs = (dragSeekTargetMs + deltaMs.toLong()).coerceIn(0L, duration)
                                activeFeedbackIcon = "SEEK"
                                activeFeedbackText = formatTimeMs(dragSeekTargetMs)
                            }
                            ActiveDragAxis.VOLUME -> {
                                val delta = -dragAmount.y / viewportSize.height.coerceAtLeast(1)
                                dragStartVolume = (dragStartVolume + delta).coerceIn(0f, 1f)
                                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
                                val targetVol = (dragStartVolume * maxVol).roundToInt().coerceIn(0, maxVol)
                                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                activeFeedbackIcon = "VOL"
                                activeFeedbackText = "الصوت ${(dragStartVolume * 100).roundToInt()}%"
                            }
                            ActiveDragAxis.BRIGHTNESS -> {
                                val delta = -dragAmount.y / viewportSize.height.coerceAtLeast(1)
                                dragStartBrightness = (dragStartBrightness + delta).coerceIn(0.05f, 1f)
                                activity?.window?.let { win ->
                                    val attrs = win.attributes
                                    attrs.screenBrightness = dragStartBrightness
                                    win.attributes = attrs
                                }
                                activeFeedbackIcon = "BRIGHT"
                                activeFeedbackText = "السطوع ${(dragStartBrightness * 100).roundToInt()}%"
                            }
                            ActiveDragAxis.NONE -> Unit
                        }
                    },
                    onDragEnd = {
                        if (activeDragAxis == ActiveDragAxis.SEEK) {
                            viewModel.seekTo(dragSeekTargetMs)
                        }
                        activeDragAxis = ActiveDragAxis.NONE
                        activeFeedbackText = null
                        activeFeedbackIcon = null
                    },
                    onDragCancel = {
                        activeDragAxis = ActiveDragAxis.NONE
                        activeFeedbackText = null
                        activeFeedbackIcon = null
                    }
                )
            }
    ) {
        val density = LocalDensity.current
        val videoModifier = when (uiState.resizeModeEnum) {
            VideoResizeMode.RATIO_16_9 -> Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
            VideoResizeMode.RATIO_4_3 -> Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
            VideoResizeMode.ORIGINAL_SIZE -> {
                if (uiState.videoWidth > 0 && uiState.videoHeight > 0) {
                    val boundedWidthPx = uiState.videoWidth.coerceAtMost(viewportSize.width.coerceAtLeast(1))
                    val boundedHeightPx = uiState.videoHeight.coerceAtMost(viewportSize.height.coerceAtLeast(1))
                    val widthRatio = boundedWidthPx.toFloat() / uiState.videoWidth.toFloat()
                    val heightRatio = boundedHeightPx.toFloat() / uiState.videoHeight.toFloat()
                    val scale = minOf(widthRatio, heightRatio)
                    val targetW = with(density) { (uiState.videoWidth * scale).toDp() }
                    val targetH = with(density) { (uiState.videoHeight * scale).toDp() }
                    Modifier
                        .align(Alignment.Center)
                        .size(targetW, targetH)
                } else {
                    Modifier.fillMaxSize()
                }
            }
            else -> Modifier.fillMaxSize()
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = VideoPlayerManager.getInstance(ctx).exoPlayer
                    resizeMode = uiState.resizeModeEnum.playerViewResizeMode
                    playerViewRef = this
                }
            },
            update = { playerView ->
                playerView.resizeMode = uiState.resizeModeEnum.playerViewResizeMode
                playerViewRef = playerView
            },
            modifier = videoModifier.graphicsLayer {
                scaleX = zoomScale
                scaleY = zoomScale
                translationX = panOffset.x
                translationY = panOffset.y
            }
        )

        if (!isInPipMode) {
            DualSubtitlesOverlay(
                primaryCues = uiState.activePrimaryCues,
                secondaryCues = uiState.activeSecondaryCues,
                primaryStyle = primaryStyle,
                secondaryStyle = secondaryStyle
            )
        }

        if (uiState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = palette.accent
            )
        }

        // Temporary 2.0x Speed Banner
        if (uiState.isTemporarySpeedActive) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
                shape = RoundedCornerShape(20.dp),
                color = palette.surface.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, palette.accent)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${uiState.temporarySpeed}x سرعة مؤقتة",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurface
                    )
                }
            }
        }

        // Gesture Center Feedback Pill (Seek / Volume / Brightness / Zoom)
        val feedback = activeFeedbackText
        if (feedback != null) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                color = palette.surface.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, palette.accent)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeFeedbackIcon == "VOL") {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = palette.accent)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else if (activeFeedbackIcon == "BRIGHT") {
                        Icon(Icons.Default.BrightnessHigh, contentDescription = null, tint = palette.accent)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else if (activeFeedbackIcon == "ZOOM") {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = null, tint = palette.accent)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurface
                    )
                }
            }
        }

        // Floating confirmation pill (e.g. screenshot saved, +10s, -10s)
        val banner = statusBannerText
        if (banner != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp),
                shape = RoundedCornerShape(20.dp),
                color = palette.surface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, palette.accent)
            ) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (!isInPipMode && !uiState.isPipActive) {
            PlayerControlsOverlay(
                uiState = uiState,
                thumbnailState = thumbnailState,
                zoomScale = zoomScale,
                onBack = onBackClick,
                onPlayPause = {
                    if (uiState.isPlaying) viewModel.pause() else viewModel.play()
                },
                onPreviousTrack = { viewModel.previousTrack() },
                onNextTrack = { viewModel.nextTrack() },
                onSeekStart = viewModel::onScrubStart,
                onSeekProgress = viewModel::onScrubProgress,
                onSeekStop = viewModel::onScrubStop,
                onForward10 = {
                    val maxDur = if (uiState.durationMs > 0L) uiState.durationMs else Long.MAX_VALUE
                    viewModel.seekTo((uiState.currentPositionMs + 10_000L).coerceAtMost(maxDur))
                },
                onReplay10 = { viewModel.seekTo((uiState.currentPositionMs - 10_000L).coerceAtLeast(0L)) },
                onToggleLock = viewModel::toggleLock,
                onRotateScreen = {
                    isLandscape = !isLandscape
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                },
                onCaptureFrame = {
                    val legacyPerm = PermissionHelper.getLegacyWritePermission()
                    if (legacyPerm != null && !PermissionHelper.hasLegacyWritePermission(context)) {
                        legacyWritePermissionLauncher.launch(legacyPerm)
                    } else {
                        performFrameCapture()
                    }
                },
                onResetZoom = {
                    zoomScale = 1f
                    panOffset = Offset.Zero
                },
                isPipAllowed = !isVaultVideo,
                onEnterPip = {
                    if (!isVaultVideo) {
                        activity?.let {
                            val entered = pipHelper.enterPip(it, uiState.isPlaying)
                            if (entered) viewModel.setPipActive(true)
                        }
                    } else {
                        statusBannerText = "صورة في صورة معطّلة داخل الخزنة الخاصة"
                    }
                },
                onCycleResizeMode = {
                    val next = viewModel.cycleResizeMode()
                    statusBannerText = next.titleArabic
                },
                onSelectResizeMode = { mode ->
                    viewModel.setVideoResizeMode(mode)
                    statusBannerText = mode.titleArabic
                },
                onOpenSubtitlesDialog = { isSubtitlesDialogVisible = true },
                onSpeedClick = {
                    val speeds = VideoPlayerManager.SUPPORTED_PLAYBACK_SPEEDS
                    val nextIndex = (speeds.indexOf(uiState.playbackSpeed) + 1) % speeds.size
                    val nextSpeed = speeds[nextIndex]
                    viewModel.setPlaybackSpeed(nextSpeed)
                    statusBannerText = "السرعة ${VideoPlayerManager.formatSpeedLabel(nextSpeed)}"
                },
                onSelectSpeed = { speed ->
                    viewModel.setPlaybackSpeed(speed)
                    statusBannerText = "السرعة ${VideoPlayerManager.formatSpeedLabel(speed)}"
                },
                onSelectPlaybackMode = viewModel::setPlaybackMode,
                onCycleRepeatMode = viewModel::cycleRepeatMode
            )
        }

        // Floating Reset-Zoom Pill even when controls are hidden while zoomed in
        if (zoomScale > 1.05f && !uiState.isControlsVisible && !uiState.isLocked && !isInPipMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .clickable {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    },
                shape = RoundedCornerShape(20.dp),
                color = palette.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, palette.accent)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ZoomOutMap, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إعادة ضبط التكبير (100%)", color = palette.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3rd Resume Mode: ASK_EVERY_TIME Dialog
        val resumePrompt = pendingResumePrompt
        if (resumePrompt != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissResumePrompt() },
                containerColor = palette.surface,
                titleContentColor = palette.onSurface,
                textContentColor = palette.onSurface,
                title = {
                    Text("استئناف التشغيل؟", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = resumePrompt.videoTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.accent
                        )
                        Text(
                            text = "توقفت سابقًا عند ${formatTimeMs(resumePrompt.savedPositionMs)}. هل تريد الاستئناف من نفس الموضع أم البدء من البداية؟",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceMuted
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.resolveResumePrompt(resumeFromSaved = true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("استئناف (${formatTimeMs(resumePrompt.savedPositionMs)})", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { viewModel.resolveResumePrompt(resumeFromSaved = false) },
                        border = BorderStroke(1.dp, palette.border)
                    ) {
                        Text("البدء من البداية")
                    }
                }
            )
        }

        if (isSubtitlesDialogVisible) {
            SubtitleManagerDialog(
                availableTracks = uiState.availableSubtitleTracks,
                primaryTrack = uiState.primaryTrack,
                secondaryTrack = uiState.secondaryTrack,
                primaryStyle = primaryStyle,
                secondaryStyle = secondaryStyle,
                onSelectPrimaryTrack = viewModel::setPrimarySubtitleTrack,
                onSelectSecondaryTrack = viewModel::setSecondarySubtitleTrack,
                onImportSubtitle = {
                    isSubtitlesDialogVisible = false
                    subtitlePickerLauncher.launch("*/*")
                },
                onChangePrimaryFontSize = viewModel::updatePrimaryFontSize,
                onChangeSecondaryFontSize = viewModel::updateSecondaryFontSize,
                onDismiss = { isSubtitlesDialogVisible = false }
            )
        }
    }
}

private fun formatTimeMs(millis: Long): String {
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
