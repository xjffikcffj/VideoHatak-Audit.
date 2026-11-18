package com.videohatak.player

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.model.VideoSortOrder
import com.videohatak.player.core.permission.VideoPermissionStatus
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.trash.repository.TrashRepositoryImpl
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.db.VaultDatabaseStore
import com.videohatak.player.core.vault.model.VaultEntryState
import com.videohatak.player.core.vault.model.VaultHiddenMatcher
import com.videohatak.player.core.vault.model.VaultOriginalStorageType
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.PublicExportOutcome
import com.videohatak.player.core.vault.repository.PublicExportRequest
import com.videohatak.player.core.vault.repository.PublicMediaExporter
import com.videohatak.player.core.vault.repository.VaultDeleteOutcome
import com.videohatak.player.core.vault.repository.VaultImportOutcome
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.core.vault.repository.VaultRestoreOutcome
import com.videohatak.player.feature.library.LibraryViewModel
import com.videohatak.player.feature.playlist.PlaylistViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNull
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
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VaultOptionAHiddenOriginalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()

    private fun testApp(): Application = RuntimeEnvironment.getApplication()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun clearAllStorage() {
        File(testApp().filesDir, "vault_encrypted").deleteRecursively()
        File(testApp().cacheDir, "vault_restore_tmp").deleteRecursively()
        val vaultDb = VaultDatabaseHelper(testApp())
        vaultDb.getAllVideos().forEach { vaultDb.deleteVideo(it.id) }
        vaultDb.close()

        val plDb = VideoHatakDatabaseHelper(testApp())
        plDb.getPlaylists().forEach { plDb.deletePlaylist(it.id) }
        plDb.clearAllTrashItems()
        plDb.close()
    }

    private fun registerMediaStoreProvider(backingDir: File): TestMediaProvider {
        val provider = TestMediaProvider(backingDir)
        val info = ProviderInfo().apply {
            authority = MediaStore.AUTHORITY
            exported = true
        }
        provider.attachInfo(testApp(), info)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        return provider
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clearAllStorage()
    }

    @After
    fun tearDown() {
        clearAllStorage()
        Dispatchers.resetMain()
    }

    // Test 1: Moving video to Vault encrypts, records full metadata + SHA-256, preserves original untouched, and marks hidden
    @Test
    fun test01_moveToVault_encryptsAndRecordsFullMetadataAndMarksHiddenWithoutTouchingOriginal() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(testApp(), crypto, db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val payload = ByteArray(140_000) { (it * 13 + 5).toByte() }
        val expectedSha = sha256Hex(payload)
        val srcDir = tempFolder.newFolder("t01_src")
        val srcFile = File(srcDir, "vacation_trip.mp4").apply {
            writeBytes(payload)
            setLastModified(1_700_000_000_000L)
        }
        val srcUri = Uri.fromFile(srcFile)

        val outcome = repo.importMediaVideoDetailed(
            sourceUri = srcUri,
            displayName = "رحلة الصيف",
            originalFileName = "vacation_trip.mp4",
            originalRelativePath = "${Environment.DIRECTORY_MOVIES}/Trips/",
            originalSizeBytes = payload.size.toLong(),
            originalDateModifiedSec = 1_700_000_000L,
            originalMediaStoreId = 99L,
            originalFilePath = srcFile.absolutePath
        )

        assertTrue(outcome is VaultImportOutcome.Imported)
        val saved = (outcome as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(saved)
        assertTrue(encFile.exists() && encFile.length() > payload.size)

        // Original file MUST remain completely untouched on disk
        assertTrue(srcFile.exists())
        assertEquals(payload.size.toLong(), srcFile.length())
        assertArrayEquals(payload, srcFile.readBytes())

        // Metadata & Option A reference persisted in DB
        val row = db.getVideoById(saved.id)!!
        assertEquals(srcUri.toString(), row.originalUri)
        assertEquals(99L, row.originalMediaStoreId)
        assertEquals(srcFile.absolutePath, row.originalFilePath)
        assertEquals(1_700_000_000L, row.originalDateModifiedSec)
        assertEquals(expectedSha, row.originalSha256)
        assertEquals(VaultEntryState.ORIGINAL_HIDDEN, row.vaultState)
        assertTrue(row.isHiddenInLibrary)
        assertEquals(1, repo.hiddenOriginalEntries.value.size)
        db.close()
    }

    // Test 2: Encryption or DB failure aborts cleanly, leaves original visible & intact, cleans staging/temp files
    @Test
    fun test02_encryptionOrDbFailure_cleansTempFilesAndLeavesOriginalVisibleAndIntact() = runTest(testDispatcher) {
        val realDb = VaultDatabaseHelper(testApp())
        val flakeyDb = object : VaultDatabaseStore by realDb {
            override fun insertVideo(video: VaultVideo): Long = -1L
        }
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(testApp(), crypto, flakeyDb, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val payload = "IMPORTANT_CLIP_DO_NOT_LOSE".toByteArray()
        val srcFile = File(tempFolder.newFolder("t02_src"), "keep_safe.mp4").apply { writeBytes(payload) }
        val outcome = repo.importMediaVideoDetailed(Uri.fromFile(srcFile), "keep_safe")

        assertTrue(outcome is VaultImportOutcome.Failed)
        assertTrue(srcFile.exists())
        assertArrayEquals(payload, srcFile.readBytes())
        assertTrue(realDb.getAllVideos().isEmpty())
        assertTrue(repo.hiddenOriginalEntries.value.isEmpty())

        val vaultDir = File(testApp().filesDir, "vault_encrypted")
        val leftoverStage = vaultDir.listFiles()?.filter {
            it.name.endsWith(".stage") || it.name.endsWith(".tmp") || it.name.endsWith(".enc")
        }.orEmpty()
        assertTrue("No partial vault artifacts may linger after failed commit", leftoverStage.isEmpty())
        realDb.close()
    }

    // Test 3: Moving to Vault never deletes/moves/renames original & never invokes delete consent/requests
    @Test
    fun test03_moveToVault_neverDeletesOrRenamesOriginalAndNeverRequestsDeleteConsent() = runTest(testDispatcher) {
        val provider = registerMediaStoreProvider(tempFolder.newFolder("t03_ms"))
        val db = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(testApp(), crypto, db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val payload = "ORIGINAL_MUST_NEVER_BE_DELETED".toByteArray()
        val uri = provider.seedPublishedEntry("original_stay.mp4", "${Environment.DIRECTORY_MOVIES}/", payload)

        val outcome = repo.importMediaVideoDetailed(
            sourceUri = uri,
            displayName = "original_stay.mp4",
            originalFileName = "original_stay.mp4",
            originalMediaStoreId = ContentUris.parseId(uri)
        )
        assertTrue(outcome is VaultImportOutcome.Imported)
        assertEquals("MediaStore delete must NEVER be called", 0, provider.deleteCallCount.get())
        assertEquals("MediaStore update/rename must NEVER be called", 0, provider.updateCallCount.get())
        assertEquals(1, provider.rowCount())
        assertEquals("original_stay.mp4", provider.entryForUri(uri)!!.displayName)
        db.close()
    }

    // Test 4: Hidden original is excluded from All Videos, Folders, Search, Favorites, and Multi-select
    @Test
    fun test04_hiddenOriginal_disappearsFromLibraryFoldersSearchFavoritesAndMultiSelect() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val vaultRepo = VaultRepositoryImpl(testApp(), crypto, db, ioDispatcher = testDispatcher)
        vaultRepo.setUnlocked(true)

        val f1 = File(tempFolder.newFolder("t04_a"), "alpha_clip.mp4").apply { writeBytes("VIDEO_ALPHA_12345".toByteArray()) }
        val f2 = File(tempFolder.newFolder("t04_b"), "beta_clip.mp4").apply { writeBytes("VIDEO_BETA_67890".toByteArray()) }

        val v1 = VideoItem(id = 101L, contentUri = Uri.fromFile(f1), title = "alpha_clip", displayName = "alpha_clip.mp4", path = f1.absolutePath, bucketId = "b_a", bucketDisplayName = "Folder A")
        val v2 = VideoItem(id = 102L, contentUri = Uri.fromFile(f2), title = "beta_clip", displayName = "beta_clip.mp4", path = f2.absolutePath, bucketId = "b_b", bucketDisplayName = "Folder B")

        val fakeVideoRepo = InMemoryVideoRepo(listOf(v1, v2))
        val plDb = VideoHatakDatabaseHelper(testApp())
        val plRepo = PlaylistRepositoryImpl(
            context = testApp(),
            dbHelper = plDb,
            videoRepository = fakeVideoRepo,
            ioDispatcher = testDispatcher,
            hiddenOriginalsProvider = { vaultRepo.hiddenOriginalEntries.value }
        )

        val libVm = LibraryViewModel(
            videoRepository = fakeVideoRepo,
            vaultHiddenEntries = vaultRepo.hiddenOriginalEntries
        )
        val plVm = PlaylistViewModel(
            repository = plRepo,
            vaultHiddenEntries = vaultRepo.hiddenOriginalEntries
        )

        val uiStateCollectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            libVm.uiState.collect {}
        }

        libVm.updatePermissionState(VideoPermissionStatus.FullAccess)
        plRepo.toggleFavoriteDetailed(v1)
        plRepo.toggleFavoriteDetailed(v2)
        advanceUntilIdle()

        assertEquals(2, libVm.uiState.value.allVideos.size)
        assertEquals(2, libVm.uiState.value.folders.size)
        assertEquals(2, plVm.favoriteItems.value.size)

        // Move v1 to Vault -> immediately hidden across all library/folder/search/favorites views
        val imp = vaultRepo.importMediaVideoDetailed(
            sourceUri = v1.contentUri,
            displayName = v1.safeTitle,
            originalFilePath = v1.path
        )
        assertTrue(imp is VaultImportOutcome.Imported)
        advanceUntilIdle()

        assertEquals(listOf(102L), libVm.uiState.value.allVideos.map { it.id })
        assertEquals(listOf("b_b"), libVm.uiState.value.folders.map { it.bucketId })

        libVm.onSearchQueryChanged("alpha")
        advanceUntilIdle()
        assertTrue("Search must exclude hidden video", libVm.uiState.value.filteredVideos.isEmpty())

        libVm.onSearchQueryChanged("")
        advanceUntilIdle()
        assertEquals(listOf(102L), plVm.favoriteItems.value.map { it.videoId })

        uiStateCollectJob.cancel()
        db.close()
        plDb.close()
    }

    // Test 5: Hidden original stays hidden across app/repository restart even while Vault is LOCKED
    @Test
    fun test05_hiddenOriginal_persistsAcrossRestartEvenWhenVaultIsLocked() = runTest(testDispatcher) {
        val db1 = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo1 = VaultRepositoryImpl(testApp(), crypto, db1, ioDispatcher = testDispatcher)
        repo1.setUnlocked(true)

        val f1 = File(tempFolder.newFolder("t05"), "restart_persist.mp4").apply { writeBytes("PERSIST_LOCKED_BYTES".toByteArray()) }
        val imp = repo1.importMediaVideoDetailed(Uri.fromFile(f1), "restart_persist", originalFilePath = f1.absolutePath)
        assertTrue(imp is VaultImportOutcome.Imported)
        db1.close()

        // Simulate fresh app restart -> NEW repository instance starts LOCKED (isUnlocked == false)
        val db2 = VaultDatabaseHelper(testApp())
        val repo2 = VaultRepositoryImpl(testApp(), crypto, db2, ioDispatcher = testDispatcher)
        assertFalse(repo2.isUnlocked.value)
        assertTrue("Vault videos must be empty while locked", repo2.vaultVideos.value.isEmpty())
        assertEquals("Hidden entries MUST load while locked", 1, repo2.hiddenOriginalEntries.value.size)

        val hiddenCandidate = VideoItem(id = 201L, contentUri = Uri.fromFile(f1), path = f1.absolutePath)
        assertTrue(VaultHiddenMatcher.isVideoHidden(hiddenCandidate, repo2.hiddenOriginalEntries.value))
        db2.close()
    }

    // Test 6: Favorites & Playlist DB rows are preserved intact while hidden, unplayable publicly, and automatically reappear on restore
    @Test
    fun test06_playlistsAndFavoritesDbRecords_preservedWhileHiddenAndAutoReappearOnRestore() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val plDb = VideoHatakDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val vaultRepo = VaultRepositoryImpl(testApp(), crypto, db, ioDispatcher = testDispatcher)
        vaultRepo.setUnlocked(true)

        val f1 = File(tempFolder.newFolder("t06"), "fav_clip.mp4").apply { writeBytes("PLAYLIST_AUTO_REAPPEAR".toByteArray()) }
        val v1 = VideoItem(id = 301L, contentUri = Uri.fromFile(f1), title = "مفضلتي", displayName = "fav_clip.mp4", path = f1.absolutePath)

        val fakeVideoRepo = InMemoryVideoRepo(listOf(v1))
        val plRepo = PlaylistRepositoryImpl(
            context = testApp(),
            dbHelper = plDb,
            videoRepository = fakeVideoRepo,
            ioDispatcher = testDispatcher,
            hiddenOriginalsProvider = { vaultRepo.hiddenOriginalEntries.value }
        )

        val customPlId = plRepo.createPlaylist("مختاراتي")
        plRepo.addVideosToPlaylists(listOf(v1), listOf(customPlId))
        plRepo.toggleFavoriteDetailed(v1)

        // Move into Vault
        val imported = (vaultRepo.importMediaVideoDetailed(v1.contentUri, v1.safeTitle, originalFilePath = v1.path) as VaultImportOutcome.Imported).video

        // Public repository getters hide item, BUT underlying SQLite rows in plDb remain untouched!
        assertTrue(plRepo.getPlaylistItems(customPlId).isEmpty())
        assertTrue(plRepo.getFavoritesPlaylistWithItems()!!.second.isEmpty())
        assertEquals(1, plDb.getItemsForPlaylist(customPlId).size)
        assertEquals(0, plRepo.relinkMissingItems(customPlId))

        // Remove from Vault -> DB records still present, so video automatically reappears in Favorites & Playlists!
        val restored = vaultRepo.restoreVaultVideo(imported.id)
        assertTrue(restored is VaultRestoreOutcome.Restored)
        assertEquals(1, plRepo.getPlaylistItems(customPlId).size)
        assertEquals(1, plRepo.getFavoritesPlaylistWithItems()!!.second.size)

        db.close()
        plDb.close()
    }

    // Test 7: "إزالة من الخزنة" verifies existence + SHA-256, never invokes exporter/copies, unhides cleanly, then cleans .enc
    @Test
    fun test07_restoreFromVault_verifiesSha256UnhidesOriginalWithoutExporterThenDeletesEnc() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val exportCalls = AtomicInteger(0)
        val exporter = object : PublicMediaExporter {
            override suspend fun exportVideo(request: PublicExportRequest): PublicExportOutcome {
                exportCalls.incrementAndGet()
                return PublicExportOutcome.Failed("Unexpected exporter call")
            }
        }
        val repo = VaultRepositoryImpl(testApp(), crypto, db, ioDispatcher = testDispatcher, publicMediaExporter = exporter)
        repo.setUnlocked(true)

        val bytes = "VERIFIED_UNHIDE_BYTES_0123456789".toByteArray()
        val f = File(tempFolder.newFolder("t07"), "clean_unhide.mp4").apply { writeBytes(bytes) }
        val imp = (repo.importMediaVideoDetailed(Uri.fromFile(f), "clean_unhide", originalFilePath = f.absolutePath) as VaultImportOutcome.Imported).video
        val enc = repo.resolveEncryptedFile(imp)

        val res = repo.restoreVaultVideo(imp.id)
        assertTrue(res is VaultRestoreOutcome.Restored)
        assertEquals(0, exportCalls.get())
        assertEquals(Uri.fromFile(f), (res as VaultRestoreOutcome.Restored).restoredUri)
        assertTrue(repo.hiddenOriginalEntries.value.isEmpty())
        assertFalse(enc.exists())
        assertNull(db.getVideoById(imp.id))
        db.close()
    }

    // Test 8: Original deleted externally -> OriginalMissing, .enc & DB intact
    @Test
    fun test08_restoreWhenOriginalDeletedExternally_returnsOriginalMissingAndPreservesVault() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val repo = VaultRepositoryImpl(testApp(), VaultCryptoManager("TEST_PROVIDER"), db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val f = File(tempFolder.newFolder("t08"), "gone.mp4").apply { writeBytes("ORIGINAL_WILL_BE_DELETED".toByteArray()) }
        val imp = (repo.importMediaVideoDetailed(Uri.fromFile(f), "gone") as VaultImportOutcome.Imported).video
        assertTrue(f.delete())

        val outcome = repo.restoreVaultVideo(imp.id)
        assertTrue(outcome is VaultRestoreOutcome.OriginalMissing)
        assertTrue(repo.resolveEncryptedFile(imp).exists())
        assertNotNull(db.getVideoById(imp.id))
        db.close()
    }

    // Test 9: Original altered -> OriginalChanged, .enc & DB intact
    @Test
    fun test09_restoreWhenOriginalBytesModified_returnsOriginalChangedAndPreservesVault() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val repo = VaultRepositoryImpl(testApp(), VaultCryptoManager("TEST_PROVIDER"), db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val f = File(tempFolder.newFolder("t09"), "modified.mp4").apply { writeBytes("ORIGINAL_CONTENT_V1".toByteArray()) }
        val imp = (repo.importMediaVideoDetailed(Uri.fromFile(f), "modified") as VaultImportOutcome.Imported).video
        f.writeBytes("TAMPERED_LONGER_CONTENT_V2".toByteArray())

        val outcome = repo.restoreVaultVideo(imp.id)
        assertTrue(outcome is VaultRestoreOutcome.OriginalChanged)
        assertTrue(repo.resolveEncryptedFile(imp).exists())
        assertEquals(VaultEntryState.ORIGINAL_CHANGED, db.getVideoById(imp.id)!!.vaultState)
        db.close()
    }

    // Test 10: Different file placed at original path/name -> SHA-256 mismatch -> OriginalChanged
    @Test
    fun test10_restoreWhenDifferentFileHasSameName_returnsOriginalChangedAndPreservesVault() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val repo = VaultRepositoryImpl(testApp(), VaultCryptoManager("TEST_PROVIDER"), db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val orig = "EXACT_16_BYTE_AA".toByteArray()
        val replacement = "EXACT_16_BYTE_BB".toByteArray()
        val dir = tempFolder.newFolder("t10")
        val f = File(dir, "same_name.mp4").apply { writeBytes(orig) }
        val imp = (repo.importMediaVideoDetailed(Uri.fromFile(f), "same_name") as VaultImportOutcome.Imported).video

        // Replace with same byte length & same filename, different content
        assertTrue(f.delete())
        File(dir, "same_name.mp4").writeBytes(replacement)

        val outcome = repo.restoreVaultVideo(imp.id)
        assertTrue(outcome is VaultRestoreOutcome.OriginalChanged)
        assertTrue(repo.resolveEncryptedFile(imp).exists())
        assertNotNull(db.getVideoById(imp.id))
        db.close()
    }

    // Test 11: SAF / persistable URI permission lost -> RequiresUriPermission, .enc & DB intact
    @Test
    fun test11_restoreWhenSafPermissionRevoked_returnsRequiresUriPermissionAndPreservesVault() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val safFile = File(tempFolder.newFolder("t11"), "saf_doc.mp4").apply { writeBytes("SAF_VIDEO_BYTES".toByteArray()) }
        var hasPermission = true
        val repo = VaultRepositoryImpl(
            context = testApp(),
            cryptoManager = VaultCryptoManager("TEST_PROVIDER"),
            dbHelper = db,
            ioDispatcher = testDispatcher,
            uriPermissionChecker = { hasPermission }
        )
        repo.setUnlocked(true)

        val imp = (repo.importMediaVideoDetailed(Uri.fromFile(safFile), "saf_doc") as VaultImportOutcome.Imported).video
        hasPermission = false

        val outcome = repo.restoreVaultVideo(imp.id)
        assertTrue(outcome is VaultRestoreOutcome.RequiresUriPermission)
        assertEquals(VaultEntryState.REQUIRES_PERMISSION, db.getVideoById(imp.id)!!.vaultState)
        assertTrue(repo.resolveEncryptedFile(imp).exists())
        db.close()
    }

    // Test 12: SD / SAF / MediaStore content URI storage type classification & unmounted volume safe failure
    @Test
    fun test12_storageTypeHandling_sdSafAndMediaStoreClassifiedAndMissingVolumeFailsSafely() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val repo = VaultRepositoryImpl(testApp(), VaultCryptoManager("TEST_PROVIDER"), db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val extDir = tempFolder.newFolder("sd_card_sim")
        val f = File(extDir, "sd_clip.mp4").apply { writeBytes("SD_VOLUME_CLIP_DATA".toByteArray()) }
        val imp = (
            repo.importMediaVideoDetailed(
                sourceUri = Uri.fromFile(f),
                displayName = "sd_clip",
                originalFilePath = "/storage/ABCD-1234/Movies/sd_clip.mp4"
            ) as VaultImportOutcome.Imported
            ).video

        val persisted = db.getVideoById(imp.id)!!
        assertEquals(VaultOriginalStorageType.EXTERNAL_SD_OR_REMOVABLE, persisted.storageType)

        // Simulate unmounted SD volume (file inaccessible/missing)
        assertTrue(f.delete())
        val res = repo.restoreVaultVideo(imp.id)
        assertTrue(res is VaultRestoreOutcome.OriginalMissing)
        assertTrue((res as VaultRestoreOutcome.OriginalMissing).reason.contains("SD"))
        db.close()
    }

    // Test 13: Concurrent duplicate Move-to-Vault / Restore calls never create duplicate records or corrupt files
    @Test
    fun test13_concurrentDuplicateCalls_neverCreateDuplicateVaultRecordsOrCorruptState() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val repo = VaultRepositoryImpl(testApp(), VaultCryptoManager("TEST_PROVIDER"), db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val f = File(tempFolder.newFolder("t13"), "concurrent.mp4").apply { writeBytes("CONCURRENT_SAFETY_VIDEO".toByteArray()) }
        val uri = Uri.fromFile(f)

        val importResults = listOf(
            async { repo.importMediaVideoDetailed(uri, "concurrent") },
            async { repo.importMediaVideoDetailed(uri, "concurrent") }
        ).awaitAll()

        val importedCount = importResults.count { it is VaultImportOutcome.Imported }
        assertEquals(1, importedCount)
        assertEquals(1, db.getAllVideos().size)

        val targetId = db.getAllVideos().first().id
        val restoreResults = listOf(
            async { repo.restoreVaultVideo(targetId) },
            async { repo.restoreVaultVideo(targetId) }
        ).awaitAll()

        assertEquals(1, restoreResults.count { it is VaultRestoreOutcome.Restored })
        assertTrue(db.getAllVideos().isEmpty())
        assertTrue(f.exists())
        db.close()
    }

    // Test 14: Crash recovery for ENCRYPTING / ENCRYPTED_VERIFIED / RESTORING / ORIGINAL_RESTORED_VERIFIED + v3->v4 migration
    @Test
    fun test14_crashRecoveryStatesAndSqlV3ToV4MigrationPreserveIntegrity() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager("TEST_PROVIDER")
        val repo = VaultRepositoryImpl(testApp(), crypto, db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val f = File(tempFolder.newFolder("t14"), "crash_test.mp4").apply { writeBytes("CRASH_RECOVERY_PAYLOAD".toByteArray()) }
        val imp = (repo.importMediaVideoDetailed(Uri.fromFile(f), "crash_test") as VaultImportOutcome.Imported).video
        val enc = repo.resolveEncryptedFile(imp)

        // Scenario A: Crash during RESTORING -> recovery returns entry to ORIGINAL_HIDDEN & preserves .enc
        db.updateVaultState(imp.id, VaultEntryState.RESTORING, isHiddenInLibrary = true)
        repo.refresh()
        assertEquals(VaultEntryState.ORIGINAL_HIDDEN, db.getVideoById(imp.id)!!.vaultState)
        assertTrue(enc.exists())

        // Scenario B: Crash after ORIGINAL_RESTORED_VERIFIED (un-hidden) -> recovery finishes deferred cleanup
        db.updateVaultState(imp.id, VaultEntryState.ORIGINAL_RESTORED_VERIFIED, isHiddenInLibrary = false)
        repo.refresh()
        assertNull(db.getVideoById(imp.id))
        assertFalse(enc.exists())

        // Scenario C: SQL upgrade v3 -> v4 adds all 11 Option-A columns cleanly
        val executedSql = mutableListOf<String>()
        val existingCols = mutableSetOf("id", "display_name", "encrypted_meta_base64")
        val adapter = object : VaultDatabaseHelper.SqlUpgradeAdapter {
            override fun beginTransaction() {}
            override fun setTransactionSuccessful() {}
            override fun endTransaction() {}
            override fun execSQL(sql: String) {
                executedSql.add(sql)
                val addPrefix = "ADD COLUMN "
                val idx = sql.indexOf(addPrefix)
                if (idx >= 0) {
                    existingCols.add(sql.substring(idx + addPrefix.length).trim().substringBefore(' '))
                }
            }
            override fun hasColumn(tableName: String, columnName: String): Boolean = columnName in existingCols
        }
        db.upgradeSchema(adapter, oldVersion = 3, newVersion = 4)
        assertTrue(VaultDatabaseHelper.COLUMN_VAULT_STATE in existingCols)
        assertTrue(VaultDatabaseHelper.COLUMN_ORIGINAL_SHA256 in existingCols)
        assertTrue(VaultDatabaseHelper.COLUMN_IS_HIDDEN_IN_LIBRARY in existingCols)
        db.close()
    }

    // Test 15: "حذف نهائي" blocked when .enc is sole copy; succeeds only when verified intact original exists
    @Test
    fun test15_permanentDeleteBlockedWhenSoleRemainingCopy_andAllowedWhenOriginalVerified() = runTest(testDispatcher) {
        val db = VaultDatabaseHelper(testApp())
        val repo = VaultRepositoryImpl(testApp(), VaultCryptoManager("TEST_PROVIDER"), db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)

        val payload = "PERMANENT_DELETE_GUARD_TEST".toByteArray()
        val f = File(tempFolder.newFolder("t15"), "guarded.mp4").apply { writeBytes(payload) }
        val imp = (repo.importMediaVideoDetailed(Uri.fromFile(f), "guarded") as VaultImportOutcome.Imported).video

        // Delete original externally so .enc is now the SOLE remaining copy
        assertTrue(f.delete())
        val blocked = repo.deleteVaultVideoDetailed(imp.id)
        assertTrue(blocked is VaultDeleteOutcome.BlockedSoleRemainingCopy)
        assertTrue(repo.resolveEncryptedFile(imp).exists())
        assertNotNull(db.getVideoById(imp.id))

        // Restore original bytes -> now original is intact -> permanent delete allowed!
        f.writeBytes(payload)
        val deleted = repo.deleteVaultVideoDetailed(imp.id)
        assertEquals(VaultDeleteOutcome.Deleted, deleted)
        assertFalse(repo.resolveEncryptedFile(imp).exists())
        assertNull(db.getVideoById(imp.id))
        db.close()
    }

    // Test 16: Option-A move & restore never modify Trash Bin, other Playlists, or Library state outside intended entries
    @Test
    fun test16_vaultMoveAndRestore_leaveTrashBinAndUnrelatedPlaylistsCompletelyUnaffected() = runTest(testDispatcher) {
        val vaultDb = VaultDatabaseHelper(testApp())
        val plDb = VideoHatakDatabaseHelper(testApp())
        val trashRepo = TrashRepositoryImpl(plDb)
        val vaultRepo = VaultRepositoryImpl(testApp(), VaultCryptoManager("TEST_PROVIDER"), vaultDb, ioDispatcher = testDispatcher)
        vaultRepo.setUnlocked(true)

        val trashedFile = File(tempFolder.newFolder("t16_trash"), "trashed.mp4").apply { writeBytes("TRASH_UNTOUCHED".toByteArray()) }
        val vaultFile = File(tempFolder.newFolder("t16_vault"), "vaulted.mp4").apply { writeBytes("VAULT_SEPARATE".toByteArray()) }

        val trashedVideo = VideoItem(id = 401L, contentUri = Uri.fromFile(trashedFile), title = "trashed", displayName = "trashed.mp4", path = trashedFile.absolutePath)
        val vaultedVideo = VideoItem(id = 402L, contentUri = Uri.fromFile(vaultFile), title = "vaulted", displayName = "vaulted.mp4", path = vaultFile.absolutePath)

        assertNotNull(trashRepo.moveToTrash(trashedVideo))
        assertEquals(1, trashRepo.trashItems.value.size)

        val imp = (vaultRepo.importMediaVideoDetailed(vaultedVideo.contentUri, vaultedVideo.safeTitle, originalFilePath = vaultedVideo.path) as VaultImportOutcome.Imported).video
        assertEquals("Trash bin MUST remain untouched during Vault hide", 1, trashRepo.trashItems.value.size)

        val restored = vaultRepo.restoreVaultVideo(imp.id)
        assertTrue(restored is VaultRestoreOutcome.Restored)
        assertEquals("Trash bin MUST remain untouched during Vault restore", 1, trashRepo.trashItems.value.size)

        vaultDb.close()
        plDb.close()
    }

    private class InMemoryVideoRepo(
        private val items: List<VideoItem>
    ) : VideoRepository {
        private val flow = MutableStateFlow(items)
        override fun observeVideos(): Flow<List<VideoItem>> = flow.asStateFlow()
        override suspend fun getVideos(): List<VideoItem> = items
        override fun filterAndSort(
            videos: List<VideoItem>,
            query: String,
            sortOrder: VideoSortOrder,
            folderId: String?
        ): List<VideoItem> {
            return videos.filter { v ->
                (folderId == null || v.bucketId == folderId) &&
                    (query.isBlank() || v.safeTitle.contains(query, ignoreCase = true) || v.displayName.contains(query, ignoreCase = true))
            }
        }

        override fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder> {
            return videos.groupBy { it.bucketId }.map { (bucketId, list) ->
                VideoFolder(
                    bucketId = bucketId,
                    displayName = list.first().bucketDisplayName,
                    videoCount = list.size,
                    totalSizeBytes = list.sumOf { it.sizeBytes },
                    firstVideoItem = list.firstOrNull()
                )
            }
        }
    }

    private class TestMediaProvider(private val backingDir: File) : ContentProvider() {
        data class Entry(
            val id: Long,
            var displayName: String,
            var relativePath: String,
            var mimeType: String,
            val file: File
        )
        private val nextId = AtomicLong(1L)
        private val entries = LinkedHashMap<Long, Entry>()
        val deleteCallCount = AtomicInteger(0)
        val updateCallCount = AtomicInteger(0)

        fun rowCount(): Int = entries.size
        fun entryForUri(uri: Uri): Entry? = uri.lastPathSegment?.toLongOrNull()?.let { entries[it] }

        fun seedPublishedEntry(
            displayName: String,
            relativePath: String,
            bytes: ByteArray,
            mimeType: String = "video/mp4"
        ): Uri {
            val id = nextId.getAndIncrement()
            val file = File(backingDir, "ms_$id.bin").apply { writeBytes(bytes) }
            entries[id] = Entry(id, displayName, relativePath, mimeType, file)
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        }

        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cols = projection ?: arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.SIZE,
                @Suppress("DEPRECATION")
                MediaStore.Video.Media.DATA
            )
            val cursor = MatrixCursor(cols)
            val id = uri.lastPathSegment?.toLongOrNull()
            val target = if (id != null) listOfNotNull(entries[id]) else entries.values.toList()
            for (e in target) {
                cursor.addRow(
                    cols.map { col ->
                        when (col) {
                            MediaStore.Video.Media._ID -> e.id
                            MediaStore.Video.Media.DISPLAY_NAME -> e.displayName
                            MediaStore.Video.Media.MIME_TYPE -> e.mimeType
                            MediaStore.Video.Media.RELATIVE_PATH -> e.relativePath
                            MediaStore.Video.Media.SIZE -> e.file.length()
                            @Suppress("DEPRECATION")
                            MediaStore.Video.Media.DATA -> e.file.absolutePath
                            else -> null
                        }
                    }.toTypedArray()
                )
            }
            return cursor
        }

        override fun getType(uri: Uri): String = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updateCallCount.incrementAndGet()
            return 0
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleteCallCount.incrementAndGet()
            return 0
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException()
            val e = entries[id] ?: throw FileNotFoundException()
            if (!e.file.exists()) throw FileNotFoundException()
            return ParcelFileDescriptor.open(e.file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
