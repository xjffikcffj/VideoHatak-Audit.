package com.videohatak.player

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.data.repository.PendingVideoFileOp
import com.videohatak.player.core.data.repository.VideoFileOperationResult
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.service.VideoPlaybackService
import com.videohatak.player.core.playlist.db.PlaylistDatabaseStore
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.playlist.repository.ToggleFavoriteResult
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultImportOutcome
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.feature.library.LibraryUiEvent
import com.videohatak.player.feature.library.LibraryViewModel
import com.videohatak.player.feature.player.components.SeekBackwardDoubleTriangleIcon
import com.videohatak.player.feature.player.components.SeekForwardDoubleTriangleIcon
import com.videohatak.player.feature.playlist.PlaylistUiEvent
import com.videohatak.player.feature.playlist.PlaylistViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SupplementaryReviewFixesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()

    private fun testApp(): Application = RuntimeEnvironment.getApplication()

    private fun clearVaultStorage() {
        File(testApp().filesDir, "vault_encrypted").deleteRecursively()
        val vaultDb = VaultDatabaseHelper(testApp())
        vaultDb.getAllVideos().forEach { vaultDb.deleteVideo(it.id) }
        vaultDb.close()
    }

    private fun readFullDecryptedBytes(crypto: VaultCryptoManager, encFile: File): ByteArray {
        val header = crypto.parseEncryptedFileHeader(encFile)
        val out = ByteArray(header.plaintextSizeBytes.toInt())
        val read = crypto.readDecryptedRange(
            encryptedFile = encFile,
            fileSalt = header.fileSalt,
            wrappedDek = header.encryptedDek,
            chunkSize = header.chunkSize,
            plaintextSize = header.plaintextSizeBytes,
            headerSize = header.headerSize,
            requestedOffset = 0L,
            outBuffer = out,
            outBufferOffset = 0,
            requestedLength = out.size
        )
        assertEquals(out.size, read)
        return out
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearVaultStorage()
    }

    @After
    fun tearDown() {
        clearVaultStorage()
        Dispatchers.resetMain()
    }

    @Test
    fun vaultImport_differentContentWithSameUri_encryptsNewEntryAndVerifiesContentSha256() = runBlocking {
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(testApp(), crypto, dbHelper)
        repo.setUnlocked(true)

        val sharedFile = File(tempFolder.newFolder(), "shared_uri_video.mp4")
        val contentAlpha = "CONTENT_ALPHA_BYTES_111111111111".toByteArray()
        val contentBravo = "CONTENT_BRAVO_BYTES_222222222222".toByteArray()
        assertEquals(contentAlpha.size, contentBravo.size)

        sharedFile.writeBytes(contentAlpha)
        val sharedUri = Uri.fromFile(sharedFile)

        val firstOutcome = repo.importVideoDetailed(sharedUri, "shared_uri_video.mp4")
        assertTrue(firstOutcome is VaultImportOutcome.Imported)
        val firstVideo = (firstOutcome as VaultImportOutcome.Imported).video

        // Overwrite the exact same URI with DIFFERENT video bytes
        sharedFile.writeBytes(contentBravo)

        val secondOutcome = repo.importVideoDetailed(sharedUri, "shared_uri_video.mp4")
        assertTrue(
            "Different content at same URI must NEVER match prior URI-based entry",
            secondOutcome is VaultImportOutcome.Imported
        )
        val secondVideo = (secondOutcome as VaultImportOutcome.Imported).video
        assertNotEquals(firstVideo.id, secondVideo.id)
        assertEquals(2, dbHelper.getAllVideos().size)

        val dec1 = readFullDecryptedBytes(crypto, repo.resolveEncryptedFile(firstVideo))
        val dec2 = readFullDecryptedBytes(crypto, repo.resolveEncryptedFile(secondVideo))
        assertArrayEquals(contentAlpha, dec1)
        assertArrayEquals(contentBravo, dec2)

        dbHelper.close()
    }

    @Test
    fun vaultImport_sameNameSizeAndDurationButDifferentContent_neverTreatedAsDuplicate() = runBlocking {
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(testApp(), crypto, dbHelper)
        repo.setUnlocked(true)

        val folder = tempFolder.newFolder()
        val payloadA = ByteArray(4096) { idx -> (idx % 97).toByte() }
        val payloadB = ByteArray(4096) { idx -> ((idx + 17) % 97).toByte() }
        val fileA = File(folder, "dirA").apply { mkdirs() }.let { File(it, "identical_name.mp4") }
        val fileB = File(folder, "dirB").apply { mkdirs() }.let { File(it, "identical_name.mp4") }
        val fileACopy = File(folder, "dirC").apply { mkdirs() }.let { File(it, "renamed_copy.mp4") }
        fileA.writeBytes(payloadA)
        fileB.writeBytes(payloadB)
        fileACopy.writeBytes(payloadA)

        // Seed a legacy-style row with identical title, size, duration and legacy URI hash ("src") but NO contentSha256
        val legacyEncName = "vault_legacy_meta_collision.enc"
        val vaultDir = File(testApp().filesDir, "vault_encrypted").apply { mkdirs() }
        val legacyEncFile = File(vaultDir, legacyEncName)
        val legacyBytes = ByteArray(4096) { idx -> ((idx + 53) % 97).toByte() }
        crypto.encryptStream(
            inputStream = legacyBytes.inputStream(),
            destinationFile = legacyEncFile,
            totalBytesEstimated = legacyBytes.size.toLong()
        )
        val uriBDigest = MessageDigest.getInstance("SHA-256")
            .digest(Uri.fromFile(fileB).toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val legacyMeta = crypto.encryptMetadata(
            """{"title":"identical_name.mp4","file":"$legacyEncName","mime":"video/mp4","src":"$uriBDigest"}"""
        )
        dbHelper.insertVideo(
            VaultVideo(
                id = 0L,
                displayName = "مقطع خاص مشفر",
                originalSizeBytes = 4096L,
                encryptedSizeBytes = legacyEncFile.length(),
                durationMs = 0L,
                mimeType = "application/octet-stream",
                width = 0,
                height = 0,
                relativeEncryptedPath = legacyEncName,
                createdAtEpochMs = 1000L,
                ivBase64 = "aXY=",
                encryptedMetaBase64 = legacyMeta
            )
        )

        // Import payloadA (same displayName, same 4096 size, same 0L duration as legacy row)
        val outcomeA = repo.importVideoDetailed(Uri.fromFile(fileA), "identical_name.mp4")
        assertTrue(outcomeA is VaultImportOutcome.Imported)

        // Import payloadB (same displayName, same size, same duration, AND same URI hash as legacy row)
        val outcomeB = repo.importVideoDetailed(Uri.fromFile(fileB), "identical_name.mp4")
        assertTrue(
            "Same title, size, duration, and legacy URI hash must NOT match different video content",
            outcomeB is VaultImportOutcome.Imported
        )

        // Re-importing exact identical content bytes (even from different path/name) IS detected & verified
        val copyOutcome = repo.importVideoDetailed(Uri.fromFile(fileACopy), "renamed_copy.mp4")
        assertTrue(copyOutcome is VaultImportOutcome.AlreadyInVault)
        assertTrue((copyOutcome as VaultImportOutcome.AlreadyInVault).integrityVerified)

        assertEquals(3, dbHelper.getAllVideos().size)
        dbHelper.close()
    }

    @Test
    fun vaultImport_whenReadingOrSha256Fails_abortsWithoutDeletingOriginalOrCommitting() = runBlocking {
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val failingRepo = VaultRepositoryImpl(
            context = testApp(),
            cryptoManager = crypto,
            dbHelper = dbHelper,
            contentSha256Computer = { throw IOException("Simulated SHA-256 read error") }
        )

        val originalBytes = "IMPORTANT_ORIGINAL_VIDEO_BYTES".toByteArray()
        val sourceFile = File(tempFolder.newFolder(), "important_clip.mp4").apply {
            writeBytes(originalBytes)
        }
        val outcome = failingRepo.importVideoDetailed(Uri.fromFile(sourceFile), "important_clip.mp4")

        assertTrue(outcome is VaultImportOutcome.Failed)
        assertTrue("Original file must remain untouched on SHA-256 failure", sourceFile.exists())
        assertArrayEquals(originalBytes, sourceFile.readBytes())
        assertEquals(0, dbHelper.getAllVideos().size)

        val vaultDir = File(testApp().filesDir, "vault_encrypted")
        val strayTempFiles = vaultDir.listFiles().orEmpty().filter {
            it.name.endsWith(".stage") || it.name.endsWith(".tmp")
        }
        assertTrue("No stray temp files should remain on abort", strayTempFiles.isEmpty())

        // Verify VaultCryptoManager rejects mismatched decrypted content SHA-256
        val validEnc = File(vaultDir, "hash_check.enc")
        val encRes = crypto.encryptStream(originalBytes.inputStream(), validEnc, originalBytes.size.toLong())
        assertThrows(IOException::class.java) {
            crypto.verifyEncryptedFileIntegrityAndHash(
                validEnc,
                expectedContentSha256 = "0".repeat(64)
            )
        }
        val verifiedHash = crypto.verifyEncryptedFileIntegrityAndHash(
            validEnc,
            expectedContentSha256 = encRes.contentSha256
        )
        assertEquals(encRes.contentSha256, verifiedHash)

        dbHelper.close()
    }

    @Test
    fun deleteVaultOriginals_partialSuccess_reportsExactCountsAndPreservesEncryptedVault() = runTest(testDispatcher) {
        val authority = "com.videohatak.player.test.partialdelete"
        val backingDir = tempFolder.newFolder("partial_delete_backing")
        val provider = StubMediaDeleteProvider(backingDir).apply {
            registerItem("1", "video1.mp4", deletable = true)
            registerItem("2", "video2.mp4", deletable = false)
            registerItem("3", "video3.mp4", deletable = true)
        }
        val info = ProviderInfo().apply {
            this.authority = authority
            this.exported = false
        }
        provider.attachInfo(testApp(), info)
        ShadowContentResolver.registerProviderInternal(authority, provider)

        val uri1 = Uri.parse("content://$authority/1")
        val uri2 = Uri.parse("content://$authority/2")
        val uri3 = Uri.parse("content://$authority/3")

        val videoRepo = LocalVideoRepositoryImpl(testApp(), ioDispatcher = testDispatcher)
        val deleteResult = videoRepo.deleteVaultOriginals(listOf(uri1, uri2, uri3), encryptedCount = 3)

        assertTrue(
            "Partial delete MUST return PartialSuccess and never Success",
            deleteResult is VideoFileOperationResult.PartialSuccess
        )
        val partial = deleteResult as VideoFileOperationResult.PartialSuccess
        assertEquals(2, partial.succeededCount)
        assertEquals(1, partial.failedCount)
        assertFalse(provider.exists("1"))
        assertTrue(provider.exists("2"))
        assertFalse(provider.exists("3"))

        // Verify LibraryViewModel reports partial counts accurately rather than full success
        val vm = LibraryViewModel(videoRepo)
        val messages = mutableListOf<String>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is LibraryUiEvent.ShowMessage) messages.add(it.message) }
        }

        // Re-populate item1/3 to run end-to-end through ViewModel
        provider.registerItem("4", "video4.mp4", deletable = true)
        provider.registerItem("5", "video5.mp4", deletable = false)
        vm.deleteMovedVaultOriginals(
            sourceUris = listOf(
                Uri.parse("content://$authority/4"),
                Uri.parse("content://$authority/5")
            ),
            encryptedCount = 2
        )
        advanceUntilIdle()

        val msg = messages.last()
        assertTrue(msg.contains("أُزيلت 1"))
        assertTrue(msg.contains("بقيت 1"))
        assertNotEquals("تمت إضافة 2 فيديو إلى الخزنة بنجاح", msg)

        collectJob.cancel()
    }

    @Test
    fun completeVaultOriginalsDeleteAfterPermission_neverReportsSuccessWhenAnyFileOrPriorFailureRemains() = runTest(testDispatcher) {
        val authority = "com.videohatak.player.test.postpermdelete"
        val backingDir = tempFolder.newFolder("postperm_backing")
        val provider = StubMediaDeleteProvider(backingDir).apply {
            registerItem("10", "p10.mp4", deletable = true)
            registerItem("11", "p11.mp4", deletable = false)
        }
        val info = ProviderInfo().apply {
            this.authority = authority
            this.exported = false
        }
        provider.attachInfo(testApp(), info)
        ShadowContentResolver.registerProviderInternal(authority, provider)

        val uri10 = Uri.parse("content://$authority/10")
        val uri11 = Uri.parse("content://$authority/11")
        val videoRepo = LocalVideoRepositoryImpl(testApp(), ioDispatcher = testDispatcher)

        // Case 1: Post-permission succeeds on pending uri10, but op.failedCount == 1 from prior batch
        val priorFailureResult = videoRepo.completeVaultOriginalsDeleteAfterPermission(
            PendingVideoFileOp.DeleteVaultOriginals(
                pendingUris = listOf(uri10),
                encryptedCount = 2,
                deletedSoFar = 0,
                failedCount = 1
            )
        )
        assertTrue(
            "Prior failedCount must be carried forward so Success is NEVER returned",
            priorFailureResult is VideoFileOperationResult.PartialSuccess
        )
        priorFailureResult as VideoFileOperationResult.PartialSuccess
        assertEquals(1, priorFailureResult.succeededCount)
        assertEquals(1, priorFailureResult.failedCount)

        // Case 2: Multiple pending items where uri11 still fails after permission
        provider.registerItem("12", "p12.mp4", deletable = true)
        val uri12 = Uri.parse("content://$authority/12")
        val mixedPostPerm = videoRepo.completeVaultOriginalsDeleteAfterPermission(
            PendingVideoFileOp.DeleteVaultOriginals(
                pendingUris = listOf(uri11, uri12),
                encryptedCount = 3,
                deletedSoFar = 1,
                failedCount = 0
            )
        )
        assertTrue(mixedPostPerm is VideoFileOperationResult.PartialSuccess)
        mixedPostPerm as VideoFileOperationResult.PartialSuccess
        assertEquals(2, mixedPostPerm.succeededCount)
        assertEquals(1, mixedPostPerm.failedCount)

        // Case 3: Zero succeeded, one failed -> Error (never Success)
        val allFailedPostPerm = videoRepo.completeVaultOriginalsDeleteAfterPermission(
            PendingVideoFileOp.DeleteVaultOriginals(
                pendingUris = listOf(uri11),
                encryptedCount = 1,
                deletedSoFar = 0,
                failedCount = 0
            )
        )
        assertTrue(allFailedPostPerm is VideoFileOperationResult.Error)
    }

    @Test
    fun vaultEncryptedPayload_remainsIntactWhenPublicOriginalsCleanupFails() = runTest(testDispatcher) {
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val vaultRepo = VaultRepositoryImpl(testApp(), crypto, dbHelper)
        vaultRepo.setUnlocked(true)

        val authority = "com.videohatak.player.test.vaultpreserve"
        val backingDir = tempFolder.newFolder("vault_preserve_backing")
        val payload = "SENSITIVE_PRIVATE_VIDEO_BYTES_XYZ".toByteArray()
        val provider = StubMediaDeleteProvider(backingDir).apply {
            registerItem("20", "private_clip.mp4", deletable = false, content = payload)
        }
        val info = ProviderInfo().apply {
            this.authority = authority
            this.exported = false
        }
        provider.attachInfo(testApp(), info)
        ShadowContentResolver.registerProviderInternal(authority, provider)

        val sourceUri = Uri.parse("content://$authority/20")
        val importOutcome = vaultRepo.importVideoDetailed(sourceUri, "private_clip.mp4")
        assertTrue(importOutcome is VaultImportOutcome.Imported)
        val imported = (importOutcome as VaultImportOutcome.Imported).video

        val videoRepo = LocalVideoRepositoryImpl(testApp(), ioDispatcher = testDispatcher)
        val vm = LibraryViewModel(videoRepo)
        val messages = mutableListOf<String>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is LibraryUiEvent.ShowMessage) messages.add(it.message) }
        }

        vm.deleteMovedVaultOriginals(listOf(sourceUri), encryptedCount = 1)
        advanceUntilIdle()

        // Public cleanup failed, yet encrypted Vault file and DB entry must be 100% intact & decryptable
        assertTrue(provider.exists("20"))
        assertTrue(messages.last().contains("تعذر"))
        val encFile = vaultRepo.resolveEncryptedFile(imported)
        assertTrue(encFile.exists() && encFile.length() > 0L)
        assertEquals(1, dbHelper.getAllVideos().size)
        val decrypted = readFullDecryptedBytes(crypto, encFile)
        assertArrayEquals(payload, decrypted)

        collectJob.cancel()
        dbHelper.close()
    }

    @Test
    fun playerSeekIcons_containOnlyTwoDirectionalTrianglesWithoutBarsOrDigits_andServiceActionsUnchanged() {
        fun inspectTrianglePaths(iconPaths: List<VectorPath>): List<Pair<Float, Float>> {
            return iconPaths.map { vp ->
                val nodes = vp.pathData
                assertEquals(
                    "Each triangle path must contain strictly MoveTo, LineTo, LineTo, Close",
                    4,
                    nodes.size
                )
                val move = nodes[0] as PathNode.MoveTo
                val apex = nodes[1] as PathNode.LineTo
                val end = nodes[2] as PathNode.LineTo
                assertTrue(nodes[3] is PathNode.Close)
                // Symmetrical triangle: base vertices share x, apex differs
                assertEquals(move.x, end.x, 0.001f)
                assertTrue(move.y < apex.y && apex.y < end.y)
                move.x to apex.x
            }
        }

        val forwardPaths = SeekForwardDoubleTriangleIcon.root.filterIsInstance<VectorPath>()
        assertEquals("Forward seek icon must have ONLY 2 triangles (no side bars)", 2, forwardPaths.size)
        val forwardCoords = inspectTrianglePaths(forwardPaths)
        assertTrue(
            "Both forward triangles must point strictly RIGHT (apexX > baseX)",
            forwardCoords.all { (baseX, apexX) -> apexX > baseX }
        )

        val backwardPaths = SeekBackwardDoubleTriangleIcon.root.filterIsInstance<VectorPath>()
        assertEquals("Backward seek icon must have ONLY 2 triangles (no side bars)", 2, backwardPaths.size)
        val backwardCoords = inspectTrianglePaths(backwardPaths)
        assertTrue(
            "Both backward triangles must point strictly LEFT (apexX < baseX)",
            backwardCoords.all { (baseX, apexX) -> apexX < baseX }
        )

        // Verify notification action contract on VideoPlaybackService remains completely untouched
        assertEquals("com.videohatak.player.action.PLAY_PAUSE", VideoPlaybackService.ACTION_PLAY_PAUSE)
        assertEquals("com.videohatak.player.action.NEXT", VideoPlaybackService.ACTION_NEXT)
        assertEquals("com.videohatak.player.action.PREVIOUS", VideoPlaybackService.ACTION_PREVIOUS)
        assertEquals("com.videohatak.player.action.STOP", VideoPlaybackService.ACTION_STOP)
    }

    @Test
    fun toggleFavorite_dbWriteFailures_emitFailureNotificationsAndPreserveFavoritesData() = runTest(testDispatcher) {
        val backingDb = VideoHatakDatabaseHelper(testApp())
        backingDb.replaceAllPlaylistsAndItems(emptyList())

        var failInsertItem = false
        var failDeleteItem = false
        val failingDbStore = object : PlaylistDatabaseStore {
            override fun insertPlaylist(title: String): Long = backingDb.insertPlaylist(title)
            override fun updatePlaylist(id: Long, title: String): Int = backingDb.updatePlaylist(id, title)
            override fun deletePlaylist(id: Long): Int = backingDb.deletePlaylist(id)
            override fun getPlaylists(): List<Playlist> = backingDb.getPlaylists()
            override fun insertItem(item: PlaylistItem): Long {
                if (failInsertItem) return -1L
                return backingDb.insertItem(item)
            }
            override fun deleteItem(itemId: Long, playlistId: Long): Int {
                if (failDeleteItem) return 0
                return backingDb.deleteItem(itemId, playlistId)
            }
            override fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem> =
                backingDb.getItemsForPlaylist(playlistId)
            override fun updateItemOrder(itemId: Long, newIndex: Int) = backingDb.updateItemOrder(itemId, newIndex)
            override fun updateItemAvailability(itemId: Long, isAvailable: Boolean) =
                backingDb.updateItemAvailability(itemId, isAvailable)
            override fun updateItemUri(itemId: Long, newUri: String) = backingDb.updateItemUri(itemId, newUri)
        }

        val repo = PlaylistRepositoryImpl(testApp(), failingDbStore, ioDispatcher = testDispatcher)
        val vm = PlaylistViewModel(repo)
        advanceUntilIdle()

        val emittedMessages = mutableListOf<String>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is PlaylistUiEvent.ShowMessage) emittedMessages.add(it.message) }
        }

        val existingFavVideo = VideoItem(
            id = 801L,
            contentUri = Uri.parse("content://media/external/video/media/801"),
            title = "مفضل محفوظ",
            displayName = "saved_801.mp4",
            durationMs = 15_000L,
            sizeBytes = 1_500_000L,
            dateAddedSec = 100L
        )
        val targetVideo = VideoItem(
            id = 802L,
            contentUri = Uri.parse("content://media/external/video/media/802"),
            title = "فيديو اختبار المفضلة",
            displayName = "target_802.mp4",
            durationMs = 20_000L,
            sizeBytes = 2_000_000L,
            dateAddedSec = 101L
        )

        // Seed existing Favorites entry cleanly first
        val initialAdd = repo.toggleFavoriteDetailed(existingFavVideo)
        assertTrue(initialAdd is ToggleFavoriteResult.Added)
        vm.refresh()
        advanceUntilIdle()
        val favPlaylistIdBefore = vm.favoritesPlaylistId.value!!

        // 1. Simulate DB INSERT failure during toggleFavorite
        failInsertItem = true
        var callbackFavStateAfterAddFail = true
        var callbackMsgAfterAddFail = ""
        vm.toggleFavoriteWithFeedback(targetVideo) { isNowFav, msg ->
            callbackFavStateAfterAddFail = isNowFav
            callbackMsgAfterAddFail = msg
        }
        advanceUntilIdle()

        assertFalse("UI favorite state must remain false when DB insert fails", callbackFavStateAfterAddFail)
        assertFalse(vm.isFavorite(targetVideo))
        assertTrue(callbackMsgAfterAddFail.contains("تعذر"))
        assertFalse(callbackMsgAfterAddFail.contains("بنجاح"))
        assertEquals(callbackMsgAfterAddFail, emittedMessages.last())
        // Pre-existing Favorites playlist ID and item must remain untouched (no drop/recreate)
        assertEquals(favPlaylistIdBefore, vm.favoritesPlaylistId.value)
        assertTrue(vm.isFavorite(existingFavVideo))
        assertEquals(1, repo.getFavoritesPlaylistWithItems()?.second?.size)

        // 2. Allow INSERT to succeed, then simulate DB DELETE failure
        failInsertItem = false
        vm.toggleFavoriteWithFeedback(targetVideo) { _, _ -> }
        advanceUntilIdle()
        assertTrue(vm.isFavorite(targetVideo))
        assertEquals(2, repo.getFavoritesPlaylistWithItems()?.second?.size)

        failDeleteItem = true
        var callbackFavStateAfterDeleteFail = false
        var callbackMsgAfterDeleteFail = ""
        vm.toggleFavoriteWithFeedback(targetVideo) { isNowFav, msg ->
            callbackFavStateAfterDeleteFail = isNowFav
            callbackMsgAfterDeleteFail = msg
        }
        advanceUntilIdle()

        assertTrue("UI favorite state must stay true when DB delete fails", callbackFavStateAfterDeleteFail)
        assertTrue(vm.isFavorite(targetVideo))
        assertTrue(callbackMsgAfterDeleteFail.contains("تعذر"))
        assertFalse(callbackMsgAfterDeleteFail.contains("بنجاح"))
        assertEquals(callbackMsgAfterDeleteFail, emittedMessages.last())
        assertEquals(favPlaylistIdBefore, vm.favoritesPlaylistId.value)
        assertEquals(2, repo.getFavoritesPlaylistWithItems()?.second?.size)

        collectJob.cancel()
        backingDb.close()
    }

    @Test
    fun playlistViewModel_writeExceptionsAndZeroRowUpdates_neverEmitSuccessMessagesAndKeepEagerFavoritesId() = runTest(testDispatcher) {
        val backingDb = VideoHatakDatabaseHelper(testApp())
        backingDb.replaceAllPlaylistsAndItems(emptyList())

        var throwOnInsertItem = false
        var failUpdatePlaylist = false
        var failDeletePlaylist = false
        var failDeleteItem = false
        val flakyDbStore = object : PlaylistDatabaseStore {
            override fun insertPlaylist(title: String): Long = backingDb.insertPlaylist(title)
            override fun updatePlaylist(id: Long, title: String): Int =
                if (failUpdatePlaylist) 0 else backingDb.updatePlaylist(id, title)
            override fun deletePlaylist(id: Long): Int =
                if (failDeletePlaylist) 0 else backingDb.deletePlaylist(id)
            override fun getPlaylists(): List<Playlist> = backingDb.getPlaylists()
            override fun insertItem(item: PlaylistItem): Long {
                if (throwOnInsertItem) throw IllegalStateException("Simulated SQLite write error")
                return backingDb.insertItem(item)
            }
            override fun deleteItem(itemId: Long, playlistId: Long): Int =
                if (failDeleteItem) 0 else backingDb.deleteItem(itemId, playlistId)
            override fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem> =
                backingDb.getItemsForPlaylist(playlistId)
            override fun updateItemOrder(itemId: Long, newIndex: Int) = backingDb.updateItemOrder(itemId, newIndex)
            override fun updateItemAvailability(itemId: Long, isAvailable: Boolean) =
                backingDb.updateItemAvailability(itemId, isAvailable)
            override fun updateItemUri(itemId: Long, newUri: String) = backingDb.updateItemUri(itemId, newUri)
        }

        val repo = PlaylistRepositoryImpl(testApp(), flakyDbStore, ioDispatcher = testDispatcher)
        val favPlaylist = repo.ensureFavoritesPlaylist()!!
        val customPlaylistId = repo.createPlaylist("قائمة تجريبية")
        val vm = PlaylistViewModel(repo)
        advanceUntilIdle()

        // Must be populated eagerly without an active downstream collector
        assertEquals(favPlaylist.id, vm.favoritesPlaylistId.value)

        val emittedMessages = mutableListOf<String>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.events.collect { if (it is PlaylistUiEvent.ShowMessage) emittedMessages.add(it.message) }
        }

        val sampleVideo = VideoItem(
            id = 850L,
            contentUri = Uri.parse("content://media/external/video/media/850"),
            title = "اختبار الاستثناءات",
            displayName = "sample_850.mp4",
            durationMs = 12_000L,
            sizeBytes = 1_200_000L,
            dateAddedSec = 500L
        )

        // Exception during insertItem must report failure, not crash or claim success
        throwOnInsertItem = true
        vm.addVideoToPlaylists(sampleVideo, listOf(customPlaylistId))
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("تعذر"))
        assertFalse(emittedMessages.last().contains("بنجاح"))

        vm.toggleFavoriteWithFeedback(sampleVideo) { isNowFav, msg ->
            assertFalse(isNowFav)
            assertTrue(msg.contains("تعذر"))
            assertFalse(msg.contains("بنجاح"))
        }
        advanceUntilIdle()

        // Rename / delete / remove failure must emit failure notification (never success)
        throwOnInsertItem = false
        vm.addVideoToPlaylists(sampleVideo, listOf(customPlaylistId))
        advanceUntilIdle()
        val insertedItemId = repo.getPlaylistItems(customPlaylistId).first().id

        failUpdatePlaylist = true
        vm.renamePlaylist(customPlaylistId, "اسم جديد")
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("تعذر"))
        assertFalse(emittedMessages.last().contains("بنجاح"))

        failDeletePlaylist = true
        vm.deletePlaylist(customPlaylistId)
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("تعذر"))
        assertFalse(emittedMessages.last().contains("بنجاح"))

        failDeleteItem = true
        vm.removeItemFromPlaylist(insertedItemId, customPlaylistId)
        advanceUntilIdle()
        assertTrue(emittedMessages.last().contains("تعذر"))
        assertFalse(emittedMessages.last().contains("بنجاح"))
        assertEquals(1, repo.getPlaylistItems(customPlaylistId).size)

        collectJob.cancel()
        backingDb.close()
    }

    private class StubMediaDeleteProvider(
        private val backingDir: File
    ) : ContentProvider() {

        private data class Entry(val file: File, val deletable: Boolean)

        private val entries = LinkedHashMap<String, Entry>()

        fun registerItem(
            id: String,
            name: String,
            deletable: Boolean,
            content: ByteArray = "MEDIA_BYTES_$id".toByteArray()
        ) {
            val f = File(backingDir, name).apply { writeBytes(content) }
            entries[id] = Entry(f, deletable)
        }

        fun exists(id: String): Boolean = entries[id]?.file?.exists() == true

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            val id = uri.lastPathSegment ?: return null
            val entry = entries[id] ?: return null
            if (!entry.file.exists()) return MatrixCursor(projection ?: arrayOf(MediaStore.Video.Media._ID))
            val cols = projection ?: arrayOf(MediaStore.Video.Media._ID)
            return MatrixCursor(cols).apply {
                addRow(Array<Any?>(cols.size) { id.toLongOrNull() ?: 1L })
            }
        }

        override fun getType(uri: Uri): String = "video/mp4"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment ?: return 0
            val entry = entries[id] ?: return 0
            if (!entry.deletable) return 0
            return if (entry.file.delete()) {
                entries.remove(id)
                1
            } else {
                0
            }
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = uri.lastPathSegment ?: throw FileNotFoundException()
            val entry = entries[id] ?: throw FileNotFoundException()
            if (!entry.file.exists()) throw FileNotFoundException()
            return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
