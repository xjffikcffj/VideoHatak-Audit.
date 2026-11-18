package com.videohatak.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.videohatak.player.core.backup.BackupManager
import com.videohatak.player.core.backup.BackupParseResult
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.PlayerOrientationMode
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.service.VideoPlaybackService
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.subtitle.speech.PcmSpeechConditioner
import com.videohatak.player.core.subtitle.translation.OfflineTranslationTextProcessor
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.subtitle.translation.TranslatedCuePair
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultRepository
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.core.vault.security.VaultSecurityPreferences
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UpgradeMigrationAndFeatureVerificationTest {

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

    // -------------------------------------------------------------------------
    // 1. SQLite Schema Upgrade: VideoHatakDatabaseHelper v1 -> v3
    // -------------------------------------------------------------------------

    @Test
    fun playlistDb_upgradeFromV1ToV3_preservesAllDataAndMigratesFavoritesTitle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "videohatak_playlists.db"
        context.deleteDatabase(dbName)

        // Create authentic v1 SQLite database (without COL_I_IS_AVAILABLE)
        val v1Helper = object : SQLiteOpenHelper(context, dbName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE ${VideoHatakDatabaseHelper.TABLE_PLAYLISTS} (
                        ${VideoHatakDatabaseHelper.COL_P_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                        ${VideoHatakDatabaseHelper.COL_P_TITLE} TEXT NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_P_CREATED_AT} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_P_UPDATED_AT} INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE ${VideoHatakDatabaseHelper.TABLE_PLAYLIST_ITEMS} (
                        ${VideoHatakDatabaseHelper.COL_I_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                        ${VideoHatakDatabaseHelper.COL_I_PLAYLIST_ID} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_I_VIDEO_ID} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_I_ORDER_INDEX} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_I_TITLE} TEXT NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_I_DURATION} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_I_SIZE} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_I_DATE_ADDED} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_I_URI} TEXT NOT NULL,
                        FOREIGN KEY (${VideoHatakDatabaseHelper.COL_I_PLAYLIST_ID})
                            REFERENCES ${VideoHatakDatabaseHelper.TABLE_PLAYLISTS}(${VideoHatakDatabaseHelper.COL_P_ID}) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE ${VideoHatakDatabaseHelper.TABLE_TRASH} (
                        ${VideoHatakDatabaseHelper.COL_T_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                        ${VideoHatakDatabaseHelper.COL_T_VIDEO_ID} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_TITLE} TEXT NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_DISPLAY_NAME} TEXT NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_DURATION} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_SIZE} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_MIME_TYPE} TEXT NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_BUCKET_ID} TEXT NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_BUCKET_NAME} TEXT NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_WIDTH} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_HEIGHT} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_DATE_ADDED} INTEGER NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_URI} TEXT NOT NULL UNIQUE,
                        ${VideoHatakDatabaseHelper.COL_T_FILE_PATH} TEXT NOT NULL,
                        ${VideoHatakDatabaseHelper.COL_T_TRASHED_AT} INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val v1Db = v1Helper.writableDatabase
        val favId = v1Db.insert(
            VideoHatakDatabaseHelper.TABLE_PLAYLISTS,
            null,
            ContentValues().apply {
                put(VideoHatakDatabaseHelper.COL_P_TITLE, PlaylistRepository.LEGACY_FAVORITES_PLAYLIST_TITLE)
                put(VideoHatakDatabaseHelper.COL_P_CREATED_AT, 1_700_000_000L)
                put(VideoHatakDatabaseHelper.COL_P_UPDATED_AT, 1_700_000_100L)
            }
        )
        val customId = v1Db.insert(
            VideoHatakDatabaseHelper.TABLE_PLAYLISTS,
            null,
            ContentValues().apply {
                put(VideoHatakDatabaseHelper.COL_P_TITLE, "مقاطعي المفضلة القديمة")
                put(VideoHatakDatabaseHelper.COL_P_CREATED_AT, 1_700_000_200L)
                put(VideoHatakDatabaseHelper.COL_P_UPDATED_AT, 1_700_000_300L)
            }
        )
        v1Db.insert(
            VideoHatakDatabaseHelper.TABLE_PLAYLIST_ITEMS,
            null,
            ContentValues().apply {
                put(VideoHatakDatabaseHelper.COL_I_PLAYLIST_ID, favId)
                put(VideoHatakDatabaseHelper.COL_I_VIDEO_ID, 401L)
                put(VideoHatakDatabaseHelper.COL_I_ORDER_INDEX, 0)
                put(VideoHatakDatabaseHelper.COL_I_TITLE, "Legacy Fav Clip")
                put(VideoHatakDatabaseHelper.COL_I_DURATION, 45_000L)
                put(VideoHatakDatabaseHelper.COL_I_SIZE, 12_000_000L)
                put(VideoHatakDatabaseHelper.COL_I_DATE_ADDED, 1_700_000L)
                put(VideoHatakDatabaseHelper.COL_I_URI, "content://media/external/video/media/401")
            }
        )
        v1Db.insert(
            VideoHatakDatabaseHelper.TABLE_PLAYLIST_ITEMS,
            null,
            ContentValues().apply {
                put(VideoHatakDatabaseHelper.COL_I_PLAYLIST_ID, customId)
                put(VideoHatakDatabaseHelper.COL_I_VIDEO_ID, 402L)
                put(VideoHatakDatabaseHelper.COL_I_ORDER_INDEX, 0)
                put(VideoHatakDatabaseHelper.COL_I_TITLE, "Custom List Clip")
                put(VideoHatakDatabaseHelper.COL_I_DURATION, 90_000L)
                put(VideoHatakDatabaseHelper.COL_I_SIZE, 24_000_000L)
                put(VideoHatakDatabaseHelper.COL_I_DATE_ADDED, 1_700_100L)
                put(VideoHatakDatabaseHelper.COL_I_URI, "content://media/external/video/media/402")
            }
        )
        v1Db.insert(
            VideoHatakDatabaseHelper.TABLE_TRASH,
            null,
            ContentValues().apply {
                put(VideoHatakDatabaseHelper.COL_T_VIDEO_ID, 403L)
                put(VideoHatakDatabaseHelper.COL_T_TITLE, "Trashed Old Clip")
                put(VideoHatakDatabaseHelper.COL_T_DISPLAY_NAME, "Trashed Old Clip.mp4")
                put(VideoHatakDatabaseHelper.COL_T_DURATION, 30_000L)
                put(VideoHatakDatabaseHelper.COL_T_SIZE, 5_000_000L)
                put(VideoHatakDatabaseHelper.COL_T_MIME_TYPE, "video/mp4")
                put(VideoHatakDatabaseHelper.COL_T_BUCKET_ID, "b1")
                put(VideoHatakDatabaseHelper.COL_T_BUCKET_NAME, "Camera")
                put(VideoHatakDatabaseHelper.COL_T_WIDTH, 1920)
                put(VideoHatakDatabaseHelper.COL_T_HEIGHT, 1080)
                put(VideoHatakDatabaseHelper.COL_T_DATE_ADDED, 1_700_200L)
                put(VideoHatakDatabaseHelper.COL_T_URI, "content://media/external/video/media/403")
                put(VideoHatakDatabaseHelper.COL_T_FILE_PATH, "/storage/emulated/0/Movies/trashed.mp4")
                put(VideoHatakDatabaseHelper.COL_T_TRASHED_AT, 1_700_250_000L)
            }
        )
        v1Helper.close()

        // Reopen with production v3 helper
        val upgradedHelper = VideoHatakDatabaseHelper(context)
        try {
            val playlists = upgradedHelper.getPlaylists()
            assertEquals(2, playlists.size)

            val favItems = upgradedHelper.getItemsForPlaylist(favId)
            assertEquals(1, favItems.size)
            assertEquals("Legacy Fav Clip", favItems.first().videoTitle)
            assertTrue(favItems.first().isAvailable)

            val customItems = upgradedHelper.getItemsForPlaylist(customId)
            assertEquals(1, customItems.size)
            assertEquals("Custom List Clip", customItems.first().videoTitle)
            assertTrue(customItems.first().isAvailable)

            val trashItems = upgradedHelper.getAllTrashItems()
            assertEquals(1, trashItems.size)
            assertEquals("Trashed Old Clip", trashItems.first().title)

            // Repository refresh migrates legacy favorites title to protected "المفضلة ⭐"
            val playlistRepo = PlaylistRepositoryImpl(context, upgradedHelper)
            playlistRepo.refresh()
            val refreshed = playlistRepo.playlists.first()
            assertTrue(refreshed.any { it.id == favId && it.title == PlaylistRepository.FAVORITES_PLAYLIST_TITLE })
            assertTrue(refreshed.any { it.id == customId && it.title == "مقاطعي المفضلة القديمة" })
            assertEquals(PlaylistRepository.FAVORITES_PLAYLIST_TITLE, refreshed.first().title)
        } finally {
            upgradedHelper.close()
            context.deleteDatabase(dbName)
        }
    }

    // -------------------------------------------------------------------------
    // 2. SQLite Schema Upgrade: VaultDatabaseHelper v2 -> v3 + Plaintext Row Migration
    // -------------------------------------------------------------------------

    @Test
    fun vaultDb_upgradeFromV2ToV3_preservesRowsAndEncryptsLegacyPlaintextMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "videohatak_vault.db"
        context.deleteDatabase(dbName)

        // Create authentic v2 vault DB (without COLUMN_ENCRYPTED_META)
        val v2Helper = object : SQLiteOpenHelper(context, dbName, null, 2) {
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
                        ${VaultDatabaseHelper.COLUMN_IV_BASE64} TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val vaultDir = File(context.filesDir, "vault_encrypted").apply { mkdirs() }
        val legacyEncFile = File(vaultDir, "legacy_clip_1.enc").apply { writeText("enc-payload") }

        val v2Db = v2Helper.writableDatabase
        v2Db.insert(
            VaultDatabaseHelper.TABLE_VAULT,
            null,
            ContentValues().apply {
                put(VaultDatabaseHelper.COLUMN_DISPLAY_NAME, "legacy_private_video.mp4")
                put(VaultDatabaseHelper.COLUMN_ORIGINAL_SIZE, 2_048L)
                put(VaultDatabaseHelper.COLUMN_ENCRYPTED_SIZE, 2_120L)
                put(VaultDatabaseHelper.COLUMN_DURATION_MS, 15_000L)
                put(VaultDatabaseHelper.COLUMN_MIME_TYPE, "video/mp4")
                put(VaultDatabaseHelper.COLUMN_WIDTH, 1280)
                put(VaultDatabaseHelper.COLUMN_HEIGHT, 720)
                put(VaultDatabaseHelper.COLUMN_RELATIVE_PATH, legacyEncFile.absolutePath)
                put(VaultDatabaseHelper.COLUMN_CREATED_AT, 1_700_500_000L)
                put(VaultDatabaseHelper.COLUMN_IV_BASE64, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            }
        )
        v2Helper.close()

        val upgradedVaultDb = VaultDatabaseHelper(context)
        try {
            // Even before unlocking, count query works cleanly without exposing videos
            assertEquals(1, upgradedVaultDb.getVideoCount())

            val repo = VaultRepositoryImpl(
                context = context,
                cryptoManager = VaultCryptoManager("TEST_PROVIDER"),
                dbHelper = upgradedVaultDb
            )

            // Locked refresh keeps vaultVideos empty while preserving count = 1
            repo.refresh()
            assertEquals(1, repo.vaultCount.value)
            assertTrue(repo.vaultVideos.first().isEmpty())

            // Unlock triggers in-place encryption of legacy plaintext metadata
            repo.setUnlocked(true)
            repo.refresh()

            val unlockedVideos = repo.vaultVideos.first()
            assertEquals(1, unlockedVideos.size)
            assertEquals("legacy_private_video.mp4", unlockedVideos.first().displayName)
            assertTrue(
                unlockedVideos.first().encryptedMetaBase64?.startsWith("VHTKMETA1:") == true
            )

            // Raw SQLite columns are now masked on disk
            val rawRow = upgradedVaultDb.getAllVideos().first()
            assertEquals("مقطع خاص مشفر", rawRow.displayName)
            assertEquals("application/octet-stream", rawRow.mimeType)
            assertTrue(rawRow.encryptedMetaBase64?.startsWith("VHTKMETA1:") == true)
        } finally {
            upgradedVaultDb.close()
            context.deleteDatabase(dbName)
            legacyEncFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // 3. Legacy DataStore & Backup v1/v2 -> v3 Migration
    // -------------------------------------------------------------------------

    @Test
    fun playbackPrefs_migratesLegacyAskEveryTimeAndNumericPositionKey_onUpgrade() = runBlocking {
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dsFile = tempFolder.newFile("legacy_upgrade_playback.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { dsFile }
            )

            // Seed v1/v2 keys before repository reads them
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("resume_mode_v1")] = "ASK_EVERY_TIME"
                prefs[longPreferencesKey("playback_pos_777")] = 42_500L
            }

            val repo = PlaybackPreferencesRepositoryImpl(dataStore)
            assertEquals(ResumeMode.RESUME, repo.resumeMode.first())
            assertEquals(PlayerOrientationMode.AUTO, repo.playerOrientationMode.first())
            assertEquals(10, repo.seekIntervalSeconds.first())

            val uri = "content://media/external/video/media/777"
            val migratedPos = repo.getSavedVideoPosition(videoUri = uri, durationMs = 120_000L)
            assertEquals(42_500L, migratedPos)

            val exported = repo.exportSavedPositions()
            assertEquals(1, exported.size)
            assertTrue(exported.keys.first().startsWith("vid_pos_"))
            assertEquals(42_500L, exported.values.first())
        } finally {
            dsScope.cancel()
        }
    }

    @Test
    fun backupManager_parsesV1SchemaWithoutNewFields_andDefaultsSeekInterval() {
        val canonicalV1Body =
            "{" +
                "\"settings\":{\"selectedTheme\":\"ROYAL_IVORY\",\"themeMode\":\"DARK\",\"playbackSpeed\":1.0,\"resizeMode\":0,\"playbackMode\":\"PLAY_ALL_IN_ORDER\",\"resumeMode\":\"ASK_EVERY_TIME\"}," +
                "\"subtitles\":{\"primaryFontSizeSp\":18.0,\"secondaryFontSizeSp\":15.0}," +
                "\"vaultSecurity\":{\"autoLockTimeout\":\"IMMEDIATE\"}," +
                "\"playlists\":[{\"title\":\"المفضلة ⭐\",\"createdAt\":1700000000,\"updatedAt\":1700000000,\"items\":[]}]" +
                "}"
        val checksum = sha256Hex(canonicalV1Body)
        val rawV1Json =
            "{\n" +
                "  \"schemaVersion\": 1,\n" +
                "  \"createdAtEpochMs\": 1700000000000,\n" +
                "  \"sha256Checksum\": \"$checksum\",\n" +
                "  \"body\": $canonicalV1Body\n" +
                "}"

        val parsed = BackupManager.parseAndValidateBackupJson(rawV1Json)
        assertTrue(parsed is BackupParseResult.Success)
        val payload = (parsed as BackupParseResult.Success).payload
        assertEquals(1, payload.schemaVersion)
        assertEquals(ResumeMode.RESUME, payload.settings.resumeMode)
        assertEquals(10, payload.settings.seekIntervalSeconds)
        assertTrue(payload.resumePositions.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 4. Unit Tests for New & Updated Features
    // -------------------------------------------------------------------------

    @Test
    fun pcmSpeechConditioner_boostsQuietSpeech_ignoresSubFloorSilence_andPreventsClipping() {
        val conditioner = PcmSpeechConditioner(sampleRateHz = 16_000)

        // Sub-floor silence / hum (amplitude 6 < SPEECH_NOISE_FLOOR_RMS = 28) is not boosted
        val silence = ShortArray(960) { 6 }
        val processedSilence = conditioner.process(silence)
        val maxSilence = processedSilence.maxOf { abs(it.toInt()) }
        assertTrue("Expected unboosted silence, got max=$maxSilence", maxSilence <= 15)

        // Quiet speech (~250Hz sine wave @ amplitude 220 -> RMS ~155) gets boosted significantly
        val quietSpeech = ShortArray(3200) { idx ->
            (220.0 * sin(2.0 * Math.PI * 250.0 * idx / 16_000.0)).toInt().toShort()
        }
        val boostedSpeech = conditioner.process(quietSpeech)
        val tailPeak = boostedSpeech.drop(1600).maxOf { abs(it.toInt()) }
        assertTrue("Expected boosted quiet speech > 900, got $tailPeak", tailPeak > 900)

        // Sudden loud peak (amplitude 32,000) stays safely bounded within Short range
        val loudSpeech = ShortArray(640) { idx ->
            (32_000.0 * sin(2.0 * Math.PI * 400.0 * idx / 16_000.0)).toInt().toShort()
        }
        val limitedSpeech = conditioner.process(loudSpeech)
        assertTrue(limitedSpeech.all { it >= Short.MIN_VALUE && it <= Short.MAX_VALUE })
        assertTrue(limitedSpeech.maxOf { abs(it.toInt()) } <= Short.MAX_VALUE.toInt())
    }

    @Test
    fun offlineTranslationTextProcessor_normalizesContractionsPunctuationAndDualCueFormatting() {
        val normalizedStmt = OfflineTranslationTextProcessor.prepareSourceForTranslation(
            text = "im sure i dont agree",
            sourceLanguage = SubtitleTranslationLanguage.ENGLISH
        )
        assertEquals("I'm sure I don't agree.", normalizedStmt)

        val normalizedQuestion = OfflineTranslationTextProcessor.prepareSourceForTranslation(
            text = "why did i wait",
            sourceLanguage = SubtitleTranslationLanguage.ENGLISH
        )
        assertEquals("Why did I wait?", normalizedQuestion)

        val rescued = OfflineTranslationTextProcessor.lookupNaturalConversationalRescue(
            sourceText = "i knew!",
            sourceLanguage = SubtitleTranslationLanguage.ENGLISH,
            targetLanguage = SubtitleTranslationLanguage.ARABIC
        )
        assertEquals("كنت أعلم", rescued)

        val postRescued = OfflineTranslationTextProcessor.postProcessTranslation(
            translatedText = "الآن",
            sourceText = "i knew",
            sourceLanguage = SubtitleTranslationLanguage.ENGLISH,
            targetLanguage = SubtitleTranslationLanguage.ARABIC
        )
        assertEquals("كنت أعلم", postRescued)

        val pair = TranslatedCuePair(
            index = 1,
            startTimeMs = 1_000L,
            endTimeMs = 3_000L,
            originalText = "I knew",
            translatedText = "كنت أعلم"
        )
        assertEquals("I knew\nكنت أعلم", pair.toCue(includeSourceText = true).text)
        assertEquals("كنت أعلم", pair.toCue(includeSourceText = false).text)
    }

    @Test
    fun playerOrientationMode_cyclesSequentially_andParsesStorageCodesSafely() {
        assertEquals(PlayerOrientationMode.AUTO, PlayerOrientationMode.DEFAULT)
        assertEquals(PlayerOrientationMode.LANDSCAPE, PlayerOrientationMode.PORTRAIT.next())
        assertEquals(PlayerOrientationMode.AUTO, PlayerOrientationMode.LANDSCAPE.next())
        assertEquals(PlayerOrientationMode.PORTRAIT, PlayerOrientationMode.AUTO.next())

        assertEquals(PlayerOrientationMode.PORTRAIT, PlayerOrientationMode.fromStorageCode("portrait"))
        assertEquals(PlayerOrientationMode.LANDSCAPE, PlayerOrientationMode.fromStorageCode("LANDSCAPE"))
        assertEquals(PlayerOrientationMode.AUTO, PlayerOrientationMode.fromStorageCode("UNKNOWN"))
        assertEquals(PlayerOrientationMode.AUTO, PlayerOrientationMode.fromStorageCode(null))
    }

    @Test
    fun videoPlaybackService_shouldKeepForegroundNotification_respectsPausedMediaAndVaultPrivacy() {
        assertTrue(
            VideoPlaybackService.shouldKeepForegroundNotification(
                mediaItemCount = 1,
                playbackState = Player.STATE_READY,
                isVaultPrivate = false
            )
        )
        assertTrue(
            VideoPlaybackService.shouldKeepForegroundNotification(
                mediaItemCount = 1,
                playbackState = Player.STATE_BUFFERING,
                isVaultPrivate = false
            )
        )
        assertFalse(
            VideoPlaybackService.shouldKeepForegroundNotification(
                mediaItemCount = 1,
                playbackState = Player.STATE_ENDED,
                isVaultPrivate = false
            )
        )
        assertFalse(
            VideoPlaybackService.shouldKeepForegroundNotification(
                mediaItemCount = 0,
                playbackState = Player.STATE_READY,
                isVaultPrivate = false
            )
        )
        // Vault media MUST NEVER keep a foreground service or notification alive
        assertFalse(
            VideoPlaybackService.shouldKeepForegroundNotification(
                mediaItemCount = 1,
                playbackState = Player.STATE_READY,
                isVaultPrivate = true
            )
        )
    }

    private class FakeVaultRepoForViewModel : VaultRepository {
        private val _videos = MutableStateFlow(
            listOf(
                VaultVideo(id = 11L, displayName = "clip1.mp4", originalSizeBytes = 100L, encryptedSizeBytes = 120L, durationMs = 1000L, mimeType = "video/mp4", width = 100, height = 100, relativeEncryptedPath = "c1.enc", createdAtEpochMs = 1L, ivBase64 = "iv"),
                VaultVideo(id = 12L, displayName = "clip2.mp4", originalSizeBytes = 200L, encryptedSizeBytes = 220L, durationMs = 2000L, mimeType = "video/mp4", width = 100, height = 100, relativeEncryptedPath = "c2.enc", createdAtEpochMs = 2L, ivBase64 = "iv")
            )
        )
        private val _isUnlocked = MutableStateFlow(false)
        private val _vaultCount = MutableStateFlow(2)
        private val _vaultError = MutableStateFlow<String?>(null)
        private var _lastActivity = 0L

        override val vaultVideos: Flow<List<VaultVideo>> = _videos.asStateFlow()
        override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()
        override val vaultCount: StateFlow<Int> = _vaultCount.asStateFlow()
        override val vaultError: StateFlow<String?> = _vaultError.asStateFlow()
        override val lastActivityTimestampMs: Long get() = _lastActivity

        val deletedIds = mutableListOf<Long>()

        override fun setUnlocked(unlocked: Boolean) {
            _isUnlocked.value = unlocked
        }

        override fun recordActivity(nowMs: Long) {
            _lastActivity = nowMs
        }

        override fun clearError() {
            _vaultError.value = null
        }

        override suspend fun refresh() = Unit

        override suspend fun importVideo(
            sourceUri: Uri,
            displayName: String?,
            onProgress: (Float) -> Unit,
            isCancelled: () -> Boolean
        ): VaultVideo? = null

        override suspend fun renameVideo(videoId: Long, newTitle: String): Boolean = true

        override suspend fun deleteVaultVideo(id: Long): Boolean {
            deletedIds.add(id)
            _videos.value = _videos.value.filterNot { it.id == id }
            _vaultCount.value = _videos.value.size
            return true
        }

        override fun getVideoById(id: Long): VaultVideo? = _videos.value.firstOrNull { it.id == id }
        override fun resolveEncryptedFile(video: VaultVideo): File = File(video.relativeEncryptedPath)
    }

    @Test
    fun vaultViewModel_pinSetupChangeAndBatchDelete_workSafelyWithHooks() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vmStore = ViewModelStore()
        try {
            val dsFile = tempFolder.newFile("vault_vm_pin_ops.preferences_pb")
            val prefs = VaultSecurityPreferences(
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { dsFile })
            )
            val repo = FakeVaultRepoForViewModel()
            val hookedBeforeDelete = mutableListOf<Long>()

            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VaultViewModel(
                        repository = repo,
                        biometricHelper = VaultBiometricHelper(context) { 34 },
                        securityPreferences = prefs,
                        nowMsProvider = { 5_000L },
                        beforeDeleteVideo = { id -> hookedBeforeDelete.add(id) }
                    ) as T
                }
            }
            val vm = ViewModelProvider(vmStore, factory)[VaultViewModel::class.java]

            // 1. Setup initial mandatory PIN -> unlocks immediately
            var setupOk = false
            vm.setupInitialVaultPinAndUnlock("2580") { ok, _ -> setupOk = ok }
            advanceUntilIdle()
            assertTrue(setupOk)
            assertTrue(vm.isUnlocked.value)

            // 2. Wrong current PIN rejected during changeVaultPin
            var wrongChangeOk = true
            vm.changeVaultPin(currentPin = "0000", newPin = "3690") { ok, _ -> wrongChangeOk = ok }
            advanceUntilIdle()
            assertFalse(wrongChangeOk)
            assertTrue(prefs.verifyPin("2580"))

            // 3. Correct current PIN updates PIN cleanly
            var rightChangeOk = false
            vm.changeVaultPin(currentPin = "2580", newPin = "3690") { ok, _ -> rightChangeOk = ok }
            advanceUntilIdle()
            assertTrue(rightChangeOk)
            assertTrue(prefs.verifyPin("3690"))
            assertFalse(prefs.verifyPin("2580"))

            // 4. Batch delete triggers beforeDeleteVideo hook for every item & removes all
            var batchSucceeded = 0
            var batchFailed = -1
            vm.deleteVideos(setOf(11L, 12L)) { s, f ->
                batchSucceeded = s
                batchFailed = f
            }
            advanceUntilIdle()
            assertEquals(2, batchSucceeded)
            assertEquals(0, batchFailed)
            assertEquals(listOf(11L, 12L), hookedBeforeDelete.sorted())
            assertEquals(0, repo.vaultCount.value)
        } finally {
            vmStore.clear()
            scope.cancel()
        }
    }

    @Test
    fun playlistRepository_bulkOperationsAndFavoritesBatchHelpers_workAtomically() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "videohatak_playlists.db"
        context.deleteDatabase(dbName)
        val dbHelper = VideoHatakDatabaseHelper(context)
        try {
            val repo = PlaylistRepositoryImpl(context, dbHelper)
            val customPlId = repo.createPlaylist("سهرة")

            val v1 = VideoItem(id = 901L, contentUri = Uri.parse("content://media/external/video/media/901"), title = "One", displayName = "One.mp4", durationMs = 10_000L)
            val v2 = VideoItem(id = 902L, contentUri = Uri.parse("content://media/external/video/media/902"), title = "Two", displayName = "Two.mp4", durationMs = 20_000L)

            assertEquals(2, repo.addVideosToPlaylists(listOf(v1, v2), listOf(customPlId)))
            // Re-adding duplicates inserts 0 extra items
            assertEquals(0, repo.addVideosToPlaylists(listOf(v1, v2), listOf(customPlId)))

            val addedItems = dbHelper.getItemsForPlaylist(customPlId)
            assertEquals(2, addedItems.size)

            // Batch favorites add & remove
            assertEquals(2, repo.addVideosToFavorites(listOf(v1, v2)))
            val (favIds, _) = repo.getFavoriteVideoKeys()
            assertEquals(setOf(901L, 902L), favIds)

            assertEquals(1, repo.removeVideosFromFavorites(listOf(v1)))
            assertEquals(setOf(902L), repo.getFavoriteVideoKeys().first)

            // Single video removal by id/uri inside playlist
            assertTrue(repo.removeVideoByIdOrUriFromPlaylist(customPlId, 901L, v1.contentUri.toString()))
            assertEquals(1, dbHelper.getItemsForPlaylist(customPlId).size)
            assertEquals(902L, dbHelper.getItemsForPlaylist(customPlId).first().videoId)

            // Favorites playlist cannot be renamed or deleted
            val favPlId = repo.playlists.first().first { it.title == PlaylistRepository.FAVORITES_PLAYLIST_TITLE }.id
            assertFalse(repo.renamePlaylist(favPlId, "اسم آخر"))
            assertFalse(repo.deletePlaylist(favPlId))
            assertNotNull(repo.getFavoritesPlaylistWithItems())
        } finally {
            dbHelper.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
