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
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.db.VaultDatabaseStore
import com.videohatak.player.core.vault.model.VaultEntryState
import com.videohatak.player.core.vault.model.VaultHiddenOriginal
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.MediaStorePublicMediaExporter
import com.videohatak.player.core.vault.repository.PublicMediaExportResult
import com.videohatak.player.core.vault.repository.PublicMediaExporter
import com.videohatak.player.core.vault.repository.VaultDeleteOutcome
import com.videohatak.player.core.vault.repository.VaultImportOutcome
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.core.vault.repository.VaultRestoreOutcome
import com.videohatak.player.feature.vault.VaultRestoreState
import com.videohatak.player.feature.vault.VaultUiEvent
import com.videohatak.player.feature.vault.VaultViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VaultRestoreVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()

    private fun testApp(): Application = RuntimeEnvironment.getApplication()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun clearVaultStorage() {
        File(testApp().filesDir, "vault_encrypted").deleteRecursively()
        File(testApp().cacheDir, "vault_restore_tmp").deleteRecursively()
        val vaultDb = VaultDatabaseHelper(testApp())
        vaultDb.getAllVideos().forEach { vaultDb.deleteVideo(it.id) }
        vaultDb.close()
    }

    private fun registerMediaStoreProvider(backingDir: File): FakeMediaStoreVideoProvider {
        val provider = FakeMediaStoreVideoProvider(backingDir)
        val info = ProviderInfo().apply {
            authority = MediaStore.AUTHORITY
            exported = true
        }
        provider.attachInfo(testApp(), info)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        return provider
    }

    private fun buildRepository(
        crypto: VaultCryptoManager = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER"),
        dbStore: VaultDatabaseStore = VaultDatabaseHelper(testApp()),
        publicMediaExporter: PublicMediaExporter? = null,
        uriPermissionChecker: ((Uri) -> Boolean)? = null
    ): VaultRepositoryImpl {
        return VaultRepositoryImpl(
            context = testApp(),
            cryptoManager = crypto,
            dbHelper = dbStore,
            contentSha256Computer = null,
            ioDispatcher = testDispatcher,
            publicMediaExporter = publicMediaExporter,
            uriPermissionChecker = uriPermissionChecker
        )
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
    fun restoreVaultVideo_verifiesIntactOriginalAtSameUri_unhidesInPlaceWithoutDuplicate_andDeletesVaultAfterVerify() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_success"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val exportCallCounter = AtomicInteger(0)
        val trackingExporter = object : PublicMediaExporter {
            override suspend fun exportEncryptedVideo(
                video: VaultVideo,
                encryptedFile: File,
                displayName: String,
                relativePath: String,
                mimeType: String,
                expectedContentSha256: String?,
                expectedSizeBytes: Long
            ): PublicMediaExportResult {
                exportCallCounter.incrementAndGet()
                return PublicMediaExportResult.Failed("Option A must never invoke PublicMediaExporter")
            }
        }
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper, publicMediaExporter = trackingExporter)
        repo.setUnlocked(true)

        val payload = ByteArray(180_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val expectedSha = sha256Hex(payload)
        val sourceUri = mediaProvider.seedPublishedEntry(
            displayName = "trip_clip.mkv",
            relativePath = "${Environment.DIRECTORY_MOVIES}/Trips/",
            bytes = payload,
            mimeType = "video/x-matroska"
        )

        val importOutcome = repo.importMediaVideoDetailed(
            sourceUri = sourceUri,
            displayName = "رحلة الجبل",
            originalFileName = "trip_clip.mkv",
            originalRelativePath = "${Environment.DIRECTORY_MOVIES}/Trips/"
        )
        assertTrue(importOutcome is VaultImportOutcome.Imported)
        val imported = (importOutcome as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)
        assertTrue(encFile.exists() && encFile.length() > 0L)
        assertEquals(VaultEntryState.ORIGINAL_HIDDEN, imported.vaultState)
        assertTrue(imported.isHiddenInLibrary)
        assertEquals(1, repo.hiddenOriginalEntries.value.size)

        // Original stays completely untouched on device!
        assertEquals(1, mediaProvider.rowCount())
        assertArrayEquals(payload, mediaProvider.entryForUri(sourceUri)!!.file.readBytes())

        // Perform "إزالة من الخزنة" -> Option A verifies original & un-hides in place WITHOUT exporter/copy
        val restoreOutcome = repo.restoreVaultVideo(imported.id)
        assertTrue("Expected Restored outcome but got $restoreOutcome", restoreOutcome is VaultRestoreOutcome.Restored)
        val restored = restoreOutcome as VaultRestoreOutcome.Restored
        assertEquals(sourceUri, restored.restoredUri)
        assertEquals(payload.size.toLong(), restored.verifiedSizeBytes)
        assertEquals(expectedSha, restored.verifiedSha256)
        assertEquals("PublicMediaExporter must never be invoked under Option A", 0, exportCallCounter.get())
        assertEquals("Original row must remain single without creating duplicates", 1, mediaProvider.rowCount())

        // Verify hidden flag & Vault encrypted copy and DB row were removed ONLY after verification + un-hide
        assertTrue(repo.hiddenOriginalEntries.value.isEmpty())
        assertFalse(encFile.exists())
        assertNull(dbHelper.getVideoById(imported.id))
        assertTrue(repo.vaultVideos.value.isEmpty())
        assertEquals(0, repo.vaultCount.value)
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_whenMetadataDecryptionFails_preservesEncryptedFileDbAndHiddenState() = runTest(testDispatcher) {
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val payload = "VAULT_TAMPER_PROTECTED_BYTES_0123456789".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_tamper"), "secret.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "secret") as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)

        // Corrupt encrypted metadata JSON payload
        val raw = dbHelper.getVideoById(imported.id)!!
        dbHelper.updateEncryptedMetadata(
            id = imported.id,
            maskedDisplayName = raw.displayName,
            internalPath = encFile.name,
            maskedMimeType = raw.mimeType,
            encryptedMetaBase64 = "corrupted_invalid_base64_meta"
        )

        val outcome = repo.restoreVaultVideo(imported.id)
        assertTrue(outcome is VaultRestoreOutcome.Failed)
        assertTrue((outcome as VaultRestoreOutcome.Failed).vaultEntryPreserved)

        // Encrypted file, DB row, and hidden entry remain intact; original untouched
        assertTrue(encFile.exists() && encFile.length() > 0L)
        assertNotNull(dbHelper.getVideoById(imported.id))
        assertTrue(sourceFile.exists() && sourceFile.readBytes().contentEquals(payload))
        assertEquals(1, repo.hiddenOriginalEntries.value.size)
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_whenOriginalContentChangedOrSha256Mismatches_preservesVaultAndKeepsHidden() = runTest(testDispatcher) {
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val payload = "SHA_MISMATCH_VERIFICATION_PAYLOAD_ABC".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_sha"), "clip.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "clip") as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)

        // Alter original bytes externally -> same size, different bytes (SHA-256 mismatch)
        val alteredPayload = "SHA_MISMATCH_VERIFICATION_PAYLOAD_XYZ".toByteArray()
        sourceFile.writeBytes(alteredPayload)

        val outcome = repo.restoreVaultVideo(imported.id)
        assertTrue("Expected OriginalChanged but got $outcome", outcome is VaultRestoreOutcome.OriginalChanged)

        assertTrue(encFile.exists() && encFile.length() > 0L)
        val persisted = dbHelper.getVideoById(imported.id)!!
        assertEquals(VaultEntryState.ORIGINAL_CHANGED, persisted.vaultState)
        assertTrue(persisted.isHiddenInLibrary)
        assertEquals(1, repo.hiddenOriginalEntries.value.size)
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_whenOriginalMissingOrPermissionLost_preservesVaultIntact() = runTest(testDispatcher) {
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        var allowPermission = true
        val repo = buildRepository(
            crypto = crypto,
            dbStore = dbHelper,
            uriPermissionChecker = { allowPermission }
        )
        repo.setUnlocked(true)

        val payload = "MISSING_OR_PERMISSION_TEST_VIDEO_BYTES".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_missing"), "holiday.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "holiday") as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)

        // Case A: Permission lost -> RequiresUriPermission
        allowPermission = false
        val permFailOutcome = repo.restoreVaultVideo(imported.id)
        assertTrue(permFailOutcome is VaultRestoreOutcome.RequiresUriPermission)
        assertTrue(encFile.exists())
        assertEquals(VaultEntryState.REQUIRES_PERMISSION, dbHelper.getVideoById(imported.id)!!.vaultState)

        // Case B: Permission restored, but original file deleted externally -> OriginalMissing
        allowPermission = true
        assertTrue(sourceFile.delete())
        val missingOutcome = repo.restoreVaultVideo(imported.id)
        assertTrue(missingOutcome is VaultRestoreOutcome.OriginalMissing)
        assertTrue(encFile.exists())
        assertEquals(VaultEntryState.ORIGINAL_MISSING, dbHelper.getVideoById(imported.id)!!.vaultState)

        // Vault encrypted payload remains intact & decryptable
        val verifiedSha = crypto.verifyEncryptedFileIntegrityAndHash(encFile, sha256Hex(payload))
        assertEquals(sha256Hex(payload), verifiedSha)
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_whenDbUnhideOrDeleteFails_rollsBackSafelyAndPreservesVault() = runTest(testDispatcher) {
        val realDb = VaultDatabaseHelper(testApp())
        var failUpdateState = true
        var failDelete = false
        val flakeyDb = object : VaultDatabaseStore by realDb {
            override fun updateVaultState(
                id: Long,
                vaultState: VaultEntryState,
                isHiddenInLibrary: Boolean,
                stateUpdatedAtMs: Long,
                lastError: String?
            ): Int {
                if (failUpdateState && vaultState == VaultEntryState.ORIGINAL_RESTORED_VERIFIED) {
                    return 0
                }
                return realDb.updateVaultState(id, vaultState, isHiddenInLibrary, stateUpdatedAtMs, lastError)
            }

            override fun deleteVideo(id: Long): Int {
                if (failDelete) return 0
                return realDb.deleteVideo(id)
            }
        }
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = flakeyDb)
        repo.setUnlocked(true)

        val payload = "POST_VERIFY_ROLLBACK_BYTES_0123456789".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_rb"), "rollback.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "rollback") as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)

        // Case A: updateVaultState to ORIGINAL_RESTORED_VERIFIED fails -> .enc & DB row remain intact
        val stateFail = repo.restoreVaultVideo(imported.id)
        assertTrue(stateFail is VaultRestoreOutcome.Failed)
        assertTrue(encFile.exists())
        assertNotNull(realDb.getVideoById(imported.id))

        // Case B: deleteVideo fails after .enc staged -> .enc restored from .pending_delete & DB row stays
        failUpdateState = false
        failDelete = true
        val deleteFail = repo.restoreVaultVideo(imported.id)
        assertTrue(deleteFail is VaultRestoreOutcome.Failed)
        assertTrue("Encrypted file must be rolled back if DB deleteVideo fails", encFile.exists())
        assertNotNull(realDb.getVideoById(imported.id))
        realDb.close()
    }

    @Test
    fun restoreVaultVideo_retryAfterInterruptedStaging_neverDuplicatesPublicMedia() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_dedup"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val payload = "IDENTICAL_RETRY_CONTENT_NO_DUPLICATE".toByteArray()
        val sourceUri = mediaProvider.seedPublishedEntry(
            displayName = "meeting.mp4",
            relativePath = "${Environment.DIRECTORY_MOVIES}/",
            bytes = payload
        )
        val imported = (repo.importMediaVideoDetailed(sourceUri, "meeting") as VaultImportOutcome.Imported).video
        assertEquals(1, mediaProvider.rowCount())

        val outcome1 = repo.restoreVaultVideo(imported.id)
        assertTrue(outcome1 is VaultRestoreOutcome.Restored)
        assertEquals(sourceUri, (outcome1 as VaultRestoreOutcome.Restored).restoredUri)
        assertEquals("Must never create a second copy in MediaStore", 1, mediaProvider.rowCount())
        assertFalse(repo.resolveEncryptedFile(imported).exists())
        assertNull(dbHelper.getVideoById(imported.id))

        // Re-attempting restore after already removed returns clean Failed, never creating duplicates
        val outcome2 = repo.restoreVaultVideo(imported.id)
        assertTrue(outcome2 is VaultRestoreOutcome.Failed)
        assertEquals(1, mediaProvider.rowCount())
        dbHelper.close()
    }

    @Test
    fun publicMediaExporter_normalizesFolderRelativePathAndPreservesExtension() {
        assertEquals(
            "Movies/Trips/",
            MediaStorePublicMediaExporter.normalizeMediaStoreVideoRelativePath("Movies/Trips/")
        )
        assertEquals(
            "DCIM/Camera/",
            MediaStorePublicMediaExporter.normalizeMediaStoreVideoRelativePath("DCIM/Camera")
        )
        assertEquals(
            "Movies/",
            MediaStorePublicMediaExporter.normalizeMediaStoreVideoRelativePath("Downloads/Private")
        )
        assertEquals(
            "Movies/",
            MediaStorePublicMediaExporter.normalizeMediaStoreVideoRelativePath("vault")
        )

        val (nameMkv, mimeMkv, relMkv) = MediaStorePublicMediaExporter.resolveMetadataForExport(
            rawDisplayName = "concert_2025.mkv",
            rawTitle = "حفل",
            rawMimeType = "video/x-matroska",
            rawRelativePath = "Movies/Concerts/"
        )
        assertEquals("concert_2025.mkv", nameMkv)
        assertEquals("video/x-matroska", mimeMkv)
        assertEquals("Movies/Concerts/", relMkv)

        val (nameFallback, mimeFallback, relFallback) = MediaStorePublicMediaExporter.resolveMetadataForExport(
            rawDisplayName = "clip_without_ext",
            rawTitle = "clip_without_ext",
            rawMimeType = "video/webm",
            rawRelativePath = "SomeIllegalRoot/Sub"
        )
        assertEquals("clip_without_ext.webm", nameFallback)
        assertEquals("video/webm", mimeFallback)
        assertEquals("Movies/", relFallback)
    }

    @Test
    fun vaultViewModel_restoreSuccessAndFailure_andPermanentDeleteSoleCopyGuard() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_vm"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val vm = VaultViewModel(
            repository = repo,
            biometricHelper = VaultBiometricHelper(testApp())
        )
        val events = mutableListOf<VaultUiEvent>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.events.collect { events.add(it) }
        }

        val payload1 = "VIEWMODEL_RESTORE_ONE_PAYLOAD".toByteArray()
        val payload2 = "VIEWMODEL_PERMANENT_DELETE_PAYLOAD".toByteArray()
        val uri1 = mediaProvider.seedPublishedEntry("one.mp4", "${Environment.DIRECTORY_MOVIES}/", payload1)
        val f2 = File(tempFolder.newFolder("vm_src2"), "two.mp4").apply { writeBytes(payload2) }

        val v1 = (repo.importMediaVideoDetailed(uri1, "one.mp4") as VaultImportOutcome.Imported).video
        val v2 = (repo.importMediaVideoDetailed(Uri.fromFile(f2), "two.mp4") as VaultImportOutcome.Imported).video
        advanceUntilIdle()
        assertEquals(2, vm.vaultVideos.value.size)

        // Case 1: Original temporarily unavailable -> restore fails cleanly, keeps v1 in Vault, no LibraryMediaUpdated
        mediaProvider.failOpenInputForIds.add(ContentUris.parseId(uri1))
        vm.restoreVideoFromVault(v1.id)
        advanceUntilIdle()
        assertEquals(VaultRestoreState.Idle, vm.restoreState.value)
        assertEquals(2, vm.vaultVideos.value.size)
        assertFalse(events.any { it is VaultUiEvent.LibraryMediaUpdated })
        assertNotNull(vm.vaultError.value)

        // Case 2: Original readable again -> restore succeeds in-place without duplicating MediaStore rows
        mediaProvider.failOpenInputForIds.clear()
        events.clear()
        vm.restoreVideoFromVault(v1.id)
        advanceUntilIdle()
        assertEquals(1, vm.vaultVideos.value.size)
        assertEquals(v2.id, vm.vaultVideos.value.first().id)
        assertTrue(events.any { it is VaultUiEvent.LibraryMediaUpdated })
        assertTrue(
            events.filterIsInstance<VaultUiEvent.ShowMessage>().any {
                it.message.contains("استعادته إلى المكتبة")
            }
        )
        assertEquals(1, mediaProvider.rowCount())

        // Case 3: Permanent delete ("حذف نهائي") blocked if original deleted externally (.enc is sole copy!)
        assertTrue(f2.delete())
        vm.deleteVideo(v2.id)
        advanceUntilIdle()
        assertEquals("Must block permanent delete when .enc is sole copy", 1, vm.vaultVideos.value.size)
        assertNotNull(vm.vaultError.value)

        // Restore f2 intact -> now permanent delete succeeds cleanly without duplicating MediaStore
        f2.writeBytes(payload2)
        vm.deleteVideo(v2.id)
        advanceUntilIdle()
        assertTrue(vm.vaultVideos.value.isEmpty())
        assertEquals("Permanent delete must NOT insert into MediaStore", 1, mediaProvider.rowCount())

        collectJob.cancel()
        dbHelper.close()
    }

    @Test
    fun moveToVaultOptionA_preservesOriginalOnDbFailure_andReShowsSameOriginalAfterInVaultRename() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_move_preserve"))
        val realDb = VaultDatabaseHelper(testApp())
        var failNextDbInsert = true
        val flakeyDb = object : VaultDatabaseStore by realDb {
            override fun insertVideo(video: VaultVideo): Long {
                if (failNextDbInsert) return -1L
                return realDb.insertVideo(video)
            }
        }
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = flakeyDb)
        repo.setUnlocked(true)

        val payload = "CAMERA_RECORDING_IMPORTANT_CLIP_XYZ".toByteArray()
        val srcUri = mediaProvider.seedPublishedEntry(
            displayName = "VID_20250401.mkv",
            relativePath = "${Environment.DIRECTORY_DCIM}/Camera/",
            bytes = payload,
            mimeType = "video/x-matroska"
        )

        // 1. DB commit failure during move-to-vault MUST fail import, leave original untouched & unhidden
        val failedImport = repo.importMediaVideoDetailed(
            sourceUri = srcUri,
            displayName = "فيديو مهم",
            originalFileName = "VID_20250401.mkv",
            originalRelativePath = "${Environment.DIRECTORY_DCIM}/Camera/"
        )
        assertTrue(failedImport is VaultImportOutcome.Failed)
        assertArrayEquals(payload, mediaProvider.entryForUri(srcUri)!!.file.readBytes())
        assertTrue(realDb.getAllVideos().isEmpty())
        assertTrue(repo.hiddenOriginalEntries.value.isEmpty())

        // 2. Successful move-to-vault followed by in-vault rename & later Option-A restore
        failNextDbInsert = false
        val imported = (
            repo.importMediaVideoDetailed(
                sourceUri = srcUri,
                displayName = "فيديو مهم",
                originalFileName = "VID_20250401.mkv",
                originalRelativePath = "${Environment.DIRECTORY_DCIM}/Camera/"
            ) as VaultImportOutcome.Imported
            ).video

        assertTrue(repo.renameVideo(imported.id, "ذكرى عائلية"))
        val restoreOutcome = repo.restoreVaultVideo(imported.id)
        assertTrue(restoreOutcome is VaultRestoreOutcome.Restored)
        val restored = restoreOutcome as VaultRestoreOutcome.Restored
        assertEquals(srcUri, restored.restoredUri)
        assertEquals(1, mediaProvider.rowCount())
        val entry = mediaProvider.entryForUri(srcUri)!!
        assertEquals("VID_20250401.mkv", entry.displayName)
        assertEquals("${Environment.DIRECTORY_DCIM}/Camera/", entry.relativePath)
        assertArrayEquals(payload, entry.file.readBytes())
        assertTrue(repo.hiddenOriginalEntries.value.isEmpty())
        realDb.close()
    }

    private class FakeMediaStoreVideoProvider(
        private val backingDir: File
    ) : ContentProvider() {

        data class Entry(
            val id: Long,
            var displayName: String,
            var title: String,
            var mimeType: String,
            var relativePath: String,
            var sizeBytes: Long,
            var isPending: Int,
            val file: File
        )

        private val nextId = AtomicLong(1L)
        private val entries = LinkedHashMap<Long, Entry>()

        val failOpenInputForIds = HashSet<Long>()

        fun rowCount(): Int = entries.size

        fun entryForUri(uri: Uri): Entry? {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return null
            return entries[id]
        }

        fun seedPublishedEntry(
            displayName: String,
            relativePath: String,
            bytes: ByteArray,
            mimeType: String = "video/mp4"
        ): Uri {
            val id = nextId.getAndIncrement()
            val file = File(backingDir, "ms_$id.bin").apply { writeBytes(bytes) }
            entries[id] = Entry(
                id = id,
                displayName = displayName,
                title = displayName.substringBeforeLast('.'),
                mimeType = mimeType,
                relativePath = relativePath,
                sizeBytes = bytes.size.toLong(),
                isPending = 0,
                file = file
            )
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
            val defaultCols = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.IS_PENDING,
                @Suppress("DEPRECATION")
                MediaStore.Video.Media.DATA
            )
            val cols = projection ?: defaultCols
            val cursor = MatrixCursor(cols)
            val targetId = uri.lastPathSegment?.toLongOrNull()
            val matching = if (targetId != null) {
                listOfNotNull(entries[targetId])
            } else {
                entries.values.toList()
            }
            for (entry in matching) {
                val actualSize = if (entry.file.exists() && entry.file.length() > 0L) {
                    entry.file.length()
                } else {
                    entry.sizeBytes
                }
                val row = cols.map { col ->
                    when (col) {
                        MediaStore.Video.Media._ID -> entry.id
                        MediaStore.Video.Media.DISPLAY_NAME -> entry.displayName
                        MediaStore.Video.Media.TITLE -> entry.title
                        MediaStore.Video.Media.MIME_TYPE -> entry.mimeType
                        MediaStore.Video.Media.RELATIVE_PATH -> entry.relativePath
                        MediaStore.Video.Media.SIZE -> actualSize
                        MediaStore.Video.Media.IS_PENDING -> entry.isPending
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Media.DATA -> entry.file.absolutePath
                        else -> null
                    }
                }.toTypedArray()
                cursor.addRow(row)
            }
            return cursor
        }

        override fun getType(uri: Uri): String = "video/mp4"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("Invalid URI $uri")
            val entry = entries[id] ?: throw FileNotFoundException("Missing entry $uri")
            if (id in failOpenInputForIds) {
                throw FileNotFoundException("Simulated missing or inaccessible entry $uri")
            }
            if (!entry.file.exists()) {
                throw FileNotFoundException("Media file missing")
            }
            return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
