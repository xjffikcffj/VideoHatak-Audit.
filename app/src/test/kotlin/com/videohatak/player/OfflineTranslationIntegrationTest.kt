package com.videohatak.player

import android.media.AudioFormat
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.speech.StreamingPcmResampler
import com.videohatak.player.core.subtitle.speech.VideoAudioDecodeException
import com.videohatak.player.core.subtitle.speech.VideoAudioPcmDecoder
import com.videohatak.player.core.subtitle.speech.VoskResultCueSegmenter
import com.videohatak.player.core.subtitle.speech.VoskWordToken
import com.videohatak.player.core.subtitle.translation.MlKitModelBridge
import com.videohatak.player.core.subtitle.translation.MlKitOfflineSubtitleTranslator
import com.videohatak.player.core.subtitle.translation.OfflineModelDownloadStatus
import com.videohatak.player.core.subtitle.translation.OfflineModelManager
import com.videohatak.player.core.subtitle.translation.OfflineTranslationException
import com.videohatak.player.core.subtitle.translation.SubtitleSourceMode
import com.videohatak.player.core.subtitle.translation.SubtitleTranslationLanguage
import com.videohatak.player.core.subtitle.translation.SubtitleTurnAndGlossaryHelper
import com.videohatak.player.core.subtitle.translation.TranslationGlossaryEntry
import com.videohatak.player.core.subtitle.translation.VoskModelCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private class FakeMlKitModelBridge : MlKitModelBridge {
    val downloadedCodes = mutableSetOf<String>()
    var downloadCallCount: Int = 0

    override suspend fun getDownloadedLanguageCodes(): Set<String> = downloadedCodes.toSet()

    override suspend fun downloadLanguageModel(mlKitCode: String) {
        downloadCallCount++
        downloadedCodes.add(mlKitCode)
    }

    override suspend fun deleteLanguageModel(mlKitCode: String) {
        downloadedCodes.remove(mlKitCode)
    }
}

