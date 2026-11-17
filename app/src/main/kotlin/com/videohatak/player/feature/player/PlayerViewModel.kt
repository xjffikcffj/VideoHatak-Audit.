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
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.subtitle.parser.SubtitleParseResult
import com.videohatak.player.core.subtitle.parser.SubtitleParser
import com.videohatak.player.core.subtitle.repository.LocalSubtitleStore
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesRepository
import com.videohatak.player.core.subtitle.speech.VideoAudioPcmDecoder
import com.videohatak.player.core.subtitle.speech.VideoAudioTrackOption
import com.videohatak.player.core.subtitle.speech.VoskRecognitionException
import com.videohatak.player.core.subtitle.speech.VoskSpeechToSubtitleEngine
import com.videohatak.player.core.subtitle.translation.AudioTrackExtractor
import com.videohatak.player.core.subtitle.translation.BackendProxyConfig
import com.videohatak.player.core.subtitle.translation.ContextAwareDialogueTranslator
import com.videohatak.player.core.subtitle.translation.LanguageModelResourceState
import com.videohatak.player.core.subtitle.translation.MlKitOfflineSubtitleTranslator
import com.videohatak.player.core.subtitle.translation.OfflineModelManager
import com.videohatak.player.core.subtitle.translation.OfflineTranslationException
import com.videohatak.player.core.subtitle.translation.ProxySubtitleTranslationProvider
import com.videohatak.player.core.subtitle.translation.SubtitleSourceMode
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationProvider
import com.videohatak.player.core.subtitle.translation.TranslatedCuePair
import com.videohatak.player.core.subtitle.translation.TranslationEngineMode
import com.videohatak.player.core.subtitle.translation.TranslationGlossaryEntry
import com.videohatak.player.core.subtitle.translation.TranslationJobStatus
import com.videohatak.player.core.subtitle.translation.TranslationPairReadiness
import com.videohatak.player.core.subtitle.translation.TranslationProxyException
import com.videohatak.player.core.thumbnail.manager.ThumbnailPreviewManager
import com.videohatak.player.core.thumbnail.model.ThumbnailPreviewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val thumbnailManager: ThumbnailPreviewManager? = null,
    subtitleStore: LocalSubtitleStore? = null,
    offlineModelManager: OfflineModelManager? = null
) : AndroidViewModel(application) {

    private val safeSubtitlePreferences: SubtitlePreferencesRepository? =
        subtitlePreferences ?: try { SubtitlePreferencesRepository(application) } catch (_: Throwable) { null }

    private val safeThumbnailManager: ThumbnailPreviewManager? =
        thumbnailManager ?: try { ThumbnailPreviewManager(application) } catch (_: Throwable) { null }

    private val safeSubtitleStore: LocalSubtitleStore? =
        subtitleStore ?: try { LocalSubtitleStore(application) } catch (_: Throwable) { null }

    private val safeOfflineModelManager: OfflineModelManager? =
        offlineModelManager ?: try { OfflineModelManager.getInstance(application) } catch (_: Throwable) { null }

    private val _currentVideo = MutableStateFlow<VideoItem?>(null)
    val currentVideoFlow: StateFlow<VideoItem?> = _currentVideo.asStateFlow()

    var currentVideo: VideoItem?
        get() = _currentVideo.value
        private set(value) {
            _currentVideo.value = value
        }

    private val playlistRepository by lazy { PlaylistRepositoryImpl(getApplication()) }
    private var playlistVideos: List<VideoItem> = emptyList()
    private var translationJob: Job? = null

    private val proxyPrefs = try {
        application.getSharedPreferences("videohatak_translation_proxy", Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }

    private val _translationProxyUrl = MutableStateFlow(
        proxyPrefs?.getString("proxy_base_url", "") ?: ""
    )
    val translationProxyUrl: StateFlow<String> = _translationProxyUrl.asStateFlow()

    private val _translationEngineMode = MutableStateFlow(TranslationEngineMode.OFFLINE_ON_DEVICE)
    val translationEngineMode: StateFlow<TranslationEngineMode> = _translationEngineMode.asStateFlow()

    val offlineModelStates: StateFlow<Map<SubtitleTranslationLanguage, LanguageModelResourceState>> =
        safeOfflineModelManager?.languageStates ?: MutableStateFlow(emptyMap())

    private val _detectedVideoAudioLanguage = MutableStateFlow<SubtitleTranslationLanguage?>(null)
    val detectedVideoAudioLanguage: StateFlow<SubtitleTranslationLanguage?> =
        _detectedVideoAudioLanguage.asStateFlow()

    private val _availableAudioTracks = MutableStateFlow<List<VideoAudioTrackOption>>(emptyList())
    val availableAudioTracks: StateFlow<List<VideoAudioTrackOption>> =
        _availableAudioTracks.asStateFlow()

    private val _selectedAudioTrackIndex = MutableStateFlow<Int?>(null)
    val selectedAudioTrackIndex: StateFlow<Int?> =
        _selectedAudioTrackIndex.asStateFlow()

    private val _isPlaylistLoadPending = MutableStateFlow(false)
    val isPlaylistLoadPending: StateFlow<Boolean> =
        _isPlaylistLoadPending.asStateFlow()

    private val _subtitleFeedbackMessage = MutableStateFlow<String?>(null)
    val subtitleFeedbackMessage: StateFlow<String?> = _subtitleFeedbackMessage.asStateFlow()

    private val _translationStatus = MutableStateFlow<TranslationJobStatus>(TranslationJobStatus.Idle)
    val translationStatus: StateFlow<TranslationJobStatus> = _translationStatus.asStateFlow()

    private val _pendingResumePrompt = MutableStateFlow<ResumePromptState?>(null)
    val pendingResumePrompt: StateFlow<ResumePromptState?> = _pendingResumePrompt.asStateFlow()

    val playbackMode: StateFlow<PlaybackMode> = playbackPreferences.playbackMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackMode.PLAY_ALL_IN_ORDER)

    val resumeMode: StateFlow<ResumeMode> = playbackPreferences.resumeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ResumeMode.RESUME)

    val seekIntervalSeconds: StateFlow<Int> = playbackPreferences.seekIntervalSeconds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS
        )

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
            val matching = playlistVideos.find {
                it.id.toString() == mediaId || it.contentUri.toString() == mediaId
            }
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
        viewModelScope.launch {
            playbackPreferences.seekIntervalSeconds.collect { seconds ->
                playerManager.setSeekIntervalSeconds(seconds)
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
            loadSavedSubtitlesForVideo(targetUri)
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
            loadSavedSubtitlesForVideo(targetUri)
            handlePositionRestorationForMode(
                videoTitle = video.safeTitle,
                targetUri = targetUri,
                targetGeneration = targetGen,
                durationMs = video.durationMs,
                legacyVideoId = video.id
            )
        }
    }

    fun playPlaylist(
        playlistId: Long,
        startVideoId: Long?,
        shuffle: Boolean = playerManager.uiState.value.playbackMode.shuffleEnabled,
        onPlaylistStarted: ((VideoItem) -> Unit)? = null
    ) {
        viewModelScope.launch {
            _isPlaylistLoadPending.value = true
            try {
                val rawItems = playlistRepository.getPlaylistItems(playlistId).filter { it.isAvailable }
                if (rawItems.isEmpty()) return@launch
                val videoItems = rawItems.map { it.toVideoItem() }
                playlistVideos = videoItems

                val activeMode = playerManager.uiState.value.playbackMode
                val effectiveShuffle = shuffle || activeMode.shuffleEnabled
                val effectiveMode = if (shuffle && !activeMode.shuffleEnabled) {
                    PlaybackMode.SHUFFLE_ALL.also { setPlaybackMode(it) }
                } else {
                    activeMode
                }

                val startIndex = if (startVideoId != null) {
                    videoItems.indexOfFirst { it.id == startVideoId }.coerceAtLeast(0)
                } else if (effectiveShuffle && videoItems.size > 1) {
                    kotlin.random.Random.nextInt(videoItems.size)
                } else {
                    0
                }

                val startVideo = videoItems[startIndex]
                currentVideo = startVideo
                _pendingResumePrompt.value = null
                safeThumbnailManager?.setVideoUri(startVideo.contentUri)

                val targetUri = startVideo.contentUri.toString()
                val effectiveRepeatMode = if (effectiveShuffle && effectiveMode.repeatModeEquivalent == PlaylistRepeatMode.OFF) {
                    PlaylistRepeatMode.ALL
                } else {
                    effectiveMode.repeatModeEquivalent
                }
                playerManager.playPlaylist(
                    items = videoItems,
                    startIndex = startIndex,
                    shuffle = effectiveShuffle,
                    repeatMode = effectiveRepeatMode,
                    startPositionMs = 0L
                )
                onPlaylistStarted?.invoke(startVideo)
                val targetGen = playerManager.getCurrentPlaybackGeneration()

                loadSavedSubtitlesForVideo(targetUri)
                handlePositionRestorationForMode(
                    videoTitle = startVideo.safeTitle,
                    targetUri = targetUri,
                    targetGeneration = targetGen,
                    durationMs = startVideo.durationMs,
                    legacyVideoId = startVideo.id
                )
            } finally {
                _isPlaylistLoadPending.value = false
            }
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

    fun setSeekIntervalSeconds(seconds: Int) {
        val valid = PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(seconds)
        playerManager.setSeekIntervalSeconds(valid)
        viewModelScope.launch {
            playbackPreferences.setSeekIntervalSeconds(valid)
        }
    }

    fun seekForward(): Long {
        val target = playerManager.seekForward()
        val uri = playerManager.uiState.value.mediaUri?.toString() ?: currentVideo?.contentUri?.toString()
        val durationMs = playerManager.uiState.value.durationMs.takeIf { it > 0L } ?: (currentVideo?.durationMs ?: 0L)
        if (!uri.isNullOrBlank()) positionManager.enqueueSave(uri, target, durationMs)
        return target
    }

    fun seekBackward(): Long {
        val target = playerManager.seekBackward()
        val uri = playerManager.uiState.value.mediaUri?.toString() ?: currentVideo?.contentUri?.toString()
        val durationMs = playerManager.uiState.value.durationMs.takeIf { it > 0L } ?: (currentVideo?.durationMs ?: 0L)
        if (!uri.isNullOrBlank()) positionManager.enqueueSave(uri, target, durationMs)
        return target
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
    fun setAudioOnlyMode(enabled: Boolean) = playerManager.setAudioOnlyMode(enabled)
    fun toggleAudioOnlyMode(): Boolean = playerManager.toggleAudioOnlyMode()

    fun updateActiveVideo(updated: VideoItem) {
        if (currentVideo?.id == updated.id || currentVideo?.contentUri == updated.contentUri) {
            currentVideo = updated
            playerManager.updateActiveTitle(updated.id, updated.contentUri, updated.safeTitle)
        }
    }

    fun syncVideoFromPlayerManager(): VideoItem? {
        currentVideo?.let { return it }
        playerManager.syncWithCurrentPlayer()
        val state = playerManager.uiState.value
        val uri = state.mediaUri ?: return null
        val restored = VideoItem(
            id = uri.hashCode().toLong(),
            contentUri = uri,
            title = state.title.ifBlank { "تشغيل حالي" },
            displayName = state.title.ifBlank { "تشغيل حالي" },
            durationMs = state.durationMs,
            sizeBytes = 0L,
            bucketId = if (uri.scheme.equals("vault", ignoreCase = true)) "secure_vault" else "restored",
            dateAddedSec = 0L,
            width = state.videoWidth,
            height = state.videoHeight
        )
        currentVideo = restored
        return restored
    }

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
    fun setSubtitlesVisible(visible: Boolean) = playerManager.setSubtitlesVisible(visible)

    fun adjustPrimarySubtitleOffsetMs(deltaMs: Long) {
        val current = playerManager.uiState.value.primarySubtitleOffsetMs
        playerManager.setPrimarySubtitleOffsetMs(current + deltaMs)
    }

    fun resetPrimarySubtitleOffset() = playerManager.setPrimarySubtitleOffsetMs(0L)

    fun adjustSecondarySubtitleOffsetMs(deltaMs: Long) {
        val current = playerManager.uiState.value.secondarySubtitleOffsetMs
        playerManager.setSecondarySubtitleOffsetMs(current + deltaMs)
    }

    fun resetSecondarySubtitleOffset() = playerManager.setSecondarySubtitleOffsetMs(0L)

    fun clearSubtitleFeedback() {
        _subtitleFeedbackMessage.value = null
    }

    private suspend fun loadSavedSubtitlesForVideo(videoUri: String) {
        val store = safeSubtitleStore ?: return
        val savedTracks = runCatching { store.loadTracksForVideo(videoUri) }.getOrDefault(emptyList())
        if (savedTracks.isNotEmpty()) {
            savedTracks.forEach { track ->
                playerManager.addSubtitleTrack(track)
            }
            if (playerManager.uiState.value.primaryTrack == null) {
                playerManager.setPrimarySubtitleTrack(savedTracks.first())
            }
        }
    }

    fun importSubtitle(context: Context, uri: Uri, name: String) {
        viewModelScope.launch {
            when (val result = SubtitleParser.parseSafely(context, uri, fallbackName = name)) {
                is SubtitleParseResult.Error -> {
                    _subtitleFeedbackMessage.value = result.message
                }
                is SubtitleParseResult.Success -> {
                    val activeVideoUri = playerManager.uiState.value.mediaUri?.toString()
                        ?: currentVideo?.contentUri?.toString().orEmpty()
                    val cleanName = result.resolvedFileName.ifBlank { name }

                    val track = if (activeVideoUri.isNotBlank() && safeSubtitleStore != null) {
                        runCatching {
                            safeSubtitleStore.saveTrackForVideo(
                                videoUri = activeVideoUri,
                                trackName = cleanName,
                                cues = result.cues,
                                format = result.format
                            )
                        }.getOrElse {
                            SubtitleTrack(
                                id = UUID.randomUUID().toString(),
                                name = cleanName,
                                uri = uri,
                                cues = result.cues,
                                isExternal = true
                            )
                        }
                    } else {
                        SubtitleTrack(
                            id = UUID.randomUUID().toString(),
                            name = cleanName,
                            uri = uri,
                            cues = result.cues,
                            isExternal = true
                        )
                    }

                    playerManager.addSubtitleTrack(track)
                    if (playerManager.uiState.value.primaryTrack == null) {
                        playerManager.setPrimarySubtitleTrack(track)
                    } else if (playerManager.uiState.value.secondaryTrack == null) {
                        playerManager.setSecondarySubtitleTrack(track)
                    }
                    _subtitleFeedbackMessage.value =
                        "تم تحميل \"${track.name}\" (${result.cues.size} جملة • ${result.detectedEncoding})"
                }
            }
        }
    }

    fun updateSubtitleCueText(trackId: String, cueIndex: Int, newText: String) {
        val currentTrack = playerManager.uiState.value.availableSubtitleTracks.firstOrNull { it.id == trackId }
            ?: return
        val updatedCues = currentTrack.cues.mapIndexed { idx, cue ->
            if (idx == cueIndex) cue.copy(text = newText.trim()) else cue
        }
        playerManager.updateSubtitleTrackCues(trackId, updatedCues)
        val activeVideoUri = playerManager.uiState.value.mediaUri?.toString()
            ?: currentVideo?.contentUri?.toString().orEmpty()
        if (activeVideoUri.isNotBlank()) {
            viewModelScope.launch {
                safeSubtitleStore?.updateTrackCues(activeVideoUri, trackId, updatedCues)
            }
        }
    }

    fun removeSubtitleTrack(trackId: String) {
        playerManager.removeSubtitleTrack(trackId)
        val activeVideoUri = playerManager.uiState.value.mediaUri?.toString()
            ?: currentVideo?.contentUri?.toString().orEmpty()
        if (activeVideoUri.isNotBlank()) {
            viewModelScope.launch {
                safeSubtitleStore?.removeTrackForVideo(activeVideoUri, trackId)
            }
        }
    }

    fun exportTrackAsText(track: SubtitleTrack, format: SubtitleFormat): String {
        return SubtitleParser.export(track.cues, format)
    }

    fun setTranslationProxyUrl(url: String) {
        val cleaned = url.trim()
        _translationProxyUrl.value = cleaned
        proxyPrefs?.edit()?.putString("proxy_base_url", cleaned)?.apply()
    }

    fun setTranslationEngineMode(mode: TranslationEngineMode) {
        _translationEngineMode.value = mode
    }

    fun refreshOfflineModelStates() {
        viewModelScope.launch {
            safeOfflineModelManager?.refreshAllStates()
        }
    }

    fun inspectVideoAudioLanguage(context: Context, videoUri: Uri) {
        viewModelScope.launch {
            val tracks = runCatching {
                VideoAudioPcmDecoder.inspectAudioTracks(context, videoUri)
            }.getOrDefault(emptyList())
            _availableAudioTracks.value = tracks
            val currentIdx = _selectedAudioTrackIndex.value
            val chosen = tracks.firstOrNull { it.trackIndex == currentIdx } ?: tracks.firstOrNull()
            _selectedAudioTrackIndex.value = chosen?.trackIndex
            _detectedVideoAudioLanguage.value = chosen?.detectedLanguage
        }
    }

    fun selectAudioTrackIndex(trackIndex: Int) {
        _selectedAudioTrackIndex.value = trackIndex
        val matching = _availableAudioTracks.value.firstOrNull { it.trackIndex == trackIndex }
        _detectedVideoAudioLanguage.value = matching?.detectedLanguage
    }

    fun computeOfflinePairReadiness(
        sourceMode: SubtitleSourceMode,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ): TranslationPairReadiness? {
        return safeOfflineModelManager?.computePairReadiness(sourceMode, sourceLanguage, targetLanguage)
    }

    fun downloadSpeechModel(language: SubtitleTranslationLanguage) {
        val manager = safeOfflineModelManager ?: return
        manager.startSpeechModelDownload(language)
    }

    fun cancelSpeechModelDownload(language: SubtitleTranslationLanguage) {
        safeOfflineModelManager?.cancelSpeechModelDownload(language)
    }

    fun deleteSpeechModel(language: SubtitleTranslationLanguage) {
        val manager = safeOfflineModelManager ?: return
        viewModelScope.launch {
            val result = manager.deleteSpeechModel(language)
            _subtitleFeedbackMessage.value = result.message
        }
    }

    fun downloadTranslationModel(language: SubtitleTranslationLanguage) {
        val manager = safeOfflineModelManager ?: return
        manager.startTranslationModelDownload(language)
    }

    fun cancelTranslationModelDownload(language: SubtitleTranslationLanguage) {
        safeOfflineModelManager?.cancelTranslationModelDownload(language)
    }

    fun deleteTranslationModel(language: SubtitleTranslationLanguage) {
        val manager = safeOfflineModelManager ?: return
        viewModelScope.launch {
            val result = manager.deleteTranslationModel(language)
            _subtitleFeedbackMessage.value = result.message
        }
    }

    fun downloadRequiredModelsForPair(
        sourceMode: SubtitleSourceMode,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ) {
        val manager = safeOfflineModelManager ?: return
        viewModelScope.launch {
            manager.refreshAllStates()
            val readiness = manager.computePairReadiness(sourceMode, sourceLanguage, targetLanguage)
            if (readiness.isSpeechRequired && readiness.speechLanguage != null && !readiness.isSpeechReady) {
                manager.startSpeechModelDownload(readiness.speechLanguage)
            }
            readiness.missingMlKitLanguages.forEach { lang ->
                manager.startTranslationModelDownload(lang)
            }
        }
    }

    fun clearTranslationStatus() {
        _translationStatus.value = TranslationJobStatus.Idle
    }

    fun cancelSmartSubtitleTranslation() {
        translationJob?.cancel()
        translationJob = null
        val currentPairs = (_translationStatus.value as? TranslationJobStatus.Completed)?.pairs ?: emptyList()
        _translationStatus.value = TranslationJobStatus.Cancelled(currentPairs)
    }

    fun updateTranslatedDraftCue(positionIndex: Int, newTranslatedText: String) {
        val completed = _translationStatus.value as? TranslationJobStatus.Completed ?: return
        val updated = completed.pairs.mapIndexed { idx, pair ->
            if (idx == positionIndex) pair.copy(translatedText = newTranslatedText) else pair
        }
        _translationStatus.value = completed.copy(pairs = updated)
    }

    fun saveAndAttachTranslatedSubtitles(
        trackTitle: String,
        targetLanguage: SubtitleTranslationLanguage,
        format: SubtitleFormat = SubtitleFormat.SRT,
        assignAsSecondary: Boolean = false
    ) {
        val completed = _translationStatus.value as? TranslationJobStatus.Completed ?: return
        val cues = completed.pairs.map { it.toTranslatedCue() }
        if (cues.isEmpty()) return

        viewModelScope.launch {
            val activeVideoUri = playerManager.uiState.value.mediaUri?.toString()
                ?: currentVideo?.contentUri?.toString().orEmpty()
            val savedTrack = if (activeVideoUri.isNotBlank() && safeSubtitleStore != null) {
                safeSubtitleStore.saveTrackForVideo(
                    videoUri = activeVideoUri,
                    trackName = trackTitle,
                    language = targetLanguage.code,
                    cues = cues,
                    format = format
                )
            } else {
                SubtitleTrack(
                    id = UUID.randomUUID().toString(),
                    name = trackTitle,
                    uri = Uri.EMPTY,
                    language = targetLanguage.code,
                    cues = cues,
                    isExternal = true
                )
            }
            playerManager.addSubtitleTrack(savedTrack)
            if (assignAsSecondary) {
                playerManager.setSecondarySubtitleTrack(savedTrack)
            } else {
                playerManager.setPrimarySubtitleTrack(savedTrack)
            }
            _subtitleFeedbackMessage.value = "تم حفظ وتطبيق الترجمة الذكية: ${savedTrack.name}"
        }
    }

    fun startSmartSubtitleTranslation(
        context: Context,
        videoUri: Uri,
        sourceMode: SubtitleSourceMode,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage,
        sourceTrack: SubtitleTrack?,
        glossaryRawText: String = "",
        customProvider: SubtitleTranslationProvider? = null
    ) {
        translationJob?.cancel()
        val glossary = glossaryRawText.lines()
            .mapNotNull { line ->
                val parts = line.split("=", ":")
                if (parts.size >= 2) {
                    val src = parts[0].trim()
                    val dst = parts.drop(1).joinToString("=").trim()
                    if (src.isNotBlank() && dst.isNotBlank()) TranslationGlossaryEntry(src, dst) else null
                } else null
            }

        val useCloudProxy = customProvider != null ||
            _translationEngineMode.value == TranslationEngineMode.CLOUD_PROXY

        translationJob = viewModelScope.launch {
            try {
                if (useCloudProxy) {
                    val provider = customProvider ?: ProxySubtitleTranslationProvider {
                        BackendProxyConfig(baseUrl = _translationProxyUrl.value)
                    }
                    runCloudProxyTranslation(
                        context = context,
                        videoUri = videoUri,
                        sourceMode = sourceMode,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        sourceTrack = sourceTrack,
                        glossary = glossary,
                        provider = provider
                    )
                } else {
                    val modelManager = safeOfflineModelManager
                        ?: OfflineModelManager.getInstance(context.applicationContext)
                    runOfflineOnDeviceTranslation(
                        context = context,
                        videoUri = videoUri,
                        sourceMode = sourceMode,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        sourceTrack = sourceTrack,
                        glossary = glossary,
                        modelManager = modelManager
                    )
                }
            } catch (_: CancellationException) {
                _translationStatus.value = TranslationJobStatus.Cancelled()
            } catch (proxyEx: TranslationProxyException) {
                _translationStatus.value = TranslationJobStatus.Error(
                    message = proxyEx.message ?: "تعذر إتمام الترجمة عبر الخادم",
                    retryAfterSeconds = proxyEx.retryAfterSeconds
                )
            } catch (offlineEx: OfflineTranslationException) {
                _translationStatus.value = TranslationJobStatus.Error(
                    message = offlineEx.message ?: "تعذر إتمام الترجمة المحلية دون إنترنت"
                )
            } catch (voskEx: VoskRecognitionException) {
                _translationStatus.value = TranslationJobStatus.Error(
                    message = voskEx.message ?: "تعذر إتمام التفريغ الصوتي المحلي"
                )
            } catch (e: Exception) {
                _translationStatus.value = TranslationJobStatus.Error(
                    message = e.localizedMessage ?: "حدث خطأ غير متوقع أثناء الترجمة"
                )
            }
        }
    }

    private suspend fun runOfflineOnDeviceTranslation(
        context: Context,
        videoUri: Uri,
        sourceMode: SubtitleSourceMode,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage,
        sourceTrack: SubtitleTrack?,
        glossary: List<TranslationGlossaryEntry>,
        modelManager: OfflineModelManager
    ) {
        val translator = MlKitOfflineSubtitleTranslator(modelManager)
        modelManager.refreshAllStates()

        when (sourceMode) {
            SubtitleSourceMode.EXISTING_SUBTITLE -> {
                val cues = sourceTrack?.cues ?: playerManager.uiState.value.primaryTrack?.cues.orEmpty()
                if (cues.isEmpty()) {
                    _translationStatus.value = TranslationJobStatus.Error(
                        "يرجى استيراد أو تحديد مسار ترجمة أولًا للترجمة النصية، أو اختيار خيار التفريغ الصوتي المحلي."
                    )
                    return
                }
                _translationStatus.value = TranslationJobStatus.Running(
                    progress = 0.05f,
                    completedBatches = 0,
                    totalBatches = cues.size,
                    phaseLabel = "جاري تجهيز الترجمة المحلية دون إنترنت (${cues.size} جملة)..."
                )
                val execResult = translator.translateCuesOffline(
                    cues = cues,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    glossary = glossary,
                    downloadMissingModels = false
                ) { done, total, label ->
                    val fraction = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0.05f, 1f) else 1f
                    _translationStatus.value = TranslationJobStatus.Running(
                        progress = fraction,
                        completedBatches = done,
                        totalBatches = total,
                        phaseLabel = label
                    )
                }
                val engineNote = if (execResult.usedTranslationEngine) {
                    "عبر ML Kit محليًا (${execResult.resolvedSourceLanguage.arabicName} ← ${targetLanguage.arabicName}) دون إنترنت."
                } else {
                    "لغة المصدر مطابقة للهدف (${targetLanguage.arabicName})."
                }
                _translationStatus.value = TranslationJobStatus.Completed(
                    pairs = execResult.pairs,
                    targetLanguage = targetLanguage,
                    summaryMessage = "تمت معالجة ${execResult.pairs.size} جملة $engineNote"
                )
            }

            SubtitleSourceMode.VIDEO_AUDIO_EXTRACTION -> {
                val selectedAudioTrackIdx = _selectedAudioTrackIndex.value
                val resolvedAudioLang = if (sourceLanguage != SubtitleTranslationLanguage.AUTO) {
                    sourceLanguage
                } else {
                    _translationStatus.value = TranslationJobStatus.Running(
                        progress = 0.03f,
                        completedBatches = 0,
                        totalBatches = 100,
                        phaseLabel = "جاري فحص بيانات المسار الصوتي لاكتشاف لغة الفيديو..."
                    )
                    val meta = VideoAudioPcmDecoder.inspectAudioTrack(
                        context = context,
                        videoUri = videoUri,
                        selectedTrackIndex = selectedAudioTrackIdx
                    )
                    _detectedVideoAudioLanguage.value = meta.detectedTrackLanguage
                    meta.detectedTrackLanguage
                }

                if (resolvedAudioLang == null || !resolvedAudioLang.supportsVoskSpeech) {
                    _translationStatus.value = TranslationJobStatus.Error(
                        "لم يتم تضمين وسم لغة مدعومة في مسار صوت هذا الفيديو. يرجى اختيار «لغة الفيديو الأصلية» يدويًا ثم بدء العملية."
                    )
                    return
                }

                if (modelManager.getVerifiedSpeechModelDirectory(resolvedAudioLang) == null) {
                    _translationStatus.value = TranslationJobStatus.Error(
                        "نموذج التفريغ الصوتي للغة ${resolvedAudioLang.arabicName} غير محمّل في ذاكرة التطبيق. يرجى تأكيد تنزيله أولًا دون أي تنزيل صامت."
                    )
                    return
                }

                if (resolvedAudioLang != targetLanguage) {
                    val mlKitReadiness = modelManager.computePairReadiness(
                        sourceMode = SubtitleSourceMode.EXISTING_SUBTITLE,
                        sourceLanguage = resolvedAudioLang,
                        targetLanguage = targetLanguage
                    )
                    if (mlKitReadiness.missingMlKitLanguages.isNotEmpty()) {
                        val missingNames = mlKitReadiness.missingMlKitLanguages.joinToString("، ") { it.arabicName }
                        _translationStatus.value = TranslationJobStatus.Error(
                            "يلزم تنزيل حزمة الترجمة النصية للغة ($missingNames) بموافقتك قبل بدء التفريغ والترجمة دون إنترنت."
                        )
                        return
                    }
                }

                val voskEngine = VoskSpeechToSubtitleEngine(modelManager)
                val transcribedCues = voskEngine.transcribeVideoAudioToCues(
                    context = context,
                    videoUri = videoUri,
                    sourceLanguage = resolvedAudioLang,
                    selectedAudioTrackIndex = selectedAudioTrackIdx
                ) { fraction, label ->
                    val scaled = (0.08f + fraction * 0.52f).coerceIn(0.08f, 0.60f)
                    _translationStatus.value = TranslationJobStatus.Running(
                        progress = scaled,
                        completedBatches = (scaled * 100).toInt(),
                        totalBatches = 100,
                        phaseLabel = label
                    )
                }

                _translationStatus.value = TranslationJobStatus.Running(
                    progress = 0.62f,
                    completedBatches = 62,
                    totalBatches = 100,
                    phaseLabel = "تم تفريغ ${transcribedCues.size} جملة. جاري تجهيز الترجمة إلى ${targetLanguage.arabicName}..."
                )

                val execResult = translator.translateCuesOffline(
                    cues = transcribedCues,
                    sourceLanguage = resolvedAudioLang,
                    targetLanguage = targetLanguage,
                    glossary = glossary,
                    downloadMissingModels = false
                ) { done, total, label ->
                    val subFrac = if (total > 0) done.toFloat() / total.toFloat() else 1f
                    val scaled = (0.62f + subFrac * 0.38f).coerceIn(0.62f, 1f)
                    _translationStatus.value = TranslationJobStatus.Running(
                        progress = scaled,
                        completedBatches = (scaled * 100).toInt(),
                        totalBatches = 100,
                        phaseLabel = label
                    )
                }

                val modeSummary = if (execResult.usedTranslationEngine) {
                    "تم تفريغ الكلام (${resolvedAudioLang.arabicName} عبر Vosk) وترجمة ${execResult.pairs.size} جملة إلى ${targetLanguage.arabicName} محليًا عبر ML Kit دون إنترنت."
                } else {
                    "تم تفريغ ${execResult.pairs.size} جملة باللغة ${targetLanguage.arabicName} محليًا عبر Vosk دون إنترنت."
                }
                _translationStatus.value = TranslationJobStatus.Completed(
                    pairs = execResult.pairs,
                    targetLanguage = targetLanguage,
                    summaryMessage = modeSummary
                )
            }
        }
    }

    private suspend fun runCloudProxyTranslation(
        context: Context,
        videoUri: Uri,
        sourceMode: SubtitleSourceMode,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage,
        sourceTrack: SubtitleTrack?,
        glossary: List<TranslationGlossaryEntry>,
        provider: SubtitleTranslationProvider
    ) {
        when (sourceMode) {
            SubtitleSourceMode.EXISTING_SUBTITLE -> {
                val cues = sourceTrack?.cues ?: playerManager.uiState.value.primaryTrack?.cues.orEmpty()
                if (cues.isEmpty()) {
                    _translationStatus.value = TranslationJobStatus.Error(
                        "يرجى استيراد أو تحديد مسار ترجمة أولًا للترجمة النصية، أو اختيار خيار استخراج صوت الفيديو."
                    )
                    return
                }
                val totalBatches = ((cues.size - 1) / ContextAwareDialogueTranslator.DEFAULT_BATCH_SIZE) + 1
                _translationStatus.value = TranslationJobStatus.Running(
                    progress = 0.05f,
                    completedBatches = 0,
                    totalBatches = totalBatches,
                    phaseLabel = "تجهيز الحوارات والسياق (${cues.size} جملة)..."
                )
                val translatedPairs = ContextAwareDialogueTranslator.translateAllCues(
                    cues = cues,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    provider = provider,
                    glossary = glossary,
                    onProgress = { completed, total, _ ->
                        val fraction = if (total > 0) completed.toFloat() / total.toFloat() else 1f
                        _translationStatus.value = TranslationJobStatus.Running(
                            progress = fraction,
                            completedBatches = completed,
                            totalBatches = total,
                            phaseLabel = "ترجمة الدفعة $completed من $total..."
                        )
                    }
                )
                _translationStatus.value = TranslationJobStatus.Completed(
                    pairs = translatedPairs,
                    targetLanguage = targetLanguage,
                    summaryMessage = "تمت ترجمة ${translatedPairs.size} جملة إلى ${targetLanguage.arabicName} بنجاح."
                )
            }

            SubtitleSourceMode.VIDEO_AUDIO_EXTRACTION -> {
                _translationStatus.value = TranslationJobStatus.Running(
                    progress = 0.15f,
                    completedBatches = 0,
                    totalBatches = 3,
                    phaseLabel = "جاري استخراج مسار الصوت محليًا من الفيديو..."
                )
                val extractedAudio = AudioTrackExtractor.extractAudioWithMetadataToTempM4a(
                    context = context,
                    videoUri = videoUri,
                    selectedTrackIndex = _selectedAudioTrackIndex.value
                )
                val tempAudio = extractedAudio.file
                try {
                    _translationStatus.value = TranslationJobStatus.Running(
                        progress = 0.55f,
                        completedBatches = 1,
                        totalBatches = 3,
                        phaseLabel = "جاري النسخ النصي وترجمة الحوارات عبر الخادم الآمن..."
                    )
                    val pairs = provider.transcribeAndTranslateAudio(
                        audioFile = tempAudio,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    ).map {
                        it.copy(translatedText = ContextAwareDialogueTranslator.formatSubtitleLines(it.translatedText))
                    }
                    val capNotice = if (extractedAudio.wasTruncatedAtSizeCap) {
                        "\nملاحظة: تم الاكتفاء بأول 18 ميجابايت من مسار الصوت نظرًا لحد الرفع السحابي."
                    } else {
                        ""
                    }
                    _translationStatus.value = TranslationJobStatus.Completed(
                        pairs = pairs,
                        targetLanguage = targetLanguage,
                        summaryMessage = "تم توليد وترجمة ${pairs.size} جملة من الصوت إلى ${targetLanguage.arabicName}.$capNotice"
                    )
                } finally {
                    tempAudio.delete()
                }
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
