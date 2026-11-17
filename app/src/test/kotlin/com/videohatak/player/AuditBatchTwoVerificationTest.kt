package com.videohatak.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.videohatak.player.core.backup.BackupManager
import com.videohatak.player.core.backup.BackupParseResult
import com.videohatak.player.core.backup.BackupPayload
import com.videohatak.player.core.backup.BackupSettingsData
import com.videohatak.player.core.backup.BackupSubtitleData
import com.videohatak.player.core.backup.BackupVaultSecurityData
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepository
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPositionManager
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.pip.PipHelper
import com.videohatak.player.core.playlist.db.BackupPlaylistStore
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.playlist.model.PlaylistRepeatMode
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import com.videohatak.player.core.subtitle.repository.LocalSubtitleStore
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesStore
import com.videohatak.player.core.subtitle.speech.StreamingPcmResampler
import com.videohatak.player.core.subtitle.speech.VideoAudioPcmDecoder
import com.videohatak.player.core.subtitle.speech.VoskResultCueSegmenter
import com.videohatak.player.core.subtitle.speech.VoskWordToken
import com.videohatak.player.core.subtitle.translation.AudioTrackExtractor
import com.videohatak.player.core.subtitle.translation.MlKitModelBridge
import com.videohatak.player.core.subtitle.translation.OfflineModelDownloadStatus
import com.videohatak.player.core.subtitle.translation.OfflineModelManager
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.subtitle.translation.TranslationProxyException
import com.videohatak.player.core.subtitle.translation.VoskModelCatalog
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseStore
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultRepository
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultSecurityPreferences
import com.videohatak.player.feature.player.PlayerViewModel
import com.videohatak.player.feature.vault.VaultViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuditBatchTwoVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()
    private val tempFiles = mutableListOf<File>()
    private val activeScopes = mutableListOf<CoroutineScope>()
    private val activeManagers = mutableListOf<PlaybackPositionManager>()
    private val activePlayerManagers = mutableListOf<VideoPlayerManager>()
    private val activeViewModelStores = mutableListOf<ViewModelStore>()

    private fun testApp(): Application = RuntimeEnvironment.getApplication()

    private fun createScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeScopes.add(scope)
        return scope
    }

    private fun createDataStore(scope: CoroutineScope, prefix: String = "batch2_"): DataStore<Preferences> {
        val file = File.createTempFile(prefix, ".preferences_pb")
        file.deleteOnExit()
        tempFiles.add(file)
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    private inline fun <reified T : ViewModel> createManagedViewModel(
        crossinline creator: () -> T
    ): T {
        val store = ViewModelStore().also { activeViewModelStores.add(it) }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
        }
        return ViewModelProvider(store, factory)[T::class.java]
    }

    private suspend fun drainAsync(timeoutMs: Long = 5_000L, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (condition()) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for async state")
            }
            delay(10L)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        try {
            activeViewModelStores.forEach { runCatching { it.clear() } }
            activeViewModelStores.clear()
            activePlayerManagers.forEach { runCatching { it.release() } }
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

    private class RecordingExoPlayer : InvocationHandler {
        var currentPosition: Long = 0L
        var duration: Long = 100_000L
        var bufferedPosition: Long = 0L
        var currentMediaItemIndex: Int = 0
        var playbackState: Int = Player.STATE_READY
        var isPlaying: Boolean = false
        var repeatMode: Int = Player.REPEAT_MODE_OFF
        var shuffleModeEnabled: Boolean = false
        var pauseAtEndOfMediaItems: Boolean = false
        var playWhenReady: Boolean = false
        var playbackSpeed: Float = 1.0f
        var shuffleOrderSetCount: Int = 0
        var lastShuffleOrderLength: Int = -1
        val callOrder = CopyOnWriteArrayList<String>()
        val mediaItems = mutableListOf<MediaItem>()
        val listeners = mutableListOf<Player.Listener>()

        val exoPlayer: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            this
        ) as ExoPlayer

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            val nonNullArgs = args ?: emptyArray()
            return when (method.name) {
                "addListener" -> {
                    listeners.add(nonNullArgs[0] as Player.Listener)
                    null
                }
                "removeListener" -> {
                    listeners.remove(nonNullArgs[0] as Player.Listener)
                    null
                }
                "getCurrentPosition" -> currentPosition
                "getDuration" -> duration
                "getBufferedPosition" -> bufferedPosition
                "getCurrentMediaItemIndex" -> currentMediaItemIndex
                "getPlaybackState" -> playbackState
                "isPlaying" -> isPlaying
                "play" -> {
                    isPlaying = true
                    null
                }
                "pause" -> {
                    isPlaying = false
                    null
                }
                "stop" -> {
                    isPlaying = false
                    null
                }
                "clearMediaItems" -> {
                    mediaItems.clear()
                    null
                }
                "setMediaItems" -> {
                    callOrder.add("setMediaItems")
                    @Suppress("UNCHECKED_CAST")
                    val items = nonNullArgs[0] as List<MediaItem>
                    mediaItems.clear()
                    mediaItems.addAll(items)
                    if (nonNullArgs.size >= 2) currentMediaItemIndex = nonNullArgs[1] as Int
                    if (nonNullArgs.size >= 3) currentPosition = nonNullArgs[2] as Long
                    null
                }
                "setMediaItem" -> {
                    callOrder.add("setMediaItem")
                    mediaItems.clear()
                    mediaItems.add(nonNullArgs[0] as MediaItem)
                    currentMediaItemIndex = 0
                    if (nonNullArgs.size >= 2) currentPosition = nonNullArgs[1] as Long
                    null
                }
                "setShuffleOrder" -> {
                    callOrder.add("setShuffleOrder")
                    val order = nonNullArgs[0] as ShuffleOrder
                    shuffleOrderSetCount++
                    lastShuffleOrderLength = order.length
                    null
                }
                "setShuffleModeEnabled" -> {
                    callOrder.add("setShuffleModeEnabled:${nonNullArgs[0]}")
                    shuffleModeEnabled = nonNullArgs[0] as Boolean
                    null
                }
                "getShuffleModeEnabled" -> shuffleModeEnabled
                "setRepeatMode" -> {
                    callOrder.add("setRepeatMode:${nonNullArgs[0]}")
                    repeatMode = nonNullArgs[0] as Int
                    null
                }
                "getRepeatMode" -> repeatMode
                "setPauseAtEndOfMediaItems" -> {
                    pauseAtEndOfMediaItems = nonNullArgs[0] as Boolean
                    null
                }
                "getPauseAtEndOfMediaItems" -> pauseAtEndOfMediaItems
                "setPlayWhenReady" -> {
                    playWhenReady = nonNullArgs[0] as Boolean
                    null
                }
                "getPlayWhenReady" -> playWhenReady
                "setPlaybackSpeed" -> {
                    playbackSpeed = nonNullArgs[0] as Float
                    null
                }
                "getPlaybackParameters" -> PlaybackParameters(playbackSpeed)
                "hasNextMediaItem" -> currentMediaItemIndex < mediaItems.size - 1
                "hasPreviousMediaItem" -> currentMediaItemIndex > 0
                "getMediaItemCount" -> mediaItems.size
                "getCurrentMediaItem" -> mediaItems.getOrNull(currentMediaItemIndex)
                "seekTo" -> {
                    currentPosition = if (nonNullArgs.size == 1) nonNullArgs[0] as Long else nonNullArgs[1] as Long
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === nonNullArgs.getOrNull(0)
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            }
        }
    }

    private fun sampleVideos(count: Int, prefix: String = "v"): List<VideoItem> = (1..count).map { idx ->
        VideoItem(
            id = 5000L + idx,
            contentUri = Uri.parse("content://media/external/video/media/${prefix}_$idx"),
            title = "Video $idx",
            displayName = "video_$idx.mp4",
            durationMs = 90_000L,
            sizeBytes = 10_000L,
            dateAddedSec = idx.toLong()
        )
    }

    // -------------------------------------------------------------------------
    // Scenarios 1, 2, 3, 4, 5: Shuffle ordering, SQLite playlists, 5-mode parity & STOP_AFTER_CURRENT
    // -------------------------------------------------------------------------

    @Test
    fun scenario1_folderContextPlayVideoInContext_setsMediaItemsBeforeShuffleOrder() = runBlocking {
        val scope = createScope()
        val realRepo = PlaybackPreferencesRepositoryImpl(createDataStore(scope, "sc1_"))
        val posManager = PlaybackPositionManager(realRepo, scope).also { activeManagers.add(it) }
        val fakePlayer = RecordingExoPlayer()
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer).also { activePlayerManagers.add(it) }

        val vm = createManagedViewModel {
            PlayerViewModel(testApp(), playerManager, realRepo, posManager, null, null)
        }

        vm.setPlaybackMode(PlaybackMode.SHUFFLE_ALL)
        drainAsync { playerManager.uiState.value.playbackMode == PlaybackMode.SHUFFLE_ALL }
        fakePlayer.callOrder.clear()

        val videos = sampleVideos(4, "folder")
        vm.playVideoInContext(videos[1], videos)
        testDispatcher.scheduler.advanceUntilIdle()

        val setItemsIdx = fakePlayer.callOrder.indexOf("setMediaItems")
        val setShuffleOrderIdx = fakePlayer.callOrder.indexOf("setShuffleOrder")
        assertTrue("setMediaItems must be called", setItemsIdx >= 0)
        assertTrue("setShuffleOrder must be called after setMediaItems", setShuffleOrderIdx > setItemsIdx)
        assertTrue(fakePlayer.shuffleModeEnabled)
        assertEquals(4, fakePlayer.lastShuffleOrderLength)
        assertEquals(videos[1].id, vm.currentVideo?.id)
    }

    @Test
    fun scenario2_playPlaylistWithShuffleTrueFromRepeatOne_switchesToShuffleAllAndAllRepeat() = runBlocking {
        val scope = createScope()
        val realRepo = PlaybackPreferencesRepositoryImpl(createDataStore(scope, "sc2_"))
        val posManager = PlaybackPositionManager(realRepo, scope).also { activeManagers.add(it) }
        val fakePlayer = RecordingExoPlayer()
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer).also { activePlayerManagers.add(it) }

        val playlistRepo = PlaylistRepositoryImpl(testApp())
        val playlistId = playlistRepo.createPlaylist("اختبار الخلط ${System.nanoTime()}")
        val videos = (1..3).map { idx ->
            val file = tempFolder.newFile("pl_shuffle_$idx.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            VideoItem(
                id = 5100L + idx,
                contentUri = Uri.fromFile(file),
                title = "Playlist Video $idx",
                displayName = "pl_shuffle_$idx.mp4",
                durationMs = 90_000L,
                sizeBytes = 3L,
                dateAddedSec = idx.toLong()
            )
        }
        videos.forEach { playlistRepo.addVideoToPlaylists(it, listOf(playlistId)) }

        val vm = createManagedViewModel {
            PlayerViewModel(testApp(), playerManager, realRepo, posManager, null, null)
        }

        vm.setPlaybackMode(PlaybackMode.REPEAT_ONE)
        drainAsync {
            vm.playbackMode.first() == PlaybackMode.REPEAT_ONE &&
                playerManager.uiState.value.playbackMode == PlaybackMode.REPEAT_ONE &&
                realRepo.playbackMode.first() == PlaybackMode.REPEAT_ONE
        }
        fakePlayer.callOrder.clear()

        var started: VideoItem? = null
        vm.playPlaylist(playlistId = playlistId, startVideoId = videos[0].id, shuffle = true) { started = it }
        drainAsync { fakePlayer.mediaItems.size == 3 && started != null }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PlaybackMode.SHUFFLE_ALL, vm.uiState.value.playbackMode)
        assertEquals(Player.REPEAT_MODE_ALL, fakePlayer.repeatMode)
        assertTrue(fakePlayer.shuffleModeEnabled)
        val setItemsIdx = fakePlayer.callOrder.indexOf("setMediaItems")
        val setShuffleOrderIdx = fakePlayer.callOrder.indexOf("setShuffleOrder")
        assertTrue(setShuffleOrderIdx > setItemsIdx)
    }

    @Test
    fun scenario3_and_4_allFivePlaybackModesParityAndMidPlaybackShuffleOrderUpdate() = runBlocking {
        val scope = createScope()
        val realRepo = PlaybackPreferencesRepositoryImpl(createDataStore(scope, "sc34_"))
        val posManager = PlaybackPositionManager(realRepo, scope).also { activeManagers.add(it) }
        val fakePlayer = RecordingExoPlayer()
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer).also { activePlayerManagers.add(it) }

        val vm = createManagedViewModel {
            PlayerViewModel(testApp(), playerManager, realRepo, posManager, null, null)
        }
        val videos = sampleVideos(3, "parity")
        vm.playVideoInContext(videos[0], videos)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(5, PlaybackMode.entries.size)
        val expectedExoModes = mapOf(
            PlaybackMode.STOP_AFTER_CURRENT to Triple(Player.REPEAT_MODE_OFF, false, true),
            PlaybackMode.PLAY_ALL_IN_ORDER to Triple(Player.REPEAT_MODE_OFF, false, false),
            PlaybackMode.REPEAT_ONE to Triple(Player.REPEAT_MODE_ONE, false, false),
            PlaybackMode.REPEAT_PLAYLIST to Triple(Player.REPEAT_MODE_ALL, false, false),
            PlaybackMode.SHUFFLE_ALL to Triple(Player.REPEAT_MODE_ALL, true, false)
        )

        val prevShuffleCount = fakePlayer.shuffleOrderSetCount
        for (mode in PlaybackMode.entries) {
            vm.setPlaybackMode(mode)
            drainAsync {
                vm.playbackMode.first() == mode &&
                    vm.uiState.value.playbackMode == mode &&
                    realRepo.playbackMode.first() == mode
            }
            val (expRepeat, expShuffle, expPauseAtEnd) = expectedExoModes.getValue(mode)
            assertEquals("Repeat mismatch for $mode", expRepeat, fakePlayer.repeatMode)
            assertEquals("Shuffle mismatch for $mode", expShuffle, fakePlayer.shuffleModeEnabled)
            assertEquals("PauseAtEnd mismatch for $mode", expPauseAtEnd, fakePlayer.pauseAtEndOfMediaItems)
        }
        assertTrue("Switching to SHUFFLE_ALL mid-playback must install ShuffleOrder", fakePlayer.shuffleOrderSetCount > prevShuffleCount)
    }

    @Test
    fun scenario5_stopAfterCurrentDefensiveTransitionSynchronizesUiStateAndCurrentVideoFlow() = runBlocking {
        val scope = createScope()
        val realRepo = PlaybackPreferencesRepositoryImpl(createDataStore(scope, "sc5_"))
        val posManager = PlaybackPositionManager(realRepo, scope).also { activeManagers.add(it) }
        val fakePlayer = RecordingExoPlayer().apply { isPlaying = true }
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer).also { activePlayerManagers.add(it) }

        val vm = createManagedViewModel {
            PlayerViewModel(testApp(), playerManager, realRepo, posManager, null, null)
        }
        val videos = sampleVideos(2, "stop_after")
        vm.setPlaybackMode(PlaybackMode.STOP_AFTER_CURRENT)
        drainAsync { vm.uiState.value.playbackMode == PlaybackMode.STOP_AFTER_CURRENT }

        vm.playVideoInContext(videos[0], videos)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(videos[0].id, vm.currentVideoFlow.value?.id)

        fakePlayer.currentMediaItemIndex = 1
        val item1Media = MediaItem.Builder()
            .setMediaId(videos[1].id.toString())
            .setUri(videos[1].contentUri)
            .build()
        playerManager.playerListener.onMediaItemTransition(item1Media, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Playback must remain paused under STOP_AFTER_CURRENT", fakePlayer.isPlaying)
        assertFalse(vm.uiState.value.isPlaying)
        assertEquals(videos[1].contentUri, vm.uiState.value.mediaUri)
        assertEquals(videos[1].id, vm.currentVideoFlow.value?.id)
    }

    // -------------------------------------------------------------------------
    // Scenario 6: PiP aspect ratio preservation on action updates
    // -------------------------------------------------------------------------

    @Test
    fun scenario6_pipUpdateParams_preservesClampedAspectRatioAndActions() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val helper = PipHelper(activity)

        val portraitParams = helper.buildUpdatePipParams(
            activity = activity,
            isPlaying = false,
            seekIntervalSeconds = 15,
            videoWidth = 1080,
            videoHeight = 1920
        )
        assertEquals(9f / 16f, portraitParams.aspectRatio!!.toFloat(), 0.01f)
        assertEquals(3, portraitParams.actions.size)

        val ultraWideParams = helper.buildUpdatePipParams(
            activity = activity,
            isPlaying = true,
            seekIntervalSeconds = 10,
            videoWidth = 3840,
            videoHeight = 1000
        )
        assertEquals(2.39f, ultraWideParams.aspectRatio!!.toFloat(), 0.01f)
    }

    // -------------------------------------------------------------------------
    // Scenarios 7 & 8: Vault concurrency, delete hook, crash orphan preservation, & rename error surfacing
    // -------------------------------------------------------------------------

    private class FakeVaultDbStore : VaultDatabaseStore {
        private val rows = linkedMapOf<Long, VaultVideo>()
        private var nextId = 1L
        var failNextUpdate = false

        @Synchronized
        override fun insertVideo(video: VaultVideo): Long {
            val id = nextId++
            rows[id] = video.copy(id = id)
            return id
        }

        @Synchronized
        override fun updateEncryptedMetadata(
            id: Long,
            maskedDisplayName: String,
            internalPath: String,
            maskedMimeType: String,
            encryptedMetaBase64: String
        ): Int {
            if (failNextUpdate) {
                failNextUpdate = false
                return 0
            }
            val existing = rows[id] ?: return 0
            rows[id] = existing.copy(
                displayName = maskedDisplayName,
                relativeEncryptedPath = internalPath,
                mimeType = maskedMimeType,
                encryptedMetaBase64 = encryptedMetaBase64
            )
            return 1
        }

        @Synchronized
        override fun getAllVideos(): List<VaultVideo> = rows.values.toList()

        @Synchronized
        override fun getVideoById(id: Long): VaultVideo? = rows[id]

        @Synchronized
        override fun deleteVideo(id: Long): Int = if (rows.remove(id) != null) 1 else 0
    }

    @Test
    fun scenario7_vaultRepository_serializesConcurrentImportsPreventsDuplicatesAndCleansDelete() = runBlocking {
        val context: Context = testApp()
        val vaultDir = File(context.filesDir, "vault_encrypted")
        vaultDir.deleteRecursively()

        val db = FakeVaultDbStore()
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(context, crypto, db)
        repo.setUnlocked(true)

        val srcFile = tempFolder.newFile("sample_clip.mp4")
        srcFile.writeBytes(ByteArray(4096) { (it % 127).toByte() })
        val srcUri = Uri.fromFile(srcFile)

        val imports = listOf(
            async(Dispatchers.IO) { repo.importVideo(srcUri, "مشهد تجريبي") },
            async(Dispatchers.IO) { repo.importVideo(srcUri, "مشهد تجريبي") },
            async(Dispatchers.IO) { repo.refresh() }
        )
        val results = imports.map { it.await() }
        val nonNullImports = results.filterIsInstance<VaultVideo>()
        assertEquals("Duplicate concurrent imports must produce exactly 1 Vault record", 1, nonNullImports.size)
        assertEquals(1, db.getAllVideos().size)

        val inserted = nonNullImports.first()
        val encFile = repo.resolveEncryptedFile(inserted)
        assertTrue(encFile.exists())
        assertTrue(repo.deleteVaultVideo(inserted.id))
        assertFalse(encFile.exists())
        assertEquals(0, db.getAllVideos().size)
    }

    @Test
    fun scenario8_vaultDeleteHook_reconciliationPreservesOrphans_andRenameErrorSurfacesInStateFlow() = runBlocking {
        val context: Context = testApp()
        val vaultDir = File(context.filesDir, "vault_encrypted")
        vaultDir.deleteRecursively()
        vaultDir.mkdirs()

        val db = FakeVaultDbStore()
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(context, crypto, db)
        repo.setUnlocked(true)

        // Seed active row whose file was temporarily moved to .pending_delete during interrupted delete
        val activeEncName = "vault_active_1.enc"
        val pendingRestoreFile = File(vaultDir, "$activeEncName.1700000.pending_delete").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        // Seed unreferenced orphan .enc file & stale .tmp file
        val orphanEncFile = File(vaultDir, "vault_unreferenced_orphan.enc").apply {
            writeBytes(byteArrayOf(9, 8, 7))
        }
        val staleTmpFile = File(vaultDir, "aborted_import.enc.stage").apply {
            writeBytes(byteArrayOf(4, 5))
        }
        val encMeta = crypto.encryptMetadata("""{"title":"فيديو محفوظ","file":"$activeEncName","mime":"video/mp4"}""")
        val rowId = db.insertVideo(
            VaultVideo(
                id = 0L,
                displayName = "مقطع خاص مشفر",
                originalSizeBytes = 3L,
                encryptedSizeBytes = 3L,
                durationMs = 10_000L,
                mimeType = "application/octet-stream",
                width = 1920,
                height = 1080,
                relativeEncryptedPath = activeEncName,
                createdAtEpochMs = 100L,
                ivBase64 = "aXY=",
                encryptedMetaBase64 = encMeta
            )
        )

        repo.refresh()
        assertTrue("Active file should be restored from .pending_delete", File(vaultDir, activeEncName).exists())
        assertFalse(pendingRestoreFile.exists())
        assertFalse("Stale .stage file should be deleted", staleTmpFile.exists())
        assertTrue("Unreferenced .enc orphan must NEVER be deleted blindly", orphanEncFile.exists())
        assertEquals(1, repo.lastPreservedOrphanCount)

        // Test VaultViewModel beforeDeleteVideo hook + rename error surfacing cleanly without ViewModel collector lag
        val hookedIds = mutableListOf<Long>()
        val scope = createScope()
        val secPrefs = VaultSecurityPreferences(createDataStore(scope, "sc8_vs_"))
        val vm = createManagedViewModel {
            VaultViewModel(
                repository = repo,
                biometricHelper = VaultBiometricHelper(testApp()),
                securityPreferences = secPrefs,
                beforeDeleteVideo = { id -> hookedIds.add(id) }
            )
        }

        // Rename blank title or failing DB update must immediately surface error in vm.vaultError
        db.failNextUpdate = true
        vm.renameVideo(rowId, "اسم جديد")
        drainAsync { vm.vaultError.value != null }
        assertTrue(vm.vaultError.value!!.contains("فشل"))
        vm.clearError()
        drainAsync { vm.vaultError.value == null }

        vm.deleteVideo(rowId)
        drainAsync { hookedIds.contains(rowId) && db.getVideoById(rowId) == null }
        assertEquals(listOf(rowId), hookedIds)
    }

    // -------------------------------------------------------------------------
    // Scenarios 9 & 10: Backup localSubtitles full replace/rollback + JSON float lexeme preservation
    // -------------------------------------------------------------------------

    private class InMemoryThemeRepo(initialTheme: AppThemeId = AppThemeId.OBSIDIAN_ELITE) : ThemePreferencesRepository {
        val theme = MutableStateFlow(initialTheme)
        val mode = MutableStateFlow(ThemeMode.DARK)
        override val appThemeFlow: Flow<AppThemeId> = theme
        override val themeModeFlow: Flow<ThemeMode> = mode
        override suspend fun setAppTheme(theme: AppThemeId) { this.theme.value = theme }
        override suspend fun setThemeMode(mode: ThemeMode) { this.mode.value = mode }
    }

    private class InMemorySubtitlePrefStore : SubtitlePreferencesStore {
        val primaryFlow = MutableStateFlow(SubtitleStyleConfig())
        val secondaryFlow = MutableStateFlow(SubtitleStyleConfig())
        override val primaryStyle: Flow<SubtitleStyleConfig> = primaryFlow
        override val secondaryStyle: Flow<SubtitleStyleConfig> = secondaryFlow
        override suspend fun updatePrimaryFontSize(sizeSp: Float) {
            primaryFlow.value = primaryFlow.value.copy(fontSizeSp = sizeSp)
        }
        override suspend fun updateSecondaryFontSize(sizeSp: Float) {
            secondaryFlow.value = secondaryFlow.value.copy(fontSizeSp = sizeSp)
        }
    }

    private class InMemoryPlaylistStore : BackupPlaylistStore {
        private val items = mutableListOf<Pair<Playlist, List<PlaylistItem>>>()
        override fun getPlaylists(): List<Playlist> = items.map { it.first }
        override fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem> =
            items.firstOrNull { it.first.id == playlistId }?.second.orEmpty()
        override fun replaceAllPlaylistsAndItems(entries: List<Pair<Playlist, List<PlaylistItem>>>) {
            items.clear()
            entries.forEachIndexed { idx, pair -> items.add(pair.first.copy(id = idx + 1L) to pair.second) }
        }
    }

    @Test
    fun scenario9_backupLocalSubtitles_fullReplaceAndMidFailureRollback() = runBlocking {
        val scope = createScope()
        val localSubDir = tempFolder.newFolder("bkp_local_subs")
        val localSubStore = LocalSubtitleStore(localSubDir)
        val oldVideoUri = "content://media/external/video/media/old_9"
        localSubStore.saveTrackForVideo(
            videoUri = oldVideoUri,
            trackName = "الترجمة القديمة",
            language = "ar",
            cues = com.videohatak.player.core.subtitle.parser.SubtitleParser.parse(
                "1\n00:00:01,000 --> 00:00:03,000\nمرحبا\n",
                SubtitleFormat.SRT
            ),
            format = SubtitleFormat.SRT
        )
        assertEquals(1, localSubStore.loadTracksForVideo(oldVideoUri).size)

        val playbackRepo = PlaybackPreferencesRepositoryImpl(createDataStore(scope, "sc9_pb_"))
        val secPrefs = VaultSecurityPreferences(createDataStore(scope, "sc9_vs_"))
        val manager = BackupManager.createForTesting(
            themeRepository = InMemoryThemeRepo(),
            playbackRepository = playbackRepo,
            subtitleRepository = InMemorySubtitlePrefStore(),
            vaultSecurityStore = secPrefs,
            playlistStore = InMemoryPlaylistStore(),
            subtitleStore = localSubStore
        )

        val emptySubsPayload = BackupPayload(
            createdAtEpochMs = 1_000L,
            settings = BackupSettingsData(
                selectedTheme = AppThemeId.ROYAL_IVORY,
                themeMode = ThemeMode.DARK,
                playbackSpeed = 1.0f,
                resizeMode = 0,
                playbackMode = PlaybackMode.PLAY_ALL_IN_ORDER,
                resumeMode = ResumeMode.RESUME
            ),
            subtitles = BackupSubtitleData(18f, 15f),
            vaultSecurity = BackupVaultSecurityData(VaultAutoLockTimeout.IMMEDIATE),
            playlists = emptyList(),
            localSubtitles = emptyList()
        )

        // Simulate failure inside subtitle replacement after wiping old tracks -> must roll back!
        localSubStore.beforeCommitWriteHook = { throw IllegalStateException("Simulated disk write failure") }
        val failedRestore = runCatching { manager.applyPayload(emptySubsPayload) }
        assertTrue(failedRestore.isFailure)
        assertEquals(
            "Old subtitle tracks must be restored on mid-apply failure",
            1,
            localSubStore.loadTracksForVideo(oldVideoUri).size
        )

        // Clean apply with empty localSubtitles must replace (clear stale tracks) completely
        localSubStore.beforeCommitWriteHook = null
        manager.applyPayload(emptySubsPayload)
        assertTrue(
            "Empty localSubtitles in valid backup must wipe prior tracks",
            localSubStore.loadTracksForVideo(oldVideoUri).isEmpty()
        )
    }

    @Test
    fun scenario10_backupJsonChecksum_preservesFloatLexemesWhileDetectingTampering() {
        val samplePayload = BackupPayload(
            createdAtEpochMs = 1700000000000L,
            settings = BackupSettingsData(
                selectedTheme = AppThemeId.OBSIDIAN_ELITE,
                themeMode = ThemeMode.DARK,
                playbackSpeed = 1.0f,
                resizeMode = 0,
                playbackMode = PlaybackMode.PLAY_ALL_IN_ORDER,
                resumeMode = ResumeMode.RESUME,
                seekIntervalSeconds = 10
            ),
            subtitles = BackupSubtitleData(primaryFontSizeSp = 18f, secondaryFontSizeSp = 15f),
            vaultSecurity = BackupVaultSecurityData(VaultAutoLockTimeout.IMMEDIATE),
            playlists = emptyList()
        )
        // Build canonical body manually with "1.00" / "18.00" numeric formatting
        val customBody = """{"settings":{"selectedTheme":"OBSIDIAN_ELITE","themeMode":"DARK","playbackSpeed":1.00,"resizeMode":0,"playbackMode":"PLAY_ALL_IN_ORDER","resumeMode":"RESUME","seekIntervalSeconds":10},"subtitles":{"primaryFontSizeSp":18.00,"secondaryFontSizeSp":15.0},"vaultSecurity":{"autoLockTimeout":"IMMEDIATE"},"playlists":[],"resumePositions":{}}"""
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(customBody.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val customJson = """{"schemaVersion":3,"createdAtEpochMs":1700000000000,"sha256Checksum":"$checksum","body":$customBody}"""
        val parsed = BackupManager.parseAndValidateBackupJson(customJson)
        assertTrue("1.00 / 18.00 formatting must validate identically", parsed is BackupParseResult.Success)
        assertEquals(1.0f, (parsed as BackupParseResult.Success).payload.settings.playbackSpeed, 0.0001f)

        // Tampering numeric value with stale checksum must STILL fail
        val tampered = customJson.replace("\"playbackSpeed\":1.00", "\"playbackSpeed\":2.00")
        assertTrue(BackupManager.parseAndValidateBackupJson(tampered) is BackupParseResult.Error)

        // Round-trip sanity check with serializer
        val serialized = BackupManager.serializeBackupJson(samplePayload)
        assertTrue(BackupManager.parseAndValidateBackupJson(serialized) is BackupParseResult.Success)
    }

    // -------------------------------------------------------------------------
    // Scenarios 11 & 12: Vosk structure hardening, honest SHA disclosure & bounded cue timing
    // -------------------------------------------------------------------------

    @Test
    fun scenario11_voskStructureRejectsIncompleteGraphAndDisclosesMissingSha256() {
        // Incomplete graph: Gr.fst exists without words.txt/phones
        val incompleteDir = tempFolder.newFolder("vosk_incomplete").apply {
            File(this, "am").mkdirs()
            File(this, "am/final.mdl").writeBytes(ByteArray(128_000) { 1 })
            File(this, "conf").mkdirs()
            File(this, "conf/model.conf").writeText("--sample-frequency=16000")
            File(this, "graph").mkdirs()
            File(this, "graph/Gr.fst").writeBytes(byteArrayOf(1, 2, 3))
        }
        assertFalse(OfflineModelManager.hasValidVoskStructure(incompleteDir))

        // Invalid binary conf file
        File(incompleteDir, "graph/words.txt").writeText("<eps> 0\nhello 1\n")
        File(incompleteDir, "conf/model.conf").writeBytes(byteArrayOf(0, 1, 2, 3))
        assertFalse(OfflineModelManager.hasValidVoskStructure(incompleteDir))

        // Valid structure passes
        File(incompleteDir, "conf/model.conf").writeText("--sample-frequency=16000")
        assertTrue(OfflineModelManager.hasValidVoskStructure(incompleteDir))

        // Tiny final.mdl when production minExpectedBytes > 0L is rejected even if other dir bytes exist
        File(incompleteDir, "am/final.mdl").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(OfflineModelManager.hasValidVoskStructure(incompleteDir, minExpectedBytes = 1024L))

        // Honest checksum disclosure check
        VoskModelCatalog.officialModels.values.forEach { spec ->
            assertNull(spec.expectedSha256)
            assertFalse(spec.hasCryptographicChecksum)
            assertTrue(spec.checksumStatusArabic.contains("غير متوفرة"))
        }
    }

    @Test
    fun scenario12_voskCueSegmenter_clampsBeyondDurationAndMergesTightOverlapsWithout1msFlickers() {
        val words = listOf(
            VoskWordToken("hello", 500L, 620L),
            VoskWordToken("again", 1_100L, 1_220L),
            VoskWordToken("fast1", 2_000L, 2_080L),
            // Gap 460ms forces rawGroup split between fast1 & fast2, yet start difference is only 150ms? Let's test 100ms split with forced boundary
            VoskWordToken("near_end", 4_600L, 5_500L),
            VoskWordToken("beyond", 5_200L, 6_000L)
        )
        val cues = VoskResultCueSegmenter.segmentWordsIntoCues(
            words = words,
            language = SubtitleTranslationLanguage.ENGLISH,
            videoDurationMs = 5_000L
        )
        assertTrue(cues.isNotEmpty())
        cues.forEachIndexed { idx, cue ->
            assertTrue("Cue $idx must end within videoDurationMs (5000)", cue.endTimeMs <= 5_000L)
            assertTrue("Cue $idx must have positive duration", cue.endTimeMs > cue.startTimeMs)
            assertFalse("Words starting after videoDurationMs must be dropped", cue.text.contains("beyond"))
            if (idx + 1 < cues.size) {
                assertTrue(cue.endTimeMs <= cues[idx + 1].startTimeMs)
            }
        }

        // Ultra-tight cues (only 100ms apart in raw groups): must merge or avoid 1ms flickers
        val longCue1 = VoskWordToken("A".repeat(75), 1_000L, 1_050L)
        val longCue2 = VoskWordToken("B".repeat(20), 1_120L, 1_300L)
        val tightMerged = VoskResultCueSegmenter.segmentWordsIntoCues(
            words = listOf(longCue1, longCue2),
            language = SubtitleTranslationLanguage.ENGLISH,
            videoDurationMs = 10_000L
        )
        assertTrue(tightMerged.all { (it.endTimeMs - it.startTimeMs) >= 250L })
    }

    // -------------------------------------------------------------------------
    // Scenario 13: Resampler low-pass anti-aliasing + chunk boundary continuity + AudioTrackExtractor
    // -------------------------------------------------------------------------

    @Test
    fun scenario13_resamplerAntiAliasingChunkContinuityAndStrictAudioTrackSelection() {
        val resampler = StreamingPcmResampler()

        // Compare 1 kHz in-band tone vs 18 kHz out-of-band tone @ 48 kHz -> 16 kHz
        fun measureResampledRms(freqHz: Double): Double {
            val r = StreamingPcmResampler()
            val input = ShortArray(48_000) { i ->
                (16_000.0 * sin(2.0 * PI * freqHz * i / 48_000.0)).roundToInt().toShort()
            }
            val out = r.resampleTo16k(input, 48_000)
            // Skip first 160 samples (10ms) initial filter settling transient
            val tail = out.drop(160)
            val meanSq = tail.sumOf { it.toDouble() * it.toDouble() } / tail.size.coerceAtLeast(1)
            return sqrt(meanSq)
        }

        val passbandRms = measureResampledRms(1_000.0)
        val stopbandRms = measureResampledRms(18_000.0)
        assertTrue("1 kHz passband RMS ($passbandRms) should stay strong", passbandRms > 8_000.0)
        assertTrue("18 kHz stopband RMS ($stopbandRms) must be strongly attenuated vs 1 kHz ($passbandRms)", stopbandRms < passbandRms * 0.10)

        // Chunk boundary continuity check @ 44_100 Hz across awkward 997-sample chunk boundary
        val continuous44k = ShortArray(4_410) { i ->
            (12_000.0 * sin(2.0 * PI * 440.0 * i / 44_100.0)).roundToInt().toShort()
        }
        val singleShot = StreamingPcmResampler().resampleTo16k(continuous44k, 44_100)
        val chunkedResampler = StreamingPcmResampler()
        val chunkedOut = ArrayList<Short>(singleShot.size + 4)
        var offset = 0
        while (offset < continuous44k.size) {
            val end = minOf(offset + 997, continuous44k.size)
            chunkedOut.addAll(chunkedResampler.resampleTo16k(continuous44k.copyOfRange(offset, end), 44_100).toList())
            offset = end
        }
        assertEquals(singleShot.size, chunkedOut.size)
        for (i in singleShot.indices) {
            assertEquals("Chunked output must match single-shot at index $i", singleShot[i], chunkedOut[i])
        }

        // Strict selectedTrackIndex validation in AudioTrackExtractor & stall guard constant
        val mimes = listOf("video/avc", "audio/mp4a-latm", "audio/ac3")
        assertEquals(1, AudioTrackExtractor.resolveAudioTrackIndex(mimes, null))
        assertEquals(2, AudioTrackExtractor.resolveAudioTrackIndex(mimes, 2))
        assertThrows(TranslationProxyException::class.java) {
            AudioTrackExtractor.resolveAudioTrackIndex(mimes, 0) // video track rejected
        }
        assertThrows(TranslationProxyException::class.java) {
            AudioTrackExtractor.resolveAudioTrackIndex(mimes, 99) // out of bounds rejected
        }
        assertEquals(500, VideoAudioPcmDecoder.MAX_CONSECUTIVE_IDLE_CYCLES)
    }

    // -------------------------------------------------------------------------
    // Scenario 14: ML Kit per-language concurrency, cancellation + background completion, & IPC error surfacing
    // -------------------------------------------------------------------------

    @Test
    fun scenario14_mlKitConcurrentDownloadsCancelObserverAndIpcErrorSurfacing() = runBlocking {
        val rootDir = tempFolder.newFolder("mlkit_sc14")
        val arStarted = CompletableDeferred<Unit>()
        val arGate = CompletableDeferred<Unit>()
        val frDone = CompletableDeferred<Unit>()
        val downloaded = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        var throwQueryError = false
        val arCalls = AtomicInteger(0)

        val bridge = object : MlKitModelBridge {
            override suspend fun getDownloadedLanguageCodes(): Set<String> {
                if (throwQueryError) throw IllegalStateException("Binder IPC failed")
                return downloaded.toSet()
            }

            override suspend fun downloadLanguageModel(mlKitCode: String) {
                if (mlKitCode == "ar") {
                    arCalls.incrementAndGet()
                    arStarted.complete(Unit)
                    arGate.await()
                    downloaded.add("ar")
                } else {
                    downloaded.add(mlKitCode)
                    frDone.complete(Unit)
                }
            }

            override suspend fun deleteLanguageModel(mlKitCode: String) {
                downloaded.remove(mlKitCode)
            }
        }

        val manager = OfflineModelManager(testRootDir = rootDir, mlKitBridge = bridge)

        // Start Arabic (blocked on arGate), then download French concurrently -> French must finish immediately!
        val arJob = manager.startTranslationModelDownload(SubtitleTranslationLanguage.ARABIC)
        arStarted.await()
        val frResult = manager.downloadTranslationModelBlocking(SubtitleTranslationLanguage.FRENCH)
        assertTrue("French download must not be blocked behind Arabic download", frResult.success)
        frDone.await()
        assertEquals(
            OfflineModelDownloadStatus.READY,
            manager.languageStates.value[SubtitleTranslationLanguage.FRENCH]?.translationStatus
        )

        // Cancel monitoring on Arabic while Play Services underlying operation continues
        manager.cancelTranslationModelDownload(SubtitleTranslationLanguage.ARABIC)
        drainAsync { !arJob.isActive && manager.languageStates.value[SubtitleTranslationLanguage.ARABIC]?.translationStatus != OfflineModelDownloadStatus.DOWNLOADING }
        val arMidState = manager.languageStates.value[SubtitleTranslationLanguage.ARABIC]!!
        assertEquals(OfflineModelDownloadStatus.NOT_DOWNLOADED, arMidState.translationStatus)
        assertNotNull("Honest status note must explain background Play Services continuation", arMidState.translationStatusNote)

        // Now Play Services finishes Arabic download -> observer updates status to READY automatically without duplicate call
        arGate.complete(Unit)
        drainAsync { manager.languageStates.value[SubtitleTranslationLanguage.ARABIC]?.translationStatus == OfflineModelDownloadStatus.READY }
        assertEquals(1, arCalls.get())
        assertNull(manager.languageStates.value[SubtitleTranslationLanguage.ARABIC]?.translationStatusNote)

        // IPC query error must surface ERROR status instead of falsely claiming NOT_DOWNLOADED
        throwQueryError = true
        manager.refreshAllStates()
        val germanState = manager.languageStates.value[SubtitleTranslationLanguage.GERMAN]!!
        assertEquals(OfflineModelDownloadStatus.ERROR, germanState.translationStatus)
        assertNotNull(germanState.translationErrorMessage)
    }
}
