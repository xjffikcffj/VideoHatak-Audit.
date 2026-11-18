package com.videohatak.player

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.videohatak.player.core.data.repository.MediaExistenceResult
import com.videohatak.player.core.data.repository.PendingVideoFileOp
import com.videohatak.player.core.data.repository.VideoFileOperationResult
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.model.VideoSortOrder
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultImportOutcome
import com.videohatak.player.core.vault.repository.VaultRepository
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.core.vault.security.PinChangeResult
import com.videohatak.player.core.vault.security.PinVerifyResult
import com.videohatak.player.core.vault.security.VaultPinHasher
import com.videohatak.player.core.vault.security.VaultSecurityPreferences
import com.videohatak.player.feature.library.DataStoreLibraryScrollStore
import com.videohatak.player.feature.library.LibraryScrollPosition
import com.videohatak.player.feature.library.LibraryTab
import com.videohatak.player.feature.library.LibraryUiEvent
import com.videohatak.player.feature.library.LibraryViewModel
import com.videohatak.player.feature.playlist.PlaylistUiEvent
import com.videohatak.player.feature.playlist.PlaylistViewModel
import com.videohatak.player.feature.vault.VaultUiEvent
import com.videohatak.player.feature.vault.VaultViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserFixesVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()
    private val createdScopes = mutableListOf<CoroutineScope>()

    private fun testApp(): Application = RuntimeEnvironment.getApplication()

    private fun createDataStore(name: String): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        createdScopes.add(scope)
        val file = File(tempFolder.newFolder(), "$name.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        createdScopes.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun navigationTabs_hideFavoritesTab_whilePreservingFavoritesInPlaylists() = runBlocking {
        assertFalse(LibraryTab.bottomBarTabs.contains(LibraryTab.FAVORITES))
        assertEquals(
            listOf(LibraryTab.VIDEOS, LibraryTab.FOLDERS, LibraryTab.PLAYLISTS, LibraryTab.SETTINGS),
            LibraryTab.bottomBarTabs
        )

        val dbHelper = VideoHatakDatabaseHelper(testApp())
        dbHelper.replaceAllPlaylistsAndItems(emptyList())
        val repo = PlaylistRepositoryImpl(testApp(), dbHelper)
        val video = VideoItem(
            id = 501L,
            contentUri = Uri.parse("content://media/external/video/media/501"),
            title = "فيديو مفضل",
            displayName = "fav_501.mp4",
            durationMs = 45_000L,
            sizeBytes = 2_000_000L,
            dateAddedSec = 1700000000L
        )
        assertTrue(repo.toggleFavorite(video))

        val ensured = repo.ensureFavoritesPlaylist()
        assertNotNull(ensured)
        assertEquals(PlaylistRepository.FAVORITES_PLAYLIST_TITLE, ensured?.title)
        assertEquals(1, repo.getFavoritesPlaylistWithItems()?.second?.size)
        dbHelper.close()
    }

    @Test
    fun libraryViewModel_scrollPositionPersistsAcrossReloadAndTabNormalization() = runTest(testDispatcher) {
        val store = DataStoreLibraryScrollStore(createDataStore("scroll_persist"))
        val fakeRepo = FakeVideoRepository()
        val vm1 = LibraryViewModel(fakeRepo, trashRepository = null, scrollStore = store)
        advanceUntilIdle()

        vm1.onTabSelected(LibraryTab.FAVORITES)
        assertEquals(LibraryTab.PLAYLISTS, vm1.currentTab.value)

        vm1.saveVideosScrollPosition(index = 14, offset = 280)
        advanceUntilIdle()
        assertEquals(LibraryScrollPosition(14, 280), vm1.videosScrollPosition.value)

        val vm2 = LibraryViewModel(fakeRepo, trashRepository = null, scrollStore = store)
        advanceUntilIdle()
        assertTrue(vm2.isVideosScrollLoaded.value)
        assertEquals(LibraryScrollPosition(14, 280), vm2.videosScrollPosition.value)
        assertEquals(LibraryScrollPosition(4, 280), LibraryScrollPosition(14, 280).clampForItemCount(5))
    }

    @Test
    fun playlistDuplicatePrevention_checksUriAndPositiveId_neverFilenameAlone() = runBlocking {
        val dbHelper = VideoHatakDatabaseHelper(testApp())
        dbHelper.replaceAllPlaylistsAndItems(emptyList())
        val repo = PlaylistRepositoryImpl(testApp(), dbHelper)
        val playlistId = repo.createPlaylist("قائمتي")

        val videoA = VideoItem(
            id = 0L,
            contentUri = Uri.parse("file:///storage/emulated/0/Movies/clip.mp4"),
            title = "clip.mp4",
            displayName = "clip.mp4",
            durationMs = 10_000L,
            sizeBytes = 1000L,
            dateAddedSec = 100L
        )
        val videoB = VideoItem(
            id = 0L,
            contentUri = Uri.parse("file:///storage/emulated/0/Download/clip.mp4"),
            title = "clip.mp4",
            displayName = "clip.mp4",
            durationMs = 10_000L,
            sizeBytes = 1000L,
            dateAddedSec = 101L
        )
        val mediaStoreVideo = VideoItem(
            id = 777L,
            contentUri = Uri.parse("content://media/external/video/media/777"),
            title = "ms_777.mp4",
            displayName = "ms_777.mp4",
            durationMs = 20_000L,
            sizeBytes = 2000L,
            dateAddedSec = 102L
        )

        val firstAdd = repo.addVideosToPlaylistsDetailed(listOf(videoA), listOf(playlistId))
        assertEquals(1, firstAdd.addedCount)
        assertEquals(0, firstAdd.duplicateCount)

        // Exact same URI must be blocked as duplicate
        val duplicateAdd = repo.addVideosToPlaylistsDetailed(listOf(videoA), listOf(playlistId))
        assertEquals(0, duplicateAdd.addedCount)
        assertEquals(1, duplicateAdd.duplicateCount)

        // Same filename & zero ID but DIFFERENT URI must be allowed as distinct file
        val differentPathAdd = repo.addVideosToPlaylistsDetailed(listOf(videoB), listOf(playlistId))
        assertEquals(1, differentPathAdd.addedCount)
        assertEquals(0, differentPathAdd.duplicateCount)

        // Positive MediaStore ID duplicate must be blocked even with different URI representation
        val msAdd1 = repo.addVideosToPlaylistsDetailed(listOf(mediaStoreVideo), listOf(playlistId))
        assertEquals(1, msAdd1.addedCount)
        val msSameIdDifferentUri = mediaStoreVideo.copy(
            contentUri = Uri.parse("content://media/external/video/media/777?alt=1")
        )
        val msAdd2 = repo.addVideosToPlaylistsDetailed(listOf(msSameIdDifferentUri), listOf(playlistId))
        assertEquals(0, msAdd2.addedCount)
        assertEquals(1, msAdd2.duplicateCount)

        assertEquals(3, repo.getPlaylistItems(playlistId).size)
        dbHelper.close()
    }

    @Test
    fun playlistViewModel_emitsAccuratePostCompletionNotifications() = runTest(testDispatcher) {
        val dbHelper = VideoHatakDatabaseHelper(testApp())
        dbHelper.replaceAllPlaylistsAndItems(emptyList())
        val repo = PlaylistRepositoryImpl(testApp(), dbHelper)
        val vm = PlaylistViewModel(repo)
        advanceUntilIdle()

        val emittedMessages = mutableListOf<String>()
        val collectJob = launch {
            vm.events.collect { event ->
                if (event is PlaylistUiEvent.ShowMessage) {
                    emittedMessages.add(event.message)
                }
            }
        }

        val pId = repo.createPlaylist("مفضلات السفر")
        val video = VideoItem(
            id = 901L,
            contentUri = Uri.parse("content://media/external/video/media/901"),
            title = "رحلة",
            displayName = "trip.mp4",
            durationMs = 30_000L,
            sizeBytes = 3_000_000L,
            dateAddedSec = 200L
        )

        vm.addVideoToPlaylists(video, listOf(pId))
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("تمت إضافة"))

        vm.addVideoToPlaylists(video, listOf(pId))
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("موجود بالفعل"))
        assertFalse(emittedMessages.last().contains("بنجاح"))

        vm.toggleFavorite(video)
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("تمت إضافة الفيديو إلى المفضلة"))

        vm.toggleFavorite(video)
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("تمت إزالة الفيديو من المفضلة"))

        val item = repo.getPlaylistItems(pId).first()
        vm.removeItemFromPlaylist(item.id, pId)
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("تمت إزالة الفيديو من القائمة"))

        collectJob.cancel()
        dbHelper.close()
    }

    @Test
    fun vaultFlexiblePasscode_andChangePin_preserveOldPinOnErrorAndKeepEncryptedVideosReadable() = runTest(testDispatcher) {
        val prefs = VaultSecurityPreferences(createDataStore("vault_flex_pin"))
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")

        // Encrypt sample payload to file using MasterKey BEFORE setting/changing passcode
        val originalVideoBytes = "ENCRYPTED_VIDEO_PAYLOAD_BYTES_12345".toByteArray()
        val destEncFile = tempFolder.newFile("vault_passcode_change_test.enc")
        val encResult = crypto.encryptStream(
            inputStream = ByteArrayInputStream(originalVideoBytes),
            destinationFile = destEncFile,
            totalBytesEstimated = originalVideoBytes.size.toLong()
        )

        // 1. Legacy numeric PIN works and can be changed to Arabic+English+Symbols mixed passcode
        assertTrue(prefs.setPin("1234"))
        assertEquals(PinVerifyResult.Success, prefs.verifyPinWithRateLimit("1234", nowMs = 1000L))

        // Wrong current passcode keeps old PIN active
        val wrongChange = prefs.changePin("9999", "أمان#2026_Secure!", nowMs = 2000L)
        assertTrue(wrongChange is PinChangeResult.InvalidCurrentPin)
        assertEquals(PinVerifyResult.Success, prefs.verifyPinWithRateLimit("1234", nowMs = 3000L))

        // Valid change updates atomically to mixed Arabic/English/Digits/Symbols passcode
        val newPasscode = "أمان#2026_Secure!"
        val okChange = prefs.changePin("1234", newPasscode, nowMs = 4000L)
        assertTrue(okChange is PinChangeResult.Success)
        assertTrue(prefs.verifyPinWithRateLimit("1234", nowMs = 5000L) is PinVerifyResult.InvalidPin)
        assertEquals(PinVerifyResult.Success, prefs.verifyPinWithRateLimit(newPasscode, nowMs = 6000L))

        // Verify exact characters preserved (trailing space or case sensitivity must NOT match blindly)
        assertTrue(prefs.verifyPinWithRateLimit("أمان#2026_secure!", nowMs = 7000L) is PinVerifyResult.InvalidPin)

        // Previously encrypted Vault content still decrypts after passcode change
        val outBuf = ByteArray(originalVideoBytes.size)
        val readCount = crypto.readDecryptedRange(
            encryptedFile = destEncFile,
            fileSalt = encResult.fileSalt,
            wrappedDek = encResult.encryptedDek,
            chunkSize = encResult.chunkSize,
            plaintextSize = encResult.plaintextSizeBytes,
            headerSize = encResult.headerSize,
            requestedOffset = 0L,
            outBuffer = outBuf,
            outBufferOffset = 0,
            requestedLength = originalVideoBytes.size
        )
        assertEquals(originalVideoBytes.size, readCount)
        assertArrayEquals(originalVideoBytes, outBuf)

        // VaultViewModel changeVaultPin emits success message
        val fakeRepo = FakeVaultRepository()
        val vm = VaultViewModel(
            repository = fakeRepo,
            biometricHelper = VaultBiometricHelper(testApp()),
            securityPreferences = prefs,
            nowMsProvider = { 8000L }
        )
        val vaultMessages = mutableListOf<String>()
        val collectJob = launch {
            vm.events.collect { if (it is VaultUiEvent.ShowMessage) vaultMessages.add(it.message) }
        }

        var changeSuccess = false
        vm.changeVaultPin(newPasscode, "كلمة_سر@جديدة99") { ok, _ ->
            changeSuccess = ok
        }
        advanceUntilIdle()
        assertTrue(changeSuccess)
        assertEquals("تم تغيير رمز قفل الخزنة بنجاح", vaultMessages.last())

        // Removal from Vault emits explicit removal notification
        vm.deleteVideo(1L)
        advanceUntilIdle()
        assertEquals("تمت إزالة الفيديو من الخزنة بنجاح", vaultMessages.last())

        collectJob.cancel()
    }

    @Test
    fun vaultImportDetailed_verifiesEncryptionIntegrityAndKeepsSourceSafeOnFailure() = runBlocking {
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(testApp(), crypto, dbHelper)

        // Create real temporary video file
        val sourceFile = File(tempFolder.newFolder(), "sample_move.mp4").apply {
            writeBytes("REAL_VIDEO_STREAM_DATA_987654321".toByteArray())
        }
        val sourceUri = Uri.fromFile(sourceFile)

        val outcome = repo.importVideoDetailed(sourceUri = sourceUri, displayName = "sample_move.mp4")
        assertTrue(outcome is VaultImportOutcome.Imported)
        val imported = (outcome as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)
        assertTrue(encFile.exists() && encFile.length() > 0L)
        // Original source file must remain intact until caller completes post-verification public copy removal
        assertTrue(sourceFile.exists())

        // Re-importing same source reports AlreadyInVault with verified integrity
        val duplicateOutcome = repo.importVideoDetailed(sourceUri = sourceUri, displayName = "sample_move.mp4")
        assertTrue(duplicateOutcome is VaultImportOutcome.AlreadyInVault)
        assertTrue((duplicateOutcome as VaultImportOutcome.AlreadyInVault).integrityVerified)

        // Missing/broken source fails cleanly without corrupting vault count
        val missingUri = Uri.fromFile(File(tempFolder.root, "nonexistent_video.mp4"))
        val failedOutcome = repo.importVideoDetailed(sourceUri = missingUri, displayName = "missing.mp4")
        assertTrue(failedOutcome is VaultImportOutcome.Failed)

        repo.deleteVaultVideo(imported.id)
        dbHelper.close()
    }

    @Test
    fun libraryViewModel_moveToVaultNotifications_neverImplyLossOrOrdinaryDeletion() = runTest(testDispatcher) {
        val vm = LibraryViewModel(FakeVideoRepository())
        val messages = mutableListOf<String>()
        val collectJob = launch {
            vm.events.collect { if (it is LibraryUiEvent.ShowMessage) messages.add(it.message) }
        }

        vm.deleteMovedVaultOriginals(
            sourceUris = listOf(Uri.parse("content://media/external/video/media/10")),
            encryptedCount = 1,
            encryptedFailedCount = 0
        )
        advanceUntilIdle()
        assertEquals("تمت إضافة الفيديو إلى الخزنة بنجاح", messages.last())
        assertFalse(messages.last().contains("حذف"))

        collectJob.cancel()
    }

    private class FakeVideoRepository : VideoRepository {
        override fun observeVideos(): Flow<List<VideoItem>> = flowOf(emptyList())
        override suspend fun getVideos(): List<VideoItem> = emptyList()
        override fun filterAndSort(
            videos: List<VideoItem>,
            query: String,
            sortOrder: VideoSortOrder,
            folderId: String?
        ): List<VideoItem> = videos
        override fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder> = emptyList()
        override suspend fun renameVideo(video: VideoItem, newName: String): VideoFileOperationResult =
            VideoFileOperationResult.Success
        override suspend fun moveVideoToFolder(
            video: VideoItem,
            targetRelativePath: String,
            targetFolderName: String
        ): VideoFileOperationResult = VideoFileOperationResult.Success
        override suspend fun deleteVaultOriginals(uris: List<Uri>, encryptedCount: Int): VideoFileOperationResult =
            VideoFileOperationResult.Success
        override suspend fun completeVaultOriginalsDeleteAfterPermission(
            op: PendingVideoFileOp.DeleteVaultOriginals
        ): VideoFileOperationResult = VideoFileOperationResult.Success
    }

    private class FakeVaultRepository : VaultRepository {
        private val _videos = MutableStateFlow<List<VaultVideo>>(emptyList())
        override val vaultVideos: Flow<List<VaultVideo>> = _videos.asStateFlow()
        private val _unlocked = MutableStateFlow(true)
        override val isUnlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
        override fun setUnlocked(unlocked: Boolean) { _unlocked.value = unlocked }
        override suspend fun refresh() {}
        override suspend fun importVideo(
            sourceUri: Uri,
            displayName: String?,
            onProgress: (Float) -> Unit,
            isCancelled: () -> Boolean
        ): VaultVideo? = null
        override suspend fun renameVideo(videoId: Long, newTitle: String): Boolean = true
        override suspend fun deleteVaultVideo(id: Long): Boolean = true
        override fun getVideoById(id: Long): VaultVideo? = null
        override fun resolveEncryptedFile(video: VaultVideo): File = File("/tmp/fake.enc")
    }
}
