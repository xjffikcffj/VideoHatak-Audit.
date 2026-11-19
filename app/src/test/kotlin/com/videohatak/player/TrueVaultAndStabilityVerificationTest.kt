package com.videohatak.player

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.model.VideoSortOrder
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.service.MediaNotificationArtworkLoader
import com.videohatak.player.core.playback.service.VideoPlaybackService
import com.videohatak.player.core.playlist.db.PlaylistDatabaseStore
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.model.VaultEntryState
import com.videohatak.player.core.vault.model.VaultHiddenMatcher
import com.videohatak.player.core.vault.model.VaultOriginalReference
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.PublicExportOutcome
import com.videohatak.player.core.vault.repository.PublicExportRequest
import com.videohatak.player.core.vault.repository.PublicMediaExporter
import com.videohatak.player.core.vault.repository.VaultDeleteOutcome
import com.videohatak.player.core.vault.repository.VaultImportOutcome
import com.videohatak.player.core.vault.repository.VaultOriginalDeleter
import com.videohatak.player.core.vault.repository.VaultOriginalDeleteOutcome
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.core.vault.repository.VaultRestoreOutcome
import com.videohatak.player.feature.vault.VaultUiEvent
import com.videohatak.player.feature.vault.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrueVaultAndStabilityVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()

    private fun testApp(): Application = RuntimeEnvironment.getApplication()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cleanVaultEnvironment() {
        File(testApp().filesDir, "vault_encrypted").deleteRecursively()
        File(testApp().cacheDir, "vault_restore_tmp").deleteRecursively()
        testApp().deleteDatabase("videohatak_vault.db")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        cleanVaultEnvironment()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        cleanVaultEnvironment()
        MediaNotificationArtworkLoader.clearCacheForTesting()
    }

    // -------------------------------------------------------------------------
    // 1. True Private Vault End-to-End Flow
    // -------------------------------------------------------------------------

    @Test
    fun trueVault_encryptVerifyAndDeleteOriginal_removesFileAndPersistsAcrossRestart() = runTest(testDispatcher) {
        val publicDir = tempFolder.newFolder("Movies")
        val sourceFile = File(publicDir, "private_clip.mp4")
        val payload = ByteArray(128 * 1024) { ((it * 29) xor 0x3A).toByte() }
        sourceFile.writeBytes(payload)
        val expectedSha = sha256Hex(payload)

        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val db = VaultDatabaseHelper(testApp())
        val repo = VaultRepositoryImpl(
            context = testApp(),
            cryptoManager = crypto,
            dbHelper = db,
            ioDispatcher = testDispatcher
        )
        repo.setUnlocked(true)
        repo.refresh()

        val importOutcome = repo.importMediaVideoDetailed(
            sourceUri = Uri.fromFile(sourceFile),
            displayName = "Private Clip",
            originalFileName = "private_clip.mp4",
            originalRelativePath = "Movies/",
            originalSizeBytes = payload.size.toLong(),
            originalFilePath = sourceFile.absolutePath
        )
        assertTrue(importOutcome is VaultImportOutcome.Imported)
        val imported = (importOutcome as VaultImportOutcome.Imported).video
        // Prior to original deletion step, original still exists
        assertTrue(sourceFile.exists())

        val deleteOutcome = repo.deleteOriginalForVaultEntry(imported.id)
        assertTrue("Expected DeletedVerified, got $deleteOutcome", deleteOutcome is VaultOriginalDeleteOutcome.DeletedVerified)

        // Original MUST be physically deleted and verified gone
        assertFalse("Original unencrypted file MUST be deleted from public storage", sourceFile.exists())
        val encFile = repo.resolveEncryptedFile(imported)
        assertTrue("Encrypted Vault file MUST exist", encFile.exists() && encFile.length() > 0L)
        assertEquals(expectedSha, crypto.verifyEncryptedFileIntegrityAndHash(encFile, expectedSha))

        val loadedAfterDelete = repo.vaultVideos.first().first { it.id == imported.id }
        assertEquals(VaultEntryState.ORIGINAL_DELETED_VERIFIED, loadedAfterDelete.vaultState)
        assertTrue(loadedAfterDelete.originalDeleteVerified)
        assertTrue(loadedAfterDelete.isOriginalDeletedProtected)
        assertFalse(loadedAfterDelete.needsOriginalDeletionAttention)
        db.close()

        // Simulate cold restart with fresh DB helper and locked repository
        val dbRestart = VaultDatabaseHelper(testApp())
        val repoRestart = VaultRepositoryImpl(
            context = testApp(),
            cryptoManager = crypto,
            dbHelper = dbRestart,
            ioDispatcher = testDispatcher
        )
        assertTrue(repoRestart.hiddenOriginalEntries.value.any { it.vaultId == imported.id })
        repoRestart.setUnlocked(true)
        repoRestart.refresh()
        val persisted = repoRestart.vaultVideos.first().first { it.id == imported.id }
        assertEquals(VaultEntryState.ORIGINAL_DELETED_VERIFIED, persisted.vaultState)
        assertTrue(persisted.isOriginalDeletedProtected)
        dbRestart.close()
    }

    @Test
    fun trueVault_restoreDecryptsToPublicExporter_preservesVaultOnFailureAndCleansUpOnSuccess() = runTest(testDispatcher) {
        val publicDir = tempFolder.newFolder("PublicVideos")
        val sourceFile = File(publicDir, "vacation.mp4")
        val payload = ByteArray(96 * 1024) { ((it * 13) + 5).toByte() }
        sourceFile.writeBytes(payload)
        val expectedSha = sha256Hex(payload)

        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val db = VaultDatabaseHelper(testApp())
        var shouldFailExport = true
        var exportedBytesCaptured: ByteArray? = null
        var capturedRequest: PublicExportRequest? = null
        val restoredOutFile = File(publicDir, "restored_vacation.mp4")

        val controllableExporter = object : PublicMediaExporter {
            override suspend fun exportVideo(request: PublicExportRequest): PublicExportOutcome {
                capturedRequest = request
                if (shouldFailExport) {
                    return PublicExportOutcome.Failed("Simulated storage write error")
                }
                val decryptedBytes = request.decryptedTempFile.readBytes()
                exportedBytesCaptured = decryptedBytes
                restoredOutFile.writeBytes(decryptedBytes)
                return PublicExportOutcome.Exported(
                    mediaUri = Uri.fromFile(restoredOutFile),
                    displayName = request.displayName,
                    relativePath = request.relativePath ?: "Movies/",
                    mimeType = request.mimeType,
                    verifiedSizeBytes = decryptedBytes.size.toLong(),
                    verifiedSha256 = sha256Hex(decryptedBytes)
                )
            }
        }

        val repo = VaultRepositoryImpl(
            context = testApp(),
            cryptoManager = crypto,
            dbHelper = db,
            ioDispatcher = testDispatcher,
            publicMediaExporter = controllableExporter
        )
        repo.setUnlocked(true)
        repo.refresh()

        val imp = (repo.importMediaVideoDetailed(
            sourceUri = Uri.fromFile(sourceFile),
            displayName = "Vacation",
            originalFileName = "vacation.mp4",
            originalRelativePath = "Movies/Trips/",
            originalSizeBytes = payload.size.toLong(),
            originalFilePath = sourceFile.absolutePath
        ) as VaultImportOutcome.Imported).video

        assertTrue(repo.deleteOriginalForVaultEntry(imp.id) is VaultOriginalDeleteOutcome.DeletedVerified)
        assertFalse(sourceFile.exists())
        val encFile = repo.resolveEncryptedFile(imp)
        assertTrue(encFile.exists())

        // Attempt 1: Public export fails -> Vault encrypted payload & DB record MUST be preserved!
        val failedRestore = repo.restoreVaultVideo(imp.id)
        assertTrue(failedRestore is VaultRestoreOutcome.Failed)
        assertTrue(encFile.exists() && encFile.length() > 0L)
        val afterFailRow = db.getVideoById(imp.id)!!
        assertEquals(VaultEntryState.ORIGINAL_DELETED_VERIFIED, afterFailRow.vaultState)
        assertTrue(afterFailRow.isOriginalDeletedProtected)
        // Temp decrypted files must be cleaned up immediately on failure
        val tempFilesAfterFail = File(testApp().cacheDir, "vault_restore_tmp").listFiles()?.toList().orEmpty()
        assertTrue("Temp decrypted cache files must be cleaned up on failure", tempFilesAfterFail.isEmpty())

        // Attempt 2: Public export succeeds -> bytes match byte-for-byte, .enc & DB row deleted
        shouldFailExport = false
        val okRestore = repo.restoreVaultVideo(imp.id)
        assertTrue("Expected Restored, got $okRestore", okRestore is VaultRestoreOutcome.Restored)
        val restored = okRestore as VaultRestoreOutcome.Restored
        assertEquals(Uri.fromFile(restoredOutFile), restored.restoredUri)
        assertEquals(expectedSha, restored.verifiedSha256)
        assertEquals("Movies/Trips/", capturedRequest?.relativePath)
        assertArrayEquals(payload, exportedBytesCaptured)
        assertTrue(restoredOutFile.exists())
        assertArrayEquals(payload, restoredOutFile.readBytes())
        assertFalse("Encrypted payload should be deleted after verified restore", encFile.exists())
        assertNull("DB row should be deleted after verified restore", db.getVideoById(imp.id))
        assertTrue(repo.vaultVideos.first().isEmpty())
        db.close()
    }

    @Test
    fun trueVault_consentDeniedThenRetried_preservesEncryptedFileUntilConsentGranted() = runTest(testDispatcher) {
        val publicDir = tempFolder.newFolder("ScopedMedia")
        val sourceFile = File(publicDir, "clip.mp4")
        val payload = ByteArray(48 * 1024) { 0x5B.toByte() }
        sourceFile.writeBytes(payload)

        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val db = VaultDatabaseHelper(testApp())
        var grantDelete = false
        var fakePresent = true

        val dummySender = PendingIntent.getActivity(
            testApp(),
            101,
            Intent("com.videohatak.TEST_CONSENT"),
            PendingIntent.FLAG_IMMUTABLE
        ).intentSender

        val fakeDeleter = object : VaultOriginalDeleter {
            override fun attemptDeleteOriginal(ref: VaultOriginalReference): VaultOriginalDeleter.SingleDeleteAttempt {
                return if (!grantDelete) {
                    VaultOriginalDeleter.SingleDeleteAttempt.NeedsUserConsent(
                        intentSender = dummySender,
                        targetUri = Uri.fromFile(sourceFile)
                    )
                } else {
                    sourceFile.delete()
                    fakePresent = false
                    VaultOriginalDeleter.SingleDeleteAttempt.Deleted
                }
            }

            override fun isOriginalStillPresent(ref: VaultOriginalReference): Boolean = fakePresent && sourceFile.exists()
        }

        val repo = VaultRepositoryImpl(
            context = testApp(),
            cryptoManager = crypto,
            dbHelper = db,
            ioDispatcher = testDispatcher,
            originalDeleter = fakeDeleter
        )
        repo.setUnlocked(true)
        repo.refresh()

        val viewModel = VaultViewModel(
            repository = repo,
            biometricHelper = VaultBiometricHelper(testApp()),
            securityPreferences = null
        )
        val events = mutableListOf<VaultUiEvent>()
        val collectJob = launch { viewModel.events.collect { events.add(it) } }
        advanceUntilIdle()

        val imp = (repo.importMediaVideoDetailed(
            sourceUri = Uri.fromFile(sourceFile),
            displayName = "Clip",
            originalFileName = "clip.mp4",
            originalFilePath = sourceFile.absolutePath
        ) as VaultImportOutcome.Imported).video

        viewModel.deleteOriginalsForVaultEntries(listOf(imp.id))
        advanceUntilIdle()

        val consentEvent = events.filterIsInstance<VaultUiEvent.RequestOriginalDeleteConsent>().firstOrNull()
        assertNotNull("Expected consent IntentSender request", consentEvent)
        assertEquals(listOf(imp.id), consentEvent!!.vaultIds)

        // Simulate user denying OS dialog -> must preserve .enc & flag needsOriginalDeletionAttention
        viewModel.onOriginalDeleteConsentResult(granted = false, vaultIds = listOf(imp.id))
        advanceUntilIdle()

        val encFile = repo.resolveEncryptedFile(imp)
        assertTrue(encFile.exists())
        assertTrue(sourceFile.exists())
        val afterDeny = repo.vaultVideos.first().first { it.id == imp.id }
        assertEquals(VaultEntryState.AWAITING_ORIGINAL_DELETE_CONSENT, afterDeny.vaultState)
        assertTrue(afterDeny.needsOriginalDeletionAttention)
        assertFalse(afterDeny.isOriginalDeletedProtected)
        // Still hidden inside VideoHatak library even before delete consent!
        assertTrue(VaultHiddenMatcher.isTargetHidden(imp.id, Uri.fromFile(sourceFile).toString(), sourceFile.absolutePath, repo.hiddenOriginalEntries.value))

        // User taps "حذف الأصل الآن" and grants consent
        grantDelete = true
        viewModel.retryDeleteOriginal(imp.id)
        advanceUntilIdle()

        assertFalse("Original file must now be deleted", sourceFile.exists())
        val afterRetry = repo.vaultVideos.first().first { it.id == imp.id }
        assertEquals(VaultEntryState.ORIGINAL_DELETED_VERIFIED, afterRetry.vaultState)
        assertTrue(afterRetry.isOriginalDeletedProtected)
        assertFalse(afterRetry.needsOriginalDeletionAttention)

        collectJob.cancel()
        db.close()
    }

    @Test
    fun trueVault_crashRecovery_reconcilesPendingDeleteAndRestoringStatesSafely() = runTest(testDispatcher) {
        val publicDir = tempFolder.newFolder("Recovery")
        val missingOriginal = File(publicDir, "gone.mp4")
        val presentOriginal = File(publicDir, "still_here.mp4").apply { writeBytes(ByteArray(1024) { 7 }) }

        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val vaultDir = File(testApp().filesDir, "vault_encrypted").apply { mkdirs() }
        val db = VaultDatabaseHelper(testApp())

        fun writeEnc(name: String): Pair<File, ByteArray> {
            val f = File(vaultDir, name)
            val data = ByteArray(8192) { 3 }
            crypto.encryptStream(data.inputStream(), f, data.size.toLong())
            return f to data
        }

        val (enc1, _) = writeEnc("crashed_delete_gone.enc")
        val (enc2, _) = writeEnc("crashed_delete_present.enc")
        val (enc3, _) = writeEnc("crashed_restore_protected.enc")

        val row1Id = db.insertVideo(
            VaultVideo(
                id = 0L,
                displayName = "gone.mp4",
                originalSizeBytes = 8192L,
                encryptedSizeBytes = enc1.length(),
                durationMs = 1000L,
                relativeEncryptedPath = enc1.name,
                createdAtEpochMs = 100L,
                encryptedMetaBase64 = crypto.encryptMetadata("""{"title":"Gone","file":"${enc1.name}","mime":"video/mp4"}"""),
                originalUri = Uri.fromFile(missingOriginal).toString(),
                originalFilePath = missingOriginal.absolutePath,
                vaultState = VaultEntryState.ORIGIN_DELETE_PENDING,
                isHiddenInLibrary = true
            )
        )
        val row2Id = db.insertVideo(
            VaultVideo(
                id = 0L,
                displayName = "still_here.mp4",
                originalSizeBytes = 8192L,
                encryptedSizeBytes = enc2.length(),
                durationMs = 1000L,
                relativeEncryptedPath = enc2.name,
                createdAtEpochMs = 200L,
                encryptedMetaBase64 = crypto.encryptMetadata("""{"title":"StillHere","file":"${enc2.name}","mime":"video/mp4"}"""),
                originalUri = Uri.fromFile(presentOriginal).toString(),
                originalFilePath = presentOriginal.absolutePath,
                vaultState = VaultEntryState.ORIGIN_DELETE_PENDING,
                isHiddenInLibrary = true
            )
        )
        val row3Id = db.insertVideo(
            VaultVideo(
                id = 0L,
                displayName = "protected.mp4",
                originalSizeBytes = 8192L,
                encryptedSizeBytes = enc3.length(),
                durationMs = 1000L,
                relativeEncryptedPath = enc3.name,
                createdAtEpochMs = 300L,
                encryptedMetaBase64 = crypto.encryptMetadata("""{"title":"Protected","file":"${enc3.name}","mime":"video/mp4"}"""),
                originalUri = "",
                vaultState = VaultEntryState.RESTORING,
                isHiddenInLibrary = true,
                originalDeleteVerified = true
            )
        )

        val repo = VaultRepositoryImpl(testApp(), crypto, db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)
        repo.refresh()

        val videosById = repo.vaultVideos.first().associateBy { it.id }
        assertEquals(VaultEntryState.ORIGINAL_DELETED_VERIFIED, videosById[row1Id]?.vaultState)
        assertTrue(videosById[row1Id]?.originalDeleteVerified == true)

        assertEquals(VaultEntryState.ORIGINAL_DELETE_FAILED, videosById[row2Id]?.vaultState)
        assertFalse(videosById[row2Id]?.originalDeleteVerified == true)
        assertTrue(videosById[row2Id]?.needsOriginalDeletionAttention == true)

        assertEquals(VaultEntryState.ORIGINAL_DELETED_VERIFIED, videosById[row3Id]?.vaultState)
        assertTrue(videosById[row3Id]?.isOriginalDeletedProtected == true)
        db.close()
    }

    @Test
    fun vaultDb_upgradeFromV4ToV5_preservesExistingRowsAndPersistsV5Columns() {
        val dbName = "videohatak_vault.db"
        testApp().deleteDatabase(dbName)

        // Create authentic v4 database
        val v4Helper = object : SQLiteOpenHelper(testApp(), dbName, null, 4) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE ${VaultDatabaseHelper.TABLE_VAULT} (
                        ${VaultDatabaseHelper.COLUMN_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                        ${VaultDatabaseHelper.COLUMN_DISPLAY_NAME} TEXT NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_ORIGINAL_SIZE} INTEGER NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_ENCRYPTED_SIZE} INTEGER NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_DURATION_MS} INTEGER NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_MIME_TYPE} TEXT NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_WIDTH} INTEGER NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_HEIGHT} INTEGER NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_RELATIVE_PATH} TEXT NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_CREATED_AT} INTEGER NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_IV_BASE64} TEXT NOT NULL,
                        ${VaultDatabaseHelper.COLUMN_ENCRYPTED_META} TEXT NOT NULL DEFAULT '',
                        ${VaultDatabaseHelper.COLUMN_ORIGINAL_URI} TEXT NOT NULL DEFAULT '',
                        ${VaultDatabaseHelper.COLUMN_ORIGINAL_MEDIA_STORE_ID} INTEGER,
                        ${VaultDatabaseHelper.COLUMN_ORIGINAL_FILE_PATH} TEXT NOT NULL DEFAULT '',
                        ${VaultDatabaseHelper.COLUMN_ORIGINAL_DATE_MODIFIED_SEC} INTEGER NOT NULL DEFAULT 0,
                        ${VaultDatabaseHelper.COLUMN_ORIGINAL_SHA256} TEXT NOT NULL DEFAULT '',
                        ${VaultDatabaseHelper.COLUMN_STORAGE_TYPE} TEXT NOT NULL DEFAULT 'UNKNOWN',
                        ${VaultDatabaseHelper.COLUMN_URI_PERMISSION_FLAGS} INTEGER NOT NULL DEFAULT 0,
                        ${VaultDatabaseHelper.COLUMN_VAULT_STATE} TEXT NOT NULL DEFAULT 'ENCRYPTED_VERIFIED',
                        ${VaultDatabaseHelper.COLUMN_IS_HIDDEN_IN_LIBRARY} INTEGER NOT NULL DEFAULT 0,
                        ${VaultDatabaseHelper.COLUMN_STATE_UPDATED_AT_MS} INTEGER NOT NULL DEFAULT 0,
                        ${VaultDatabaseHelper.COLUMN_LAST_ERROR} TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO ${VaultDatabaseHelper.TABLE_VAULT}
                    (display_name, original_size_bytes, encrypted_size_bytes, duration_ms, mime_type, width, height, relative_encrypted_path, created_at_epoch_ms, iv_base64, original_sha256, vault_state, is_hidden_in_library)
                    VALUES ('LegacyMasked', 4096, 4200, 10000, 'application/octet-stream', 1920, 1080, 'legacy.enc', 123456, 'iv', 'abc123sha', 'ORIGINAL_HIDDEN', 1)
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v4Helper.writableDatabase.close()
        v4Helper.close()

        val v5Helper = VaultDatabaseHelper(testApp())
        val rows = v5Helper.getAllVideos()
        assertEquals(1, rows.size)
        val migrated = rows.first()
        assertEquals("LegacyMasked", migrated.displayName)
        assertEquals("abc123sha", migrated.originalSha256)
        assertEquals(VaultEntryState.ORIGINAL_HIDDEN, migrated.vaultState)
        assertFalse(migrated.originalDeleteVerified)

        val updated = v5Helper.updateOriginalDeletionStatus(
            id = migrated.id,
            vaultState = VaultEntryState.ORIGINAL_DELETED_VERIFIED,
            isHiddenInLibrary = true,
            originalDeleteVerified = true,
            publicRestoredUri = "content://media/external/video/media/999"
        )
        assertEquals(1, updated)
        val reloaded = v5Helper.getVideoById(migrated.id)!!
        assertEquals(VaultEntryState.ORIGINAL_DELETED_VERIFIED, reloaded.vaultState)
        assertTrue(reloaded.originalDeleteVerified)
        assertEquals("content://media/external/video/media/999", reloaded.publicRestoredUri)
        v5Helper.close()
    }

    @Test
    fun trueVault_permanentDeleteAllowedAfterUserConfirm_whenOriginalAlreadyDeleted() = runTest(testDispatcher) {
        val publicDir = tempFolder.newFolder("PermDelete")
        val sourceFile = File(publicDir, "disposable.mp4").apply { writeBytes(ByteArray(16 * 1024) { 9 }) }

        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val db = VaultDatabaseHelper(testApp())
        val repo = VaultRepositoryImpl(testApp(), crypto, db, ioDispatcher = testDispatcher)
        repo.setUnlocked(true)
        repo.refresh()

        val imp = (repo.importMediaVideoDetailed(
            sourceUri = Uri.fromFile(sourceFile),
            displayName = "Disposable",
            originalFilePath = sourceFile.absolutePath
        ) as VaultImportOutcome.Imported).video
        assertTrue(repo.deleteOriginalForVaultEntry(imp.id) is VaultOriginalDeleteOutcome.DeletedVerified)

        val outcome = repo.deleteVaultVideoDetailed(imp.id)
        assertEquals(VaultDeleteOutcome.Deleted, outcome)
        assertFalse(repo.resolveEncryptedFile(imp).exists())
        assertNull(db.getVideoById(imp.id))
        db.close()
    }

    // -------------------------------------------------------------------------
    // 2. Notification "Next" Action & Player State Stability (Problem 3)
    // -------------------------------------------------------------------------

    @Test
    fun playbackService_shouldRetainActiveServiceOnCommand_handlesEdgeStatesDeterministically() {
        assertTrue(
            VideoPlaybackService.shouldRetainActiveServiceOnCommand(
                mediaItemCount = 3,
                playbackState = Player.STATE_READY,
                isPlaying = false,
                playWhenReady = false,
                isVaultPrivate = false
            )
        )
        assertTrue(
            VideoPlaybackService.shouldRetainActiveServiceOnCommand(
                mediaItemCount = 3,
                playbackState = Player.STATE_ENDED,
                isPlaying = false,
                playWhenReady = true,
                isVaultPrivate = false
            )
        )
        assertFalse(
            VideoPlaybackService.shouldRetainActiveServiceOnCommand(
                mediaItemCount = 0,
                playbackState = Player.STATE_IDLE,
                isPlaying = false,
                playWhenReady = false,
                isVaultPrivate = false
            )
        )
        assertFalse(
            VideoPlaybackService.shouldRetainActiveServiceOnCommand(
                mediaItemCount = 3,
                playbackState = Player.STATE_READY,
                isPlaying = true,
                playWhenReady = true,
                isVaultPrivate = true
            )
        )
    }

    private fun <T> runOnLooperSync(looper: Looper, block: () -> T): T {
        if (Looper.myLooper() == looper) {
            return block()
        }
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<T?>()
        val errorRef = AtomicReference<Throwable?>()
        val posted = Handler(looper).post {
            try {
                resultRef.set(block())
            } catch (t: Throwable) {
                errorRef.set(t)
            } finally {
                latch.countDown()
            }
        }
        assertTrue("Failed to post operation onto player.applicationLooper", posted)
        assertTrue("Timed out waiting for operation on player.applicationLooper", latch.await(10L, TimeUnit.SECONDS))
        errorRef.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return resultRef.get() as T
    }

    @Test
    fun videoPlayerManager_nextTrackWrapAroundAndAudioOnlyPreserved() {
        val playerThread = HandlerThread("exo-next-track-test").apply { start() }
        val exoPlayer = runOnLooperSync(playerThread.looper) {
            ExoPlayer.Builder(testApp())
                .setLooper(playerThread.looper)
                .build()
        }
        val manager = VideoPlayerManager(testApp(), customExoPlayer = exoPlayer)
        try {
            val f1 = tempFolder.newFile("track1.mp4").apply { writeBytes(ByteArray(128)) }
            val f2 = tempFolder.newFile("track2.mp4").apply { writeBytes(ByteArray(128)) }
            val items = listOf(
                MediaItem.Builder().setMediaId("1").setUri(Uri.fromFile(f1)).build(),
                MediaItem.Builder().setMediaId("2").setUri(Uri.fromFile(f2)).build()
            )
            runOnLooperSync(exoPlayer.applicationLooper) {
                exoPlayer.setMediaItems(items, 0, 0L)
                exoPlayer.prepare()
                manager.setAudioOnlyMode(true)
                manager.setPlaybackMode(PlaybackMode.REPEAT_PLAYLIST)

                assertTrue(manager.hasResolvableNextMediaItem())
                assertTrue(manager.nextTrack(resumePlaybackIfPausedOrEnded = true))
                assertEquals(1, exoPlayer.currentMediaItemIndex)
                assertTrue(manager.uiState.value.isAudioOnlyMode)

                // Wrap around from last item to index 0 under REPEAT_PLAYLIST
                assertTrue(manager.hasResolvableNextMediaItem())
                assertTrue(manager.nextTrack(resumePlaybackIfPausedOrEnded = true))
                assertEquals(0, exoPlayer.currentMediaItemIndex)
                assertTrue(manager.uiState.value.isAudioOnlyMode)
            }
        } finally {
            try {
                runOnLooperSync(exoPlayer.applicationLooper) {
                    manager.release()
                }
            } finally {
                playerThread.quitSafely()
            }
        }
    }

    @Test
    fun videoPlayerManager_release_alwaysRunsSynchronouslyOnPlayerApplicationLooperThread() {
        val playerThread = HandlerThread("exo-release-thread-test").apply { start() }
        val releaseCompleted = AtomicBoolean(false)
        val releaseThreadRef = AtomicReference<Thread?>(null)
        val realExoPlayer = runOnLooperSync(playerThread.looper) {
            ExoPlayer.Builder(testApp())
                .setLooper(playerThread.looper)
                .build()
        }
        val trackingPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)
        ) { _, method, args ->
            if (method.name == "release") {
                releaseThreadRef.set(Thread.currentThread())
                val res = realExoPlayer.release()
                releaseCompleted.set(true)
                res
            } else {
                try {
                    if (args == null) method.invoke(realExoPlayer) else method.invoke(realExoPlayer, *args)
                } catch (ite: InvocationTargetException) {
                    throw ite.targetException
                }
            }
        } as ExoPlayer

        val manager = VideoPlayerManager(testApp(), customExoPlayer = trackingPlayer)
        try {
            assertFalse(
                "Caller thread should differ from player.applicationLooper thread to verify dispatch",
                Thread.currentThread() === playerThread
            )

            // Invoke release() from a non-player thread; it must dispatch synchronously to playerThread
            manager.release()

            assertTrue("ExoPlayer.release() must complete before VideoPlayerManager.release() returns", releaseCompleted.get())
            assertTrue(
                "ExoPlayer.release() must run on player.applicationLooper.thread",
                releaseThreadRef.get() === playerThread
            )
            assertFalse(manager.hasResolvableNextMediaItem())
        } finally {
            if (!releaseCompleted.get()) {
                runCatching { runOnLooperSync(playerThread.looper) { realExoPlayer.release() } }
            }
            playerThread.quitSafely()
        }
    }

    @Test
    fun artworkLoader_decodeSampledBitmap_safelyBoundsLargeArtworkAndRejectsCorruptBytes() {
        val large = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val pngBaos = ByteArrayOutputStream()
        large.compress(Bitmap.CompressFormat.PNG, 90, pngBaos)
        val largePngBytes = pngBaos.toByteArray()

        val sampled = MediaNotificationArtworkLoader.decodeSampledBitmap(largePngBytes, maxSizePx = 256)
        assertNotNull(sampled)
        assertTrue(sampled!!.width <= 256 && sampled.height <= 256)
        assertEquals(Bitmap.Config.RGB_565, sampled.config)

        val small = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val smallPngBaos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.PNG, 100, smallPngBaos)
        val smallSampled = MediaNotificationArtworkLoader.decodeSampledBitmap(smallPngBaos.toByteArray(), maxSizePx = 256)
        assertNotNull(smallSampled)
        assertEquals(64, smallSampled!!.width)
        assertEquals(64, smallSampled.height)
        assertEquals(Bitmap.Config.RGB_565, smallSampled.config)

        val jpegBaos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 90, jpegBaos)
        val jpegSampled = MediaNotificationArtworkLoader.decodeSampledBitmap(jpegBaos.toByteArray(), maxSizePx = 256)
        assertNotNull(jpegSampled)
        assertEquals(Bitmap.Config.RGB_565, jpegSampled!!.config)

        assertNull(MediaNotificationArtworkLoader.decodeSampledBitmap(ByteArray(0)))
        assertNull(MediaNotificationArtworkLoader.decodeSampledBitmap(byteArrayOf(1, 2, 3, 4)))
        assertNull(
            MediaNotificationArtworkLoader.decodeSampledBitmap(
                "NOT_AN_IMAGE_PAYLOAD_FOR_ARTWORK_LOADER_CHECK".repeat(3).toByteArray()
            )
        )

        // Truncated PNG (keeps valid 8-byte PNG signature & IHDR header, cuts off IDAT/IEND)
        val truncatedPng = largePngBytes.copyOf(minOf(largePngBytes.size - 16, 48))
        assertNull(MediaNotificationArtworkLoader.decodeSampledBitmap(truncatedPng))

        // Corrupted PNG (keeps PNG signature & header intact, corrupts payload/CRC inside IDAT)
        val corruptedPng = largePngBytes.copyOf()
        val corruptIdx = (corruptedPng.size / 2).coerceIn(33, corruptedPng.size - 13)
        corruptedPng[corruptIdx] = (corruptedPng[corruptIdx].toInt() xor 0xFF).toByte()
        assertNull(MediaNotificationArtworkLoader.decodeSampledBitmap(corruptedPng))

        // Oversized dimensions (> 16,384 px) and excessive total pixels (> 64M px) with valid CRC32 must be rejected
        fun pngWithIhdrDimensions(basePng: ByteArray, w: Int, h: Int): ByteArray {
            return basePng.copyOf().apply {
                this[16] = ((w ushr 24) and 0xFF).toByte()
                this[17] = ((w ushr 16) and 0xFF).toByte()
                this[18] = ((w ushr 8) and 0xFF).toByte()
                this[19] = (w and 0xFF).toByte()
                this[20] = ((h ushr 24) and 0xFF).toByte()
                this[21] = ((h ushr 16) and 0xFF).toByte()
                this[22] = ((h ushr 8) and 0xFF).toByte()
                this[23] = (h and 0xFF).toByte()
                val crc = CRC32().apply { update(this@apply, 12, 17) }.value
                this[29] = ((crc ushr 24) and 0xFF).toByte()
                this[30] = ((crc ushr 16) and 0xFF).toByte()
                this[31] = ((crc ushr 8) and 0xFF).toByte()
                this[32] = (crc and 0xFF).toByte()
            }
        }
        assertNull(MediaNotificationArtworkLoader.decodeSampledBitmap(pngWithIhdrDimensions(largePngBytes, 20_000, 20_000)))
        assertNull(MediaNotificationArtworkLoader.decodeSampledBitmap(pngWithIhdrDimensions(largePngBytes, 9_000, 9_000)))
    }

    // -------------------------------------------------------------------------
    // 3. SQLite Crash Resilience in PlaylistRepositoryImpl (Problem 2)
    // -------------------------------------------------------------------------

    @Test
    fun playlistRepository_sqlExceptions_returnSafeFallbacksInsteadOfCrashing() = runTest(testDispatcher) {
        val failingDb = object : PlaylistDatabaseStore {
            override fun insertPlaylist(title: String): Long = throw SQLiteException("Disk I/O error")
            override fun updatePlaylist(id: Long, title: String): Int = throw SQLiteException("Disk I/O error")
            override fun deletePlaylist(id: Long): Int = throw SQLiteException("Disk I/O error")
            override fun getPlaylists(): List<Playlist> = throw SQLiteException("Database corrupted")
            override fun insertItem(item: PlaylistItem): Long = throw SQLiteException("Read-only DB")
            override fun deleteItem(itemId: Long, playlistId: Long): Int = throw SQLiteException("Busy")
            override fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem> = throw SQLiteException("Busy")
            override fun updateItemOrder(itemId: Long, newIndex: Int) = throw SQLiteException("Busy")
            override fun updateItemAvailability(itemId: Long, isAvailable: Boolean) = throw SQLiteException("Busy")
            override fun updateItemUri(itemId: Long, newUri: String) = throw SQLiteException("Busy")
        }

        val dummyVideoRepo = object : VideoRepository {
            override fun observeVideos(): Flow<List<VideoItem>> = MutableStateFlow(emptyList())
            override suspend fun getVideos(): List<VideoItem> = emptyList()
            override fun filterAndSort(
                videos: List<VideoItem>,
                query: String,
                sortOrder: VideoSortOrder,
                folderId: String?
            ): List<VideoItem> = videos
            override fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder> = emptyList()
        }

        val repo = PlaylistRepositoryImpl(
            context = testApp(),
            dbHelper = failingDb,
            videoRepository = dummyVideoRepo,
            ioDispatcher = testDispatcher
        )

        repo.refresh()
        assertEquals(-1L, repo.createPlaylist("SafeTest"))
        assertFalse(repo.renamePlaylist(1L, "NewName"))
        assertTrue(repo.getPlaylistItems(1L).isEmpty())
        assertNull(repo.getPlaylistWithItems(1L))
        val sampleVideo = VideoItem(
            id = 1L,
            contentUri = Uri.parse("content://media/external/video/media/1"),
            title = "Sample Video",
            displayName = "sample_video.mp4"
        )
        assertFalse(repo.isFavorite(sampleVideo))
        assertEquals(Pair(emptySet<Long>(), emptySet<String>()), repo.getFavoriteVideoKeys())
        repo.moveItem(1L, 0, 1)
    }
}
