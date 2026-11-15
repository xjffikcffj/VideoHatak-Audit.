package com.videohatak.player

import android.app.Application
import com.videohatak.player.core.backup.BackupManager
import com.videohatak.player.core.backup.BackupParseResult
import com.videohatak.player.core.backup.BackupPayload
import com.videohatak.player.core.backup.BackupPlaylistData
import com.videohatak.player.core.backup.BackupPlaylistItemData
import com.videohatak.player.core.backup.BackupSettingsData
import com.videohatak.player.core.backup.BackupSubtitleData
import com.videohatak.player.core.backup.BackupVaultSecurityData
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.playback.FrameCaptureHelper
import com.videohatak.player.core.playback.FrameCaptureResult
import com.videohatak.player.core.playback.FrameOutputWriter
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.vault.crypto.DefaultVaultFileCommitter
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultPinHasher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Targeted engineering verification suite covering database upgrades,
 * vault security/metadata crypto, atomic commits, screenshot capture safeguards,
 * API-scoped permissions, and checksummed backup/restore validation.
 */
class EngineeringFixesVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeApp : Application()

    @Test
    fun videoHatakDatabaseUpgrade_fromV1ToV3_usesTransactionAndNeverDropsTables() {
        val executedSql = mutableListOf<String>()
        var beganTx = false
        var markedSuccess = false
        var endedTx = false

        val adapter = object : VideoHatakDatabaseHelper.SqlUpgradeAdapter {
            override fun beginTransaction() {
                beganTx = true
            }

            override fun setTransactionSuccessful() {
                markedSuccess = true
            }

            override fun endTransaction() {
                endedTx = true
            }

            override fun execSQL(sql: String) {
                executedSql.add(sql)
            }

            override fun hasColumn(tableName: String, columnName: String): Boolean {
                // Simulate v1 schema missing is_available column
                return false
            }
        }

        val helper = VideoHatakDatabaseHelper(FakeApp())
        helper.upgradeSchema(adapter, oldVersion = 1, newVersion = 3)

        assertTrue(beganTx)
        assertTrue(markedSuccess)
        assertTrue(endedTx)
        assertFalse(executedSql.any { it.contains("DROP TABLE", ignoreCase = true) })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS trash_items") })
        assertTrue(
            executedSql.any {
                it.contains("ALTER TABLE playlist_items ADD COLUMN is_available", ignoreCase = true)
            }
        )
    }

    @Test
    fun vaultDatabaseUpgrade_fromV1ToV3_usesTransactionAndAddsEncryptedMetaWithoutDrop() {
        val executedSql = mutableListOf<String>()
        var beganTx = false
        var markedSuccess = false
        var endedTx = false

        val adapter = object : VaultDatabaseHelper.SqlUpgradeAdapter {
            override fun beginTransaction() {
                beganTx = true
            }

            override fun setTransactionSuccessful() {
                markedSuccess = true
            }

            override fun endTransaction() {
                endedTx = true
            }

            override fun execSQL(sql: String) {
                executedSql.add(sql)
            }

            override fun hasColumn(tableName: String, columnName: String): Boolean {
                // Simulate v1/v2 schema missing encrypted_meta_base64
                return false
            }
        }

        val helper = VaultDatabaseHelper(FakeApp())
        helper.upgradeSchema(adapter, oldVersion = 1, newVersion = 3)

        assertTrue(beganTx)
        assertTrue(markedSuccess)
        assertTrue(endedTx)
        assertFalse(executedSql.any { it.contains("DROP TABLE", ignoreCase = true) })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS vault_videos") })
        assertTrue(
            executedSql.any {
                it.contains("ALTER TABLE vault_videos ADD COLUMN encrypted_meta_base64", ignoreCase = true)
            }
        )
    }

    @Test
    fun vaultMetadataEncryption_roundTripAndTamperRejection() {
        val crypto = VaultCryptoManager(keyStoreProvider = "TEST_PROVIDER")
        val originalJson = """{"displayName":"فيلم_خاص_2026.mp4","mimeType":"video/mp4"}"""

        val encrypted = crypto.encryptMetadata(originalJson)
        assertTrue(encrypted.startsWith("VHTKMETA1:"))
        assertFalse(encrypted.contains("فيلم_خاص"))
        assertEquals(originalJson, crypto.decryptMetadata(encrypted))

        // Flip a byte inside Base64 payload to verify GCM tag rejection
        val rawPayload = Base64.getDecoder().decode(encrypted.removePrefix("VHTKMETA1:"))
        rawPayload[rawPayload.size - 1] = (rawPayload[rawPayload.size - 1].toInt() xor 0xFF).toByte()
        val tampered = "VHTKMETA1:" + Base64.getEncoder().encodeToString(rawPayload)

        assertThrows(GeneralSecurityException::class.java) {
            crypto.decryptMetadata(tampered)
        }
        assertThrows(IllegalArgumentException::class.java) {
            crypto.decryptMetadata("PLAIN_INVALID")
        }
    }

    @Test
    fun vaultFileCommitter_failureDuringCommit_preservesExistingFileAndCommitsValidTemp() {
        val destFile = tempFolder.newFile("vault_item_1.enc")
        val originalBytes = "EXISTING_ENCRYPTED_CONTAINER".toByteArray()
        destFile.writeBytes(originalBytes)

        val missingTemp = tempFolder.newFile("missing.enc.tmp").apply { delete() }
        assertThrows(IOException::class.java) {
            DefaultVaultFileCommitter.commitTempFile(missingTemp, destFile)
        }
        assertArrayEquals(originalBytes, destFile.readBytes())

        val emptyTemp = tempFolder.newFile("empty.enc.tmp")
        assertThrows(IOException::class.java) {
            DefaultVaultFileCommitter.commitTempFile(emptyTemp, destFile)
        }
        assertArrayEquals(originalBytes, destFile.readBytes())

        val validTemp = tempFolder.newFile("valid.enc.tmp")
        val replacementBytes = "NEW_ATOMIC_ENCRYPTED_DATA".toByteArray()
        validTemp.writeBytes(replacementBytes)

        DefaultVaultFileCommitter.commitTempFile(validTemp, destFile)
        assertTrue(destFile.exists())
        assertFalse(validTemp.exists())
        assertArrayEquals(replacementBytes, destFile.readBytes())
    }

    @Test
    fun vaultPinHasher_hashesWithSaltAndRejectsWrongPinOrInvalidFormat() {
        assertFalse(VaultPinHasher.isValidPinFormat("123"))
        assertFalse(VaultPinHasher.isValidPinFormat("12a4"))
        assertFalse(VaultPinHasher.isValidPinFormat("1234567890123"))
        assertTrue(VaultPinHasher.isValidPinFormat("1234"))
        assertTrue(VaultPinHasher.isValidPinFormat("123456789012"))

        val (hash1, salt1) = VaultPinHasher.hashPin("4829")
        val (hash2, salt2) = VaultPinHasher.hashPin("4829")

        assertNotEquals(salt1, salt2)
        assertNotEquals(hash1, hash2)
        assertTrue(VaultPinHasher.verifyPin("4829", hash1, salt1))
        assertTrue(VaultPinHasher.verifyPin("4829", hash2, salt2))
        assertFalse(VaultPinHasher.verifyPin("4820", hash1, salt1))
        assertFalse(VaultPinHasher.verifyPin("4829", "invalid_base64$$", salt1))
    }

    @Test
    fun vaultAutoLockTimeout_modelsImmediateAndInactivityThresholds() {
        assertTrue(VaultAutoLockTimeout.IMMEDIATE.shouldAutoLockOnBackground())
        assertFalse(VaultAutoLockTimeout.SECONDS_30.shouldAutoLockOnBackground())
        assertFalse(VaultAutoLockTimeout.MINUTE_1.shouldAutoLockOnBackground())
        assertFalse(VaultAutoLockTimeout.MINUTES_5.shouldAutoLockOnBackground())

        assertFalse(VaultAutoLockTimeout.IMMEDIATE.isInactivityExpired(1_000L, 100_000L))
        assertFalse(VaultAutoLockTimeout.SECONDS_30.isInactivityExpired(1_000L, 30_999L))
        assertTrue(VaultAutoLockTimeout.SECONDS_30.isInactivityExpired(1_000L, 31_000L))
        assertFalse(VaultAutoLockTimeout.MINUTE_1.isInactivityExpired(1_000L, 60_999L))
        assertTrue(VaultAutoLockTimeout.MINUTE_1.isInactivityExpired(1_000L, 61_000L))
        assertTrue(VaultAutoLockTimeout.MINUTES_5.isInactivityExpired(1_000L, 301_000L))
    }

    @Test
    fun frameCaptureHelper_saveFrameWithWriter_handlesSuccessAndFailureWithCleanup() {
        assertFalse(FrameCaptureHelper.hasDrmProtectedTracks(null))

        var recycledSuccess = false
        val capturedOutput = ByteArrayOutputStream()
        val successWriter = object : FrameOutputWriter {
            override fun writePng(
                fileName: String,
                folderName: String,
                compressPng: (OutputStream) -> Boolean
            ): FrameCaptureResult {
                assertTrue(fileName.startsWith("VideoHatak_") && fileName.endsWith(".png"))
                assertEquals("لقطات فيديوهاتك", folderName)
                val ok = compressPng(capturedOutput)
                assertTrue(ok)
                return FrameCaptureResult.Success("ok")
            }
        }

        val okResult = FrameCaptureHelper.saveFrameWithWriter(
            compressPng = { out ->
                out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                true
            },
            writer = successWriter,
            onComplete = { recycledSuccess = true }
        )
        assertTrue(okResult is FrameCaptureResult.Success)
        assertTrue(recycledSuccess)
        assertEquals(4, capturedOutput.size())

        var recycledFailure = false
        val throwingWriter = object : FrameOutputWriter {
            override fun writePng(
                fileName: String,
                folderName: String,
                compressPng: (OutputStream) -> Boolean
            ): FrameCaptureResult {
                throw IllegalStateException("IO write failure")
            }
        }
        val errResult = FrameCaptureHelper.saveFrameWithWriter(
            compressPng = { true },
            writer = throwingWriter,
            onComplete = { recycledFailure = true }
        )
        assertTrue(errResult is FrameCaptureResult.Error)
        assertTrue(recycledFailure)
    }

    @Test
    fun permissionHelper_separatesMediaAndLegacyWritePermissionsAcrossApiLevels() {
        val api34Media = PermissionHelper.getMediaPermissionsForSdk(34).toSet()
        assertEquals(
            setOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ),
            api34Media
        )
        assertFalse(api34Media.contains(android.Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(api34Media.contains(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))

        val api33Media = PermissionHelper.getMediaPermissionsForSdk(33).toSet()
        assertEquals(setOf(android.Manifest.permission.READ_MEDIA_VIDEO), api33Media)
        assertFalse(api33Media.contains(android.Manifest.permission.POST_NOTIFICATIONS))

        val api28Media = PermissionHelper.getMediaPermissionsForSdk(28).toSet()
        assertEquals(setOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), api28Media)

        assertEquals(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            PermissionHelper.getLegacyWritePermissionForSdk(28)
        )
        assertNull(PermissionHelper.getLegacyWritePermissionForSdk(29))
        assertNull(PermissionHelper.getLegacyWritePermissionForSdk(33))

        assertEquals(
            android.Manifest.permission.POST_NOTIFICATIONS,
            PermissionHelper.getNotificationPermissionForSdk(33)
        )
        assertNull(PermissionHelper.getNotificationPermissionForSdk(32))
    }

    @Test
    fun backupManager_serializeParseAndChecksumValidation() {
        val originalPayload = BackupPayload(
            schemaVersion = BackupManager.SCHEMA_VERSION,
            createdAtEpochMs = 1_750_000_000_000L,
            settings = BackupSettingsData(
                selectedTheme = AppThemeId.RUBY_CINEMA,
                themeMode = ThemeMode.DARK,
                playbackSpeed = 1.5f,
                resizeMode = VideoResizeMode.RATIO_16_9.storageCode,
                playbackMode = PlaybackMode.REPEAT_ONE,
                resumeMode = ResumeMode.ASK_EVERY_TIME
            ),
            subtitles = BackupSubtitleData(
                primaryFontSizeSp = 20f,
                secondaryFontSizeSp = 14f
            ),
            vaultSecurity = BackupVaultSecurityData(
                autoLockTimeout = VaultAutoLockTimeout.MINUTE_1
            ),
            playlists = listOf(
                BackupPlaylistData(
                    title = "المفضلة \\ \"الخاصة\"",
                    createdAt = 1_700_000_000_000L,
                    updatedAt = 1_700_000_100_000L,
                    items = listOf(
                        BackupPlaylistItemData(
                            videoId = 101L,
                            videoTitle = "مقطع_تجريبي.mp4",
                            durationMs = 95_000L,
                            sizeBytes = 12_345_678L,
                            dateAddedSec = 1_700_000_000L,
                            contentUri = "content://media/external/video/media/101",
                            isAvailable = true
                        )
                    )
                )
            )
        )

        val json = BackupManager.serializeBackupJson(originalPayload)
        assertFalse(json.contains("pinHash", ignoreCase = true))
        assertFalse(json.contains("pinSalt", ignoreCase = true))

        val parsed = BackupManager.parseAndValidateBackupJson(json)
        assertTrue(parsed is BackupParseResult.Success)
        val restored = (parsed as BackupParseResult.Success).payload
        assertEquals(AppThemeId.RUBY_CINEMA, restored.settings.selectedTheme)
        assertEquals(1.5f, restored.settings.playbackSpeed, 0.001f)
        assertEquals(VideoResizeMode.RATIO_16_9.storageCode, restored.settings.resizeMode)
        assertEquals(PlaybackMode.REPEAT_ONE, restored.settings.playbackMode)
        assertEquals(ResumeMode.ASK_EVERY_TIME, restored.settings.resumeMode)
        assertEquals(VaultAutoLockTimeout.MINUTE_1, restored.vaultSecurity.autoLockTimeout)
        assertEquals(1, restored.playlists.size)
        assertEquals("المفضلة \\ \"الخاصة\"", restored.playlists[0].title)
        assertEquals(1, restored.playlists[0].items.size)

        // Tampering with body breaks SHA-256 integrity check
        val tamperedJson = json.replace("RUBY_CINEMA", "EMERALD_LUXURY")
        assertTrue(BackupManager.parseAndValidateBackupJson(tamperedJson) is BackupParseResult.Error)

        // Unsupported future schema version rejected cleanly
        val unsupportedVersion = json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertTrue(BackupManager.parseAndValidateBackupJson(unsupportedVersion) is BackupParseResult.Error)

        // Injected Vault secret key rejected cleanly
        val injectedSecret = json.replace(
            "\"autoLockTimeout\":\"MINUTE_1\"",
            "\"autoLockTimeout\":\"MINUTE_1\",\"pinHash\":\"secret\""
        )
        assertTrue(BackupManager.parseAndValidateBackupJson(injectedSecret) is BackupParseResult.Error)
    }
}
