package com.videohatak.player

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import com.videohatak.player.feature.player.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Unit & integration verification tests for:
 * 1. All 8 approved themes and palette uniqueness + ThemeViewModel switching.
 * 2. All 5 mutually exclusive PlaybackMode presets & STOP_AFTER_CURRENT enforcement.
 * 3. All 3 ResumeMode presets (RESUME, START_FROM_BEGINNING, ASK_EVERY_TIME) with real DataStore.
 * 4. Temporary 2.0x speed hold without overwriting user's persisted playback speed.
 * 5. Permission state modeling (including permanent denial state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class NewFeaturesVerificationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val tempFiles = mutableListOf<File>()
    private val activeManagers = mutableListOf<PlaybackPositionManager>()
    private val activeScopes = mutableListOf<CoroutineScope>()

    private class FakeApp : Application()

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
    fun tearDown() = runBlocking {
        Dispatchers.resetMain()
        activeManagers.forEach { runCatching { it.shutdown(500L) } }
        activeManagers.clear()
        activeScopes.forEach { runCatching { it.cancel() } }
        activeScopes.clear()
        tempFiles.forEach { if (it.exists()) it.delete() }
        tempFiles.clear()
    }

    @Test
    fun allEightThemes_produceEightDistinctPalettes() {
        val themes = AppThemeId.entries
        assertEquals(8, themes.size)

        val palettes = themes.map { paletteForTheme(it) }
        // Every theme must have unique display name, background/accent signature, and seekbar glow colors
        assertEquals(8, themes.map { it.displayName }.toSet().size)
        assertEquals(8, themes.map { it.arabicTagline }.toSet().size)
        assertEquals(8, palettes.map { it.accent }.toSet().size)
        assertEquals(8, palettes.map { it.seekBarPlayedEnd }.toSet().size)

        // Only Royal Ivory is light-by-default; remaining 7 are dark luxury themes
        assertTrue(AppThemeId.ROYAL_IVORY.isLightTheme)
        assertEquals(7, AppThemeId.entries.count { !it.isLightTheme })
    }

    @Test
    fun themeViewModel_switchThemeUpdatesThemeAndLightDarkMode() = runTest(testDispatcher) {
        val repo = InMemoryThemePreferencesRepository()
        val vm = ThemeViewModel(repo)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAppTheme(AppThemeId.ROYAL_IVORY)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(AppThemeId.ROYAL_IVORY, repo.appThemeFlow.first())
        assertEquals(ThemeMode.LIGHT, repo.themeModeFlow.first())

        vm.setAppTheme(AppThemeId.RUBY_CINEMA)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(AppThemeId.RUBY_CINEMA, repo.appThemeFlow.first())
        assertEquals(ThemeMode.DARK, repo.themeModeFlow.first())
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
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)
        val vm = PlayerViewModel(
            application = FakeApp(),
            playerManager = playerManager,
            playbackPreferences = repo,
            positionManager = posManager,
            subtitlePreferences = null,
            thumbnailManager = null
        )

        // 1. START_FROM_BEGINNING always resets start to 0ms even when 45,000ms is saved
        repo.setResumeMode(ResumeMode.START_FROM_BEGINNING)
        vm.playVideo(video)
        testDispatcher.scheduler.advanceUntilIdle()
        fakePlayer.triggerPlaybackStateChanged(Player.STATE_READY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.pendingResumePrompt.value)
        assertEquals(0L, fakePlayer.currentPosition)

        // 2. ASK_EVERY_TIME opens prompt and leaves user in control
        repo.setResumeMode(ResumeMode.ASK_EVERY_TIME)
        fakePlayer.isPlaying = true
        vm.playVideo(video)
        testDispatcher.scheduler.advanceUntilIdle()
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
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)
        val vm = PlayerViewModel(
            application = FakeApp(),
            playerManager = playerManager,
            playbackPreferences = repo,
            positionManager = posManager,
            subtitlePreferences = null,
            thumbnailManager = null
        )

        vm.setPlaybackSpeed(1.25f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1.25f, vm.uiState.value.playbackSpeed, 0.001f)
        assertEquals(1.25f, repo.playbackSpeed.first(), 0.001f)

        // Hold for temporary 2.0x boost
        vm.setTemporaryPlaybackSpeed(2.0f)
        assertTrue(vm.uiState.value.isTemporarySpeedActive)
        assertEquals(2.0f, vm.uiState.value.playbackSpeed, 0.001f)
        // Persisted preference must remain untouched at 1.25x
        assertEquals(1.25f, repo.playbackSpeed.first(), 0.001f)

        // Release finger -> restores previous speed 1.25x
        vm.clearTemporaryPlaybackSpeed()
        assertFalse(vm.uiState.value.isTemporarySpeedActive)
        assertEquals(1.25f, vm.uiState.value.playbackSpeed, 0.001f)
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
        val vm = ThemeViewModel(repo)
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

        previewJob.cancel()
        themeJob.cancel()
        modeJob.cancel()
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
}
