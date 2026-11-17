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
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import com.videohatak.player.core.subtitle.repository.LocalSubtitleStore
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesStore
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
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

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

    @Test
    fun backupManager_exportToUri_cleansPartialNonEmptyPlaceholderAndRestoresExistingFileOnMidWriteFailure() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.videohatak.player.test.failingbackupprovider"
        val backingDir = tempFolder.newFolder("failing_provider_docs")

        val docFiles = linkedMapOf<String, File>()
        val provider = object : android.content.ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): android.database.Cursor? {
                val id = uri.lastPathSegment ?: return null
                val file = docFiles[id] ?: return null
                val cols = projection ?: arrayOf(android.provider.OpenableColumns.SIZE)
                val cursor = android.database.MatrixCursor(cols)
                val row = Array<Any?>(cols.size) { idx ->
                    when (cols[idx]) {
                        android.provider.OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                }
                cursor.addRow(row)
                return cursor
            }

            override fun getType(uri: Uri): String = "application/json"
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                val id = uri.lastPathSegment ?: return 0
                val removed = docFiles.remove(id) ?: return 0
                return if (removed.delete()) 1 else 0
            }

            override fun update(
                uri: Uri,
                values: android.content.ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?
            ): Int = 0

            override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
                if (method == "android:deleteDocument") {
                    val targetUri = extras?.let {
                        androidx.core.os.BundleCompat.getParcelable(it, "uri", Uri::class.java)
                    } ?: arg?.let(Uri::parse)
                    val id = targetUri?.lastPathSegment
                    val removed = if (id != null) docFiles.remove(id) else null
                    val deleted = removed?.delete() == true
                    return android.os.Bundle().apply {
                        putBoolean("result", deleted)
                    }
                }
                return super.call(method, arg, extras)
            }

            override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
                val id = uri.lastPathSegment ?: throw java.io.FileNotFoundException()
                val file = docFiles[id] ?: throw java.io.FileNotFoundException()
                val pfdMode = if (mode.contains("w")) {
                    android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or
                        android.os.ParcelFileDescriptor.MODE_CREATE or
                        android.os.ParcelFileDescriptor.MODE_TRUNCATE
                } else {
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                }
                return android.os.ParcelFileDescriptor.open(file, pfdMode)
            }
        }

        val info = android.content.pm.ProviderInfo().apply {
            this.authority = authority
            this.exported = false
        }
        provider.attachInfo(appContext, info)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)

        val manager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null,
            context = appContext
        )
        val payload = buildSizedPayload(4_096)

        var failOnNextWrite = true
        var failAfterBytes = 128
        manager.uriStreamOpenerForTesting = { uri, mode ->
            val id = uri.lastPathSegment!!
            val backingFile = docFiles.getValue(id)
            if (mode.contains("w") && failOnNextWrite) {
                failOnNextWrite = false
                // Truncate upfront just like SAF "wt"
                val inner = java.io.FileOutputStream(backingFile, false)
                object : java.io.OutputStream() {
                    private var written = 0
                    override fun write(b: Int) {
                        if (written >= failAfterBytes) {
                            throw java.io.IOException("Simulated disk full after $written bytes")
                        }
                        inner.write(b)
                        written++
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val remaining = failAfterBytes - written
                        if (remaining <= 0) {
                            throw java.io.IOException("Simulated disk full after $written bytes")
                        }
                        val chunk = minOf(len, remaining)
                        inner.write(b, off, chunk)
                        inner.flush()
                        written += chunk
                        if (chunk < len) {
                            throw java.io.IOException("Simulated disk full after $written bytes")
                        }
                    }

                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
            } else {
                appContext.contentResolver.openOutputStream(uri, mode)
            }
        }

        // Case 1: Fresh empty document (0 bytes before write) fails mid-write after writing 128 partial bytes
        val emptyDocFile = File(backingDir, "fresh_empty.json").apply { writeBytes(ByteArray(0)) }
        docFiles["fresh_empty"] = emptyDocFile
        val emptyDocUri = Uri.parse("content://$authority/document/fresh_empty")
        failOnNextWrite = true
        failAfterBytes = 128

        val freshFailResult = manager.exportPayloadToUri(payload, emptyDocUri)
        assertTrue("Mid-write failure must return failure", freshFailResult.isFailure)
        assertFalse(
            "Partial non-empty placeholder document must be deleted or cleaned up on mid-write failure",
            emptyDocFile.exists() && emptyDocFile.length() > 0L
        )
        assertFalse("Fresh placeholder document should be removed via deleteDocument", docFiles.containsKey("fresh_empty"))

        // Case 2: Pre-existing non-empty user file fails mid-write -> must restore original content (never delete or corrupt!)
        val originalUserBytes = """{"schemaVersion":3,"user":"original_intact"}""".toByteArray(Charsets.UTF_8)
        val existingDocFile = File(backingDir, "existing_user.json").apply { writeBytes(originalUserBytes) }
        docFiles["existing_user"] = existingDocFile
        val existingDocUri = Uri.parse("content://$authority/document/existing_user")
        failOnNextWrite = true
        failAfterBytes = 96

        val existingFailResult = manager.exportPayloadToUri(payload, existingDocUri)
        assertTrue("Mid-write failure on existing file must return failure", existingFailResult.isFailure)
        assertTrue("Pre-existing user document must NOT be deleted on failure", existingDocFile.exists())
        assertEquals(
            "Pre-existing user document must be restored intact without partial corruption",
            originalUserBytes.toString(Charsets.UTF_8),
            existingDocFile.readText(Charsets.UTF_8)
        )

        // Case 3: File URI (pre-existing non-empty) fails mid-write -> original file also restored intact
        val realUserFile = File(tempFolder.root, "existing_file_uri.json").apply { writeBytes(originalUserBytes) }
        val fileUri = Uri.fromFile(realUserFile)
        manager.uriStreamOpenerForTesting = { uri, mode ->
            if (uri == fileUri && mode.contains("w") && failOnNextWrite) {
                failOnNextWrite = false
                val inner = java.io.FileOutputStream(realUserFile, false)
                object : java.io.OutputStream() {
                    private var written = 0
                    override fun write(b: Int) {
                        if (written >= 64) throw java.io.IOException("Simulated IO failure")
                        inner.write(b)
                        written++
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val remaining = 64 - written
                        if (remaining <= 0) throw java.io.IOException("Simulated IO failure")
                        val chunk = minOf(len, remaining)
                        inner.write(b, off, chunk)
                        inner.flush()
                        written += chunk
                        if (chunk < len) throw java.io.IOException("Simulated IO failure")
                    }

                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
            } else {
                appContext.contentResolver.openOutputStream(uri, mode)
            }
        }
        failOnNextWrite = true
        val fileUriFail = manager.exportPayloadToUri(payload, fileUri)
        assertTrue(fileUriFail.isFailure)
        assertTrue(realUserFile.exists())
        assertEquals(originalUserBytes.toString(Charsets.UTF_8), realUserFile.readText(Charsets.UTF_8))
    }

    private fun assertNoLeftoverBackupTemps(vararg dirs: File) {
        for (dir in dirs) {
            if (!dir.exists()) continue
            val leftovers = dir.listFiles().orEmpty().filter { f ->
                f.name.endsWith(".tmp") || f.name.endsWith(".partial") || f.name.endsWith(".bak.tmp") || f.name.endsWith(".restore.tmp")
            }
            assertTrue(
                "No leftover backup temp/partial files allowed in ${dir.absolutePath}, found: ${leftovers.map { it.name }}",
                leftovers.isEmpty()
            )
        }
    }

    @Test
    fun backupManager_fileExport_newSuccess_isReadableIntegrityVerified_andLeavesNoTemps() = runBlocking {
        val themeRepo = FakeThemeRepo().apply { setAppTheme(AppThemeId.ROYAL_IVORY) }
        val playbackPrefs = FakePlaybackPrefs().apply { setPlaybackMode(PlaybackMode.SHUFFLE_ALL) }
        val manager = BackupManager.createForTesting(
            themeRepository = themeRepo,
            playbackRepository = playbackPrefs,
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null
        )

        val targetDir = tempFolder.newFolder("new_export_target")
        val stagingDir = tempFolder.newFolder("new_export_staging")
        val targetFile = File(targetDir, "videohatak_backup.json")

        val exportRes = manager.exportToFile(targetFile, stagingDir)
        assertTrue("New file export must succeed: ${exportRes.exceptionOrNull()}", exportRes.isSuccess)
        assertTrue(targetFile.exists() && targetFile.length() > 0L)
        assertEquals(targetFile.length().toInt(), exportRes.getOrThrow().byteSize)

        // Must be readable and pass JSON + SHA-256 verification and full import round-trip
        val parsed = BackupManager.parseAndValidateBackupJson(targetFile.readText(Charsets.UTF_8))
        assertTrue("Exported file must pass full integrity validation", parsed is BackupParseResult.Success)
        val imported = manager.importFromFile(targetFile)
        assertTrue("Exported file must import cleanly: ${imported.exceptionOrNull()}", imported.isSuccess)
        assertEquals(AppThemeId.ROYAL_IVORY, imported.getOrThrow().themeRestored)

        assertNoLeftoverBackupTemps(targetDir, stagingDir)
    }

    @Test
    fun backupManager_fileExport_midStageWriteFailure_preservesExistingValidBackupAndLeavesNoTemps() = runBlocking {
        val manager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null
        )

        val targetDir = tempFolder.newFolder("mid_stage_target")
        val stagingDir = tempFolder.newFolder("mid_stage_dir")
        val targetFile = File(targetDir, "existing_backup.json")

        val oldPayload = buildSizedPayload(2_048)
        assertTrue(manager.exportPayloadToFile(oldPayload, targetFile, stagingDir).isSuccess)
        val oldContent = targetFile.readText(Charsets.UTF_8)

        val newPayload = buildSizedPayload(4_096)
        manager.fileOutputStreamOpenerForTesting = { file, role ->
            if (role == "stage") {
                val inner = FileOutputStream(file)
                object : OutputStream() {
                    private var written = 0
                    override fun write(b: Int) {
                        if (written >= 120) throw IOException("Simulated mid-stage disk full")
                        inner.write(b)
                        written++
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val rem = 120 - written
                        if (rem <= 0) throw IOException("Simulated mid-stage disk full")
                        val chunk = minOf(len, rem)
                        inner.write(b, off, chunk)
                        written += chunk
                        if (chunk < len) throw IOException("Simulated mid-stage disk full")
                    }
                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
            } else {
                FileOutputStream(file)
            }
        }

        val failedExport = manager.exportPayloadToFile(newPayload, targetFile, stagingDir)
        assertTrue("Mid-stage write failure must fail export", failedExport.isFailure)
        assertTrue(targetFile.exists())
        assertEquals("Pre-existing valid backup must remain untouched", oldContent, targetFile.readText(Charsets.UTF_8))
        assertTrue(BackupManager.parseAndValidateBackupJson(targetFile.readText(Charsets.UTF_8)) is BackupParseResult.Success)
        assertTrue(manager.importFromFile(targetFile).isSuccess)
        assertNoLeftoverBackupTemps(targetDir, stagingDir)
    }

    @Test
    fun backupManager_fileExport_partialToDestinationCopyFailure_restoresExistingValidBackupAndCleansTemps() = runBlocking {
        val manager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null
        )

        val targetDir = tempFolder.newFolder("copy_fail_target")
        val stagingDir = tempFolder.newFolder("copy_fail_staging")
        val existingFile = File(targetDir, "existing.json")
        val oldPayload = buildSizedPayload(3_072)
        assertTrue(manager.exportPayloadToFile(oldPayload, existingFile, stagingDir).isSuccess)
        val oldBytes = existingFile.readBytes()

        val newPayload = buildSizedPayload(6_144)

        // Force fallback copy path (.partial move fails) AND fail mid-write during dest_copy
        manager.fileMoveHookForTesting = { _, _ -> false }
        manager.fileOutputStreamOpenerForTesting = { file, role ->
            if (role == "dest_copy") {
                val inner = FileOutputStream(file)
                object : OutputStream() {
                    private var written = 0
                    override fun write(b: Int) {
                        if (written >= 200) throw IOException("Simulated destination copy I/O error")
                        inner.write(b)
                        written++
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val rem = 200 - written
                        if (rem <= 0) throw IOException("Simulated destination copy I/O error")
                        val chunk = minOf(len, rem)
                        inner.write(b, off, chunk)
                        inner.flush()
                        written += chunk
                        if (chunk < len) throw IOException("Simulated destination copy I/O error")
                    }
                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
            } else {
                FileOutputStream(file)
            }
        }

        val failOverExisting = manager.exportPayloadToFile(newPayload, existingFile, stagingDir)
        assertTrue("Copy failure to destination must return failure", failOverExisting.isFailure)
        assertTrue(existingFile.exists())
        assertEquals(
            "Pre-existing valid backup must be completely restored after fallback copy failure",
            oldBytes.toString(Charsets.UTF_8),
            existingFile.readText(Charsets.UTF_8)
        )
        assertTrue(manager.importFromFile(existingFile).isSuccess)
        assertNoLeftoverBackupTemps(targetDir, stagingDir)

        // When target did not exist beforehand, failed fallback copy must delete partial target
        val brandNewTarget = File(targetDir, "brand_new_copy_fail.json")
        val failOverNew = manager.exportPayloadToFile(newPayload, brandNewTarget, stagingDir)
        assertTrue(failOverNew.isFailure)
        assertFalse("Partial new target file must be removed after copy failure", brandNewTarget.exists())
        assertNoLeftoverBackupTemps(targetDir, stagingDir)
    }

    @Test
    fun backupManager_fileExport_corruptPostWriteDestination_rejectsAndRestoresOldBackup() = runBlocking {
        val manager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null
        )

        val targetDir = tempFolder.newFolder("corrupt_verify_target")
        val stagingDir = tempFolder.newFolder("corrupt_verify_staging")
        val targetFile = File(targetDir, "verified_backup.json")
        val oldPayload = buildSizedPayload(2_500)
        assertTrue(manager.exportPayloadToFile(oldPayload, targetFile, stagingDir).isSuccess)
        val oldJson = targetFile.readText(Charsets.UTF_8)

        // Inject silent data corruption during dest_copy (non-zero file size > 0, yet invalid content!)
        manager.fileMoveHookForTesting = { _, _ -> false }
        manager.fileOutputStreamOpenerForTesting = { file, role ->
            if (role == "dest_copy") {
                val inner = FileOutputStream(file)
                object : OutputStream() {
                    override fun write(b: Int) {
                        inner.write('X'.code)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val corrupted = ByteArray(len) { 'X'.code.toByte() }
                        inner.write(corrupted, 0, len)
                    }
                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
            } else {
                FileOutputStream(file)
            }
        }

        val newPayload = buildSizedPayload(5_000)
        val res = manager.exportPayloadToFile(newPayload, targetFile, stagingDir)
        assertTrue("Non-valid backup content with size > 0 must fail verification, not succeed", res.isFailure)
        assertTrue(targetFile.exists())
        assertEquals("Old valid backup must be restored when post-write verification fails", oldJson, targetFile.readText(Charsets.UTF_8))
        assertTrue(BackupManager.parseAndValidateBackupJson(targetFile.readText(Charsets.UTF_8)) is BackupParseResult.Success)
        assertNoLeftoverBackupTemps(targetDir, stagingDir)
    }

    @Test
    fun backupManager_fileExport_dualFailureAndOversizedOverwrite_preserveOldBackupOrReportRestoreError() = runBlocking {
        val manager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null
        )

        val targetDir = tempFolder.newFolder("dual_fail_target")
        val stagingDir = tempFolder.newFolder("dual_fail_staging")
        val targetFile = File(targetDir, "protected_backup.json")
        val oldPayload = buildSizedPayload(2_048)
        assertTrue(manager.exportPayloadToFile(oldPayload, targetFile, stagingDir).isSuccess)
        val oldJson = targetFile.readText(Charsets.UTF_8)

        // 1. Oversized (> 16MB) export over existing valid backup leaves existing backup 100% untouched
        val overPayload = buildSizedPayload(BackupManager.MAX_BACKUP_BYTES + 1)
        val overRes = manager.exportPayloadToFile(overPayload, targetFile, stagingDir)
        assertTrue(overRes.isFailure)
        assertEquals(oldJson, targetFile.readText(Charsets.UTF_8))
        assertNoLeftoverBackupTemps(targetDir, stagingDir)

        // 2. Dual failure: dest_copy fails AND restore also fails -> explicit restore failure reported
        manager.fileMoveHookForTesting = { _, _ -> false }
        manager.fileOutputStreamOpenerForTesting = { file, role ->
            when (role) {
                "dest_copy" -> object : OutputStream() {
                    override fun write(b: Int) = throw IOException("Primary copy failed")
                }
                "restore" -> object : OutputStream() {
                    override fun write(b: Int) = throw IOException("Rollback restore also failed")
                }
                else -> FileOutputStream(file)
            }
        }
        val dualFail = manager.exportPayloadToFile(buildSizedPayload(4_096), targetFile, stagingDir)
        assertTrue(dualFail.isFailure)
        val err = dualFail.exceptionOrNull()!!
        assertTrue(
            "Dual failure must report both export and restore failure clearly: ${err.message}",
            err.message.orEmpty().contains("فشلت استعادة") && err.suppressed.isNotEmpty()
        )
        assertNoLeftoverBackupTemps(targetDir, stagingDir)
    }

    @Test
    fun backupManager_uriExport_rollbackSnapshotFailure_abortsBeforeTruncatingAndDualFailureReports() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.videohatak.player.test.snapfailprovider"
        val backingDir = tempFolder.newFolder("snapfail_docs")
        val docFiles = linkedMapOf<String, File>()

        val provider = object : android.content.ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): android.database.Cursor? {
                val id = uri.lastPathSegment ?: return null
                val file = docFiles[id] ?: return null
                val cols = projection ?: arrayOf(android.provider.OpenableColumns.SIZE)
                val cursor = android.database.MatrixCursor(cols)
                val row = Array<Any?>(cols.size) { idx ->
                    when (cols[idx]) {
                        android.provider.OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                }
                cursor.addRow(row)
                return cursor
            }

            override fun getType(uri: Uri): String = "application/json"
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(
                uri: Uri,
                values: android.content.ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?
            ): Int = 0

            override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
                val id = uri.lastPathSegment ?: throw java.io.FileNotFoundException()
                val file = docFiles[id] ?: throw java.io.FileNotFoundException()
                val pfdMode = if (mode.contains("w")) {
                    android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or
                        android.os.ParcelFileDescriptor.MODE_CREATE or
                        android.os.ParcelFileDescriptor.MODE_TRUNCATE
                } else {
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                }
                return android.os.ParcelFileDescriptor.open(file, pfdMode)
            }
        }
        val info = android.content.pm.ProviderInfo().apply {
            this.authority = authority
            this.exported = false
        }
        provider.attachInfo(appContext, info)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)

        val manager = BackupManager.createForTesting(
            themeRepository = FakeThemeRepo(),
            playbackRepository = FakePlaybackPrefs(),
            subtitleRepository = FakeSubtitlePrefStore(),
            vaultSecurityStore = FakeVaultSecurityStore(),
            playlistStore = FakePlaylistStore(),
            subtitleStore = null,
            context = appContext
        )

        val originalContent = """{"schemaVersion":3,"user":"must_never_be_truncated"}"""
        val existingDoc = File(backingDir, "snap_fail.json").apply { writeText(originalContent, Charsets.UTF_8) }
        docFiles["snap_fail"] = existingDoc
        val docUri = Uri.parse("content://$authority/document/snap_fail")
        val payload = buildSizedPayload(4_096)

        // 1. Rollback snapshot creation fails while reading existing URI -> MUST abort BEFORE opening "wt"
        var wtOpenedCount = 0
        manager.uriInputStreamOpenerForTesting = { uri ->
            if (uri == docUri) {
                object : java.io.InputStream() {
                    override fun read(): Int = throw IOException("Simulated snapshot read failure")
                }
            } else {
                appContext.contentResolver.openInputStream(uri)
            }
        }
        manager.uriStreamOpenerForTesting = { uri, mode ->
            if (mode.contains("w")) wtOpenedCount++
            appContext.contentResolver.openOutputStream(uri, mode)
        }

        val snapFailRes = manager.exportPayloadToUri(payload, docUri)
        assertTrue("Export must fail when rollback snapshot cannot be created", snapFailRes.isFailure)
        assertEquals("Must NEVER open URI in 'wt' mode when rollback snapshot fails", 0, wtOpenedCount)
        assertEquals("Original existing URI file must remain 100% intact", originalContent, existingDoc.readText(Charsets.UTF_8))

        // 2. Also for file:// URI: snapshot failure aborts BEFORE truncating target
        val fileTarget = File(tempFolder.root, "file_uri_snap_fail.json").apply { writeText(originalContent, Charsets.UTF_8) }
        val fileTargetUri = Uri.fromFile(fileTarget)
        wtOpenedCount = 0
        manager.uriInputStreamOpenerForTesting = null
        manager.fileOutputStreamOpenerForTesting = { file, role ->
            if (role == "rollback_snapshot") {
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("Simulated rollback disk full")
                }
            } else {
                FileOutputStream(file)
            }
        }
        val fileSnapFailRes = manager.exportPayloadToUri(payload, fileTargetUri)
        assertTrue(fileSnapFailRes.isFailure)
        assertEquals(0, wtOpenedCount)
        assertEquals(originalContent, fileTarget.readText(Charsets.UTF_8))

        // 3. Dual failure on content:// URI (mid-write failure AND restore write fails) -> clear error + non-empty suppressed
        manager.fileOutputStreamOpenerForTesting = null
        var openWriteCalls = 0
        manager.uriStreamOpenerForTesting = { uri, mode ->
            openWriteCalls++
            val inner = FileOutputStream(existingDoc, false) // simulates SAF "wt" truncate
            if (openWriteCalls == 1) {
                object : OutputStream() {
                    private var count = 0
                    override fun write(b: Int) {
                        if (count >= 50) throw IOException("Mid-write failure on URI")
                        inner.write(b)
                        count++
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val rem = 50 - count
                        if (rem <= 0) throw IOException("Mid-write failure on URI")
                        val chunk = minOf(len, rem)
                        inner.write(b, off, chunk)
                        count += chunk
                        if (chunk < len) throw IOException("Mid-write failure on URI")
                    }
                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
            } else {
                inner.close()
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("Simulated restore URI write failure")
                }
            }
        }

        val uriDualFail = manager.exportPayloadToUri(payload, docUri)
        assertTrue(uriDualFail.isFailure)
        val dualErr = uriDualFail.exceptionOrNull()!!
        assertTrue(
            "URI dual failure must clearly report failed restoration: ${dualErr.message}",
            dualErr.message.orEmpty().contains("فشلت استعادة") && dualErr.suppressed.isNotEmpty()
        )
        assertNoLeftoverBackupTemps(File(appContext.cacheDir, "backup_staging"))
    }
}
