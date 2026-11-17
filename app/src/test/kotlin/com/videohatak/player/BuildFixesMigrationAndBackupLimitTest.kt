package com.videohatak.player

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.videohatak.player.core.backup.BackupManager
import com.videohatak.player.core.backup.BackupParseResult
import com.videohatak.player.core.backup.BackupPayload
import com.videohatak.player.core.backup.BackupSettingsData
import com.videohatak.player.core.backup.BackupSubtitleData
import com.videohatak.player.core.backup.BackupVaultSecurityData
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepository
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playlist.db.BackupPlaylistStore
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.repository.LocalSubtitleStore
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesStore
import com.videohatak.player.core.subtitle.repository.SubtitleStyleConfig
import com.videohatak.player.core.subtitle.translation.MlKitModelBridge
import com.videohatak.player.core.subtitle.translation.OfflineModelDownloadStatus
import com.videohatak.player.core.subtitle.translation.OfflineModelManager
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultSecurityBackupStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BuildFixesMigrationAndBackupLimitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(60)

    private class FakeThemeRepo : ThemePreferencesRepository {
        val selected = MutableStateFlow(AppThemeId.OBSIDIAN_ELITE)
        val mode = MutableStateFlow(ThemeMode.DARK)
        override val appThemeFlow: Flow<AppThemeId> = selected
        override val themeModeFlow: Flow<ThemeMode> = mode
        override suspend fun setAppTheme(theme: AppThemeId) { selected.value = theme }
        override suspend fun setThemeMode(mode: ThemeMode) { this.mode.value = mode }
    }

    private class FakePlaybackPrefs : PlaybackPreferencesRepository {
        val speed = MutableStateFlow(1.0f)
        val resize = MutableStateFlow(0)
        val repeat = MutableStateFlow("OFF")
        val mode = MutableStateFlow(PlaybackMode.PLAY_ALL_IN_ORDER)
        val resume = MutableStateFlow(ResumeMode.RESUME)
        val seekInterval = MutableStateFlow(10)
        private val positions = linkedMapOf<String, Long>()

        override val playbackSpeed: Flow<Float> = speed
        override val resizeMode: Flow<Int> = resize
        override val repeatMode: Flow<String> = repeat
        override val playbackMode: Flow<PlaybackMode> = mode
        override val resumeMode: Flow<ResumeMode> = resume
        override val seekIntervalSeconds: Flow<Int> = seekInterval

        override suspend fun setPlaybackSpeed(speed: Float) { this.speed.value = speed }
        override suspend fun setResizeMode(mode: Int) { resize.value = mode }
        override suspend fun setRepeatMode(mode: String) { repeat.value = mode }
        override suspend fun setPlaybackMode(mode: PlaybackMode) { this.mode.value = mode }
        override suspend fun setResumeMode(mode: ResumeMode) { resume.value = mode }
        override suspend fun setSeekIntervalSeconds(seconds: Int) { seekInterval.value = seconds }
        override suspend fun saveVideoPosition(videoUri: String, positionMs: Long, durationMs: Long) {
            positions[videoUri] = positionMs
        }
        override suspend fun getSavedVideoPosition(videoUri: String, durationMs: Long, legacyVideoId: Long?): Long =
            positions[videoUri] ?: 0L
        override suspend fun exportSavedPositions(): Map<String, Long> = positions.toMap()
        override suspend fun restoreSavedPositions(positions: Map<String, Long>) {
            this.positions.clear()
            this.positions.putAll(positions)
        }
    }

    private class FakeSubtitlePrefStore : SubtitlePreferencesStore {
        val primary = MutableStateFlow(SubtitleStyleConfig())
        val secondary = MutableStateFlow(SubtitleStyleConfig())
        override val primaryStyle: Flow<SubtitleStyleConfig> = primary
        override val secondaryStyle: Flow<SubtitleStyleConfig> = secondary
        override suspend fun updatePrimaryFontSize(sizeSp: Float) {
            primary.value = primary.value.copy(fontSizeSp = sizeSp)
        }
        override suspend fun updateSecondaryFontSize(sizeSp: Float) {
            secondary.value = secondary.value.copy(fontSizeSp = sizeSp)
        }
    }

    private class FakeVaultSecurityStore : VaultSecurityBackupStore {
        val timeout = MutableStateFlow(VaultAutoLockTimeout.IMMEDIATE)
        override val autoLockTimeout: Flow<VaultAutoLockTimeout> = timeout
        override suspend fun setAutoLockTimeout(timeout: VaultAutoLockTimeout) {
            this.timeout.value = timeout
        }
    }

    private class FakePlaylistStore : BackupPlaylistStore {
        private val data = mutableListOf<Pair<Playlist, List<PlaylistItem>>>()
        override fun getPlaylists(): List<Playlist> = data.map { it.first }
        override fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem> =
            data.firstOrNull { it.first.id == playlistId }?.second.orEmpty()
        override fun replaceAllPlaylistsAndItems(entries: List<Pair<Playlist, List<PlaylistItem>>>) {
            data.clear()
            entries.forEachIndexed { idx, pair -> data.add(pair.first.copy(id = idx + 1L) to pair.second) }
        }
    }

    private fun seedLegacyPlaintextDir(
        rootDir: File,
        videoKeyUri: String,
        trackId: String = "legacy_track_1",
        trackName: String = "ترجمة قديمة",
        srtText: String = "1\n00:00:01,000 --> 00:00:03,000\nسطر قديم سري\n",
        ext: String = "srt"
    ): File {
        val dir = File(rootDir, LocalSubtitleStore.computeVideoKeyHash(videoKeyUri)).apply { mkdirs() }
        val fileName = "$trackId.$ext"
        File(dir, fileName).writeText(srtText, Charsets.UTF_8)
        val arr = JSONArray().put(
            JSONObject().apply {
                put("id", trackId)
                put("name", trackName)
                put("language", "ar")
                put("format", if (ext == "vtt") SubtitleFormat.WEBVTT.name else SubtitleFormat.SRT.name)
                put("fileName", fileName)
                put("updatedAtMs", 1700000000L)
            }
        )
        File(dir, "index.json").writeText(arr.toString(), Charsets.UTF_8)
        return dir
    }

    // -------------------------------------------------------------------------
    // 1. OfflineModelManager VoskModelCatalog.officialModels lookup on .bak recovery
    // -------------------------------------------------------------------------

    @Test
    fun offlineModelManager_recoverInterruptedBackup_matchesOfficialModelsCatalogCleanly() = runBlocking {
        val rootDir = tempFolder.newFolder("vosk_bak_official_models")
        val fakeBridge = object : MlKitModelBridge {
            override suspend fun getDownloadedLanguageCodes(): Set<String> = setOf("en", "fr")
            override suspend fun downloadLanguageModel(mlKitCode: String) {}
            override suspend fun deleteLanguageModel(mlKitCode: String) {}
        }

        val voskDir = File(rootDir, "vosk").apply { mkdirs() }
        val frenchBakDir = File(voskDir, "vosk-model-small-fr-0.22.bak").apply {
            File(this, "am").mkdirs()
            File(this, "am/final.mdl").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            File(this, "conf").mkdirs()
            File(this, "conf/model.conf").writeText("--sample-frequency=16000")
            File(this, "graph").mkdirs()
            File(this, "graph/HCLG.fst").writeBytes(byteArrayOf(9, 8, 7))
            File(this, "graph/words.txt").writeText("<eps> 0\nbonjour 1\n")
        }

        val manager = OfflineModelManager(testRootDir = rootDir, mlKitBridge = fakeBridge)
        manager.refreshAllStates()

        assertFalse(".bak folder should be promoted to live directory", frenchBakDir.exists())
        assertEquals(
            OfflineModelDownloadStatus.READY,
            manager.languageStates.value[SubtitleTranslationLanguage.FRENCH]?.speechStatus
        )
        assertNotNull(manager.getVerifiedSpeechModelDirectory(SubtitleTranslationLanguage.FRENCH))
    }

    // -------------------------------------------------------------------------
    // 2. Legacy Vault Subtitle Safe Migration, Verification Gate & Unclassified Exclusion
    // -------------------------------------------------------------------------

    @Test
    fun legacyVaultSubtitles_discoversNonVaultPrefixKeysFromVaultDb_encryptsVerifiesAndDeletesPlaintext() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val subRoot = tempFolder.newFolder("vault_migration_db_test")
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")

        val encFile = File(appContext.filesDir, "vault_encrypted/vault_item_55.enc").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val dbHelper = VaultDatabaseHelper(appContext)
        val rowId = try {
            dbHelper.insertVideo(
                VaultVideo(
                    id = 55L,
                    displayName = "مقطع خاص",
                    originalSizeBytes = 100L,
                    encryptedSizeBytes = 128L,
                    durationMs = 15_000L,
                    mimeType = "application/octet-stream",
                    width = 1920,
                    height = 1080,
                    relativeEncryptedPath = encFile.name,
                    createdAtEpochMs = 1700000000L,
                    ivBase64 = "aXY="
                )
            )
        } finally {
            dbHelper.close()
        }

        try {
            // Legacy Vault subtitles saved without `vault://` prefix (e.g. file:// URI to vault .enc)
            val legacyFileUri = Uri.fromFile(encFile).toString()
            val legacyVaultFileDir = seedLegacyPlaintextDir(
                rootDir = subRoot,
                videoKeyUri = legacyFileUri,
                trackId = "t_file_uri",
                trackName = "ترجمة خزنة بمسار ملف",
                srtText = "1\n00:00:01,000 --> 00:00:03,000\nنص خزنة قديم غير مبدوء بـ vault\n"
            )
            // Legacy Vault subtitles saved with `vault://55` before `.vault_private` / `.vsub` existed
            val legacyVaultSchemeDir = seedLegacyPlaintextDir(
                rootDir = subRoot,
                videoKeyUri = "vault://$rowId",
                trackId = "t_vault_scheme",
                trackName = "ترجمة خزنة قديمة",
                srtText = "1\n00:00:02,000 --> 00:00:04,000\nسر قديم بالخزنة\n"
            )

            val store = LocalSubtitleStore(
                subtitlesRootDir = subRoot,
                cryptoManager = crypto,
                vaultCandidateUrisProvider = {
                    LocalSubtitleStore.discoverVaultSubtitleCandidateUris(appContext, crypto)
                }
            )
            // Verified public track
            store.saveTrackForVideo(
                videoUri = "content://media/external/video/media/public_7",
                trackName = "ترجمة عامة موثقة",
                language = "ar",
                cues = listOf(SubtitleCue(1, 0L, 2_000L, "نص عام")),
                format = SubtitleFormat.SRT
            )

            // Trigger backup export -> auto-migrates legacy Vault dirs and strictly excludes them
            val exported = store.exportAllStoredTracksForBackup()
            assertEquals(1, exported.size)
            assertEquals("ترجمة عامة موثقة", exported.first().trackName)
            assertTrue(exported.none { it.content.contains("خزنة") })

            for ((dir, uri, expectedText) in listOf(
                Triple(legacyVaultFileDir, legacyFileUri, "نص خزنة قديم غير مبدوء بـ vault"),
                Triple(legacyVaultSchemeDir, "vault://$rowId", "سر قديم بالخزنة")
            )) {
                assertTrue(File(dir, ".vault_private").exists())
                assertTrue(File(dir, "index.vsub").exists())
                assertFalse("Plaintext index.json must be deleted after verification", File(dir, "index.json").exists())
                assertTrue("Plaintext .srt must be deleted after verification", dir.listFiles()?.none { it.extension == "srt" } == true)
                val vsubFile = dir.listFiles()?.firstOrNull { it.extension == "vsub" && it.name != "index.vsub" }
                assertNotNull(vsubFile)
                assertTrue(vsubFile!!.readText(Charsets.UTF_8).startsWith("VHTKMETA1:"))

                val reloaded = store.loadTracksForVideo(uri)
                assertEquals(1, reloaded.size)
                assertEquals(expectedText, reloaded.first().cues.first().text)
            }

            assertTrue(store.lastMigrationReport.migratedDirectories.contains(legacyVaultFileDir.name))
            assertTrue(store.lastMigrationReport.migratedDirectories.contains(legacyVaultSchemeDir.name))
            assertTrue(store.lastMigrationReport.failedDirectories.isEmpty())
        } finally {
            VaultDatabaseHelper(appContext).apply {
                deleteVideo(rowId)
                close()
            }
            encFile.delete()
        }
    }

    @Test
    fun legacyVaultSubtitles_verificationFailureOrNullCrypto_preservesPlaintextAndStillBlocksBackupLeakage() = runBlocking {
        val subRoot = tempFolder.newFolder("vault_migration_fail_preserve")
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val vaultUri = "vault://88"
        val secretSrt = "1\n00:00:01,000 --> 00:00:03,000\nبيانات يجب ألا تضيع\n"
        val dir = seedLegacyPlaintextDir(
            rootDir = subRoot,
            videoKeyUri = vaultUri,
            trackId = "t_safe",
            trackName = "محمية",
            srtText = secretSrt
        )

        // 1. Fail verification after writing index.vsub by corrupting .vsub right before verify
        val failingStore = LocalSubtitleStore(
            subtitlesRootDir = subRoot,
            cryptoManager = crypto,
            vaultCandidateUrisProvider = { setOf(vaultUri) }
        )
        failingStore.migrationFaultHook = { stage, targetDir ->
            if (stage == "after_index_write") {
                File(targetDir, "t_safe.vsub").writeText("VHTKMETA1:CORRUPTED_BASE64", Charsets.UTF_8)
            }
        }
        val exportedAfterFail = failingStore.exportAllStoredTracksForBackup()
        assertTrue("Failed Vault migration must NEVER leak into backup", exportedAfterFail.isEmpty())
        assertTrue("Original index.json must remain intact on verification failure", File(dir, "index.json").exists())
        assertTrue("Original .srt must remain intact on verification failure", File(dir, "t_safe.srt").exists())
        assertEquals(secretSrt, File(dir, "t_safe.srt").readText(Charsets.UTF_8))
        assertTrue(failingStore.lastMigrationReport.failedDirectories.containsKey(dir.name))

        // 2. Subsequent clean recovery (or crash-resume) repairs corrupt .vsub, verifies, and cleans plaintext safely
        val cleanStore = LocalSubtitleStore(
            subtitlesRootDir = subRoot,
            cryptoManager = crypto,
            vaultCandidateUrisProvider = { setOf(vaultUri) }
        )
        val report = cleanStore.migrateLegacyVaultSubtitles()
        assertTrue(report.migratedDirectories.contains(dir.name))
        assertFalse(File(dir, "index.json").exists())
        assertFalse(File(dir, "t_safe.srt").exists())
        val loaded = cleanStore.loadTracksForVideo(vaultUri)
        assertEquals(1, loaded.size)
        assertEquals("بيانات يجب ألا تضيع", loaded.first().cues.first().text)
    }

    @Test
    fun legacyVaultSubtitles_resumesCleanlyAfterInterruptedPartialMigration() = runBlocking {
        val subRoot = tempFolder.newFolder("vault_migration_resume")
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val vaultUri = "vault://91"
        val dir = File(subRoot, LocalSubtitleStore.computeVideoKeyHash(vaultUri)).apply { mkdirs() }

        val srt1 = "1\n00:00:01,000 --> 00:00:02,000\nالمقطع الأول\n"
        val vtt2 = "WEBVTT\n\n00:00:03.000 --> 00:00:05.000\nالمقطع الثاني\n"

        // Simulate crash mid-migration: track1 already has .vsub, track2 only had .tmp, index.json + plaintext still exist
        File(dir, ".vault_private").writeText("vault", Charsets.UTF_8)
        File(dir, "t1.srt").writeText(srt1, Charsets.UTF_8)
        File(dir, "t2.vtt").writeText(vtt2, Charsets.UTF_8)
        File(dir, "t1.vsub").writeText(crypto.encryptMetadata(srt1), Charsets.UTF_8)
        File(dir, "t2.vsub.tmp").writeText("partial_interrupted_write", Charsets.UTF_8)
        File(dir, "index.vsub.tmp").writeText("partial_index", Charsets.UTF_8)
        val legacyIndex = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "t1")
                put("name", "الأولى")
                put("language", "ar")
                put("format", SubtitleFormat.SRT.name)
                put("fileName", "t1.srt")
                put("updatedAtMs", 100L)
            })
            put(JSONObject().apply {
                put("id", "t2")
                put("name", "الثانية")
                put("language", "ar")
                put("format", SubtitleFormat.WEBVTT.name)
                put("fileName", "t2.vtt")
                put("updatedAtMs", 200L)
            })
        }
        File(dir, "index.json").writeText(legacyIndex.toString(), Charsets.UTF_8)

        val store = LocalSubtitleStore(
            subtitlesRootDir = subRoot,
            cryptoManager = crypto,
            vaultCandidateUrisProvider = { setOf(vaultUri) }
        )
        val report = store.migrateLegacyVaultSubtitles()
        assertTrue(report.migratedDirectories.contains(dir.name))
        assertFalse(File(dir, "index.json").exists())
        assertFalse(File(dir, "t1.srt").exists())
        assertFalse(File(dir, "t2.vtt").exists())
        assertFalse(File(dir, "t2.vsub.tmp").exists())
        assertFalse(File(dir, "index.vsub.tmp").exists())
        assertTrue(File(dir, "index.vsub").exists())
        assertTrue(File(dir, "t1.vsub").exists())
        assertTrue(File(dir, "t2.vsub").exists())

        val reloaded = store.loadTracksForVideo(vaultUri)
        assertEquals(2, reloaded.size)
        assertEquals("المقطع الأول", reloaded.first { it.id == "t1" }.cues.first().text)
        assertEquals("المقطع الثاني", reloaded.first { it.id == "t2" }.cues.first().text)
    }

    @Test
    fun legacySubtitles_unclassifiedDirectoryIsPreservedUntouchedAndExcludedFromPublicBackup() = runBlocking {
        val subRoot = tempFolder.newFolder("sub_unclassified_vs_public")
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")

        // Unclassified legacy dir (neither vault match nor .public_verified)
        val unclassifiedUri = "file:///storage/emulated/0/unknown_legacy_video.mp4"
        val unclassifiedDir = seedLegacyPlaintextDir(
            rootDir = subRoot,
            videoKeyUri = unclassifiedUri,
            trackId = "legacy_unc",
            trackName = "غير مصنفة",
            srtText = "1\n00:00:01,000 --> 00:00:03,000\nمحتوى قديم غير مصنف\n"
        )

        val store = LocalSubtitleStore(subRoot, crypto)

        // Normal public track continues working and backing up normally
        val publicUri = "content://media/external/video/media/404"
        store.saveTrackForVideo(
            videoUri = publicUri,
            trackName = "ترجمة عادية",
            language = "ar",
            cues = listOf(SubtitleCue(1, 0L, 1_500L, "ترجمة فيديو عادي")),
            format = SubtitleFormat.SRT
        )

        val exported = store.exportAllStoredTracksForBackup()
        assertEquals(1, exported.size)
        assertEquals("ترجمة عادية", exported.first().trackName)
        assertTrue(store.lastMigrationReport.unclassifiedDirectories.contains(unclassifiedDir.name))

        // Public backup restore wipes public tracks while preserving unclassified legacy dir untouched
        store.replaceAllStoredTracksFromBackup(emptyList())
        assertTrue(store.loadTracksForVideo(publicUri).isEmpty())
        assertTrue(File(unclassifiedDir, "index.json").exists())
        assertTrue(File(unclassifiedDir, "legacy_unc.srt").exists())
        assertEquals(1, store.loadTracksForVideo(unclassifiedUri).size)
    }

    // -------------------------------------------------------------------------
    // 3. Unified 16MB Backup Size Limit across Export & Import
    // -------------------------------------------------------------------------

    private fun buildSizedPayload(totalTargetBytes: Int): BackupPayload {
        // Use 10 localSubtitle tracks (< 2MB each) so MiniJsonParser limits hold cleanly up to 16MB
        val trackCount = 10
        val placeholderTracks = (0 until trackCount).map { idx ->
            LocalSubtitleStore.BackupStoredSubtitleTrack(
                videoKeyHash = "%032x".format(idx + 1),
                trackId = "track_$idx",
                trackName = "Track $idx",
                language = "en",
                format = SubtitleFormat.SRT,
                content = "A".repeat(1_000),
                updatedAtMs = 1700000000L
            )
        }
        val basePayload = BackupPayload(
            schemaVersion = BackupManager.SCHEMA_VERSION,
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
            subtitles = BackupSubtitleData(18f, 15f),
            vaultSecurity = BackupVaultSecurityData(VaultAutoLockTimeout.IMMEDIATE),
            playlists = emptyList(),
            resumePositions = emptyMap(),
            localSubtitles = placeholderTracks
        )
        val baseSize = BackupManager.serializeBackupJson(basePayload).toByteArray(Charsets.UTF_8).size
        val extraNeeded = totalTargetBytes - baseSize
        val chunkPerTrack = extraNeeded / trackCount
        val remainder = extraNeeded % trackCount
        val finalTracks = placeholderTracks.mapIndexed { idx, track ->
            val add = chunkPerTrack + if (idx == trackCount - 1) remainder else 0
            track.copy(content = "A".repeat(1_000 + add))
        }
        return basePayload.copy(localSubtitles = finalTracks)
    }

    @Test
    fun backupManager_unified16MbLimit_succeedsAtExact16MbAndRoundTripsWithImport() = runBlocking {
        val manager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null
        )

        val exact16MbPayload = buildSizedPayload(BackupManager.MAX_BACKUP_BYTES)
        val serializedExactBytes = BackupManager.serializeBackupJson(exact16MbPayload).toByteArray(Charsets.UTF_8).size
        assertEquals(BackupManager.MAX_BACKUP_BYTES, serializedExactBytes)

        val exactFile = File(tempFolder.root, "exact_16mb_backup.json")
        val stagingDir = tempFolder.newFolder("staging_exact")
        val exportResult = manager.exportPayloadToFile(exact16MbPayload, exactFile, stagingDir)

        assertTrue("Exact 16MB backup export must succeed", exportResult.isSuccess)
        assertTrue(exactFile.exists())
        assertEquals(BackupManager.MAX_BACKUP_BYTES.toLong(), exactFile.length())
        assertEquals(BackupManager.MAX_BACKUP_BYTES, exportResult.getOrThrow().byteSize)
        assertTrue("Staging directory must have no leftover temp files", stagingDir.listFiles().isNullOrEmpty())

        // Exact 16MB exported file must import cleanly on the same version
        val importResult = manager.importFromFile(exactFile)
        assertTrue("Exact 16MB backup import must succeed: ${importResult.exceptionOrNull()}", importResult.isSuccess)
        assertEquals(10, importResult.getOrThrow().localSubtitleTrackCount)
    }

    @Test
    fun backupManager_rejectsOver16MbAndMultiByteUtf8OverflowWithoutLeavingCorruptOutputFile() = runBlocking {
        val manager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null
        )

        // 1. Over limit by 1 single byte (16MB + 1)
        val over1BytePayload = buildSizedPayload(BackupManager.MAX_BACKUP_BYTES + 1)
        val overTargetFile = File(tempFolder.root, "oversized_backup.json")
        val stagingDir = tempFolder.newFolder("staging_over")

        val exportOverResult = manager.exportPayloadToFile(over1BytePayload, overTargetFile, stagingDir)
        assertTrue("Backup of 16MB + 1 byte must be rejected on export", exportOverResult.isFailure)
        assertFalse("Rejected export must not create partial/corrupt target file", overTargetFile.exists())
        assertTrue("Staging directory must clean up temp files on failure", stagingDir.listFiles().isNullOrEmpty())
        val errMsg = exportOverResult.exceptionOrNull()?.localizedMessage.orEmpty()
        assertTrue("Error message must clearly explain 16 MB limit: $errMsg", errMsg.contains("16 MB"))

        // 2. Import of 16MB + 1 byte file must also be rejected cleanly
        val rawOversizedBytes = BackupManager.serializeBackupJson(over1BytePayload).toByteArray(Charsets.UTF_8)
        assertEquals(BackupManager.MAX_BACKUP_BYTES + 1, rawOversizedBytes.size)
        val manualOverFile = tempFolder.newFile("manual_over_16mb.json").apply { writeBytes(rawOversizedBytes) }
        val importOverResult = manager.importFromFile(manualOverFile)
        assertTrue("Import of >16MB file must also fail", importOverResult.isFailure)
        assertTrue(importOverResult.exceptionOrNull()?.localizedMessage.orEmpty().contains("16 MB"))

        // 3. Multi-byte UTF-8 Arabic payload: String.length < 16MB while actual UTF-8 file bytes > 16MB
        val arabicTracks = (0 until 10).map { idx ->
            LocalSubtitleStore.BackupStoredSubtitleTrack(
                videoKeyHash = "%032x".format(idx + 100),
                trackId = "ar_$idx",
                trackName = "عربي $idx",
                language = "ar",
                format = SubtitleFormat.SRT,
                content = "ب".repeat(900_000), // 900k chars -> 1.8M UTF-8 bytes per track (total 18MB bytes!)
                updatedAtMs = 1700000000L
            )
        }
        val arabicPayload = buildSizedPayload(100_000).copy(localSubtitles = arabicTracks)
        val arabicRawJson = BackupManager.serializeBackupJson(arabicPayload)
        assertTrue("String.length is < 16MB", arabicRawJson.length < BackupManager.MAX_BACKUP_BYTES)
        assertTrue("Actual UTF-8 byte size exceeds 16MB", arabicRawJson.toByteArray(Charsets.UTF_8).size > BackupManager.MAX_BACKUP_BYTES)

        val arabicOutFile = File(tempFolder.root, "arabic_over_16mb.json")
        val arabicExportRes = manager.exportPayloadToFile(arabicPayload, arabicOutFile, stagingDir)
        assertTrue("UTF-8 file size > 16MB must be rejected on export", arabicExportRes.isFailure)
        assertFalse(arabicOutFile.exists())

        // 4. Verify exportToUri via Robolectric context does not leak empty file:// placeholder on overflow
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val subRoot = tempFolder.newFolder("uri_export_test_subs")
        val subStore = LocalSubtitleStore(subRoot, VaultCryptoManager("TEST_PROVIDER"))
        val bigText = "1\n00:00:01,000 --> 00:00:02,000\n" + "X".repeat(1_850_000) + "\n"
        repeat(10) { idx ->
            subStore.saveTrackForVideo(
                videoUri = "content://media/external/video/media/big_$idx",
                trackName = "Big $idx",
                language = "en",
                cues = com.videohatak.player.core.subtitle.parser.SubtitleParser.parse(bigText, SubtitleFormat.SRT),
                format = SubtitleFormat.SRT
            )
        }
        val uriBackupManager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = subStore,
            context = appContext
        )
        val uriTargetFile = File(tempFolder.root, "uri_over_limit.json").apply { writeBytes(ByteArray(0)) }
        val uriResult = uriBackupManager.exportToUri(Uri.fromFile(uriTargetFile))
        assertTrue(uriResult.isFailure)
        assertFalse("0-byte placeholder should be cleaned up on export failure", uriTargetFile.exists())
        assertEquals(true, BackupManager.parseAndValidateBackupJson(BackupManager.serializeBackupJson(buildSizedPayload(60_000))) is BackupParseResult.Success)
    }
}
