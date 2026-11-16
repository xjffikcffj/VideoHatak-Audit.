package com.videohatak.player

import android.app.Application
import android.net.Uri
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepository
import com.videohatak.player.core.designsystem.theme.ThemeViewModel
import com.videohatak.player.core.designsystem.theme.paletteForTheme
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.permission.VideoPermissionStatus
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPositionManager
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playback.VolumeComboShortcutDetector
import com.videohatak.player.core.playback.service.MediaNotificationArtworkLoader
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.feature.player.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/**
 * Unit & integration verification tests for:
 * 1. All 8 approved themes and palette uniqueness + ThemeViewModel switching.
 * 2. All 5 mutually exclusive PlaybackMode presets & STOP_AFTER_CURRENT enforcement.
 * 3. All 3 ResumeMode presets (RESUME, START_FROM_BEGINNING, ASK_EVERY_TIME) with real DataStore.
 * 4. Temporary 2.0x speed hold without overwriting user's persisted playback speed.
 * 5. Permission state modeling (including permanent denial state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NewFeaturesVerificationTest {

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()
    private val tempFiles = mutableListOf<File>()
    private val activeManagers = mutableListOf<PlaybackPositionManager>()
    private val activePlayerManagers = mutableListOf<VideoPlayerManager>()
    private val activeViewModelStores = mutableListOf<ViewModelStore>()
    private val activeScopes = mutableListOf<CoroutineScope>()

    private fun testApp(): Application = RuntimeEnvironment.getApplication()

    private inline fun <reified T : ViewModel> createManagedViewModel(
        crossinline creator: () -> T
    ): Pair<ViewModelStore, T> {
        val store = ViewModelStore().also { activeViewModelStores.add(it) }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
        }
        return store to ViewModelProvider(store, factory)[T::class.java]
    }

    private class InMemoryThemePreferencesRepository : ThemePreferencesRepository {
        private val modeFlow = MutableStateFlow(ThemeMode.DARK)
        private val themeFlow = MutableStateFlow(AppThemeId.OBSIDIAN_ELITE)

        override val themeModeFlow: Flow<ThemeMode> = modeFlow
        override val appThemeFlow: Flow<AppThemeId> = themeFlow

        override suspend fun setThemeMode(mode: ThemeMode) {
            modeFlow.value = mode
        }

        override suspend fun setAppTheme(theme: AppThemeId) {
            themeFlow.value = theme
        }
    }

    private fun createScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeScopes.add(scope)
        return scope
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val file = File.createTempFile("test_new_features_", ".preferences_pb")
        file.deleteOnExit()
        tempFiles.add(file)
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        try {
            activeViewModelStores.forEach { store ->
                runCatching { store.clear() }
            }
            activeViewModelStores.clear()
            activePlayerManagers.forEach { pm ->
                runCatching { pm.release() }
            }
            activePlayerManagers.clear()
            runCatching { testDispatcher.scheduler.advanceUntilIdle() }
            runBlocking {
                activeManagers.forEach { runCatching { it.shutdown(500L) } }
                activeManagers.clear()
                activeScopes.forEach { scope ->
                    runCatching { scope.coroutineContext[Job]?.cancelAndJoin() }
                    runCatching { scope.cancel() }
                }
                activeScopes.clear()
            }
            runCatching { testDispatcher.scheduler.advanceUntilIdle() }
        } finally {
            Dispatchers.resetMain()
            tempFiles.forEach { if (it.exists()) it.delete() }
            tempFiles.clear()
        }
    }

    private suspend fun drainMainAndIo(
        timeoutMs: Long = 5_000L,
        condition: suspend () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (condition()) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for async Main/IO condition")
            }
            delay(10L)
        }
    }

    @Test
    fun allEightThemes_produceEightDistinctPalettes() {
        val themes = AppThemeId.entries
        assertEquals(8, themes.size)

        val palettes = themes.map { paletteForTheme(it) }
        val lightPalettes = themes.map { paletteForTheme(it, forceLight = true) }
        // Every theme must have unique display name, background/accent signature, and seekbar glow colors in both dark and light modes
        assertEquals(8, themes.map { it.displayName }.toSet().size)
        assertEquals(8, themes.map { it.arabicTagline }.toSet().size)
        assertEquals(8, palettes.map { it.accent }.toSet().size)
        assertEquals(8, palettes.map { it.seekBarPlayedEnd }.toSet().size)
        assertEquals(8, lightPalettes.map { it.accent }.toSet().size)
        assertEquals(8, lightPalettes.map { it.seekBarPlayedEnd }.toSet().size)

        // Only Royal Ivory is light-by-default; remaining 7 are dark luxury themes
        assertTrue(AppThemeId.ROYAL_IVORY.isLightTheme)
        assertEquals(7, AppThemeId.entries.count { !it.isLightTheme })
    }

    @Test
    fun themeViewModel_switchThemeUpdatesThemeAndLightDarkMode() = runTest(testDispatcher) {
        val repo = InMemoryThemePreferencesRepository()
        val (vmStore, vm) = createManagedViewModel { ThemeViewModel(repo) }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAppTheme(AppThemeId.ROYAL_IVORY)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(AppThemeId.ROYAL_IVORY, repo.appThemeFlow.first())
        assertEquals(ThemeMode.LIGHT, repo.themeModeFlow.first())

        vm.setAppTheme(AppThemeId.RUBY_CINEMA)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(AppThemeId.RUBY_CINEMA, repo.appThemeFlow.first())
        assertEquals(ThemeMode.DARK, repo.themeModeFlow.first())

        vmStore.clear()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun playbackModes_fiveModesConfigureExoPlayerMutuallyExclusively() {
        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer()
        val manager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        manager.setPlaybackMode(PlaybackMode.STOP_AFTER_CURRENT)
        assertEquals(Player.REPEAT_MODE_OFF, fakePlayer.repeatMode)
        assertFalse(fakePlayer.shuffleModeEnabled)
        assertEquals(PlaybackMode.STOP_AFTER_CURRENT, manager.uiState.value.playbackMode)

        manager.setPlaybackMode(PlaybackMode.SHUFFLE_ALL)
        assertEquals(Player.REPEAT_MODE_ALL, fakePlayer.repeatMode)
        assertTrue(fakePlayer.shuffleModeEnabled)
        assertEquals(PlaybackMode.SHUFFLE_ALL, manager.uiState.value.playbackMode)

        manager.setPlaybackMode(PlaybackMode.PLAY_ALL_IN_ORDER)
        assertEquals(Player.REPEAT_MODE_OFF, fakePlayer.repeatMode)
        assertFalse(fakePlayer.shuffleModeEnabled)

        manager.setPlaybackMode(PlaybackMode.REPEAT_ONE)
        assertEquals(Player.REPEAT_MODE_ONE, fakePlayer.repeatMode)
        assertFalse(fakePlayer.shuffleModeEnabled)

        manager.setPlaybackMode(PlaybackMode.REPEAT_PLAYLIST)
        assertEquals(Player.REPEAT_MODE_ALL, fakePlayer.repeatMode)
        assertFalse(fakePlayer.shuffleModeEnabled)
    }

    @Test
    fun stopAfterCurrent_defensivelyPausesOnAutoTransition() {
        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer()
        val manager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)
        manager.setPlaybackMode(PlaybackMode.STOP_AFTER_CURRENT)
        fakePlayer.isPlaying = true

        val nextMedia = MediaItem.Builder()
            .setMediaId("202")
            .setUri(Uri.parse("content://media/external/video/media/202"))
            .build()

        manager.playerListener.onMediaItemTransition(nextMedia, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertFalse("STOP_AFTER_CURRENT must pause playback on automatic next-item transition", fakePlayer.isPlaying)
        assertFalse(manager.uiState.value.isPlaying)
    }

    @Test
    fun resumeModes_startFromBeginningAndAskEveryTimeBehaveCorrectly() = runBlocking {
        val scope = createScope()
        val dataStore = createDataStore(scope)
        val repo = PlaybackPreferencesRepositoryImpl(dataStore)
        val posManager = PlaybackPositionManager(repo, scope).also { activeManagers.add(it) }

        val uri = Uri.parse("content://media/external/video/media/resume_mode_test")
        repo.saveVideoPosition(uri.toString(), 45_000L, 120_000L)

        val video = VideoItem(
            id = 505L,
            contentUri = uri,
            title = "اختبار الاستئناف",
            displayName = "resume_mode_test.mp4",
            durationMs = 120_000L,
            sizeBytes = 10_000_000L,
            dateAddedSec = 1700000000L
        )

        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer().apply {
            duration = 120_000L
        }
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer).also { activePlayerManagers.add(it) }
        val (vmStore, vm) = createManagedViewModel {
            PlayerViewModel(
                application = testApp(),
                playerManager = playerManager,
                playbackPreferences = repo,
                positionManager = posManager,
                subtitlePreferences = null,
                thumbnailManager = null
            )
        }

        // 1. RESUME automatically restores the saved 45,000ms position
        repo.setResumeMode(ResumeMode.RESUME)
        drainMainAndIo {
            repo.resumeMode.first() == ResumeMode.RESUME &&
                vm.uiState.value.resumeMode == ResumeMode.RESUME
        }
        fakePlayer.playbackState = Player.STATE_IDLE
        vm.playVideo(video)
        drainMainAndIo {
            playerManager.restorationCoordinator.pendingUri == uri.toString() ||
                playerManager.restorationCoordinator.isRestorationApplied
        }
        fakePlayer.triggerPlaybackStateChanged(Player.STATE_READY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.pendingResumePrompt.value)
        assertEquals(45_000L, fakePlayer.currentPosition)

        // 2. START_FROM_BEGINNING always resets start to 0ms without wiping saved 45,000ms
        repo.setResumeMode(ResumeMode.START_FROM_BEGINNING)
        drainMainAndIo {
            repo.resumeMode.first() == ResumeMode.START_FROM_BEGINNING &&
                vm.uiState.value.resumeMode == ResumeMode.START_FROM_BEGINNING
        }
        fakePlayer.playbackState = Player.STATE_IDLE
        vm.playVideo(video)
        drainMainAndIo {
            playerManager.restorationCoordinator.pendingUri == uri.toString() ||
                playerManager.restorationCoordinator.isRestorationApplied
        }
        fakePlayer.triggerPlaybackStateChanged(Player.STATE_READY)
        testDispatcher.scheduler.advanceUntilIdle()
        posManager.flushPending(timeoutMs = 2_000L)

        assertNull(vm.pendingResumePrompt.value)
        assertEquals(0L, fakePlayer.currentPosition)
        assertEquals(45_000L, repo.getSavedVideoPosition(uri.toString(), 120_000L))

        // 3. ASK_EVERY_TIME opens prompt, pauses playback, and leaves user in control
        repo.setResumeMode(ResumeMode.ASK_EVERY_TIME)
        drainMainAndIo {
            repo.resumeMode.first() == ResumeMode.ASK_EVERY_TIME &&
                vm.uiState.value.resumeMode == ResumeMode.ASK_EVERY_TIME
        }
        fakePlayer.playbackState = Player.STATE_IDLE
        fakePlayer.isPlaying = true
        vm.playVideo(video)
        drainMainAndIo { vm.pendingResumePrompt.value != null }
        assertFalse("ASK_EVERY_TIME must pause player while waiting for user choice", fakePlayer.isPlaying)
        fakePlayer.triggerPlaybackStateChanged(Player.STATE_READY)
        testDispatcher.scheduler.advanceUntilIdle()

        val prompt = vm.pendingResumePrompt.value
        assertNotNull("ASK_EVERY_TIME must emit pendingResumePrompt when savedPos > 0", prompt)
        assertEquals(45_000L, prompt?.savedPositionMs)

        vm.resolveResumePrompt(resumeFromSaved = true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.pendingResumePrompt.value)
        assertEquals(45_000L, fakePlayer.currentPosition)
        assertTrue(fakePlayer.isPlaying)
    }

    @Test
    fun temporarySpeedHold_restoresOriginalSpeedWithoutOverwritingSavedPreference() = runBlocking {
        val scope = createScope()
        val dataStore = createDataStore(scope)
        val repo = PlaybackPreferencesRepositoryImpl(dataStore)
        val posManager = PlaybackPositionManager(repo, scope).also { activeManagers.add(it) }

        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer()
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer).also { activePlayerManagers.add(it) }
        val (_, vm) = createManagedViewModel {
            PlayerViewModel(
                application = testApp(),
                playerManager = playerManager,
                playbackPreferences = repo,
                positionManager = posManager,
                subtitlePreferences = null,
                thumbnailManager = null
            )
        }

        vm.setPlaybackSpeed(1.25f)
        drainMainAndIo {
            abs(repo.playbackSpeed.first() - 1.25f) < 0.001f &&
                abs(vm.uiState.value.playbackSpeed - 1.25f) < 0.001f
        }
        assertEquals(1.25f, vm.uiState.value.playbackSpeed, 0.001f)
        assertEquals(1.25f, vm.uiState.value.effectivePlaybackSpeed, 0.001f)
        assertEquals(1.25f, fakePlayer.playbackSpeed, 0.001f)
        assertEquals(1.25f, repo.playbackSpeed.first(), 0.001f)

        // Hold for temporary 2.0x boost: ExoPlayer and effectivePlaybackSpeed run at 2.0x,
        // while saved preference & base playbackSpeed remain 1.25x
        vm.setTemporaryPlaybackSpeed(2.0f)
        assertTrue(vm.uiState.value.isTemporarySpeedActive)
        assertEquals(2.0f, vm.uiState.value.effectivePlaybackSpeed, 0.001f)
        assertEquals(2.0f, fakePlayer.playbackSpeed, 0.001f)
        assertEquals(1.25f, vm.uiState.value.playbackSpeed, 0.001f)
        // Persisted preference must remain untouched at 1.25x
        assertEquals(1.25f, repo.playbackSpeed.first(), 0.001f)

        // Release finger -> restores previous speed 1.25x on both UI state and ExoPlayer
        vm.clearTemporaryPlaybackSpeed()
        assertFalse(vm.uiState.value.isTemporarySpeedActive)
        assertEquals(1.25f, vm.uiState.value.playbackSpeed, 0.001f)
        assertEquals(1.25f, vm.uiState.value.effectivePlaybackSpeed, 0.001f)
        assertEquals(1.25f, fakePlayer.playbackSpeed, 0.001f)
    }

    @Test
    fun permissionState_modelsPermanentDenialAndPartialAccessCleanly() {
        val deniedPermanently = VideoPermissionStatus.Denied(
            shouldShowRationale = false,
            isPermanentlyDenied = true
        )
        assertFalse(PermissionHelper.hasAnyAccess(deniedPermanently))
        assertTrue(deniedPermanently.isPermanentlyDenied)

        val partial = VideoPermissionStatus.PartialAccess
        assertTrue(PermissionHelper.hasAnyAccess(partial))
        assertFalse(partial is VideoPermissionStatus.Denied)
    }

    @Test
    fun themeViewModel_previewBeforeApply_doesNotPersistUntilApplyAndRevertsOnCancel() = runTest(testDispatcher) {
        val repo = InMemoryThemePreferencesRepository()
        val (vmStore, vm) = createManagedViewModel { ThemeViewModel(repo) }
        val previewJob = backgroundScope.launch { vm.isPreviewActive.collect {} }
        val themeJob = backgroundScope.launch { vm.effectiveAppTheme.collect {} }
        val modeJob = backgroundScope.launch { vm.effectiveThemeMode.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startPreviewTheme(AppThemeId.ROYAL_IVORY)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isPreviewActive.value)
        assertEquals(AppThemeId.ROYAL_IVORY, vm.effectiveAppTheme.value)
        assertEquals(ThemeMode.LIGHT, vm.effectiveThemeMode.value)
        assertEquals(AppThemeId.OBSIDIAN_ELITE, repo.appThemeFlow.first())

        vm.cancelPreview()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.isPreviewActive.value)
        assertEquals(AppThemeId.OBSIDIAN_ELITE, vm.effectiveAppTheme.value)
        assertEquals(ThemeMode.DARK, repo.themeModeFlow.first())

        vm.startPreviewTheme(AppThemeId.EMERALD_LUXURY)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.isPreviewActive.value)
        vm.applyPreview()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isPreviewActive.value)
        assertEquals(AppThemeId.EMERALD_LUXURY, repo.appThemeFlow.first())
        assertEquals(ThemeMode.DARK, repo.themeModeFlow.first())

        previewJob.cancelAndJoin()
        themeJob.cancelAndJoin()
        modeJob.cancelAndJoin()
        vmStore.clear()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun temporarySpeedHold_from1xRestores1x_andClampsOutOfRangeSpeeds() = runBlocking {
        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer()
        val manager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        assertEquals(1.0f, manager.uiState.value.playbackSpeed, 0.001f)
        manager.setTemporaryPlaybackSpeed(2.0f)
        assertTrue(manager.uiState.value.isTemporarySpeedActive)
        assertEquals(2.0f, manager.uiState.value.effectivePlaybackSpeed, 0.001f)
        manager.clearTemporaryPlaybackSpeed()
        assertFalse(manager.uiState.value.isTemporarySpeedActive)
        assertEquals(1.0f, manager.uiState.value.playbackSpeed, 0.001f)
        assertEquals(1.0f, manager.uiState.value.effectivePlaybackSpeed, 0.001f)

        assertEquals(0.25f, VideoPlayerManager.clampPlaybackSpeed(0.1f), 0.001f)
        assertEquals(3.0f, VideoPlayerManager.clampPlaybackSpeed(5.0f), 0.001f)
        assertEquals(1.0f, VideoPlayerManager.clampPlaybackSpeed(Float.NaN), 0.001f)

        assertEquals(6, VideoResizeMode.entries.size)
        var mode = VideoResizeMode.FIT
        val visited = mutableListOf(mode)
        repeat(5) {
            mode = mode.next()
            visited.add(mode)
        }
        assertEquals(VideoResizeMode.entries.toList(), visited)
        assertEquals(VideoResizeMode.FIT, mode.next())
    }

    @Test
    fun pipAndControlsVisibility_resetsCleanlyOnExitAndNewPlayback() {
        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer()
        val manager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)

        manager.setControlsVisible(false)
        manager.setPipActive(true)
        assertFalse(manager.uiState.value.isControlsVisible)
        assertTrue(manager.uiState.value.isPipActive)

        // Exiting PiP and restoring controls
        manager.setPipActive(false)
        manager.setControlsVisible(true)
        assertFalse(manager.uiState.value.isPipActive)
        assertTrue(manager.uiState.value.isControlsVisible)

        // Starting new media must also clear stale PiP / hidden controls state
        manager.setControlsVisible(false)
        manager.setPipActive(true)
        manager.playMedia(Uri.parse("content://media/external/video/media/901"), "فيديو اختباري")
        assertFalse(manager.uiState.value.isPipActive)
        assertTrue(manager.uiState.value.isControlsVisible)

        // Stopping playback clears PiP and restores default controls visibility
        manager.setControlsVisible(false)
        manager.setPipActive(true)
        manager.stopPlayback()
        assertFalse(manager.uiState.value.isPipActive)
        assertTrue(manager.uiState.value.isControlsVisible)
    }

    @Test
    fun videoResizeMode_computeFittedVideoSize_neverOverflowsPortraitOrLandscape() {
        // Landscape phone (2400x1080) with 4:3 target must constrain height=1080 and width=1440 (never overflow height)
        val landscape43 = VideoResizeMode.computeFittedVideoSize(
            viewportWidthPx = 2400,
            viewportHeightPx = 1080,
            resizeMode = VideoResizeMode.RATIO_4_3
        )
        assertNotNull(landscape43)
        assertTrue(landscape43!!.first <= 2400)
        assertEquals(1080, landscape43.second)

        // Portrait phone (1080x2400) with 16:9 target must constrain width=1080 and height=607
        val portrait169 = VideoResizeMode.computeFittedVideoSize(
            viewportWidthPx = 1080,
            viewportHeightPx = 2400,
            resizeMode = VideoResizeMode.RATIO_16_9
        )
        assertNotNull(portrait169)
        assertEquals(1080, portrait169!!.first)
        assertTrue(portrait169.second in 600..615)

        // FIT/FILL/ZOOM delegate full viewport bounds cleanly
        assertNull(VideoResizeMode.computeFittedVideoSize(1080, 2400, VideoResizeMode.FIT))
        assertNull(VideoResizeMode.computeFittedVideoSize(2400, 1080, VideoResizeMode.FILL))
    }

    @Test
    fun audioOnlyMode_disablesVideoTrackPreservingPlaybackAndResetsOnStop() {
        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer()
        val manager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer).also { activePlayerManagers.add(it) }

        val uri = Uri.parse("content://media/external/video/media/audio_only_test")
        manager.playMedia(uri, "حفل موسيقي")
        assertFalse(manager.uiState.value.isAudioOnlyMode)
        assertFalse(fakePlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))

        val enabled = manager.toggleAudioOnlyMode()
        assertTrue(enabled)
        assertTrue(manager.uiState.value.isAudioOnlyMode)
        assertTrue(fakePlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))

        manager.stopPlayback()
        assertFalse(manager.uiState.value.isAudioOnlyMode)
        assertFalse(fakePlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
    }

    @Test
    fun volumeComboShortcutDetector_triggersOnceOnSimultaneousPress_andAllowsSingleKeyVolume() {
        var triggerCount = 0
        val detector = VolumeComboShortcutDetector { triggerCount++ }

        // 1. Single Volume Up press + release must pass through (false) and never trigger shortcut
        assertFalse(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0, isShortcutActive = true))
        assertFalse(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, 0, isShortcutActive = true))
        assertEquals(0, triggerCount)

        // 2. Single Volume Down press + release must pass through (false)
        assertFalse(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0, isShortcutActive = true))
        assertFalse(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, 0, isShortcutActive = true))
        assertEquals(0, triggerCount)

        // 3. Simultaneous press triggers exactly once per hold even with repeat events
        assertFalse(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0, isShortcutActive = true))
        assertTrue(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0, isShortcutActive = true))
        assertTrue(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 1, isShortcutActive = true))
        assertTrue(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 2, isShortcutActive = true))
        assertEquals(1, triggerCount)

        // Combo releases consumed cleanly, resetting state for next intentional combo
        assertTrue(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, 0, isShortcutActive = true))
        assertTrue(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, 0, isShortcutActive = true))

        // 4. Subsequent single volume press after reset still works normally
        assertFalse(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0, isShortcutActive = true))
        assertFalse(detector.onKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, 0, isShortcutActive = true))
        assertEquals(1, triggerCount)
    }

    @Test
    fun favoritesRepository_lazilyCreatesFavoritesPlaylistAndTogglesCleanly() = runBlocking {
        val dbHelper = VideoHatakDatabaseHelper(testApp())
        dbHelper.replaceAllPlaylistsAndItems(emptyList())
        val repo = PlaylistRepositoryImpl(testApp(), dbHelper)

        val video = VideoItem(
            id = 808L,
            contentUri = Uri.parse("content://media/external/video/media/808"),
            title = "مقطع مفضل",
            displayName = "fav_808.mp4",
            durationMs = 90_000L,
            sizeBytes = 5_000_000L,
            dateAddedSec = 1700000000L
        )

        // Empty DB stays empty before first favorite toggle
        assertTrue(dbHelper.getPlaylists().isEmpty())
        assertFalse(repo.isFavorite(video))

        // First toggle creates "المفضلة" and adds video
        assertTrue(repo.toggleFavorite(video))
        assertTrue(repo.isFavorite(video))
        val playlistsAfterAdd = dbHelper.getPlaylists()
        assertEquals(1, playlistsAfterAdd.size)
        assertEquals(PlaylistRepository.FAVORITES_PLAYLIST_TITLE, playlistsAfterAdd.first().title)

        // Second toggle removes video cleanly
        assertFalse(repo.toggleFavorite(video))
        assertFalse(repo.isFavorite(video))
        dbHelper.close()
    }

    @Test
    fun mediaNotificationArtworkLoader_masksVaultAndReturnsValidFallbackBitmap() = runBlocking {
        MediaNotificationArtworkLoader.clearCacheForTesting()
        val vaultBitmap = MediaNotificationArtworkLoader.loadOrFallbackArtwork(
            context = testApp(),
            uri = Uri.parse("vault://secret_video_1"),
            isVaultPrivate = true
        )
        assertNotNull(vaultBitmap)
        assertFalse(vaultBitmap.isRecycled)
        assertNull(MediaNotificationArtworkLoader.getCachedArtwork("vault://secret_video_1"))

        val programmaticFallback = MediaNotificationArtworkLoader.createProgrammaticFallbackBitmap(96)
        assertEquals(96, programmaticFallback.width)
        assertEquals(96, programmaticFallback.height)
    }

    @Test
    fun playerViewModel_syncVideoFromPlayerManager_recoversActiveVideoMetadata() = runBlocking {
        val scope = createScope()
        val dataStore = createDataStore(scope)
        val repo = PlaybackPreferencesRepositoryImpl(dataStore)
        val posManager = PlaybackPositionManager(repo, scope).also { activeManagers.add(it) }
        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer().apply {
            duration = 75_000L
            currentPosition = 12_000L
            isPlaying = true
        }
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer).also { activePlayerManagers.add(it) }
        val targetUri = Uri.parse("content://media/external/video/media/950")
        playerManager.playMedia(targetUri, "فيديو يعمل بالخلفية")

        // Create a fresh ViewModel representing Activity recreation while background playback continues
        val (_, restoredVm) = createManagedViewModel {
            PlayerViewModel(
                application = testApp(),
                playerManager = playerManager,
                playbackPreferences = repo,
                positionManager = posManager,
                subtitlePreferences = null,
                thumbnailManager = null
            )
        }
        assertNull(restoredVm.currentVideo)

        val recovered = restoredVm.syncVideoFromPlayerManager()
        assertNotNull(recovered)
        assertEquals(targetUri, recovered?.contentUri)
        assertEquals("فيديو يعمل بالخلفية", recovered?.safeTitle)
    }
}
