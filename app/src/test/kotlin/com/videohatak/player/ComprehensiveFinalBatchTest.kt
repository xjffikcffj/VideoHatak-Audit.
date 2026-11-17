package com.videohatak.player

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.videohatak.player.core.backup.BackupManager
import com.videohatak.player.core.backup.BackupParseResult
import com.videohatak.player.core.backup.BackupPayload
import com.videohatak.player.core.backup.BackupPlaylistData
import com.videohatak.player.core.backup.BackupSettingsData
import com.videohatak.player.core.backup.BackupSubtitleData
import com.videohatak.player.core.backup.BackupVaultSecurityData
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playlist.repository.PlaylistRepository
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.parser.SubtitleParseResult
import com.videohatak.player.core.subtitle.parser.SubtitleParser
import com.videohatak.player.core.subtitle.repository.LocalSubtitleStore
import com.videohatak.player.core.subtitle.translation.BackendProxyConfig
import com.videohatak.player.core.subtitle.translation.ContextAwareDialogueTranslator
import com.videohatak.player.core.subtitle.translation.SubtitleBatchRequest
import com.videohatak.player.core.subtitle.translation.SubtitleBatchResponse
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationProvider
import com.videohatak.player.core.subtitle.translation.TranslatedCuePair
import com.videohatak.player.core.subtitle.translation.TranslationGlossaryEntry
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.feature.settings.SettingsAccordionState
import com.videohatak.player.feature.settings.SettingsSectionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ComprehensiveFinalBatchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    @Test
    fun seekInterval_persistenceAndSanitization_supports5_10_15Seconds() = runTest {
        assertEquals(5, PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(5))
        assertEquals(10, PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(10))
        assertEquals(15, PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(15))
        assertEquals(5, PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(3))
        assertEquals(15, PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(25))

        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dsFile = tempFolder.newFile("seek_interval.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { dsFile }
            )
            val repo = PlaybackPreferencesRepositoryImpl(dataStore)
            assertEquals(10, repo.seekIntervalSeconds.first())

            repo.setSeekIntervalSeconds(15)
            assertEquals(15, repo.seekIntervalSeconds.first())

            repo.setSeekIntervalSeconds(5)
            assertEquals(5, repo.seekIntervalSeconds.first())
        } finally {
            dsScope.cancel()
        }
    }

    @Test
    fun backupManager_schemaV3PreservesSeekInterval_andValidatesV2Payloads() {
        val v3Payload = BackupPayload(
            schemaVersion = BackupManager.SCHEMA_VERSION,
            createdAtEpochMs = 1700000000000L,
            settings = BackupSettingsData(
                selectedTheme = AppThemeId.ROYAL_IVORY,
                themeMode = ThemeMode.DARK,
                playbackSpeed = 1.25f,
                resizeMode = 0,
                playbackMode = PlaybackMode.PLAY_ALL_IN_ORDER,
                resumeMode = ResumeMode.RESUME,
                seekIntervalSeconds = 15
            ),
            subtitles = BackupSubtitleData(
                primaryFontSizeSp = 18f,
                secondaryFontSizeSp = 15f
            ),
            vaultSecurity = BackupVaultSecurityData(VaultAutoLockTimeout.IMMEDIATE),
            playlists = listOf(
                BackupPlaylistData("المفضلة ⭐", 1700000000L, 1700000000L, emptyList())
            ),
            resumePositions = emptyMap()
        )
        val rawJson = BackupManager.serializeBackupJson(v3Payload)
        val parsed = BackupManager.parseAndValidateBackupJson(rawJson)
        assertTrue(parsed is BackupParseResult.Success)
        val restored = (parsed as BackupParseResult.Success).payload
        assertEquals(3, restored.schemaVersion)
        assertEquals(15, restored.settings.seekIntervalSeconds)

        // Legacy v2 payload without seekIntervalSeconds still passes integrity check and defaults to 10
        val v2Payload = v3Payload.copy(
            schemaVersion = 2,
            settings = v3Payload.settings.copy(seekIntervalSeconds = 10)
        )
        val v2Json = BackupManager.serializeBackupJson(v2Payload)
        val parsedV2 = BackupManager.parseAndValidateBackupJson(v2Json)
        assertTrue(parsedV2 is BackupParseResult.Success)
        assertEquals(10, (parsedV2 as BackupParseResult.Success).payload.settings.seekIntervalSeconds)
    }

    @Test
    fun settingsAccordionState_startsAllCollapsed_andTogglesIndependently() {
        val state = SettingsAccordionState()
        SettingsSectionId.entries.forEach { section ->
            assertFalse("Section $section should start collapsed", state.isExpanded(section))
        }

        state.toggle(SettingsSectionId.THEMES)
        state.toggle(SettingsSectionId.SEEK_INTERVAL)
        assertTrue(state.isExpanded(SettingsSectionId.THEMES))
        assertTrue(state.isExpanded(SettingsSectionId.SEEK_INTERVAL))
        assertFalse(state.isExpanded(SettingsSectionId.THEME_MODE))
        assertFalse(state.isExpanded(SettingsSectionId.BACKUP))

        state.toggle(SettingsSectionId.THEMES)
        assertFalse(state.isExpanded(SettingsSectionId.THEMES))
        assertTrue(state.isExpanded(SettingsSectionId.SEEK_INTERVAL))
    }

    @Test
    fun protectedFavorites_cannotBeRenamedOrDeleted_migratesLegacyTitle_andDeduplicatesAdditions() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "playlist_test_${System.nanoTime()}.db"
        // Isolate clean DB by using PlaylistRepositoryImpl against Robolectric app context
        val repo = PlaylistRepositoryImpl(context)

        // Seed legacy "المفضلة" and another playlist directly if not already present
        val legacyId = repo.createPlaylist(PlaylistRepository.LEGACY_FAVORITES_PLAYLIST_TITLE)
        val customId = repo.createPlaylist("سلسلة وثائقية")
        assertTrue(legacyId > 0L)
        assertTrue(customId > 0L)

        repo.refresh()
        val playlistsAfterRefresh = repo.playlists.first()
        val favoritesPlaylist = playlistsAfterRefresh.firstOrNull {
            PlaylistRepository.isProtectedFavoritesTitle(it.title)
        }
        assertNotNull(favoritesPlaylist)
        assertEquals(PlaylistRepository.FAVORITES_PLAYLIST_TITLE, favoritesPlaylist!!.title)
        // Pinned to top
        assertEquals(favoritesPlaylist.id, playlistsAfterRefresh.first().id)

        // Rename & Delete must fail safely on protected Favorites
        assertFalse(repo.renamePlaylist(favoritesPlaylist.id, "اسم آخر"))
        assertFalse(repo.deletePlaylist(favoritesPlaylist.id))

        // Deduplicate additions by same video
        val sampleVideo = VideoItem(
            id = 555L,
            contentUri = Uri.parse("content://media/external/video/media/555"),
            title = "فيلم تجريبي",
            displayName = "sample.mp4",
            durationMs = 120_000L,
            sizeBytes = 25_000_000L,
            bucketId = "movies",
            bucketDisplayName = "Movies",
            dateAddedSec = 1700000000L
        )
        repo.addVideoToPlaylists(sampleVideo, listOf(favoritesPlaylist.id))
        repo.addVideoToPlaylists(sampleVideo, listOf(favoritesPlaylist.id))
        val items = repo.getPlaylistItems(favoritesPlaylist.id)
        assertEquals(1, items.count { it.videoId == 555L })

        // Custom playlist remains editable
        assertTrue(repo.renamePlaylist(customId, "سلسلة معدلة"))
        assertTrue(repo.deletePlaylist(customId))
        context.deleteDatabase(dbName)
    }

    @Test
    fun subtitleParser_supportsAssSsa_windows1256Arabic_utf16Bom_andExportRoundtrip() = runTest {
        val assContent = """
            [Script Info]
            Title: Test ASS
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.25,0:00:04.50,Default,,0,0,0,,{\an8}مرحباً بكم في {\b1}فيديوهاتك{\b0}\Nسطر ثانٍ
        """.trimIndent()
        val assCues = SubtitleParser.parse(assContent, SubtitleParser.detectFormat("sub.ass", assContent))
        assertEquals(1, assCues.size)
        assertEquals(1250L, assCues[0].startTimeMs)
        assertEquals(4500L, assCues[0].endTimeMs)
        assertEquals("مرحباً بكم في فيديوهاتك\nسطر ثانٍ", assCues[0].text)

        // Windows-1256 Arabic encoding
        val arabicSample = "1\r\n00:00:01,000 --> 00:00:03,000\r\nأهلاً بكم في عالم الفيديو\r\n"
        val win1256Bytes = arabicSample.toByteArray(Charset.forName("windows-1256"))
        val (decodedArabic, detectedCharset) = SubtitleParser.decodeBytesSmartly(win1256Bytes)
        assertEquals("Windows-1256", detectedCharset)
        val parsedArabic = SubtitleParser.parse(decodedArabic, SubtitleFormat.SRT)
        assertEquals(1, parsedArabic.size)
        assertEquals("أهلاً بكم في عالم الفيديو", parsedArabic[0].text)

        // UTF-16LE BOM
        val utf16Payload = "\uFEFF1\n00:00:02,000 --> 00:00:04,000\nترجمة UTF-16"
        val (decodedUtf16, encoding16) = SubtitleParser.decodeBytesSmartly(utf16Payload.toByteArray(Charsets.UTF_16LE))
        assertEquals("UTF-16LE", encoding16)
        assertEquals("ترجمة UTF-16", SubtitleParser.parse(decodedUtf16, SubtitleFormat.SRT).first().text)

        // SRT / VTT export roundtrip
        val exportedSrt = SubtitleParser.exportSrt(assCues)
        val reParsedSrt = SubtitleParser.parse(exportedSrt, SubtitleFormat.SRT)
        assertEquals(assCues.size, reParsedSrt.size)
        assertEquals(assCues[0].startTimeMs, reParsedSrt[0].startTimeMs)
        assertEquals(assCues[0].endTimeMs, reParsedSrt[0].endTimeMs)
        assertEquals(assCues[0].text, reParsedSrt[0].text)

        val exportedVtt = SubtitleParser.exportVtt(assCues)
        assertTrue(exportedVtt.startsWith("WEBVTT"))
        val reParsedVtt = SubtitleParser.parse(exportedVtt, SubtitleFormat.WEBVTT)
        assertEquals(1, reParsedVtt.size)
        assertEquals(assCues[0].text, reParsedVtt[0].text)
    }

    @Test
    fun subtitleParser_rejectsOversizedOrBinaryFileSafelyWithoutOom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val binaryFile = tempFolder.newFile("fake_video.bin")
        binaryFile.writeBytes(ByteArray(128) { 0 })
        val binaryResult = SubtitleParser.parseSafely(context, Uri.fromFile(binaryFile), "fake_video.bin")
        assertTrue(binaryResult is SubtitleParseResult.Error)

        val emptyFile = tempFolder.newFile("empty.srt")
        emptyFile.writeText("", Charsets.UTF_8)
        val emptyResult = SubtitleParser.parseSafely(context, Uri.fromFile(emptyFile), "empty.srt")
        assertTrue(emptyResult is SubtitleParseResult.Error)
    }

    @Test
    fun localSubtitleStore_savesUpdatesAndReloadsWithoutModifyingOriginal() = runTest {
        val rootDir = tempFolder.newFolder("subtitles_store")
        val externalSourceFile = tempFolder.newFile("original_external.srt")
        val originalSrtText = "1\n00:00:01,000 --> 00:00:03,000\nOriginal text\n"
        externalSourceFile.writeText(originalSrtText, Charsets.UTF_8)

        val store = LocalSubtitleStore(rootDir)
        val videoUri = "content://media/external/video/media/901"
        val originalCues = SubtitleParser.parse(originalSrtText, SubtitleFormat.SRT)

        val savedTrack = store.saveTrackForVideo(
            videoUri = videoUri,
            trackName = "English Subtitle",
            language = "en",
            cues = originalCues,
            format = SubtitleFormat.SRT
        )
        assertEquals(1, savedTrack.cues.size)

        val updatedCues = listOf(originalCues[0].copy(text = "Edited offline text"))
        val updatedTrack = store.updateTrackCues(videoUri, savedTrack.id, updatedCues)
        assertNotNull(updatedTrack)

        val reloadedTracks = store.loadTracksForVideo(videoUri)
        assertEquals(1, reloadedTracks.size)
        assertEquals("Edited offline text", reloadedTracks[0].cues[0].text)

        // Original external file MUST remain completely untouched
        assertEquals(originalSrtText, externalSourceFile.readText(Charsets.UTF_8))

        store.removeTrackForVideo(videoUri, savedTrack.id)
        assertTrue(store.loadTracksForVideo(videoUri).isEmpty())
    }

    @Test
    fun contextAwareDialogueTranslator_preservesTimingsContextAndZeroClientSecrets() = runTest {
        // Proxy security & zero client API key contract check
        assertFalse(BackendProxyConfig("").isConfiguredAndSecure())
        assertFalse(BackendProxyConfig("http://insecure.example.com").isConfiguredAndSecure())
        assertTrue(BackendProxyConfig("https://proxy.videohatak.example").isConfiguredAndSecure())

        val cues = (1..20).map { i ->
            SubtitleCue(
                index = i,
                startTimeMs = i * 2000L,
                endTimeMs = i * 2000L + 1500L,
                text = "Line number $i from dialogue scene"
            )
        }

        val capturedBatches = mutableListOf<SubtitleBatchRequest>()
        val fakeProvider = object : SubtitleTranslationProvider {
            override suspend fun translateBatch(request: SubtitleBatchRequest): SubtitleBatchResponse {
                capturedBatches.add(request)
                val map = request.cues.associate { cue ->
                    cue.index to "ترجمة الجملة رقم ${cue.index} في المشهد الحواري السياقي الطويل لتقسيم السطرين"
                }
                return SubtitleBatchResponse(request.batchIndex, map)
            }

            override suspend fun transcribeAndTranslateAudio(
                audioFile: File,
                sourceLanguage: SubtitleTranslationLanguage,
                targetLanguage: SubtitleTranslationLanguage
            ): List<TranslatedCuePair> = emptyList()
        }

        val pairs = ContextAwareDialogueTranslator.translateAllCues(
            cues = cues,
            sourceLanguage = SubtitleTranslationLanguage.ENGLISH,
            targetLanguage = SubtitleTranslationLanguage.ARABIC,
            provider = fakeProvider,
            glossary = listOf(TranslationGlossaryEntry("Hero", "البطل")),
            batchSize = 10
        )

        assertEquals(2, capturedBatches.size)
        // Batch 2 receives preceding dialogue context window from Batch 1
        assertEquals(2, capturedBatches[1].previousContext.size)
        assertEquals(2, capturedBatches[0].nextContext.size)

        assertEquals(20, pairs.size)
        pairs.forEachIndexed { idx, pair ->
            assertEquals(cues[idx].startTimeMs, pair.startTimeMs)
            assertEquals(cues[idx].endTimeMs, pair.endTimeMs)
            assertEquals(cues[idx].text, pair.originalText)
            assertTrue(pair.translatedText.contains("\n")) // 2-line balanced formatting applied
        }
    }
}
