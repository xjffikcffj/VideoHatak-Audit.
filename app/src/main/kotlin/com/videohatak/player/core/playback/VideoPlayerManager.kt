package com.videohatak.player.core.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.service.VideoPlaybackService
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.vault.datasource.EncryptedVaultDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            lastReportedPositionMs = actualPos
            onPeriodicPositionUpdate?.invoke(currentUri, actualPos, actualDur)
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
        val mediaSourceFactory = DefaultMediaSourceFactory(nonNullContext)
            .setDataSourceFactory {
                defaultFactory.createDataSource()
            }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        return ExoPlayer.Builder(nonNullContext)
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

    private val scope = CoroutineScope(Dispatchers.Main)
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
                    VideoPlaybackService.startServiceForPlayback(context)
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
                    _uiState.update { it.copy(isPlaying = false) }
                    return
                }

                val currentIndex = exoPlayer.currentMediaItemIndex
                val baseId = if (!mediaItem.mediaId.isNullOrEmpty()) mediaItem.mediaId else mediaItem.localConfiguration?.uri?.toString() ?: ""
                val transitionKey = "${baseId}_#${currentIndex}"
                val isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                val isRealTransition = restorationCoordinator.onMediaItemTransition(transitionKey, isRepeat)

                val title = mediaItem.mediaMetadata.title?.toString() ?: ""
                val uri = mediaItem.localConfiguration?.uri
                lastReportedPositionMs = 0L
                _uiState.update {
                    it.copy(
                        mediaUri = uri,
                        title = title,
                        currentPositionMs = 0L,
                        hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                        hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
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
                            durationMs = durationMs,
                            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                            bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                            hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                            hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
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

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _uiState.update {
                it.copy(
                    videoWidth = videoSize.width.coerceAtLeast(0),
                    videoHeight = videoSize.height.coerceAtLeast(0)
                )
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
        _uiState.update {
            it.copy(
                mediaUri = uri,
                title = title,
                currentPositionMs = startPositionMs,
                durationMs = 0L,
                isTemporarySpeedActive = false,
                hasNextMediaItem = false,
                hasPreviousMediaItem = false
            )
        }

        if (uri.scheme == "vault") {
            val nonNullContext = checkNotNull(context) { "Context is required for vault encrypted media source" }
            val dataSourceFactory = { EncryptedVaultDataSource(nonNullContext) }
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setMediaId(uri.toString())
                        .setUri(uri)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(title)
                                .build()
                        )
                        .build()
                )
            exoPlayer.setMediaSource(mediaSource, startPositionMs)
        } else {
            val mediaItem = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title)
                        .build()
                )
                .build()
            exoPlayer.setMediaItem(mediaItem, startPositionMs)
        }

        applyCurrentPlaybackModeToExoPlayer(_uiState.value.playbackMode)
        exoPlayer.setPlaybackSpeed(_uiState.value.playbackSpeed)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun playPlaylist(
        items: List<VideoItem>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        repeatMode: PlaylistRepeatMode = PlaylistRepeatMode.OFF,
        startPositionMs: Long = 0L
    ) {
        if (items.isEmpty()) return

        saveCurrentExoPlayerPositionDirectly()

        val validStartIndex = startIndex.coerceIn(0, items.size - 1)
        val startVideo = items[validStartIndex]
        val startKey = "${startVideo.id}_#${validStartIndex}"
        restorationCoordinator.onNewPlaybackSession(startKey)

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val mediaItems = items.map { video ->
            MediaItem.Builder()
                .setMediaId(video.id.toString())
                .setUri(video.contentUri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(video.safeTitle)
                        .build()
                )
                .build()
        }

        setRepeatMode(repeatMode)
        exoPlayer.shuffleModeEnabled = shuffle
        try {
            exoPlayer.pauseAtEndOfMediaItems = _uiState.value.playbackMode.stopAfterCurrent
        } catch (_: Throwable) {
        }

        lastReportedPositionMs = startPositionMs

        _uiState.update {
            it.copy(
                mediaUri = startVideo.contentUri,
                title = startVideo.safeTitle,
                currentPositionMs = startPositionMs,
                durationMs = startVideo.durationMs,
                repeatMode = repeatMode,
                isTemporarySpeedActive = false
            )
        }

        exoPlayer.setMediaItems(mediaItems, validStartIndex, startPositionMs)
        _uiState.update {
            it.copy(
                hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
            )
        }
        exoPlayer.setPlaybackSpeed(_uiState.value.playbackSpeed)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
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
    }

    fun setResumeModeUi(mode: ResumeMode) {
        _uiState.update { it.copy(resumeMode = mode) }
    }

    fun nextTrack(): Boolean {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            return true
        }
        return false
    }

    fun previousTrack(): Boolean {
        if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
            return true
        }
        return false
    }

    fun play() {
        exoPlayer.play()
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

    fun setPrimarySubtitleTrack(track: SubtitleTrack?) {
        _uiState.update { it.copy(primaryTrack = track) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun setSecondarySubtitleTrack(track: SubtitleTrack?) {
        _uiState.update { it.copy(secondaryTrack = track) }
        updateActiveSubtitles(exoPlayer.currentPosition)
    }

    fun addSubtitleTrack(track: SubtitleTrack) {
        _uiState.update {
            val existing = it.availableSubtitleTracks.filter { t -> t.id != track.id }
            it.copy(availableSubtitleTracks = existing + track)
        }
    }

    fun syncWithCurrentPlayer() {
        val isPlaying = exoPlayer.isPlaying
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = exoPlayer.duration.coerceAtLeast(0L)
        _uiState.update {
            it.copy(
                isPlaying = isPlaying,
                currentPositionMs = pos,
                durationMs = dur,
                hasNextMediaItem = exoPlayer.hasNextMediaItem(),
                hasPreviousMediaItem = exoPlayer.hasPreviousMediaItem()
            )
        }
    }

    fun stopPlayback() {
        saveCurrentExoPlayerPositionDirectly()
        restorationCoordinator.onPlaybackStopped()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        stopProgressPolling()
        _uiState.update {
            it.copy(
                isPlaying = false,
                currentPositionMs = 0L,
                mediaUri = null,
                title = "",
                isTemporarySpeedActive = false,
                hasNextMediaItem = false,
                hasPreviousMediaItem = false
            )
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                val buf = exoPlayer.bufferedPosition.coerceAtLeast(0L)
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
        val primaryCues = state.primaryTrack?.cues?.filter {
            currentMs in it.startTimeMs..it.endTimeMs
        } ?: emptyList()

        val secondaryCues = state.secondaryTrack?.cues?.filter {
            currentMs in it.startTimeMs..it.endTimeMs
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
        _exoPlayer?.release()
        _exoPlayer = null
    }
}
