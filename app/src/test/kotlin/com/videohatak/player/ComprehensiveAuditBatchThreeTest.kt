package com.videohatak.player

import android.app.Application
import android.net.Uri
import android.os.Environment
import com.videohatak.player.core.backup.BackupManager
import com.videohatak.player.core.backup.BackupParseResult
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPositionManager
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.subtitle.repository.LocalSubtitleStore
import com.videohatak.player.core.subtitle.translation.MlKitModelBridge
import com.videohatak.player.core.subtitle.translation.OfflineModelDownloadStatus
import com.videohatak.player.core.subtitle.translation.OfflineModelManager
import com.videohatak.player.core.subtitle.translation.SubtitleBatchRequest
import com.videohatak.player.core.subtitle.translation.SubtitleBatchResponse
import com.videohatak.player.core.subtitle.translation.SubtitleSourceMode
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationProvider
import com.videohatak.player.core.subtitle.translation.TranslatedCuePair
import com.videohatak.player.core.subtitle.translation.TranslationEngineMode
import com.videohatak.player.core.subtitle.translation.TranslationJobStatus
import com.videohatak.player.core.thumbnail.retriever.ThumbnailRetriever
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseStore
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.feature.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ComprehensiveAuditBatchThreeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testApp(): Application = RuntimeEnvironment.getApplication()

    @Test
    fun vaultCrypto_verifyEncryptedFileIntegrity_acceptsValidAndRejectsTamperedOrTrailingBytes() {
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val payload = ByteArray(150 * 1024) { (it % 239).toByte() }
        val validFile = tempFolder.newFile("integrity_valid.enc")

        val result = crypto.encryptStream(
            inputStream = ByteArrayInputStream(payload),
            destinationFile = validFile,
            totalBytesEstimated = payload.size.toLong(),
            chunkSize = 64 * 1024
        )
        assertTrue(result.plaintextSizeBytes == payload.size.toLong())

        // Valid multi-chunk container must verify cleanly
        crypto.verifyEncryptedFileIntegrity(validFile)

        // Flip a byte in the second chunk's ciphertext -> AEADBadTagException
        val tamperedChunkFile = tempFolder.newFile("integrity_tampered_chunk.enc")
        validFile.copyTo(tamperedChunkFile, overwrite = true)
        RandomAccessFile(tamperedChunkFile, "rw").use { raf ->
            raf.seek(tamperedChunkFile.length() - 25L)
            val orig = raf.readByte()
            raf.seek(tamperedChunkFile.length() - 25L)
            raf.writeByte(orig.toInt() xor 0xFF)
        }
        assertThrows(AEADBadTagException::class.java) {
            crypto.verifyEncryptedFileIntegrity(tamperedChunkFile)
        }

        // Trailing unauthenticated bytes appended after last chunk -> IOException
        val trailingFile = tempFolder.newFile("integrity_trailing.enc")
        validFile.copyTo(trailingFile, overwrite = true)
        trailingFile.appendBytes(byteArrayOf(0x01, 0x02, 0x03))
        assertThrows(IOException::class.java) {
            crypto.verifyEncryptedFileIntegrity(trailingFile)
        }
    }

    @Test
    fun vaultAutoLockTimeout_includesTenMinutesOption() {
        val tenMin = VaultAutoLockTimeout.fromName("MINUTES_10")
        assertEquals(VaultAutoLockTimeout.MINUTES_10, tenMin)
        assertEquals(600_000L, tenMin.timeoutMs)
        assertFalse(tenMin.shouldAutoLockOnBackground())
        assertFalse(tenMin.isInactivityExpired(1_000L, 600_999L))
        assertTrue(tenMin.isInactivityExpired(1_000L, 601_000L))
    }

    @Test
    fun vaultRename_preservesSourceFingerprintToPreventDuplicateReimport() = runBlocking {
        val appContext = testApp()
        val vaultDir = File(appContext.filesDir, "vault_encrypted")
        vaultDir.deleteRecursively()

        val rows = linkedMapOf<Long, VaultVideo>()
        var nextId = 1L
        val fakeDb = object : VaultDatabaseStore {
            override fun insertVideo(video: VaultVideo): Long {
                val id = nextId++
                rows[id] = video.copy(id = id)
                return id
            }

            override fun updateEncryptedMetadata(
                id: Long,
                maskedDisplayName: String,
                internalPath: String,
                maskedMimeType: String,
                encryptedMetaBase64: String
            ): Int {
                val existing = rows[id] ?: return 0
                rows[id] = existing.copy(
                    displayName = maskedDisplayName,
                    relativeEncryptedPath = internalPath,
                    mimeType = maskedMimeType,
                    encryptedMetaBase64 = encryptedMetaBase64
                )
                return 1
            }

            override fun getAllVideos(): List<VaultVideo> = rows.values.toList()
            override fun getVideoById(id: Long): VaultVideo? = rows[id]
            override fun deleteVideo(id: Long): Int = if (rows.remove(id) != null) 1 else 0
        }

        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val repo = VaultRepositoryImpl(appContext, crypto, fakeDb)
        repo.setUnlocked(true)

        val srcFile = tempFolder.newFile("unique_clip.mp4").apply {
            writeBytes(ByteArray(4096) { (it % 113).toByte() })
        }
        val imported = repo.importVideo(Uri.fromFile(srcFile), "الاسم الأول")
        assertNotNull(imported)

        assertTrue(repo.renameVideo(imported!!.id, "الاسم المعدل"))

        // Re-importing the exact same source file after rename must still be detected as duplicate
        val duplicateImport = repo.importVideo(Uri.fromFile(srcFile), "اسم آخر")
        assertNull(duplicateImport)
        assertEquals(1, fakeDb.getAllVideos().size)
    }

    @Test
    fun localSubtitleStore_isolatesAndEncryptsVaultTracksAndExcludesThemFromBackups() = runBlocking {
        val rootDir = tempFolder.newFolder("sub_store_vault_test")
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val store = LocalSubtitleStore(rootDir, crypto)

        val publicUri = "content://media/external/video/media/100"
        val vaultUri = "vault://77"
        val cues = listOf(SubtitleCue(index = 1, startTimeMs = 1_000L, endTimeMs = 3_000L, text = "سر الخزنة"))

        store.saveTrackForVideo(publicUri, "ترجمة عامة", "ar", cues, SubtitleFormat.SRT)
        val savedVaultTrack = store.saveTrackForVideo(vaultUri, "ترجمة سرية", "ar", cues, SubtitleFormat.SRT)

        // Vault track file on disk must be encrypted (.vsub) with zero plaintext leakage
        val vaultTrackFile = File(savedVaultTrack.uri.path!!)
        assertTrue(vaultTrackFile.exists())
        assertEquals("vsub", vaultTrackFile.extension)
        val rawDiskText = vaultTrackFile.readText(Charsets.UTF_8)
        assertTrue(rawDiskText.startsWith("VHTKMETA1:"))
        assertFalse(rawDiskText.contains("سر الخزنة"))

        // Vault track must decrypt cleanly via loadTracksForVideo
        val loadedVault = store.loadTracksForVideo(vaultUri)
        assertEquals(1, loadedVault.size)
        assertEquals("سر الخزنة", loadedVault.first().cues.first().text)

        // Public backup export must include ONLY public tracks and NEVER vault tracks
        val exported = store.exportAllStoredTracksForBackup()
        assertEquals(1, exported.size)
        assertEquals("ترجمة عامة", exported.first().trackName)

        // Public backup replace must wipe public tracks while leaving vault tracks untouched
        store.replaceAllStoredTracksFromBackup(emptyList())
        assertTrue(store.loadTracksForVideo(publicUri).isEmpty())
        assertEquals(1, store.loadTracksForVideo(vaultUri).size)
    }

    @Test
    fun backupManager_rejectsDeepJsonAndMigratesLegacyAskEveryTimeWithValidChecksum() {
        // 1. Deep nesting > MAX_JSON_DEPTH rejected cleanly without StackOverflowError
        val deepJson = "{".repeat(45) + "}".repeat(45)
        val deepResult = BackupManager.parseAndValidateBackupJson(deepJson)
        assertTrue(deepResult is BackupParseResult.Error)

        // 2. Oversized backup > MAX_BACKUP_BYTES rejected cleanly
        val hugeJson = "a".repeat(BackupManager.MAX_BACKUP_BYTES + 10)
        assertTrue(BackupManager.parseAndValidateBackupJson(hugeJson) is BackupParseResult.Error)

        // 3. Legacy v2/v3 backup containing "ASK_EVERY_TIME" validates checksum & migrates to RESUME
        val legacyBody =
            """{"settings":{"selectedTheme":"OBSIDIAN_ELITE","themeMode":"DARK","playbackSpeed":1.0,"resizeMode":0,"playbackMode":"PLAY_ALL_IN_ORDER","resumeMode":"ASK_EVERY_TIME","seekIntervalSeconds":10},"subtitles":{"primaryFontSizeSp":18.0,"secondaryFontSizeSp":15.0},"vaultSecurity":{"autoLockTimeout":"MINUTES_10"},"playlists":[],"resumePositions":{}}"""
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(legacyBody.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val legacyJson =
            """{"schemaVersion":3,"createdAtEpochMs":1700000000000,"sha256Checksum":"$checksum","body":$legacyBody}"""

        val parsed = BackupManager.parseAndValidateBackupJson(legacyJson)
        assertTrue(parsed is BackupParseResult.Success)
        val payload = (parsed as BackupParseResult.Success).payload
        assertEquals(ResumeMode.RESUME, payload.settings.resumeMode)
        assertEquals(VaultAutoLockTimeout.MINUTES_10, payload.vaultSecurity.autoLockTimeout)
    }

    @Test
    fun videoRepository_preservesExtensionOnDotRenameAndSanitizesPathTraversal() {
        // Rename helper preserves original extension even when user adds dots
        val dotRename = LocalVideoRepositoryImpl.resolveRenamedDisplayAndTitle("trip.2026.final", "mp4")
        assertNotNull(dotRename)
        assertEquals("trip.2026.final.mp4", dotRename!!.first)
        assertEquals("trip.2026.final", dotRename.second)

        val explicitExtRename = LocalVideoRepositoryImpl.resolveRenamedDisplayAndTitle("vacation.MP4", "mp4")
        assertEquals("vacation.mp4", explicitExtRename!!.first)
        assertEquals("vacation", explicitExtRename.second)

        val differentDotExtRename = LocalVideoRepositoryImpl.resolveRenamedDisplayAndTitle("doc.txt", "mkv")
        assertEquals("doc.txt.mkv", differentDotExtRename!!.first)
        assertEquals("doc.txt", differentDotExtRename.second)

        // Path traversal sanitization forces whitelisted root & strips '..' and illegal chars
        val traversed = LocalVideoRepositoryImpl.sanitizeRelativePath("Movies/../../secret/my:clip*", "fallback")
        assertEquals("${Environment.DIRECTORY_MOVIES}/secret/myclip/", traversed)

        val unrooted = LocalVideoRepositoryImpl.sanitizeRelativePath("../custom_folder", "custom_folder")
        assertEquals("${Environment.DIRECTORY_MOVIES}/custom_folder/", unrooted)
    }

    @Test
    fun thumbnailRetriever_downscalesAspectPreservingWithoutUpscaling() {
        val (w1, h1) = ThumbnailRetriever.computeScaledDimensions(1920, 1080, 160, 90)
        assertEquals(160, w1)
        assertEquals(90, h1)

        val (w2, h2) = ThumbnailRetriever.computeScaledDimensions(1080, 1920, 160, 90)
        assertTrue(w2 <= 160 && h2 <= 90)
        assertEquals(50, w2)
        assertEquals(90, h2)

        // Smaller source is never upscaled
        val (w3, h3) = ThumbnailRetriever.computeScaledDimensions(80, 45, 160, 90)
        assertEquals(80, w3)
        assertEquals(45, h3)
    }

    @Test
    fun offlineModelManager_preservesBackupOnFailedReplacementAndRecoversOnInit() = runBlocking {
        val rootDir = tempFolder.newFolder("vosk_bak_recovery")
        val fakeBridge = object : MlKitModelBridge {
            override suspend fun getDownloadedLanguageCodes(): Set<String> = setOf("en")
            override suspend fun downloadLanguageModel(mlKitCode: String) {}
            override suspend fun deleteLanguageModel(mlKitCode: String) {}
        }

        // Seed existing valid English Vosk model and simulate an aborted replacement backup (.bak)
        val voskDir = File(rootDir, "vosk").apply { mkdirs() }
        val bakDir = File(voskDir, "vosk-model-small-en-us-0.15.bak").apply {
            File(this, "am").mkdirs()
            File(this, "am/final.mdl").writeBytes(byteArrayOf(1, 2, 3, 4))
            File(this, "conf").mkdirs()
            File(this, "conf/model.conf").writeText("--sample-frequency=16000")
            File(this, "graph").mkdirs()
            File(this, "graph/Gr.fst").writeBytes(byteArrayOf(5, 6, 7))
            File(this, "graph/words.txt").writeText("<eps> 0\nhi 1\n")
        }
        // Corrupt/incomplete live directory as if a crash happened mid-move
        File(voskDir, "vosk-model-small-en-us-0.15").apply {
            mkdirs()
            File(this, "broken.txt").writeText("partial")
        }

        val manager = OfflineModelManager(testRootDir = rootDir, mlKitBridge = fakeBridge)
        manager.refreshAllStates()

        assertFalse(".bak should be restored into live model dir", bakDir.exists())
        assertEquals(
            OfflineModelDownloadStatus.READY,
            manager.languageStates.value[SubtitleTranslationLanguage.ENGLISH]?.speechStatus
        )
        assertNotNull(manager.getVerifiedSpeechModelDirectory(SubtitleTranslationLanguage.ENGLISH))
    }

    @Test
    fun playerViewModel_blocksCloudProxyForVaultUriEvenWithCustomProvider() = runBlocking {
        val app = testApp()
        val fakePlayer = PlaybackIntegrationTest.FakeExoPlayer()
        val playerManager = VideoPlayerManager.createForTesting(fakePlayer.exoPlayer)
        val playbackPrefs = object : PlaybackPreferencesRepository {
            override val playbackSpeed: Flow<Float> = MutableStateFlow(1.0f)
            override val resizeMode: Flow<Int> = MutableStateFlow(0)
            override val repeatMode: Flow<String> = MutableStateFlow("OFF")
            override val playbackMode: Flow<PlaybackMode> = MutableStateFlow(PlaybackMode.PLAY_ALL_IN_ORDER)
            override val resumeMode: Flow<ResumeMode> = MutableStateFlow(ResumeMode.RESUME)
            override suspend fun setPlaybackSpeed(speed: Float) {}
            override suspend fun setResizeMode(mode: Int) {}
            override suspend fun setRepeatMode(mode: String) {}
            override suspend fun saveVideoPosition(videoUri: String, positionMs: Long, durationMs: Long) {}
            override suspend fun getSavedVideoPosition(videoUri: String, durationMs: Long, legacyVideoId: Long?): Long = 0L
        }
        val posManager = PlaybackPositionManager(playbackPrefs)
        val vm = PlayerViewModel(
            application = app,
            playerManager = playerManager,
            playbackPreferences = playbackPrefs,
            positionManager = posManager,
            subtitlePreferences = null,
            thumbnailManager = null
        )

        var providerInvoked = false
        val spyProvider = object : SubtitleTranslationProvider {
            override suspend fun translateBatch(request: SubtitleBatchRequest): SubtitleBatchResponse {
                providerInvoked = true
                return SubtitleBatchResponse(emptyMap())
            }

            override suspend fun transcribeAndTranslateAudio(
                audioFile: File,
                sourceLanguage: SubtitleTranslationLanguage,
                targetLanguage: SubtitleTranslationLanguage
            ): List<TranslatedCuePair> {
                providerInvoked = true
                return emptyList()
            }
        }

        val track = SubtitleTrack(
            id = "vault_track",
            name = "Test",
            uri = Uri.EMPTY,
            language = "en",
            cues = listOf(SubtitleCue(1, 0L, 1_000L, "Top secret")),
            isExternal = true
        )
        vm.setTranslationEngineMode(TranslationEngineMode.CLOUD_PROXY)
        vm.startSmartSubtitleTranslation(
            context = app,
            videoUri = Uri.parse("vault://42"),
            sourceMode = SubtitleSourceMode.EXISTING_SUBTITLE,
            sourceLanguage = SubtitleTranslationLanguage.ENGLISH,
            targetLanguage = SubtitleTranslationLanguage.ARABIC,
            sourceTrack = track,
            customProvider = spyProvider
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Cloud proxy provider must never be called for vault:// video", providerInvoked)
        assertTrue(vm.translationStatus.value is TranslationJobStatus.Error)
        playerManager.release()
        posManager.shutdown(200L)
    }
}
