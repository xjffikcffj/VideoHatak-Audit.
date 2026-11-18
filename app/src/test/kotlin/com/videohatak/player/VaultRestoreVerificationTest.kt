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
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.MediaStorePublicMediaExporter
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
import java.io.RandomAccessFile
import java.security.MessageDigest
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
        sdkIntProvider: () -> Int = { 34 }
    ): VaultRepositoryImpl {
        val exporter = MediaStorePublicMediaExporter(
            context = testApp(),
            contentResolver = testApp().contentResolver,
            cryptoManager = crypto,
            sdkIntProvider = sdkIntProvider,
            legacyPublicRootProvider = { type ->
                File(tempFolder.root, "legacy_public_$type").apply { mkdirs() }
            }
        )
        return VaultRepositoryImpl(
            context = testApp(),
            cryptoManager = crypto,
            dbHelper = dbStore,
            ioDispatcher = testDispatcher,
            publicMediaExporter = exporter
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
    fun restoreVaultVideo_decryptsVerifiesPublishesToMediaStore_andDeletesVaultOnlyAfterVerification() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_success"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        // Multi-chunk realistic payload (> 2 chunks of 64 KB)
        val payload = ByteArray(180_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val expectedSha = sha256Hex(payload)
        val sourceFile = File(tempFolder.newFolder("src"), "trip_clip.mkv").apply { writeBytes(payload) }

        val importOutcome = repo.importMediaVideoDetailed(
            sourceUri = Uri.fromFile(sourceFile),
            displayName = "رحلة الجبل",
            originalFileName = "trip_clip.mkv",
            originalRelativePath = "${Environment.DIRECTORY_MOVIES}/Trips/"
        )
        assertTrue(importOutcome is VaultImportOutcome.Imported)
        val imported = (importOutcome as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)
        assertTrue(encFile.exists() && encFile.length() > 0L)

        // Perform default action "إزالة من الخزنة" -> restoreVaultVideo
        val restoreOutcome = repo.restoreVaultVideo(imported.id)
        assertTrue("Expected Restored outcome but got $restoreOutcome", restoreOutcome is VaultRestoreOutcome.Restored)
        val restored = restoreOutcome as VaultRestoreOutcome.Restored
        assertEquals(payload.size.toLong(), restored.verifiedSizeBytes)
        assertEquals(expectedSha, restored.verifiedSha256)

        // Verify MediaStore row is published (IS_PENDING == 0) with exact original filename, ext & folder
        val entry = mediaProvider.entryForUri(restored.restoredUri)
        assertNotNull(entry)
        assertEquals(0, entry!!.isPending)
        assertEquals("trip_clip.mkv", entry.displayName)
        assertEquals("${Environment.DIRECTORY_MOVIES}/Trips/", entry.relativePath)
        assertEquals(payload.size.toLong(), entry.file.length())
        assertArrayEquals(payload, entry.file.readBytes())

        // Verify Vault encrypted file and DB row were removed ONLY after verified publish
        assertFalse(encFile.exists())
        assertNull(dbHelper.getVideoById(imported.id))
        assertTrue(repo.vaultVideos.value.isEmpty())
        assertEquals(0, repo.vaultCount.value)
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_whenDecryptionFails_preservesEncryptedFileAndDbRecord() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_dec_fail"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val payload = "VAULT_TAMPER_PROTECTED_BYTES_0123456789".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_tamper"), "secret.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "secret") as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)

        // Tamper the encrypted payload near the end of the file so AEAD GCM tag check fails
        RandomAccessFile(encFile, "rw").use { raf ->
            val targetPos = (raf.length() - 4L).coerceAtLeast(0L)
            raf.seek(targetPos)
            val orig = raf.read()
            raf.seek(targetPos)
            raf.write(orig xor 0xFF)
        }

        val outcome = repo.restoreVaultVideo(imported.id)
        assertTrue(outcome is VaultRestoreOutcome.Failed)
        assertTrue((outcome as VaultRestoreOutcome.Failed).vaultEntryPreserved)

        // Encrypted file and DB row MUST remain intact; MediaStore MUST stay clean
        assertTrue(encFile.exists() && encFile.length() > 0L)
        assertNotNull(dbHelper.getVideoById(imported.id))
        assertEquals(1, repo.vaultVideos.value.size)
        assertEquals(0, mediaProvider.rowCount())
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_whenDecryptedSha256Mismatches_preservesVaultAndLeavesNoPublicFile() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_sha_fail"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val payload = "SHA_MISMATCH_VERIFICATION_PAYLOAD_ABC".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_sha"), "clip.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "clip") as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)

        // Corrupt the expected contentSha256 in the DB encrypted metadata to force post-decrypt hash mismatch
        val row = dbHelper.getVideoById(imported.id)!!
        val json = org.json.JSONObject(crypto.decryptMetadata(row.encryptedMetaBase64!!))
        json.put("contentSha256", "0".repeat(64))
        val updatedMeta = crypto.encryptMetadata(json.toString())
        dbHelper.updateEncryptedMetadata(
            id = imported.id,
            maskedDisplayName = row.displayName,
            internalPath = encFile.name,
            maskedMimeType = row.mimeType,
            encryptedMetaBase64 = updatedMeta
        )

        val outcome = repo.restoreVaultVideo(imported.id)
        assertTrue(outcome is VaultRestoreOutcome.Failed)
        assertTrue((outcome as VaultRestoreOutcome.Failed).vaultEntryPreserved)

        assertTrue(encFile.exists() && encFile.length() > 0L)
        assertNotNull(dbHelper.getVideoById(imported.id))
        assertEquals(0, mediaProvider.rowCount())
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_whenMediaStoreInsertOrOutputStreamFails_preservesVaultIntact() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_write_fail"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val payload = "MEDIASTORE_WRITE_FAILURE_TEST_VIDEO_BYTES".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_ms_fail"), "holiday.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "holiday") as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)

        // Case A: MediaStore insert returns null
        mediaProvider.failInsert = true
        val insertFailOutcome = repo.restoreVaultVideo(imported.id)
        assertTrue(insertFailOutcome is VaultRestoreOutcome.Failed)
        assertTrue(encFile.exists())
        assertNotNull(dbHelper.getVideoById(imported.id))
        assertEquals(0, mediaProvider.rowCount())

        // Case B: MediaStore insert succeeds, openOutputStream returns null / throws -> partial row deleted
        mediaProvider.failInsert = false
        mediaProvider.failOpenOutputStream = true
        val streamFailOutcome = repo.restoreVaultVideo(imported.id)
        assertTrue(streamFailOutcome is VaultRestoreOutcome.Failed)
        assertTrue(encFile.exists())
        assertNotNull(dbHelper.getVideoById(imported.id))
        assertEquals(0, mediaProvider.rowCount())

        // Still decryptable cleanly after failures
        val verifiedSha = crypto.verifyEncryptedFileIntegrityAndHash(encFile, sha256Hex(payload))
        assertEquals(sha256Hex(payload), verifiedSha)
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_whenPublishOrPostVerifyFails_rollsBackPublicMediaStoreAndPreservesVault() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_publish_fail"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val payload = "POST_WRITE_ROLLBACK_BYTES_0123456789".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_pub_fail"), "rollback.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "rollback") as VaultImportOutcome.Imported).video
        val encFile = repo.resolveEncryptedFile(imported)

        // Case A: IS_PENDING = 0 publish update fails (returns 0 rows)
        mediaProvider.failPendingPublishUpdate = true
        val publishFail = repo.restoreVaultVideo(imported.id)
        assertTrue(publishFail is VaultRestoreOutcome.Failed)
        assertEquals(0, mediaProvider.rowCount())
        assertTrue(encFile.exists())
        assertNotNull(dbHelper.getVideoById(imported.id))
        mediaProvider.failPendingPublishUpdate = false

        // Case B: Post-publish URI cannot be opened for readback verification
        mediaProvider.failPostPublishOpenInput = true
        val openFail = repo.restoreVaultVideo(imported.id)
        assertTrue(openFail is VaultRestoreOutcome.Failed)
        assertEquals(0, mediaProvider.rowCount())
        assertTrue(encFile.exists())
        assertNotNull(dbHelper.getVideoById(imported.id))
        mediaProvider.failPostPublishOpenInput = false

        // Case C: Restored MediaStore stream corrupted vs expected SHA-256
        mediaProvider.corruptBytesOnRead = true
        val corruptFail = repo.restoreVaultVideo(imported.id)
        assertTrue(corruptFail is VaultRestoreOutcome.Failed)
        assertEquals(0, mediaProvider.rowCount())
        assertTrue(encFile.exists())
        assertNotNull(dbHelper.getVideoById(imported.id))
        dbHelper.close()
    }

    @Test
    fun restoreVaultVideo_retryOrPreExistingIdenticalMediaStoreEntry_neverCreatesDuplicates() = runTest(testDispatcher) {
        val mediaProvider = registerMediaStoreProvider(tempFolder.newFolder("ms_dedup"))
        val dbHelper = VaultDatabaseHelper(testApp())
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = buildRepository(crypto = crypto, dbStore = dbHelper)
        repo.setUnlocked(true)

        val payload = "IDENTICAL_RETRY_CONTENT_NO_DUPLICATE".toByteArray()
        val sourceFile = File(tempFolder.newFolder("src_dedup"), "meeting.mp4").apply { writeBytes(payload) }
        val imported = (repo.importMediaVideoDetailed(Uri.fromFile(sourceFile), "meeting") as VaultImportOutcome.Imported).video

        // Seed an already-published identical entry (e.g. from a prior partial crash after export)
        val existingUri = mediaProvider.seedPublishedEntry(
            displayName = "meeting.mp4",
            relativePath = "${Environment.DIRECTORY_MOVIES}/",
            bytes = payload
        )
        assertEquals(1, mediaProvider.rowCount())

        val outcome = repo.restoreVaultVideo(imported.id)
        assertTrue(outcome is VaultRestoreOutcome.Restored)
        assertEquals(existingUri, (outcome as VaultRestoreOutcome.Restored).restoredUri)
        assertEquals("Should reuse verified existing copy and not create duplicate", 1, mediaProvider.rowCount())
        assertFalse(repo.resolveEncryptedFile(imported).exists())
        assertNull(dbHelper.getVideoById(imported.id))

        // Unrelated video with SAME filename but DIFFERENT content must generate non-colliding name
        val otherPayload = "DIFFERENT_VIDEO_SAME_NAME_CONTENT_XYZ".toByteArray()
        val otherFile = File(tempFolder.newFolder("src_collision"), "meeting.mp4").apply { writeBytes(otherPayload) }
        val imported2 = (repo.importMediaVideoDetailed(Uri.fromFile(otherFile), "meeting") as VaultImportOutcome.Imported).video
        val outcome2 = repo.restoreVaultVideo(imported2.id)
        assertTrue(outcome2 is VaultRestoreOutcome.Restored)
        assertEquals(2, mediaProvider.rowCount())
        val restored2 = mediaProvider.entryForUri((outcome2 as VaultRestoreOutcome.Restored).restoredUri)!!
        assertEquals("meeting (1).mp4", restored2.displayName)
        assertArrayEquals(otherPayload, restored2.file.readBytes())
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
    fun vaultViewModel_restoreSuccessAndFailure_andPermanentDeleteRemainsDistinct() = runTest(testDispatcher) {
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
        val f1 = File(tempFolder.newFolder("vm_src1"), "one.mp4").apply { writeBytes(payload1) }
        val f2 = File(tempFolder.newFolder("vm_src2"), "two.mp4").apply { writeBytes(payload2) }

        val v1 = (repo.importMediaVideoDetailed(Uri.fromFile(f1), "one.mp4") as VaultImportOutcome.Imported).video
        val v2 = (repo.importMediaVideoDetailed(Uri.fromFile(f2), "two.mp4") as VaultImportOutcome.Imported).video
        advanceUntilIdle()
        assertEquals(2, vm.vaultVideos.value.size)

        // Case 1: Restore fails -> item stays in VaultViewModel.vaultVideos, no LibraryMediaUpdated emitted
        mediaProvider.failInsert = true
        vm.restoreVideoFromVault(v1.id)
        advanceUntilIdle()
        assertEquals(VaultRestoreState.Idle, vm.restoreState.value)
        assertEquals(2, vm.vaultVideos.value.size)
        assertFalse(events.any { it is VaultUiEvent.LibraryMediaUpdated })
        assertNotNull(vm.vaultError.value)

        // Case 2: Restore succeeds -> item restored to MediaStore, removed from vaultVideos, LibraryMediaUpdated emitted
        mediaProvider.failInsert = false
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

        // Case 3: Permanent delete ("حذف نهائي") removes v2 from Vault WITHOUT adding anything to MediaStore
        vm.deleteVideo(v2.id)
        advanceUntilIdle()
        assertTrue(vm.vaultVideos.value.isEmpty())
        assertEquals("Permanent delete must NOT export to MediaStore", 1, mediaProvider.rowCount())

        collectJob.cancel()
        dbHelper.close()
    }

    @Test
    fun moveToVaultWipeMode_preservesOriginalOnDbFailure_andPreservesOriginalNameAndFolderAcrossRenameAndRestore() = runTest(testDispatcher) {
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
        val srcFile = File(tempFolder.newFolder("src_cam"), "VID_20250401.mkv").apply { writeBytes(payload) }

        // 1. DB commit failure during move-to-vault MUST fail import and leave original untouched
        val failedImport = repo.importMediaVideoDetailed(
            sourceUri = Uri.fromFile(srcFile),
            displayName = "فيديو مهم",
            originalFileName = "VID_20250401.mkv",
            originalRelativePath = "${Environment.DIRECTORY_DCIM}/Camera/"
        )
        assertTrue(failedImport is VaultImportOutcome.Failed)
        assertTrue(srcFile.exists() && srcFile.readBytes().contentEquals(payload))
        assertTrue(realDb.getAllVideos().isEmpty())

        // 2. Successful move-to-vault followed by in-vault rename & later restore
        failNextDbInsert = false
        val imported = (
            repo.importMediaVideoDetailed(
                sourceUri = Uri.fromFile(srcFile),
                displayName = "فيديو مهم",
                originalFileName = "VID_20250401.mkv",
                originalRelativePath = "${Environment.DIRECTORY_DCIM}/Camera/"
            ) as VaultImportOutcome.Imported
            ).video

        assertTrue(repo.renameVideo(imported.id, "ذكرى عائلية"))
        val restoreOutcome = repo.restoreVaultVideo(imported.id)
        assertTrue(restoreOutcome is VaultRestoreOutcome.Restored)
        val restored = mediaProvider.entryForUri((restoreOutcome as VaultRestoreOutcome.Restored).restoredUri)!!
        assertEquals("ذكرى عائلية.mkv", restored.displayName)
        assertEquals("${Environment.DIRECTORY_DCIM}/Camera/", restored.relativePath)
        assertArrayEquals(payload, restored.file.readBytes())
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

        var failInsert: Boolean = false
        var failOpenOutputStream: Boolean = false
        var failPendingPublishUpdate: Boolean = false
        var failPostPublishOpenInput: Boolean = false
        var corruptBytesOnRead: Boolean = false

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

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (failInsert) return null
            val id = nextId.getAndIncrement()
            val v = values ?: ContentValues()
            val file = File(backingDir, "ms_$id.bin")
            entries[id] = Entry(
                id = id,
                displayName = v.getAsString(MediaStore.Video.Media.DISPLAY_NAME) ?: "video_$id.mp4",
                title = v.getAsString(MediaStore.Video.Media.TITLE) ?: "video_$id",
                mimeType = v.getAsString(MediaStore.Video.Media.MIME_TYPE) ?: "video/mp4",
                relativePath = v.getAsString(MediaStore.Video.Media.RELATIVE_PATH) ?: "${Environment.DIRECTORY_MOVIES}/",
                sizeBytes = v.getAsLong(MediaStore.Video.Media.SIZE) ?: 0L,
                isPending = v.getAsInteger(MediaStore.Video.Media.IS_PENDING) ?: 0,
                file = file
            )
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            val entry = entries[id] ?: return 0
            val v = values ?: return 0
            if (failPendingPublishUpdate && v.containsKey(MediaStore.Video.Media.IS_PENDING)) {
                return 0
            }
            v.getAsInteger(MediaStore.Video.Media.IS_PENDING)?.let { entry.isPending = it }
            v.getAsString(MediaStore.Video.Media.DISPLAY_NAME)?.let { entry.displayName = it }
            v.getAsString(MediaStore.Video.Media.RELATIVE_PATH)?.let { entry.relativePath = it }
            if (entry.file.exists()) {
                entry.sizeBytes = entry.file.length()
            }
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            val removed = entries.remove(id) ?: return 0
            removed.file.delete()
            return 1
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("Invalid URI $uri")
            val entry = entries[id] ?: throw FileNotFoundException("Missing entry $uri")
            val isWrite = mode.contains("w") || mode.contains("t")
            return if (isWrite) {
                if (failOpenOutputStream) {
                    throw FileNotFoundException("Simulated openOutputStream failure")
                }
                ParcelFileDescriptor.open(
                    entry.file,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                )
            } else {
                if (failPostPublishOpenInput && entry.isPending == 0) {
                    throw FileNotFoundException("Simulated post-publish read failure")
                }
                if (corruptBytesOnRead && entry.isPending == 0 && entry.file.exists() && entry.file.length() > 0L) {
                    val bytes = entry.file.readBytes()
                    bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
                    val badFile = File(backingDir, "corrupt_${entry.id}.bin").apply { writeBytes(bytes) }
                    return ParcelFileDescriptor.open(badFile, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                if (!entry.file.exists()) {
                    throw FileNotFoundException("Media file missing")
                }
                ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
    }
}
