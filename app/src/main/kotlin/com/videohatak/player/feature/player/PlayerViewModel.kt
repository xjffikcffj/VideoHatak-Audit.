package com.videohatak.player.feature.player

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPositionManager
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlayerUiState
import com.videohatak.player.core.playback.PositionSavePolicy
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.ResumePromptState
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.playlist.model.toVideoItem
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.subtitle.parser.SubtitleParser
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesRepository
import com.videohatak.player.core.thumbnail.manager.ThumbnailPreviewManager
import com.videohatak.player.core.thumbnail.model.ThumbnailPreviewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class PlayerViewModel(
    application: Application,
    val playerManager: VideoPlayerManager,
    private val playbackPreferences: PlaybackPreferencesRepository,
    val positionManager: PlaybackPositionManager = PlaybackPositionManager.getInstance(application),
    private val subtitlePreferences: SubtitlePreferencesRepository? = null,
    private val thumbnailManager: ThumbnailPreviewManager? = null
) : AndroidViewModel(application) {

    private val safeSubtitlePreferences: SubtitlePreferencesRepository? =
        subtitlePreferences ?: try { SubtitlePreferencesRepository(application) } catch (_: Throwable) { null }

    private val safeThumbnailManager: ThumbnailPreviewManager? =
        thumbnailManager ?: try { ThumbnailPreviewManager(application) } catch (_: Throwable) { null }

    var currentVideo: VideoItem? = null
        private set

    private val playlistRepository by lazy { PlaylistRepositoryImpl(getApplication()) }
    private var playlistVideos: List<VideoItem> = emptyList()

    private val _pendingResumePrompt = MutableStateFlow<ResumePromptState?>(null)
    val pendingResumePrompt: StateFlow<ResumePromptState?> = _pendingResumePrompt.asStateFlow()

    val playbackMode: StateFlow<PlaybackMode> = playbackPreferences.playbackMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackMode.PLAY_ALL_IN_ORDER)

    val resumeMode: StateFlow<ResumeMode> = playbackPreferences.resumeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ResumeMode.RESUME)

    companion object {
        private const val TAG = "PlayerViewModel"

        fun provideFactory(
            application: Application,
            playbackPreferences: PlaybackPreferencesRepository,
            positionManager: PlaybackPositionManager = PlaybackPositionManager.getInstance(application)
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val playerManager = VideoPlayerManager.getInstance(application)
                return PlayerViewModel(
                    application = application,
                    playerManager = playerManager,
                    playbackPreferences = playbackPreferences,
                    positionManager = positionManager,
                    subtitlePreferences = SubtitlePreferencesRepository(application),
                    thumbnailManager = ThumbnailPreviewManager(application)
                ) as T
            }
        }
    }

    init {
        playerManager.onMediaTransition = { mediaId ->
            val matching = playlistVideos.find { it.id.toString() == mediaId }
            if (matching != null) {
                currentVideo = matching
                safeThumbnailManager?.setVideoUri(matching.contentUri)
                val targetUri = matching.contentUri.toString()
                val targetGen = playerManager.getCurrentPlaybackGeneration()
                viewModelScope.launch {
                    handlePositionRestorationForMode(
                        videoTitle = matching.safeTitle,
                        targetUri = targetUri,
                        targetGeneration = targetGen,
                        durationMs = matching.durationMs,
                        legacyVideoId = matching.id
                    )
                }
            }
        }
        playerManager.onPeriodicPositionUpdate = { uri, pos, dur ->
            positionManager.enqueueSave(uri, pos, dur)
        }
        viewModelScope.launch {
            playbackPreferences.playbackMode.collect { mode ->
                playerManager.setPlaybackMode(mode)
            }
        }
        viewModelScope.launch {
            playbackPreferences.resumeMode.collect { mode ->
                playerManager.setResumeModeUi(mode)
            }
        }
        viewModelScope.launch {
            playbackPreferences.playbackSpeed.collect { savedSpeed ->
                if (!playerManager.uiState.value.isTemporarySpeedActive) {
                    playerManager.setPlaybackSpeed(VideoPlayerManager.clampPlaybackSpeed(savedSpeed))
                }
            }
        }
        viewModelScope.launch {
            playbackPreferences.resizeMode.collect { savedMode ->
                playerManager.setResizeMode(VideoResizeMode.fromCode(savedMode).storageCode)
            }
        }
    }

    val uiState: StateFlow<PlayerUiState> = playerManager.uiState
    val thumbnailPreviewState: StateFlow<ThumbnailPreviewState> =
        safeThumbnailManager?.previewState ?: MutableStateFlow(ThumbnailPreviewState())

    val primarySubtitleStyle: StateFlow<SubtitleStyleConfig> =
        safeSubtitlePreferences?.primaryStyle
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubtitleStyleConfig())
            ?: MutableStateFlow(SubtitleStyleConfig())

    val secondarySubtitleStyle: StateFlow<SubtitleStyleConfig> =
        safeSubtitlePreferences?.secondaryStyle
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubtitleStyleConfig(fontSizeSp = 15f, bottomPaddingDp = 88f))
            ?: MutableStateFlow(SubtitleStyleConfig(fontSizeSp = 15f, bottomPaddingDp = 88f))

    private suspend fun handlePositionRestorationForMode(
        videoTitle: String,
        targetUri: String,
        targetGeneration: Long,
        durationMs: Long,
        legacyVideoId: Long? = null
    ) {
        val currentResumeMode = try {
            playbackPreferences.resumeMode.first()
        } catch (_: Exception) {
            ResumeMode.RESUME
        }
        playerManager.setResumeModeUi(currentResumeMode)

        when (currentResumeMode) {
            ResumeMode.START_FROM_BEGINNING -> {
                _pendingResumePrompt.value = null
                playerManager.applyRestoredPosition(targetUri, targetGeneration, 0L)
            }
            ResumeMode.RESUME -> {
                _pendingResumePrompt.value = null
                val savedPos = playbackPreferences.getSavedVideoPosition(
                    targetUri,
                    durationMs,
                    legacyVideoId = legacyVideoId
                )
                playerManager.applyRestoredPosition(targetUri, targetGeneration, savedPos)
            }
            ResumeMode.ASK_EVERY_TIME -> {
                val savedPos = playbackPreferences.getSavedVideoPosition(
                    targetUri,
                    durationMs,
                    legacyVideoId = legacyVideoId
                )
                if (savedPos > 0L) {
                    playerManager.pause()
                    _pendingResumePrompt.value = ResumePromptState(
                        videoTitle = videoTitle,
                        targetUri = targetUri,
                        savedPositionMs = savedPos,
                        durationMs = durationMs,
                        targetGeneration = targetGenSafe(targetGeneration)
                    )
                } else {
                    _pendingResumePrompt.value = null
                }
            }
        }
    }

    private fun targetGenSafe(gen: Long): Long = gen

    fun resolveResumePrompt(resumeFromSaved: Boolean) {
        val prompt = _pendingResumePrompt.value ?: return
        _pendingResumePrompt.value = null
        if (resumeFromSaved) {
            playerManager.applyRestoredPosition(prompt.targetUri, prompt.targetGeneration, prompt.savedPositionMs)
        } else {
            playerManager.applyRestoredPosition(prompt.targetUri, prompt.targetGeneration, 0L)
        }
        playerManager.play()
    }

    fun dismissResumePrompt() {
        _pendingResumePrompt.value = null
        playerManager.play()
    }

    fun playMedia(uri: Uri, title: String) {
        currentVideo = null
        playlistVideos = emptyList()
        _pendingResumePrompt.value = null
        safeThumbnailManager?.setVideoUri(uri)
        val targetUri = uri.toString()
        playerManager.playMedia(uri, title, startPositionMs = 0L)
        val targetGen = playerManager.getCurrentPlaybackGeneration()
        viewModelScope.launch {
            handlePositionRestorationForMode(
                videoTitle = title,
                targetUri = targetUri,
                targetGeneration = targetGen,
                durationMs = 0L
            )
        }
    }

    fun playVideo(video: VideoItem) {
        playVideoInContext(video, emptyList())
    }

    fun playVideoInContext(video: VideoItem, contextVideos: List<VideoItem>) {
        currentVideo = video
        _pendingResumePrompt.value = null
        safeThumbnailManager?.setVideoUri(video.contentUri)
        val targetUri = video.contentUri.toString()

        val canQueueContext = video.bucketId != "secure_vault" &&
            contextVideos.size > 1 &&
            contextVideos.any { it.id == video.id }

        if (canQueueContext) {
            val validContext = contextVideos.filter { it.bucketId != "secure_vault" }
            val startIndex = validContext.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
            playlistVideos = validContext
            val activeMode = playerManager.uiState.value.playbackMode
            playerManager.playPlaylist(
                items = validContext,
                startIndex = startIndex,
                shuffle = activeMode.shuffleEnabled,
                repeatMode = activeMode.repeatModeEquivalent,
                startPositionMs = 0L
            )
        } else {
            playlistVideos = emptyList()
            playerManager.playMedia(video.contentUri, video.safeTitle, startPositionMs = 0L)
        }

        val targetGen = playerManager.getCurrentPlaybackGeneration()
        viewModelScope.launch {
            handlePositionRestorationForMode(
                videoTitle = video.safeTitle,
                targetUri = targetUri,
                targetGeneration = targetGen,
                durationMs = video.durationMs,
                legacyVideoId = video.id
            )
        }
    }

    fun playPlaylist(playlistId: Long, startVideoId: Long?, shuffle: Boolean) {
        viewModelScope.launch {
            val rawItems = playlistRepository.getPlaylistItems(playlistId).filter { it.isAvailable }
            if (rawItems.isEmpty()) return@launch
            val videoItems = rawItems.map { it.toVideoItem() }
            playlistVideos = videoItems

            val startIndex = if (startVideoId != null) {
                videoItems.indexOfFirst { it.id == startVideoId }.coerceAtLeast(0)
            } else 0

            val startVideo = videoItems[startIndex]
            currentVideo = startVideo
            _pendingResumePrompt.value = null
            safeThumbnailManager?.setVideoUri(startVideo.contentUri)

            val targetUri = startVideo.contentUri.toString()
            if (shuffle) {
                setPlaybackMode(PlaybackMode.SHUFFLE_ALL)
            }
            val effectiveMode = if (shuffle) PlaybackMode.SHUFFLE_ALL else playerManager.uiState.value.playbackMode
            playerManager.playPlaylist(
                items = videoItems,
                startIndex = startIndex,
                shuffle = effectiveMode.shuffleEnabled,
                repeatMode = effectiveMode.repeatModeEquivalent,
                startPositionMs = 0L
            )
            val targetGen = playerManager.getCurrentPlaybackGeneration()

            handlePositionRestorationForMode(
                videoTitle = startVideo.safeTitle,
                targetUri = targetUri,
                targetGeneration = targetGen,
                durationMs = startVideo.durationMs,
                legacyVideoId = startVideo.id
            )
        }
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        playerManager.setPlaybackMode(mode)
        viewModelScope.launch {
            playbackPreferences.setPlaybackMode(mode)
        }
    }

    fun setResumeMode(mode: ResumeMode) {
        playerManager.setResumeModeUi(mode)
        viewModelScope.launch {
            playbackPreferences.setResumeMode(mode)
        }
    }

    fun cycleRepeatMode() {
        val currentMode = playerManager.uiState.value.playbackMode
        val nextPlaybackMode = when (currentMode) {
            PlaybackMode.PLAY_ALL_IN_ORDER -> PlaybackMode.REPEAT_PLAYLIST
            PlaybackMode.REPEAT_PLAYLIST -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.SHUFFLE_ALL
            PlaybackMode.SHUFFLE_ALL -> PlaybackMode.STOP_AFTER_CURRENT
            PlaybackMode.STOP_AFTER_CURRENT -> PlaybackMode.PLAY_ALL_IN_ORDER
        }
        setPlaybackMode(nextPlaybackMode)
    }

    fun nextTrack(): Boolean = playerManager.nextTrack()

    fun previousTrack(): Boolean = playerManager.previousTrack()

    fun setTemporaryPlaybackSpeed(speed: Float = 2.0f) {
        playerManager.setTemporaryPlaybackSpeed(speed)
    }

    fun clearTemporaryPlaybackSpeed() {
        playerManager.clearTemporaryPlaybackSpeed()
    }

    fun saveCurrentPosition() {
        val capturedDirectly = playerManager.saveCurrentExoPlayerPositionDirectly()
        val uri = playerManager.uiState.value.mediaUri?.toString() ?: currentVideo?.contentUri?.toString()
        val pos = playerManager.uiState.value.currentPositionMs
        val dur = playerManager.uiState.value.durationMs

        val fallback = PositionSavePolicy.shouldEnqueueFallbackSave(
            capturedDirectlyFromPlayer = capturedDirectly,
            uri = uri,
            currentPositionMs = pos,
            durationMs = dur
        ) ?: return

        positionManager.enqueueSave(fallback.uri, fallback.positionMs, fallback.durationMs)
    }

    fun stopPlaybackCompletely() {
        currentVideo = null
        playlistVideos = emptyList()
        _pendingResumePrompt.value = null
        playerManager.stopPlayback()
    }

    fun play() = playerManager.play()

    fun pause() = playerManager.pause()

    override fun onCleared() {
        saveCurrentPosition()
        super.onCleared()
    }

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
        val uri = playerManager.uiState.value.mediaUri?.toString()
            ?: currentVideo?.contentUri?.toString()
        val durationMs = playerManager.uiState.value.durationMs
            .takeIf { it > 0L }
            ?: (currentVideo?.durationMs ?: 0L)

        if (!uri.isNullOrBlank() && positionMs >= 0L) {
            positionManager.enqueueSave(uri, positionMs, durationMs)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = VideoPlayerManager.clampPlaybackSpeed(speed)
        playerManager.setPlaybackSpeed(clamped)
        viewModelScope.launch {
            playbackPreferences.setPlaybackSpeed(clamped)
        }
    }

    fun setResizeMode(mode: Int) {
        val normalized = VideoResizeMode.fromCode(mode).storageCode
        playerManager.setResizeMode(normalized)
        viewModelScope.launch {
            playbackPreferences.setResizeMode(normalized)
        }
    }

    fun setVideoResizeMode(mode: VideoResizeMode) {
        setResizeMode(mode.storageCode)
    }

    fun cycleResizeMode(): VideoResizeMode {
        val next = playerManager.uiState.value.resizeModeEnum.next()
        setVideoResizeMode(next)
        return next
    }

    fun toggleControls() = playerManager.toggleControls()
    fun setControlsVisible(visible: Boolean) = playerManager.setControlsVisible(visible)
    fun toggleLock() = playerManager.toggleLock()
    fun setPipActive(active: Boolean) = playerManager.setPipActive(active)

    fun onScrubStart(positionMs: Long, fraction: Float) {
        safeThumbnailManager?.onScrubStart(positionMs, fraction)
    }

    fun onScrubProgress(positionMs: Long, fraction: Float) {
        safeThumbnailManager?.onScrubProgress(positionMs, fraction)
    }

    fun onScrubStop(finalPositionMs: Long) {
        safeThumbnailManager?.onScrubStop()
        seekTo(finalPositionMs)
    }

    fun setPrimarySubtitleTrack(track: SubtitleTrack?) = playerManager.setPrimarySubtitleTrack(track)
    fun setSecondarySubtitleTrack(track: SubtitleTrack?) = playerManager.setSecondarySubtitleTrack(track)

    fun importSubtitle(context: Context, uri: Uri, name: String) {
        viewModelScope.launch {
            val cues = SubtitleParser.parse(context, uri)
            val track = SubtitleTrack(
                id = UUID.randomUUID().toString(),
                name = name,
                uri = uri,
                cues = cues,
                isExternal = true
            )
            playerManager.addSubtitleTrack(track)
            if (playerManager.uiState.value.primaryTrack == null) {
                playerManager.setPrimarySubtitleTrack(track)
            } else if (playerManager.uiState.value.secondaryTrack == null) {
                playerManager.setSecondarySubtitleTrack(track)
            }
        }
    }

    fun updatePrimaryFontSize(sizeSp: Float) {
        viewModelScope.launch {
            safeSubtitlePreferences?.updatePrimaryFontSize(sizeSp)
        }
    }

    fun updateSecondaryFontSize(sizeSp: Float) {
        viewModelScope.launch {
            safeSubtitlePreferences?.updateSecondaryFontSize(sizeSp)
        }
    }
}
