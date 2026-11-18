package com.videohatak.player.feature.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.OpenableColumns
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Headphones
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.videohatak.player.R
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.playback.FrameCaptureHelper
import com.videohatak.player.core.playback.FrameCaptureResult
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playback.pip.PipHelper
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.feature.library.components.RenameVideoDialog
import com.videohatak.player.feature.library.components.VideoInfoDialog
import com.videohatak.player.feature.player.components.DualSubtitlesOverlay
import com.videohatak.player.feature.player.components.PlayerControlsOverlay
import com.videohatak.player.feature.player.components.SmartSubtitleTranslationDialog
import com.videohatak.player.feature.player.components.SubtitleCueEditorDialog
import com.videohatak.player.feature.player.components.SubtitleManagerDialog
import com.videohatak.player.feature.playlist.components.AddToPlaylistDialog
import com.videohatak.player.feature.playlist.components.CreatePlaylistDialog
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
    playlists: List<Playlist> = emptyList(),
    isFavorite: Boolean = false,
    onToggleFavorite: (VideoItem) -> Unit = {},
    onToggleFavoriteWithResult: ((VideoItem, (Boolean, String) -> Unit) -> Unit)? = null,
    onAddToPlaylists: (VideoItem, List<Long>) -> Unit = { _, _ -> },
    onAddToPlaylistsWithResult: ((VideoItem, List<Long>, (Boolean, String) -> Unit) -> Unit)? = null,
    onCreatePlaylistAndAdd: (String, VideoItem) -> Unit = { _, _ -> },
    onCreatePlaylistAndAddWithResult: ((String, VideoItem, (Boolean, String) -> Unit) -> Unit)? = null,
    onAddToVault: (VideoItem) -> Unit = {},
    onRenameVideo: (VideoItem, String) -> Unit = { _, _ -> },
    onMoveToTrash: (VideoItem) -> Unit = {},
    onPlaylistMutated: () -> Unit = {},
    onUserInteraction: () -> Unit = {},
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val palette = LocalVideoHatakPalette.current
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val thumbnailState by viewModel.thumbnailPreviewState.collectAsStateWithLifecycle()
    val primaryStyle by viewModel.primarySubtitleStyle.collectAsStateWithLifecycle()
    val secondaryStyle by viewModel.secondarySubtitleStyle.collectAsStateWithLifecycle()

    val subtitleFeedback by viewModel.subtitleFeedbackMessage.collectAsStateWithLifecycle()
    val translationStatus by viewModel.translationStatus.collectAsStateWithLifecycle()
    val translationProxyUrl by viewModel.translationProxyUrl.collectAsStateWithLifecycle()
    val translationEngineMode by viewModel.translationEngineMode.collectAsStateWithLifecycle()
    val offlineModelStates by viewModel.offlineModelStates.collectAsStateWithLifecycle()
    val detectedAudioLanguage by viewModel.detectedVideoAudioLanguage.collectAsStateWithLifecycle()
    val availableAudioTracks by viewModel.availableAudioTracks.collectAsStateWithLifecycle()
    val selectedAudioTrackIndex by viewModel.selectedAudioTrackIndex.collectAsStateWithLifecycle()
    val isPlaylistLoadPending by viewModel.isPlaylistLoadPending.collectAsStateWithLifecycle()

    var isSubtitlesDialogVisible by remember { mutableStateOf(false) }
    var trackForLocalEdit by remember { mutableStateOf<SubtitleTrack?>(null) }
    var showSmartTranslationDialog by remember { mutableStateOf(false) }
    var pendingExportSubtitleContent by remember { mutableStateOf<String?>(null) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var showTrashConfirmDialog by remember { mutableStateOf(false) }

    val pipHelper = remember { PipHelper(context) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activeVideoItem = viewModel.currentVideo ?: video

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

    LaunchedEffect(subtitleFeedback) {
        val msg = subtitleFeedback
        if (!msg.isNullOrBlank()) {
            statusBannerText = msg
            viewModel.clearSubtitleFeedback()
        }
    }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolvedName = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
            }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "ملف ترجمة خارجي"
            viewModel.importSubtitle(context, uri, resolvedName)
        }
    }

    val subtitleExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { destUri ->
        val contentToWrite = pendingExportSubtitleContent
        pendingExportSubtitleContent = null
        if (destUri != null && !contentToWrite.isNullOrBlank()) {
            val savedOk = runCatching {
                context.contentResolver.openOutputStream(destUri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(contentToWrite)
                }
                true
            }.getOrDefault(false)
            statusBannerText = if (savedOk) {
                "تم تصدير ملف الترجمة بنجاح"
            } else {
                "تعذر حفظ ملف الترجمة في الموقع المحدد"
            }
        }
    }

    val isVaultVideo = activeVideoItem.bucketId == "secure_vault" ||
        activeVideoItem.contentUri.scheme == "vault" ||
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
        if (!isPlaylistLoadPending && uiState.mediaUri != video.contentUri) {
            if (contextVideos.size > 1) {
                viewModel.playVideoInContext(video, contextVideos)
            } else {
                viewModel.playVideo(video)
            }
        }
    }

    LaunchedEffect(isInPipMode) {
        viewModel.setPipActive(isInPipMode)
        if (!isInPipMode) {
            viewModel.setControlsVisible(true)
        }
    }

    val orientationMode = uiState.playerOrientationMode
    DisposableEffect(orientationMode) {
        activity?.requestedOrientation = orientationMode.activityOrientation
        onDispose { }
    }

    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val originalBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        onDispose {
            viewModel.setPipActive(false)
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
                        val intervalSec = uiState.seekIntervalSeconds
                        when {
                            offset.x < width * 0.35f -> {
                                viewModel.seekBackward()
                                statusBannerText = "⏪ -$intervalSec ثوان"
                            }
                            offset.x > width * 0.65f -> {
                                viewModel.seekForward()
                                statusBannerText = "⏩ +$intervalSec ثوان"
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
        val fittedPx = VideoResizeMode.computeFittedVideoSize(
            viewportWidthPx = viewportSize.width,
            viewportHeightPx = viewportSize.height,
            resizeMode = uiState.resizeModeEnum,
            videoWidth = uiState.videoWidth,
            videoHeight = uiState.videoHeight
        )
        val videoModifier = if (fittedPx != null) {
            val targetW = with(density) { fittedPx.first.toDp() }
            val targetH = with(density) { fittedPx.second.toDp() }
            Modifier
                .align(Alignment.Center)
                .size(targetW, targetH)
        } else {
            Modifier.fillMaxSize()
        }

        // Layer 0: TextureView-backed PlayerView inside a clipped, z-ordered container so Compose
        // controls/overlays never fall behind the video surface in portrait or landscape.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .zIndex(0f)
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.videohatak_player_view, null, false) as PlayerView).apply {
                        useController = false
                        keepScreenOn = true
                        player = VideoPlayerManager.getInstance(ctx).exoPlayer
                        resizeMode = uiState.resizeModeEnum.playerViewResizeMode
                        playerViewRef = this
                    }
                },
                update = { playerView ->
                    val managerPlayer = VideoPlayerManager.getInstance(playerView.context).exoPlayer
                    if (playerView.player !== managerPlayer) {
                        playerView.player = managerPlayer
                    }
                    playerView.resizeMode = uiState.resizeModeEnum.playerViewResizeMode
                    playerViewRef = playerView
                },
                modifier = videoModifier
                    .alpha(if (uiState.isAudioOnlyMode) 0f else 1f)
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panOffset.x
                        translationY = panOffset.y
                    }
            )

            if (uiState.isAudioOnlyMode) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.background)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = palette.accent.copy(alpha = 0.16f),
                        border = BorderStroke(1.5.dp, palette.accent),
                        modifier = Modifier.size(92.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Headphones,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = uiState.title.ifBlank { activeVideoItem.safeTitle },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "وضع الاستماع فقط — يتم تشغيل الصوت مع إيقاف مسار الفيديو",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onSurfaceMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.setAudioOnlyMode(false)
                            statusBannerText = "تمت العودة إلى عرض الفيديو"
                        },
                        border = BorderStroke(1.dp, palette.accent)
                    ) {
                        Text(
                            text = "العودة لعرض الفيديو",
                            color = palette.accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (!isInPipMode && !uiState.isAudioOnlyMode) {
            DualSubtitlesOverlay(
                primaryCues = uiState.activePrimaryCues,
                secondaryCues = uiState.activeSecondaryCues,
                primaryStyle = primaryStyle,
                secondaryStyle = secondaryStyle,
                modifier = Modifier.zIndex(1f)
            )
        }

        if (uiState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(1.5f),
                color = palette.accent
            )
        }

        // Temporary 2.0x Speed Banner
        if (uiState.isTemporarySpeedActive) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .zIndex(3f),
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
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(3f),
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
                    .padding(top = 72.dp)
                    .zIndex(3f),
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
                onForward10 = { viewModel.seekForward() },
                onReplay10 = { viewModel.seekBackward() },
                onToggleLock = viewModel::toggleLock,
                onRotateScreen = {
                    val next = viewModel.cycleOrientationMode()
                    statusBannerText = "تدوير الشاشة: ${next.arabicLabel}"
                    viewModel.setControlsVisible(true)
                },
                onCycleOrientationMode = {
                    val next = viewModel.cycleOrientationMode()
                    statusBannerText = "تدوير الشاشة: ${next.arabicLabel}"
                    viewModel.setControlsVisible(true)
                },
                onCaptureFrame = {
                    if (uiState.isAudioOnlyMode) {
                        statusBannerText = "يرجى إيقاف وضع الاستماع فقط لالتقاط إطار من الفيديو"
                    } else {
                        val legacyPerm = PermissionHelper.getLegacyWritePermission()
                        if (legacyPerm != null && !PermissionHelper.hasLegacyWritePermission(context)) {
                            runCatching {
                                legacyWritePermissionLauncher.launch(legacyPerm)
                            }.onFailure {
                                statusBannerText = "تعذر فتح نافذة طلب إذن التخزين"
                            }
                        } else {
                            performFrameCapture()
                        }
                    }
                },
                onResetZoom = {
                    zoomScale = 1f
                    panOffset = Offset.Zero
                },
                isPipAllowed = !isVaultVideo && !uiState.isAudioOnlyMode,
                onEnterPip = {
                    if (uiState.isAudioOnlyMode) {
                        statusBannerText = "وضع الاستماع فقط يعمل بالخلفية مباشرة دون نافذة فيديو"
                    } else if (!isVaultVideo) {
                        activity?.let {
                            val w = uiState.videoWidth.takeIf { width -> width > 0 } ?: activeVideoItem.width
                            val h = uiState.videoHeight.takeIf { height -> height > 0 } ?: activeVideoItem.height
                            val entered = pipHelper.enterPip(
                                activity = it,
                                isPlaying = uiState.isPlaying,
                                videoAspectRatio = PipHelper.getValidAspectRatio(w, h),
                                seekIntervalSeconds = uiState.seekIntervalSeconds
                            )
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
                isFavorite = isFavorite,
                isPlayingFromPlaylist = uiState.activePlaylistId != null,
                onRemoveFromPlaylist = {
                    viewModel.removeCurrentVideoFromActivePlaylist { emptied ->
                        onPlaylistMutated()
                        if (emptied) {
                            onBackClick()
                        } else {
                            statusBannerText = "تمت إزالة الفيديو من القائمة فقط"
                        }
                    }
                },
                onAddToPlaylist = {
                    if (isVaultVideo) {
                        statusBannerText = "لا يمكن إضافة مقاطع الخزنة الخاصة إلى قوائم التشغيل العامة"
                    } else {
                        showAddToPlaylistDialog = true
                    }
                },
                onToggleFavorite = {
                    if (isVaultVideo) {
                        statusBannerText = "مقاطع الخزنة الخاصة معزولة عن المفضلة العامة"
                    } else if (onToggleFavoriteWithResult != null) {
                        onToggleFavoriteWithResult(activeVideoItem) { _, msg ->
                            statusBannerText = msg
                        }
                    } else {
                        onToggleFavorite(activeVideoItem)
                    }
                },
                onAddToVault = {
                    if (isVaultVideo) {
                        statusBannerText = "هذا الفيديو موجود بالفعل داخل الخزنة الخاصة"
                    } else {
                        statusBannerText = "جارٍ تشفير الفيديو لنقله إلى الخزنة الخاصة..."
                        onAddToVault(activeVideoItem)
                    }
                },
                onRenameVideo = {
                    showRenameDialog = true
                },
                onMoveToTrash = {
                    if (isVaultVideo) {
                        statusBannerText = "لحذف مقاطع الخزنة استخدم إدارة الخزنة الخاصة"
                    } else {
                        showTrashConfirmDialog = true
                    }
                },
                onShowDetails = {
                    showDetailsDialog = true
                },
                onToggleAudioOnly = {
                    val enabled = viewModel.toggleAudioOnlyMode()
                    statusBannerText = if (enabled) {
                        "تم تفعيل وضع الاستماع فقط"
                    } else {
                        "تمت العودة إلى وضع الفيديو"
                    }
                },
                modifier = Modifier.zIndex(2f)
            )
        }

        // Floating Reset-Zoom Pill even when controls are hidden while zoomed in
        if (zoomScale > 1.05f && !uiState.isControlsVisible && !uiState.isLocked && !isInPipMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .zIndex(3f)
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
                    runCatching {
                        subtitlePickerLauncher.launch(
                            arrayOf(
                                "application/x-subrip",
                                "text/vtt",
                                "text/plain",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    }.onFailure {
                        statusBannerText = "تعذر فتح منتقي ملفات الترجمة على هذا الجهاز"
                    }
                },
                onChangePrimaryFontSize = viewModel::updatePrimaryFontSize,
                onChangeSecondaryFontSize = viewModel::updateSecondaryFontSize,
                onDismiss = { isSubtitlesDialogVisible = false },
                areSubtitlesVisible = uiState.areSubtitlesVisible,
                onToggleSubtitlesVisible = viewModel::setSubtitlesVisible,
                primaryOffsetMs = uiState.primarySubtitleOffsetMs,
                onAdjustPrimaryOffsetMs = viewModel::adjustPrimarySubtitleOffsetMs,
                onResetPrimaryOffset = viewModel::resetPrimarySubtitleOffset,
                secondaryOffsetMs = uiState.secondarySubtitleOffsetMs,
                onAdjustSecondaryOffsetMs = viewModel::adjustSecondarySubtitleOffsetMs,
                onResetSecondaryOffset = viewModel::resetSecondarySubtitleOffset,
                onEditTrackLocally = { track ->
                    isSubtitlesDialogVisible = false
                    trackForLocalEdit = track
                },
                onRemoveTrack = { track ->
                    viewModel.removeSubtitleTrack(track.id)
                    statusBannerText = "تمت إزالة مسار \"${track.name}\""
                },
                onOpenSmartTranslation = {
                    isSubtitlesDialogVisible = false
                    showSmartTranslationDialog = true
                }
            )
        }

        val editingTrack = trackForLocalEdit?.let { latest ->
            uiState.availableSubtitleTracks.firstOrNull { it.id == latest.id } ?: latest
        }
        if (editingTrack != null) {
            SubtitleCueEditorDialog(
                track = editingTrack,
                onUpdateCueText = { cueIdx, newText ->
                    viewModel.updateSubtitleCueText(editingTrack.id, cueIdx, newText)
                },
                onExportTrack = { track, format ->
                    pendingExportSubtitleContent = viewModel.exportTrackAsText(track, format)
                    val ext = if (format == SubtitleFormat.WEBVTT) "vtt" else "srt"
                    val base = track.name.substringBeforeLast('.').ifBlank { "subtitle" }
                    runCatching {
                        subtitleExportLauncher.launch("${base}_edited.$ext")
                    }.onFailure {
                        pendingExportSubtitleContent = null
                        statusBannerText = "تعذر فتح نافذة تصدير ملف الترجمة على هذا الجهاز"
                    }
                },
                onDismiss = { trackForLocalEdit = null }
            )
        }

        if (showSmartTranslationDialog) {
            val currentMediaUri = uiState.mediaUri ?: activeVideoItem.contentUri
            LaunchedEffect(currentMediaUri) {
                viewModel.refreshOfflineModelStates()
                viewModel.inspectVideoAudioLanguage(context, currentMediaUri)
            }
            SmartSubtitleTranslationDialog(
                availableTracks = uiState.availableSubtitleTracks,
                selectedSourceTrack = uiState.primaryTrack ?: uiState.secondaryTrack,
                proxyUrl = translationProxyUrl,
                translationStatus = translationStatus,
                onProxyUrlChange = viewModel::setTranslationProxyUrl,
                onStartTranslation = { mode, srcLang, dstLang, srcTrack, glossary ->
                    viewModel.startSmartSubtitleTranslation(
                        context = context,
                        videoUri = currentMediaUri,
                        sourceMode = mode,
                        sourceLanguage = srcLang,
                        targetLanguage = dstLang,
                        sourceTrack = srcTrack,
                        glossaryRawText = glossary
                    )
                },
                onCancelTranslation = viewModel::cancelSmartSubtitleTranslation,
                onUpdateTranslatedCue = viewModel::updateTranslatedDraftCue,
                onSaveAndAttach = { title, targetLang, format, asSecondary ->
                    viewModel.saveAndAttachTranslatedSubtitles(title, targetLang, format, asSecondary)
                    showSmartTranslationDialog = false
                },
                onExportTranslated = { exportedContent, format ->
                    pendingExportSubtitleContent = exportedContent
                    val ext = if (format == SubtitleFormat.WEBVTT) "vtt" else "srt"
                    runCatching {
                        subtitleExportLauncher.launch("${activeVideoItem.safeTitle}_translated.$ext")
                    }.onFailure {
                        pendingExportSubtitleContent = null
                        statusBannerText = "تعذر فتح نافذة تصدير الترجمة على هذا الجهاز"
                    }
                },
                onDismiss = { showSmartTranslationDialog = false },
                engineMode = translationEngineMode,
                onEngineModeChange = viewModel::setTranslationEngineMode,
                offlineModelStates = offlineModelStates,
                detectedAudioLanguage = detectedAudioLanguage,
                availableAudioTracks = availableAudioTracks,
                selectedAudioTrackIndex = selectedAudioTrackIndex,
                onSelectAudioTrack = viewModel::selectAudioTrackIndex,
                onDownloadSpeechModel = viewModel::downloadSpeechModel,
                onCancelSpeechDownload = viewModel::cancelSpeechModelDownload,
                onDeleteSpeechModel = viewModel::deleteSpeechModel,
                onDownloadTranslationModel = viewModel::downloadTranslationModel,
                onCancelTranslationDownload = viewModel::cancelTranslationModelDownload,
                onDeleteTranslationModel = viewModel::deleteTranslationModel,
                onDownloadRequiredPair = viewModel::downloadRequiredModelsForPair,
                isVaultVideo = isVaultVideo,
                showSourceTextWithTranslation = uiState.showSourceTextWithTranslation,
                onToggleShowSourceText = viewModel::setShowSourceTextWithTranslation
            )
        }

        if (showAddToPlaylistDialog) {
            AddToPlaylistDialog(
                playlists = playlists,
                onSelectPlaylist = { playlist ->
                    showAddToPlaylistDialog = false
                    if (onAddToPlaylistsWithResult != null) {
                        onAddToPlaylistsWithResult(activeVideoItem, listOf(playlist.id)) { _, msg ->
                            statusBannerText = msg
                        }
                    } else {
                        onAddToPlaylists(activeVideoItem, listOf(playlist.id))
                    }
                },
                onCreateNewPlaylist = {
                    showAddToPlaylistDialog = false
                    showCreatePlaylistDialog = true
                },
                onDismiss = { showAddToPlaylistDialog = false }
            )
        }

        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onConfirm = { newName ->
                    showCreatePlaylistDialog = false
                    if (onCreatePlaylistAndAddWithResult != null) {
                        onCreatePlaylistAndAddWithResult(newName, activeVideoItem) { _, msg ->
                            statusBannerText = msg
                        }
                    } else {
                        onCreatePlaylistAndAdd(newName, activeVideoItem)
                    }
                },
                onDismiss = { showCreatePlaylistDialog = false }
            )
        }

        if (showRenameDialog) {
            RenameVideoDialog(
                video = activeVideoItem,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newTitle ->
                    showRenameDialog = false
                    onRenameVideo(activeVideoItem, newTitle)
                    statusBannerText = "تم إرسال طلب تغيير الاسم إلى \"$newTitle\""
                }
            )
        }

        if (showDetailsDialog) {
            VideoInfoDialog(
                video = activeVideoItem,
                onMoveToTrashClick = if (isVaultVideo) null else {
                    { showTrashConfirmDialog = true }
                },
                onDismiss = { showDetailsDialog = false }
            )
        }

        if (showTrashConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showTrashConfirmDialog = false },
                containerColor = palette.surface,
                titleContentColor = palette.onSurface,
                textContentColor = palette.onSurface,
                title = { Text("نقل إلى سلة المهملات؟", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = "سيتم إيقاف تشغيل \"${activeVideoItem.safeTitle}\" ونقله إلى سلة المهملات.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onSurfaceMuted
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showTrashConfirmDialog = false
                            viewModel.stopPlaybackCompletely()
                            onMoveToTrash(activeVideoItem)
                            onBackClick()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("نقل إلى السلة", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTrashConfirmDialog = false }) {
                        Text("إلغاء", color = palette.onSurfaceMuted)
                    }
                }
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
