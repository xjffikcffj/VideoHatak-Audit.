package com.videohatak.player.core.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.service.MediaNotificationPrivacy
import com.videohatak.player.core.playback.service.VideoPlaybackService
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.vault.datasource.EncryptedVaultDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VideoPlayerManager internal constructor(
    private val context: Context?,
    private val customExoPlayer: ExoPlayer? = null
) {

    companion object {
        private const val TAG = "VideoPlayerManager"
        const val MIN_PLAYBACK_SPEED = 0.25f
        const val MAX_PLAYBACK_SPEED = 3.0f
        val SUPPORTED_PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

        fun clampPlaybackSpeed(speed: Float): Float {
            if (speed.isNaN() || speed.isInfinite()) return 1.0f
            return speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        }

        fun formatSpeedLabel(speed: Float): String {
            val rounded = ((speed * 100f).toInt()) / 100f
            return if (rounded % 1.0f == 0f) {
                "${rounded.toInt()}.0x"
            } else {
                val formatted = java.lang.String.format(java.util.Locale.US, "%.2f", rounded)
                    .trimEnd('0')
                    .trimEnd('.')
                "${formatted}x"
            }
        }

        @Volatile
        private var instance: VideoPlayerManager? = null

        fun getInstance(context: Context): VideoPlayerManager {
            return instance ?: synchronized(this) {
                instance ?: VideoPlayerManager(context.applicationContext).also { instance = it }
            }
        }

        fun createForTesting(exoPlayer: ExoPlayer, context: Context? = null): VideoPlayerManager {
            return VideoPlayerManager(context, exoPlayer)
        }
    }

    var onMediaTransition: ((String) -> Unit)? = null
    var onPeriodicPositionUpdate: ((uri: String, positionMs: Long, durationMs: Long) -> Unit)? = null
    private var lastReportedPositionMs: Long = 0L
    val restorationCoordinator = PlaybackRestorationCoordinator()
    val knownDurations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val internalTitlesByMediaKey = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getCurrentPlaybackGeneration(): Long = restorationCoordinator.getCurrentPlaybackGeneration()

    fun saveCurrentExoPlayerPositionDirectly(): Boolean {
        val currentUri = _uiState.value.mediaUri?.toString()
        if (!currentUri.isNullOrBlank()) {
            val actualPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val actualDur = exoPlayer.duration.coerceAtLeast(0L).takeIf { it > 0L }
                ?: (knownDurations[currentUri] ?: 0L)
            if (actualDur > 0L) {
                knownDurations[currentUri] = actualDur
            }
            if (actualPos > 0L) {
                lastReportedPositionMs = actualPos
                onPeriodicPositionUpdate?.invoke(currentUri, actualPos, actualDur)
            }
            return true
        }
        return false
    }

    fun applyRestoredPosition(targetUri: String, targetGeneration: Long, restoredPositionMs: Long) {
        val currentUri = _uiState.value.mediaUri?.toString()
        val durationMs = exoPlayer.duration.coerceAtLeast(0L)
        val isReady = exoPlayer.playbackState == Player.STATE_READY

        val decision = restorationCoordinator.evaluateRestoration(
            targetUri = targetUri,
            targetGeneration = targetGeneration,
            restoredPositionMs = restoredPositionMs,
            currentUri = currentUri,
            durationMs = durationMs,
            isReady = isReady
        )

        when (decision) {
            is PlaybackRestorationCoordinator.RestorationDecision.Apply -> {
                val effective = decision.effectivePositionMs
                if (effective > 0L) {
                    exoPlayer.seekTo(effective)
                    _uiState.update { it.copy(currentPositionMs = effective) }
                } else {
                    exoPlayer.seekTo(0L)
                    _uiState.update { it.copy(currentPositionMs = 0L) }
                }
            }
            is PlaybackRestorationCoordinator.RestorationDecision.Queued -> {
                Log.d(TAG, "Restoration queued for $targetUri (waiting for duration / STATE_READY)")
            }
            is PlaybackRestorationCoordinator.RestorationDecision.Rejected -> {
                Log.d(TAG, decision.reason)
            }
        }
    }

    @Volatile
    private var _exoPlayer: ExoPlayer? = customExoPlayer
    val exoPlayer: ExoPlayer
        get() = _exoPlayer ?: synchronized(this) {
            _exoPlayer ?: createExoPlayer().also { _exoPlayer = it }
        }

    private fun createExoPlayer(): ExoPlayer {
        val nonNullContext = checkNotNull(context) { "Context is required to create a default ExoPlayer instance" }
        val defaultFactory = DefaultDataSource.Factory(nonNullContext)
        val vaultAwareFactory = androidx.media3.datasource.DataSource.Factory {
            val defaultDs = defaultFactory.createDataSource()
            val vaultDs = EncryptedVaultDataSource(nonNullContext)
            object : androidx.media3.datasource.DataSource {
                private var active: androidx.media3.datasource.DataSource? = null

                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    defaultDs.addTransferListener(transferListener)
                    vaultDs.addTransferListener(transferListener)
                }

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val chosen = if (MediaNotificationPrivacy.isVaultUri(dataSpec.uri)) vaultDs else defaultDs
                    active = chosen
                    return chosen.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
                }

                override fun getUri(): Uri? = active?.uri

                override fun getResponseHeaders(): Map<String, List<String>> {
                    return active?.responseHeaders ?: emptyMap()
                }

                override fun close() {
                    try {
                        active?.close()
                    } finally {
                        active = null
                    }
                }
            }
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(vaultAwareFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        return ExoPlayer.Builder(nonNullContext)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                addListener(playerListener)
            }
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null

    internal val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update {
                it.copy(
                    isPlaying = isPlaying,
                    hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                    hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
                )
            }
            if (isPlaying) {
                startProgressPolling()
                if (context != null && customExoPlayer == null) {
                    if (isCurrentPlaybackVault()) {
                        VideoPlaybackService.stopAndCancelNotification(context)
                    } else {
                        VideoPlaybackService.startServiceForPlayback(context)
                    }
                }
            } else {
                stopProgressPolling()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val oldUri = oldPosition.mediaItem?.localConfiguration?.uri?.toString()
            val oldPos = oldPosition.positionMs.coerceAtLeast(0L)
            if (!oldUri.isNullOrBlank() && oldPos > 0L) {
                val actualDur = knownDurations[oldUri] ?: 0L
                lastReportedPositionMs = oldPos
                onPeriodicPositionUpdate?.invoke(oldUri, oldPos, actualDur)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            checkAndApplyPendingRestoration()
        }

        private fun checkAndApplyPendingRestoration() {
            val uri = _uiState.value.mediaUri?.toString() ?: return
            val durationMs = exoPlayer.duration.coerceAtLeast(0L)
            if (durationMs <= 0L) return

            knownDurations[uri] = durationMs

            val effective = restorationCoordinator.checkAndApplyPendingRestoration(uri, durationMs)
            if (effective != null) {
                if (effective > 0L) {
                    exoPlayer.seekTo(effective)
                    _uiState.update { it.copy(currentPositionMs = effective) }
                } else {
                    exoPlayer.seekTo(0L)
                    _uiState.update { it.copy(currentPositionMs = 0L) }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                // Defensive enforcement if ExoPlayer ever transitions automatically under STOP_AFTER_CURRENT
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    _uiState.value.playbackMode == PlaybackMode.STOP_AFTER_CURRENT
                ) {
                    exoPlayer.pause()
                    val currentIndex = exoPlayer.currentMediaItemIndex
                    val baseId = if (!mediaItem.mediaId.isNullOrEmpty()) mediaItem.mediaId else mediaItem.localConfiguration?.uri?.toString() ?: ""
                    val transitionKey = "${baseId}_#${currentIndex}"
                    restorationCoordinator.onMediaItemTransition(transitionKey, isRepeat = false)
                    lastReportedPositionMs = 0L
                    val stoppedUri = mediaItem.localConfiguration?.uri
                    val stoppedTitle = internalTitlesByMediaKey[mediaItem.mediaId]
                        ?: internalTitlesByMediaKey[stoppedUri?.toString().orEmpty()]
                        ?: mediaItem.mediaMetadata.title?.toString()
                        ?: _uiState.value.title
                    _uiState.update {
                        it.copy(
                            isPlaying = false,
                            mediaUri = stoppedUri ?: it.mediaUri,
                            title = stoppedTitle,
                            currentPositionMs = 0L,
                            hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                            hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
                        )
                    }
                    onMediaTransition?.invoke(mediaItem.mediaId)
                    return
                }

                val currentIndex = exoPlayer.currentMediaItemIndex
                val baseId = if (!mediaItem.mediaId.isNullOrEmpty()) mediaItem.mediaId else mediaItem.localConfiguration?.uri?.toString() ?: ""
                val transitionKey = "${baseId}_#${currentIndex}"
                val isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                val isRealTransition = restorationCoordinator.onMediaItemTransition(transitionKey, isRepeat)

                val uri = mediaItem.localConfiguration?.uri
                val uriKey = uri?.toString().orEmpty()
                val title = internalTitlesByMediaKey[mediaItem.mediaId]
                    ?: internalTitlesByMediaKey[uriKey]
                    ?: mediaItem.mediaMetadata.title?.toString()
                    ?: ""
                lastReportedPositionMs = 0L
                val audioOnlyActive = _uiState.value.isAudioOnlyMode || runCatching {
                    C.TRACK_TYPE_VIDEO in exoPlayer.trackSelectionParameters.disabledTrackTypes
                }.getOrDefault(false)
                if (audioOnlyActive) {
                    runCatching {
                        val currentParams = exoPlayer.trackSelectionParameters
                        if (C.TRACK_TYPE_VIDEO !in currentParams.disabledTrackTypes) {
                            exoPlayer.trackSelectionParameters = currentParams
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                                .build()
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        mediaUri = uri,
                        title = title,
                        currentPositionMs = 0L,
                        errorMessage = null,
                        isAudioOnlyMode = audioOnlyActive,
                        hasNextMediaItem = runCatching { hasResolvableNextMediaItem() }.getOrDefault(exoPlayer.hasNextMediaItem()),
                        hasPreviousMediaItem = runCatching { hasResolvablePreviousMediaItem() }.getOrDefault(exoPlayer.hasPreviousMediaItem())
                    )
                }
                if (isRealTransition && !isRepeat) {
                    onMediaTransition?.invoke(mediaItem.mediaId)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _uiState.update { it.copy(isBuffering = true) }
                Player.STATE_READY -> {
                    val durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    val uri = _uiState.value.mediaUri?.toString()
                    if (!uri.isNullOrBlank() && durationMs > 0L) {
                        knownDurations[uri] = durationMs
                    }

                    checkAndApplyPendingRestoration()

                    _uiState.update {
                        it.copy(
                            isBuffering = false,
                            errorMessage = null,
                            durationMs = durationMs,
                            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                            bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                            hasNextMediaItem = runCatching { hasResolvableNextMediaItem() }.getOrDefault(exoPlayer.hasNextMediaItem()),
                            hasPreviousMediaItem = runCatching { hasResolvablePreviousMediaItem() }.getOrDefault(exoPlayer.hasPreviousMediaItem())
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    _uiState.update { it.copy(isPlaying = false, isBuffering = false) }
                    stopProgressPolling()
                    val uri = _uiState.value.mediaUri?.toString()
                    val dur = _uiState.value.durationMs
                    if (!uri.isNullOrBlank() && dur > 0L) {
                        onPeriodicPositionUpdate?.invoke(uri, dur, dur)
                    }
                }
                Player.STATE_IDLE -> {
                    _uiState.update { it.copy(isBuffering = false) }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer playback error: ${error.errorCodeName}", error)
            stopProgressPolling()
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "تعذر تشغيل هذا المقطع (${error.errorCodeName})"
                )
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _uiState.update {
                    it.copy(
                        videoWidth = videoSize.width,
                        videoHeight = videoSize.height
                    )
                }
            }
        }
    }

    init {
        customExoPlayer?.addListener(playerListener)
    }

    fun playMedia(uri: Uri, title: String, startPositionMs: Long = 0L) {
        saveCurrentExoPlayerPositionDirectly()

        val startKey = "${uri}_#0"
        restorationCoordinator.onNewPlaybackSession(startKey)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        lastReportedPositionMs = startPositionMs
        setAudioOnlyMode(false)
        _uiState.update {
            it.copy(
                mediaUri = uri,
                title = title,
                currentPositionMs = startPositionMs,
                durationMs = 0L,
                videoWidth = 0,
                videoHeight = 0,
                activePlaylistId = null,
                isTemporarySpeedActive = false,
                isPipActive = false,
                isControlsVisible = true,
                isAudioOnlyMode = false,
                hasNextMediaItem = false,
                hasPreviousMediaItem = false
            )
        }

        val isVault = MediaNotificationPrivacy.isVaultUri(uri)
        internalTitlesByMediaKey.clear()
        internalTitlesByMediaKey[uri.toString()] = title
        val publicMetadataTitle = MediaNotificationPrivacy.sanitizeMediaMetadataTitle(isVault, title)

        if (isVault) {
            if (context != null && customExoPlayer == null) {
                VideoPlaybackService.stopAndCancelNotification(context)
            }
            val nonNullContext = checkNotNull(context) { "Context is required for vault encrypted media source" }
            val dataSourceFactory = { EncryptedVaultDataSource(nonNullContext) }
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setMediaId(uri.toString())
                        .setUri(uri)
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.EMPTY)
                        .build()
                )
            exoPlayer.setMediaSource(mediaSource, startPositionMs)
        } else {
            val mediaItem = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(publicMetadataTitle)
                        .build()
                )
                .build()
            exoPlayer.setMediaItem(mediaItem, startPositionMs)
        }

        applyCurrentPlaybackModeToExoPlayer(_uiState.value.playbackMode)
        exoPlayer.setPlaybackSpeed(_uiState.value.playbackSpeed)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        if (context != null && customExoPlayer == null) {
            if (isVault || isCurrentPlaybackVault()) {
                VideoPlaybackService.stopAndCancelNotification(context)
            } else {
                VideoPlaybackService.startServiceForPlayback(context)
            }
        }
    }

    fun playPlaylist(
        items: List<VideoItem>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        repeatMode: PlaylistRepeatMode = PlaylistRepeatMode.OFF,
        startPositionMs: Long = 0L,
        activePlaylistId: Long? = null
    ) {
        if (items.isEmpty()) return

        saveCurrentExoPlayerPositionDirectly()

        val validStartIndex = startIndex.coerceIn(0, items.size - 1)
        val startVideo = items[validStartIndex]
        val startKey = "${startVideo.id}_#${validStartIndex}"
        restorationCoordinator.onNewPlaybackSession(startKey)

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        internalTitlesByMediaKey.clear()
        val validItemsHaveVault = items.any { MediaNotificationPrivacy.isVaultVideo(it) }
        val mediaItems = items.map { video ->
            val isItemVault = MediaNotificationPrivacy.isVaultVideo(video)
            val mediaId = if (isItemVault) {
                video.contentUri.toString()
            } else {
                video.id.toString()
            }
            internalTitlesByMediaKey[mediaId] = video.safeTitle
            internalTitlesByMediaKey[video.contentUri.toString()] = video.safeTitle
            val metadata = if (isItemVault) {
                androidx.media3.common.MediaMetadata.EMPTY
            } else {
                val metadataTitle = MediaNotificationPrivacy.sanitizeMediaMetadataTitle(
                    isVault = false,
                    originalTitle = video.safeTitle
                )
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(metadataTitle)
                    .build()
            }
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(video.contentUri)
                .setMediaMetadata(metadata)
                .build()
        }

        lastReportedPositionMs = startPositionMs

        setAudioOnlyMode(false)
        _uiState.update {
            it.copy(
                mediaUri = startVideo.contentUri,
                title = startVideo.safeTitle,
                currentPositionMs = startPositionMs,
                durationMs = startVideo.durationMs,
                videoWidth = startVideo.width.coerceAtLeast(0),
                videoHeight = startVideo.height.coerceAtLeast(0),
                activePlaylistId = activePlaylistId,
                repeatMode = repeatMode,
                isTemporarySpeedActive = false,
                isPipActive = false,
                isControlsVisible = true,
                isAudioOnlyMode = false
            )
        }

        // Apply media items first so ExoPlayer does not reset ShuffleOrder afterward
        exoPlayer.setMediaItems(mediaItems, validStartIndex, startPositionMs)
        setRepeatMode(repeatMode)
        exoPlayer.shuffleModeEnabled = shuffle
        if (shuffle && mediaItems.size > 1) {
            runCatching {
                exoPlayer.setShuffleOrder(
                    androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                        mediaItems.size,
                        System.nanoTime()
                    )
                )
            }
        }
        try {
            exoPlayer.pauseAtEndOfMediaItems = _uiState.value.playbackMode.stopAfterCurrent
        } catch (_: Throwable) {
        }

        _uiState.update {
            it.copy(
                hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
            )
        }
        exoPlayer.setPlaybackSpeed(_uiState.value.playbackSpeed)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        if (context != null && customExoPlayer == null) {
            if (validItemsHaveVault || isCurrentPlaybackVault()) {
                VideoPlaybackService.stopAndCancelNotification(context)
            } else {
                VideoPlaybackService.startServiceForPlayback(context)
            }
        }
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        applyCurrentPlaybackModeToExoPlayer(mode)
        _uiState.update {
            it.copy(
                playbackMode = mode,
                repeatMode = mode.repeatModeEquivalent,
                hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
            )
        }
    }

    private fun applyCurrentPlaybackModeToExoPlayer(mode: PlaybackMode) {
        exoPlayer.repeatMode = when (mode.repeatModeEquivalent) {
            PlaylistRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            PlaylistRepeatMode.ONE -> Player.REPEAT_MODE_ONE
            PlaylistRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        exoPlayer.shuffleModeEnabled = mode.shuffleEnabled
        if (mode.shuffleEnabled && exoPlayer.mediaItemCount > 1) {
            runCatching {
                exoPlayer.setShuffleOrder(
                    androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                        exoPlayer.mediaItemCount,
                        System.nanoTime()
                    )
                )
            }
        }
        try {
            exoPlayer.pauseAtEndOfMediaItems = mode.stopAfterCurrent
        } catch (_: Throwable) {
        }
    }

    fun setRepeatMode(mode: PlaylistRepeatMode) {
        exoPlayer.repeatMode = when (mode) {
            PlaylistRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            PlaylistRepeatMode.ONE -> Player.REPEAT_MODE_ONE
            PlaylistRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        _uiState.update { it.copy(repeatMode = mode) }
    }

    fun setShuffle(shuffle: Boolean) {
        exoPlayer.shuffleModeEnabled = shuffle
        if (shuffle && exoPlayer.mediaItemCount > 1) {
            runCatching {
                exoPlayer.setShuffleOrder(
                    androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                        exoPlayer.mediaItemCount,
                        System.nanoTime()
                    )
                )
            }
        }
        _uiState.update {
            it.copy(
                hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
            )
        }
    }

    fun setResumeModeUi(mode: ResumeMode) {
        _uiState.update { it.copy(resumeMode = mode) }
    }

    fun hasResolvableNextMediaItem(): Boolean {
        val player = _exoPlayer ?: return false
        return runCatching {
            val count = player.mediaItemCount
            if (count <= 0) return@runCatching false
            if (player.hasNextMediaItem()) return@runCatching true
            val mode = _uiState.value.playbackMode
            (mode == PlaybackMode.REPEAT_PLAYLIST || mode == PlaybackMode.SHUFFLE_ALL) && count > 1
        }.getOrDefault(false)
    }

    fun hasResolvablePreviousMediaItem(): Boolean {
        val player = _exoPlayer ?: return false
        return runCatching {
            val count = player.mediaItemCount
            if (count <= 0) return@runCatching false
            if (player.hasPreviousMediaItem()) return@runCatching true
            val mode = _uiState.value.playbackMode
            (mode == PlaybackMode.REPEAT_PLAYLIST || mode == PlaybackMode.SHUFFLE_ALL) && count > 1
        }.getOrDefault(false)
    }

    fun nextTrack(resumePlaybackIfPausedOrEnded: Boolean = false): Boolean {
        val player = _exoPlayer ?: return false
        return runCatching {
            val count = player.mediaItemCount
            if (count <= 0) return@runCatching false
            val shouldResume = resumePlaybackIfPausedOrEnded ||
                player.playWhenReady ||
                player.isPlaying ||
                player.playbackState == Player.STATE_ENDED

            var moved = false
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                moved = true
            } else {
                val curIdx = player.currentMediaItemIndex
                if (curIdx in 0 until (count - 1)) {
                    player.seekTo(curIdx + 1, 0L)
                    moved = true
                } else {
                    val mode = _uiState.value.playbackMode
                    if ((mode == PlaybackMode.REPEAT_PLAYLIST || mode == PlaybackMode.SHUFFLE_ALL) && count > 0) {
                        player.seekTo(0, 0L)
                        moved = true
                    }
                }
            }
            if (moved) {
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                if (shouldResume && !player.isPlaying) {
                    player.play()
                }
                _uiState.update {
                    it.copy(
                        errorMessage = null,
                        hasNextMediaItem = hasResolvableNextMediaItem(),
                        hasPreviousMediaItem = hasResolvablePreviousMediaItem()
                    )
                }
            }
            moved
        }.getOrDefault(false)
    }

    fun previousTrack(resumePlaybackIfPausedOrEnded: Boolean = false): Boolean {
        val player = _exoPlayer ?: return false
        return runCatching {
            val count = player.mediaItemCount
            if (count <= 0) return@runCatching false
            val shouldResume = resumePlaybackIfPausedOrEnded ||
                player.playWhenReady ||
                player.isPlaying ||
                player.playbackState == Player.STATE_ENDED

            var moved = false
            if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
                moved = true
            } else {
                val curIdx = player.currentMediaItemIndex
                if (curIdx > 0) {
                    player.seekTo(curIdx - 1, 0L)
                    moved = true
                } else {
                    val mode = _uiState.value.playbackMode
                    if ((mode == PlaybackMode.REPEAT_PLAYLIST || mode == PlaybackMode.SHUFFLE_ALL) && count > 1) {
                        player.seekTo(count - 1, 0L)
                        moved = true
                    }
                }
            }
            if (moved) {
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                if (shouldResume && !player.isPlaying) {
                    player.play()
                }
                _uiState.update {
                    it.copy(
                        errorMessage = null,
                        hasNextMediaItem = hasResolvableNextMediaItem(),
                        hasPreviousMediaItem = hasResolvablePreviousMediaItem()
                    )
                }
            }
            moved
        }.getOrDefault(false)
    }

    fun play() {
        val player = _exoPlayer ?: return
        runCatching {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
    }

    fun pause() {
        exoPlayer.pause()
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val uri = _uiState.value.mediaUri?.toString()
        if (!uri.isNullOrBlank()) {
            val dur = exoPlayer.duration.coerceAtLeast(0L).takeIf { it > 0L }
                ?: (knownDurations[uri] ?: 0L)
            if (dur > 0L) {
                knownDurations[uri] = dur
            }
            lastReportedPositionMs = pos
            onPeriodicPositionUpdate?.invoke(uri, pos, dur)
        }
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        restorationCoordinator.onManualSeek(clamped)
        lastReportedPositionMs = clamped
        exoPlayer.seekTo(clamped)
        _uiState.update { it.copy(currentPositionMs = clamped) }
        updateActiveSubtitles(clamped)
    }

    fun setSeekIntervalSeconds(seconds: Int) {
        val valid = PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(seconds)
        _uiState.update { it.copy(seekIntervalSeconds = valid) }
    }

    fun seekForward(): Long {
        val state = _uiState.value
        val maxDur = if (state.durationMs > 0L) state.durationMs else Long.MAX_VALUE
        val target = (state.currentPositionMs + state.seekIntervalMs).coerceAtMost(maxDur)
        seekTo(target)
        return target
    }

    fun seekBackward(): Long {
        val state = _uiState.value
        val target = (state.currentPositionMs - state.seekIntervalMs).coerceAtLeast(0L)
        seekTo(target)
        return target
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = clampPlaybackSpeed(speed)
        exoPlayer.setPlaybackSpeed(clamped)
        _uiState.update { it.copy(playbackSpeed = clamped, isTemporarySpeedActive = false) }
    }

    fun setTemporaryPlaybackSpeed(speed: Float = 2.0f) {
        val clamped = clampPlaybackSpeed(speed)
        exoPlayer.setPlaybackSpeed(clamped)
        _uiState.update { it.copy(isTemporarySpeedActive = true, temporarySpeed = clamped) }
    }

    fun clearTemporaryPlaybackSpeed() {
        val savedSpeed = clampPlaybackSpeed(_uiState.value.playbackSpeed)
        exoPlayer.setPlaybackSpeed(savedSpeed)
        _uiState.update { it.copy(isTemporarySpeedActive = false) }
    }

    fun setResizeMode(mode: Int) {
        val validCode = VideoResizeMode.fromCode(mode).storageCode
        _uiState.update { it.copy(resizeMode = validCode) }
    }

    fun setPlayerOrientationMode(mode: PlayerOrientationMode) {
        _uiState.update { it.copy(playerOrientationMode = mode) }
    }

    fun setShowSourceTextWithTranslation(show: Boolean) {
        _uiState.update { it.copy(showSourceTextWithTranslation = show) }
    }

    fun isCurrentPlaybackVault(): Boolean {
        if (MediaNotificationPrivacy.isVaultUri(_uiState.value.mediaUri)) return true
        return VideoPlaybackService.isVaultPlaybackActive(_exoPlayer)
    }

    fun removePlaylistMediaItem(videoId: Long?, contentUri: Uri?): Boolean {
        val player = _exoPlayer ?: return false
        val targetCount = player.mediaItemCount
        if (targetCount <= 1) return false

        var matchIdx = -1
        val idStr = videoId?.toString()
        val uriStr = contentUri?.toString()
        for (i in 0 until targetCount) {
            val item = player.getMediaItemAt(i)
            val itemUri = item.localConfiguration?.uri?.toString()
            if ((idStr != null && item.mediaId == idStr) ||
                (!uriStr.isNullOrBlank() && (itemUri == uriStr || item.mediaId == uriStr))
            ) {
                matchIdx = i
                break
            }
        }
        if (matchIdx < 0) return false

        player.removeMediaItem(matchIdx)
        val activeItem = runCatching { player.currentMediaItem }.getOrNull()
        val activeUri = activeItem?.localConfiguration?.uri
        val activeTitle = activeItem?.let { item ->
            val uriKey = activeUri?.toString().orEmpty()
            internalTitlesByMediaKey[item.mediaId]
                ?: internalTitlesByMediaKey[uriKey]
                ?: item.mediaMetadata.title?.toString()
        }.orEmpty()
        _uiState.update {
            it.copy(
                mediaUri = activeUri ?: it.mediaUri,
                title = activeTitle.ifBlank { it.title },
                hasNextMediaItem = player.hasNextMediaItem(),
                hasPreviousMediaItem = player.hasPreviousMediaItem()
            )
        }
        return true
    }

    fun toggleControls() {
        _uiState.update { it.copy(isControlsVisible = !it.isControlsVisible) }
    }

    fun setControlsVisible(visible: Boolean) {
        _uiState.update { it.copy(isControlsVisible = visible) }
    }

    fun toggleLock() {
        _uiState.update { it.copy(isLocked = !it.isLocked) }
    }

    fun setPipActive(active: Boolean) {
        _uiState.update { it.copy(isPipActive = active) }
    }

    fun setAudioOnlyMode(enabled: Boolean) {
        runCatching {
            val currentParams = exoPlayer.trackSelectionParameters
            exoPlayer.trackSelectionParameters = currentParams
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled)
                .build()
        }
        _uiState.update { it.copy(isAudioOnlyMode = enabled) }
    }

    fun toggleAudioOnlyMode(): Boolean {
        val next = !_uiState.value.isAudioOnlyMode
        setAudioOnlyMode(next)
        return next
    }

    fun updateActiveTitle(videoId: Long, contentUri: Uri, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        internalTitlesByMediaKey[videoId.toString()] = trimmed
        internalTitlesByMediaKey[contentUri.toString()] = trimmed
        _uiState.update { state ->
            if (state.mediaUri == contentUri) state.copy(title = trimmed) else state
        }
    }

    fun setPrimarySubtitleTrack(track: SubtitleTrack?) {
        _uiState.update { it.copy(primaryTrack = track, areSubtitlesVisible = true) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun setSecondarySubtitleTrack(track: SubtitleTrack?) {
        _uiState.update { it.copy(secondaryTrack = track, areSubtitlesVisible = true) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun addSubtitleTrack(track: SubtitleTrack) {
        _uiState.update {
            val existing = it.availableSubtitleTracks.filter { t -> t.id != track.id }
            it.copy(availableSubtitleTracks = existing + track)
        }
    }

    fun setSubtitlesVisible(visible: Boolean) {
        _uiState.update { it.copy(areSubtitlesVisible = visible) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun setPrimarySubtitleOffsetMs(offsetMs: Long) {
        _uiState.update { it.copy(primarySubtitleOffsetMs = offsetMs.coerceIn(-30_000L, 30_000L)) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun setSecondarySubtitleOffsetMs(offsetMs: Long) {
        _uiState.update { it.copy(secondarySubtitleOffsetMs = offsetMs.coerceIn(-30_000L, 30_000L)) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun updateSubtitleTrackCues(trackId: String, updatedCues: List<SubtitleCue>) {
        _uiState.update { state ->
            val updatedAvailable = state.availableSubtitleTracks.map { t ->
                if (t.id == trackId) t.copy(cues = updatedCues) else t
            }
            val updatedPrimary = state.primaryTrack?.let {
                if (it.id == trackId) it.copy(cues = updatedCues) else it
            }
            val updatedSecondary = state.secondaryTrack?.let {
                if (it.id == trackId) it.copy(cues = updatedCues) else it
            }
            state.copy(
                availableSubtitleTracks = updatedAvailable,
                primaryTrack = updatedPrimary,
                secondaryTrack = updatedSecondary
            )
        }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun removeSubtitleTrack(trackId: String) {
        _uiState.update { state ->
            state.copy(
                availableSubtitleTracks = state.availableSubtitleTracks.filterNot { it.id == trackId },
                primaryTrack = state.primaryTrack?.takeIf { it.id != trackId },
                secondaryTrack = state.secondaryTrack?.takeIf { it.id != trackId }
            )
        }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun syncWithCurrentPlayer() {
        val isPlaying = exoPlayer.isPlaying
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = exoPlayer.duration.coerceAtLeast(0L)
        val activeMediaItem = runCatching { exoPlayer.currentMediaItem }.getOrNull()
        val activeUri = activeMediaItem?.localConfiguration?.uri
        val activeTitle = activeMediaItem?.let { item ->
            val uriKey = activeUri?.toString().orEmpty()
            internalTitlesByMediaKey[item.mediaId]
                ?: internalTitlesByMediaKey[uriKey]
                ?: item.mediaMetadata.title?.toString()
        }
        _uiState.update {
            it.copy(
                isPlaying = isPlaying,
                currentPositionMs = pos,
                durationMs = dur,
                mediaUri = it.mediaUri ?: activeUri,
                title = it.title.ifBlank { activeTitle ?: "" },
                hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
            )
        }
    }

    fun stopPlayback() {
        saveCurrentExoPlayerPositionDirectly()
        restorationCoordinator.onPlaybackStopped()
        internalTitlesByMediaKey.clear()
        setAudioOnlyMode(false)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        stopProgressPolling()
        if (context != null && customExoPlayer == null) {
            VideoPlaybackService.stopAndCancelNotification(context)
        }
        _uiState.update {
            it.copy(
                isPlaying = false,
                currentPositionMs = 0L,
                mediaUri = null,
                title = "",
                videoWidth = 0,
                videoHeight = 0,
                activePlaylistId = null,
                isTemporarySpeedActive = false,
                isPipActive = false,
                isAudioOnlyMode = false,
                isControlsVisible = true,
                hasNextMediaItem = false,
                hasPreviousMediaItem = false
            )
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val player = _exoPlayer ?: break
                val snapshot = runCatching {
                    Triple(
                        player.currentPosition.coerceAtLeast(0L),
                        player.duration.coerceAtLeast(0L),
                        player.bufferedPosition.coerceAtLeast(0L)
                    )
                }.getOrNull() ?: break
                val (pos, dur, buf) = snapshot
                _uiState.update {
                    it.copy(
                        currentPositionMs = pos,
                        durationMs = dur,
                        bufferedPositionMs = buf
                    )
                }
                updateActiveSubtitles(pos)

                if (kotlin.math.abs(pos - lastReportedPositionMs) >= 5000L && dur > 0L) {
                    lastReportedPositionMs = pos
                    val currentUri = _uiState.value.mediaUri?.toString()
                    if (!currentUri.isNullOrBlank()) {
                        onPeriodicPositionUpdate?.invoke(currentUri, pos, dur)
                    }
                }

                delay(200)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
    }

    private fun updateActiveSubtitles(currentMs: Long) {
        val state = _uiState.value
        if (!state.areSubtitlesVisible) {
            _uiState.update {
                it.copy(
                    activePrimaryCues = emptyList(),
                    activeSecondaryCues = emptyList()
                )
            }
            return
        }
        val primaryLookupMs = currentMs - state.primarySubtitleOffsetMs
        val secondaryLookupMs = currentMs - state.secondarySubtitleOffsetMs

        val primaryCues = state.primaryTrack?.cues?.filter {
            primaryLookupMs in it.startTimeMs..it.endTimeMs
        } ?: emptyList()

        val secondaryCues = state.secondaryTrack?.cues?.filter {
            secondaryLookupMs in it.startTimeMs..it.endTimeMs
        } ?: emptyList()

        _uiState.update {
            it.copy(
                activePrimaryCues = primaryCues,
                activeSecondaryCues = secondaryCues
            )
        }
    }

    fun release() {
        stopProgressPolling()
        onPeriodicPositionUpdate = null
        onMediaTransition = null
        val playerToRelease = synchronized(this) {
            val current = _exoPlayer
            _exoPlayer = null
            current
        } ?: return

        releasePlayerOnApplicationThread(playerToRelease)
    }

    private fun releasePlayerOnApplicationThread(player: ExoPlayer) {
        val appLooper = runCatching { player.applicationLooper }.getOrNull()
        if (appLooper == null || Thread.currentThread() == appLooper.thread) {
            runCatching { player.removeListener(playerListener) }
            player.release()
            return
        }

        val appThread = appLooper.thread
        if (!appThread.isAlive) {
            Log.w(TAG, "Cannot dispatch ExoPlayer.release() to dead thread=${appThread.name}")
            return
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var releaseFailure: Throwable? = null
        val posted = Handler(appLooper).post {
            try {
                runCatching { player.removeListener(playerListener) }
                player.release()
            } catch (t: Throwable) {
                releaseFailure = t
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            throw IllegalStateException(
                "Failed to post ExoPlayer.release() to applicationLooper thread=${appThread.name}"
            )
        }

        val completed = try {
            latch.await(5L, java.util.concurrent.TimeUnit.SECONDS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        releaseFailure?.let {
            throw IllegalStateException("ExoPlayer.release() failed on player application thread", it)
        }
        if (!completed) {
            throw IllegalStateException(
                "Timed out waiting for ExoPlayer.release() on applicationLooper thread=${appThread.name}"
            )
        }
    }
}