@RunWith(RobolectricTestRunner::class)
class OfflineTranslationIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    @Test
    fun voskCatalog_allEightLanguagesHaveValidOfficialHttpsSpecsAndApache2License() {
        val speechLanguages = SubtitleTranslationLanguage.SPEECH_RECOGNITION_LANGUAGES
        assertEquals(8, speechLanguages.size)

        speechLanguages.forEach { lang ->
            val spec = VoskModelCatalog.specFor(lang)
            assertNotNull("Missing Vosk spec for ${lang.name}", spec)
            spec!!
            assertTrue(spec.downloadUrl.startsWith("https://alphacephei.com/vosk/models/"))
            assertTrue(spec.downloadUrl.endsWith(".zip"))
            assertEquals("Apache-2.0", spec.licenseName)
            assertTrue(spec.approximateZipMb > 0)
        }

        val arabicSpec = VoskModelCatalog.specFor(SubtitleTranslationLanguage.ARABIC)!!
        assertEquals("vosk-model-ar-mgb2-0.4", arabicSpec.modelId)
        assertNotNull(arabicSpec.accuracyCaveatArabic)
    }

    @Test
    fun subtitleLanguage_fromLanguageTag_mapsIsoCodesAndRejectsUnknown() {
        assertEquals(SubtitleTranslationLanguage.ENGLISH, SubtitleTranslationLanguage.fromLanguageTag("en"))
        assertEquals(SubtitleTranslationLanguage.ENGLISH, SubtitleTranslationLanguage.fromLanguageTag("eng"))
        assertEquals(SubtitleTranslationLanguage.ARABIC, SubtitleTranslationLanguage.fromLanguageTag("ar-SA"))
        assertEquals(SubtitleTranslationLanguage.ARABIC, SubtitleTranslationLanguage.fromLanguageTag("ara"))
        assertEquals(SubtitleTranslationLanguage.JAPANESE, SubtitleTranslationLanguage.fromLanguageTag("jpn"))
        assertEquals(SubtitleTranslationLanguage.KOREAN, SubtitleTranslationLanguage.fromLanguageTag("ko_KR"))
        assertNull(SubtitleTranslationLanguage.fromLanguageTag("und"))
        assertNull(SubtitleTranslationLanguage.fromLanguageTag("auto"))
        assertNull(SubtitleTranslationLanguage.fromLanguageTag(""))
        assertNull(SubtitleTranslationLanguage.fromLanguageTag(null))
    }

    @Test
    fun offlineModelManager_downloadExtractPersistAndDelete_blocksZipSlipAndInUseDeletion() = runTest {
        val rootDir = tempFolder.newFolder("offline_models_test")
        val fakeBridge = FakeMlKitModelBridge()
        val manager = OfflineModelManager(
            testRootDir = rootDir,
            mlKitBridge = fakeBridge,
            streamDownloader = { _, destFile, _, _ ->
                ZipOutputStream(FileOutputStream(destFile)).use { zos ->
                    zos.putNextEntry(ZipEntry("vosk-model-small-en-us-0.15/am/final.mdl"))
                    zos.write(byteArrayOf(1, 2, 3, 4))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("vosk-model-small-en-us-0.15/conf/model.conf"))
                    zos.write("--sample-frequency=16000".toByteArray())
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("vosk-model-small-en-us-0.15/graph/Gr.fst"))
                    zos.write(byteArrayOf(5, 6, 7, 8))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("vosk-model-small-en-us-0.15/graph/words.txt"))
                    zos.write("<eps> 0\nhello 1\nworld 2\n".toByteArray())
                    zos.closeEntry()
                }
            }
        )

        val dlRes = manager.downloadSpeechModelBlocking(SubtitleTranslationLanguage.ENGLISH)
        assertTrue(dlRes.success)
        assertEquals(
            OfflineModelDownloadStatus.READY,
            manager.languageStates.value[SubtitleTranslationLanguage.ENGLISH]?.speechStatus
        )
        assertNotNull(manager.getVerifiedSpeechModelDirectory(SubtitleTranslationLanguage.ENGLISH))
        assertTrue(File(rootDir, "catalog.json").exists())

        // Block deletion while speech model is actively in use
        manager.markSpeechInUse(SubtitleTranslationLanguage.ENGLISH, true)
        val blockedDel = manager.deleteSpeechModel(SubtitleTranslationLanguage.ENGLISH)
        assertFalse(blockedDel.success)
        manager.markSpeechInUse(SubtitleTranslationLanguage.ENGLISH, false)

        // Corrupt am/final.mdl -> detects ERROR and hides corrupt model directory
        val finalMdl = File(rootDir, "vosk/vosk-model-small-en-us-0.15/am/final.mdl")
        assertTrue(finalMdl.delete())
        manager.refreshAllStates()
        assertEquals(
            OfflineModelDownloadStatus.ERROR,
            manager.languageStates.value[SubtitleTranslationLanguage.ENGLISH]?.speechStatus
        )
        assertNull(manager.getVerifiedSpeechModelDirectory(SubtitleTranslationLanguage.ENGLISH))

        // Delete succeeds cleanly when not in use
        val delRes = manager.deleteSpeechModel(SubtitleTranslationLanguage.ENGLISH)
        assertTrue(delRes.success)
        assertEquals(
            OfflineModelDownloadStatus.NOT_DOWNLOADED,
            manager.languageStates.value[SubtitleTranslationLanguage.ENGLISH]?.speechStatus
        )

        // Zip Slip archive must be rejected safely
        val zipSlipManager = OfflineModelManager(
            testRootDir = tempFolder.newFolder("zip_slip_test"),
            mlKitBridge = fakeBridge,
            streamDownloader = { _, destFile, _, _ ->
                ZipOutputStream(FileOutputStream(destFile)).use { zos ->
                    zos.putNextEntry(ZipEntry("../../evil.txt"))
                    zos.write("malicious".toByteArray())
                    zos.closeEntry()
                }
            }
        )
        val maliciousRes = zipSlipManager.downloadSpeechModelBlocking(SubtitleTranslationLanguage.ENGLISH)
        assertFalse(maliciousRes.success)
        assertEquals(
            OfflineModelDownloadStatus.ERROR,
            zipSlipManager.languageStates.value[SubtitleTranslationLanguage.ENGLISH]?.speechStatus
        )
    }

    @Test
    fun offlineModelManager_keepsSpeechAndTranslationModelsStrictlyIndependent() = runTest {
        val rootDir = tempFolder.newFolder("independent_models_test")
        val fakeBridge = FakeMlKitModelBridge()
        val manager = OfflineModelManager(testRootDir = rootDir, mlKitBridge = fakeBridge)

        val mlDl = manager.downloadTranslationModelBlocking(SubtitleTranslationLanguage.ARABIC)
        assertTrue(mlDl.success)
        manager.refreshAllStates()

        val arabicState = manager.languageStates.value[SubtitleTranslationLanguage.ARABIC]!!
        assertEquals(OfflineModelDownloadStatus.READY, arabicState.translationStatus)
        assertEquals(OfflineModelDownloadStatus.NOT_DOWNLOADED, arabicState.speechStatus)
        assertEquals(
            OfflineModelDownloadStatus.READY,
            manager.languageStates.value[SubtitleTranslationLanguage.ENGLISH]?.translationStatus
        )

        val readiness = manager.computePairReadiness(
            sourceMode = SubtitleSourceMode.VIDEO_AUDIO_EXTRACTION,
            sourceLanguage = SubtitleTranslationLanguage.ARABIC,
            targetLanguage = SubtitleTranslationLanguage.ENGLISH
        )
        assertTrue(readiness.isSpeechRequired)
        assertFalse(readiness.isSpeechReady)
        assertTrue(readiness.missingMlKitLanguages.isEmpty())
        assertFalse(readiness.isFullyReadyOffline)
    }

    @Test
    fun voskResultSegmenter_parsesJsonWordsAndProducesNonOverlappingCues() {
        val sampleJson = """
            {
              "result": [
                {"conf": 1.0, "start": 0.50, "end": 0.85, "word": "hello"},
                {"conf": 0.9, "start": 0.90, "end": 1.25, "word": "world"},
                {"conf": 0.1, "start": 1.30, "end": 1.40, "word": "[unk]"},
                {"conf": 0.95, "start": 2.10, "end": 2.40, "word": "how"},
                {"conf": 0.98, "start": 2.45, "end": 2.75, "word": "are"},
                {"conf": 0.99, "start": 2.80, "end": 3.10, "word": "you"}
              ],
              "text": "hello world how are you"
            }
        """.trimIndent()

        val words = VoskResultCueSegmenter.parseVoskResultWords(sampleJson)
        assertEquals(5, words.size) // [unk] filtered out

        val cues = VoskResultCueSegmenter.segmentWordsIntoCues(words, SubtitleTranslationLanguage.ENGLISH)
        assertEquals(2, cues.size) // split on 850ms silence gap between 1.25s and 2.10s
        assertEquals("hello world", cues[0].text)
        assertEquals("how are you", cues[1].text)
        assertTrue(cues[0].endTimeMs <= cues[1].startTimeMs)
        assertTrue(cues[1].endTimeMs - cues[1].startTimeMs >= 900L)

        // Rapid adjacent words ensure strict monotonic non-overlapping enforcement
        val rapidWords = listOf(
            VoskWordToken("one", 100L, 250L),
            VoskWordToken("two", 800L, 950L)
        )
        val rapidCues = VoskResultCueSegmenter.segmentWordsIntoCues(rapidWords, SubtitleTranslationLanguage.ENGLISH)
        assertEquals(2, rapidCues.size)
        assertTrue(rapidCues[0].endTimeMs <= rapidCues[1].startTimeMs)
    }

    @Test
    fun subtitleTurnAndGlossaryHelper_preservesSpeakerTurnsAndGlossaryTerms() {
        val dualSpeakerCue = "<i>- Where is Stark?\n- In Winterfell.</i>"
        val turns = SubtitleTurnAndGlossaryHelper.splitSpeakerTurns(dualSpeakerCue)
        assertEquals(2, turns.size)
        assertEquals("- ", turns[0].prefix)
        assertEquals("Where is Stark?", turns[0].cleanText)
        assertEquals("- ", turns[1].prefix)
        assertEquals("In Winterfell.", turns[1].cleanText)

        val glossary = listOf(
            TranslationGlossaryEntry("Stark", "ستارك"),
            TranslationGlossaryEntry("Winterfell", "وينترفيل")
        )
        val protected = SubtitleTurnAndGlossaryHelper.protectGlossaryTerms(turns[0].cleanText, glossary)
        assertTrue(protected.maskedText.contains("VHTERM"))
        assertFalse(protected.maskedText.contains("Stark"))

        val fakeMlKitOutput = protected.maskedText.replace("Where is", "أين")
        val restored = SubtitleTurnAndGlossaryHelper.restoreGlossaryTerms(fakeMlKitOutput, protected)
        assertEquals("أين ستارك?", restored)
    }

    @Test
    fun streamingPcmResampler_converts48kTo16kProportionally() {
        val resampler = StreamingPcmResampler()
        val input48k = ShortArray(4_800) { idx -> (idx % 100).toShort() }
        val output16k = resampler.resampleTo16k(input48k, 48_000)
        assertTrue(output16k.size in 1_595..1_605)
    }

    @Test
    fun voskResultSegmenter_tightAdjacentWordsNeverOverlap() {
        val tightWords = (0 until 15).map { idx ->
            val start = idx * 450L
            VoskWordToken(word = "word$idx", startMs = start, endMs = start + 120L)
        }
        val cues = VoskResultCueSegmenter.segmentWordsIntoCues(
            words = tightWords,
            language = SubtitleTranslationLanguage.ENGLISH
        )
        assertTrue("Expected at least 2 cues", cues.size >= 2)
        for (i in cues.indices) {
            assertTrue("Cue $i must have positive duration", cues[i].endTimeMs > cues[i].startTimeMs)
            if (i + 1 < cues.size) {
                assertTrue(
                    "Cue $i (${cues[i].endTimeMs}) must not overlap cue ${i + 1} (${cues[i + 1].startTimeMs})",
                    cues[i].endTimeMs <= cues[i + 1].startTimeMs
                )
            }
        }
    }

    @Test
    fun offlineModelManager_rejectsInvalidZipSignature_andMlKitTranslatorBlocksAutoDownloadWhenDisabled() = runTest {
        val nonZipFile = tempFolder.newFile("not_a_zip.bin")
        nonZipFile.writeText("<html>404 Not Found</html>".repeat(4))
        assertFalse(OfflineModelManager.verifyZipSignature(nonZipFile))

        val rootDir = tempFolder.newFolder("no_auto_dl_test")
        val fakeBridge = FakeMlKitModelBridge()
        val manager = OfflineModelManager(testRootDir = rootDir, mlKitBridge = fakeBridge)
        val translator = MlKitOfflineSubtitleTranslator(manager)

        val cues = listOf(SubtitleCue(index = 1, startTimeMs = 1_000L, endTimeMs = 2_000L, text = "Hello"))
        val failure = runCatching {
            translator.translateCuesOffline(
                cues = cues,
                sourceLanguage = SubtitleTranslationLanguage.ENGLISH,
                targetLanguage = SubtitleTranslationLanguage.ARABIC,
                downloadMissingModels = false,
                onProgress = { _, _, _ -> }
            )
        }.exceptionOrNull()

        assertTrue(failure is OfflineTranslationException)
        assertEquals("Missing ML Kit models must never trigger silent downloads", 0, fakeBridge.downloadCallCount)
    }

    @Test
    fun videoAudioPcmDecoder_decodeBufferToMonoSamples_handlesAllPcmEncodingsAndRejectsUnsupported() {
        // 1. PCM 8-bit unsigned stereo (0xFF -> +32512, 0x01 -> -32512 => average ~0)
        val buf8 = ByteBuffer.wrap(byteArrayOf(0xFF.toByte(), 0x01.toByte()))
        val out8 = VideoAudioPcmDecoder.decodeBufferToMonoSamples(
            buffer = buf8,
            byteSize = 2,
            channelCount = 2,
            pcmEncoding = AudioFormat.ENCODING_PCM_8BIT
        )
        assertEquals(1, out8.size)
        assertTrue(out8[0] in -512..512)

        // 2. PCM 16-bit 6-channel surround downmix: (6000 + 6000 + 6000 + 0 + 0 + 0) / 6 = 3000
        val buf16 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        listOf<Short>(6000, 6000, 6000, 0, 0, 0).forEach { buf16.putShort(it) }
        buf16.flip()
        val out16 = VideoAudioPcmDecoder.decodeBufferToMonoSamples(
            buffer = buf16,
            byteSize = 12,
            channelCount = 6,
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        )
        assertArrayEqualsShort(shortArrayOf(3000), out16)

        // 3. PCM Float stereo (0.5f and -0.5f => 0; 1.0f mono => Short.MAX_VALUE)
        val bufFloat = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0.5f)
            flip()
        }
        val outFloat = VideoAudioPcmDecoder.decodeBufferToMonoSamples(
            buffer = bufFloat,
            byteSize = 4,
            channelCount = 1,
            pcmEncoding = AudioFormat.ENCODING_PCM_FLOAT
        )
        assertEquals(1, outFloat.size)
        assertTrue(outFloat[0] in 16_380..16_390)

        // 4. PCM 24-bit packed mono (little endian 0x00, 0x10, 0x00 => +16)
        val buf24 = ByteBuffer.wrap(byteArrayOf(0x00, 0x10, 0x00))
        val out24 = VideoAudioPcmDecoder.decodeBufferToMonoSamples(
            buffer = buf24,
            byteSize = 3,
            channelCount = 1,
            pcmEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED
        )
        assertArrayEqualsShort(shortArrayOf(16), out24)

        // 5. PCM 32-bit mono (0x01000000 shr 16 = 256)
        val buf32 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x01000000)
            flip()
        }
        val out32 = VideoAudioPcmDecoder.decodeBufferToMonoSamples(
            buffer = buf32,
            byteSize = 4,
            channelCount = 1,
            pcmEncoding = AudioFormat.ENCODING_PCM_32BIT
        )
        assertArrayEqualsShort(shortArrayOf(256), out32)

        // 6. Unsupported encoding must throw VideoAudioDecodeException
        assertThrows(VideoAudioDecodeException::class.java) {
            VideoAudioPcmDecoder.decodeBufferToMonoSamples(
                buffer = ByteBuffer.allocate(4),
                byteSize = 4,
                channelCount = 1,
                pcmEncoding = 9999
            )
        }
    }

    private fun assertArrayEqualsShort(expected: ShortArray, actual: ShortArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("Mismatch at index $i", expected[i], actual[i])
        }
    }
}
