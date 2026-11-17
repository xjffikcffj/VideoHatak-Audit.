package com.videohatak.player.core.backup

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepository
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepositoryImpl
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.VideoPlayerManager
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playlist.db.BackupPlaylistStore
import com.videohatak.player.core.playlist.db.VideoHatakDatabaseHelper
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.repository.LocalSubtitleStore
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesRepository
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesStore
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultSecurityBackupStore
import com.videohatak.player.core.vault.security.VaultSecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class BackupSettingsData(
    val selectedTheme: AppThemeId,
    val themeMode: ThemeMode,
    val playbackSpeed: Float,
    val resizeMode: Int,
    val playbackMode: PlaybackMode,
    val resumeMode: ResumeMode,
    val seekIntervalSeconds: Int = PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS
)

data class BackupSubtitleData(
    val primaryFontSizeSp: Float,
    val secondaryFontSizeSp: Float
)

data class BackupVaultSecurityData(
    val autoLockTimeout: VaultAutoLockTimeout
)

data class BackupPlaylistItemData(
    val videoId: Long,
    val videoTitle: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val contentUri: String,
    val isAvailable: Boolean
)

data class BackupPlaylistData(
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val items: List<BackupPlaylistItemData>
)

data class BackupPayload(
    val schemaVersion: Int = BackupManager.SCHEMA_VERSION,
    val createdAtEpochMs: Long,
    val settings: BackupSettingsData,
    val subtitles: BackupSubtitleData,
    val vaultSecurity: BackupVaultSecurityData,
    val playlists: List<BackupPlaylistData>,
    val resumePositions: Map<String, Long> = emptyMap(),
    val localSubtitles: List<LocalSubtitleStore.BackupStoredSubtitleTrack> = emptyList(),
    val sha256Checksum: String = ""
)

data class BackupExportSummary(
    val playlistCount: Int,
    val playlistItemCount: Int,
    val byteSize: Int,
    val resumePositionCount: Int = 0,
    val localSubtitleTrackCount: Int = 0
)

data class BackupRestoreSummary(
    val playlistCount: Int,
    val playlistItemCount: Int,
    val themeRestored: AppThemeId,
    val resumePositionCount: Int = 0,
    val localSubtitleTrackCount: Int = 0
)

sealed interface BackupParseResult {
    data class Success(val payload: BackupPayload) : BackupParseResult
    data class Error(val message: String) : BackupParseResult
}

/**
 * Backup & Restore manager for app settings, subtitle preferences,
 * non-secret Vault auto-lock policy, saved playback resume positions (hashed keys),
 * and user playlists.
 *
 * Integrity note: `sha256Checksum` detects file corruption or modification during storage/transfer,
 * NOT cryptographic origin authentication/digital signature.
 *
 * Security boundary (never backed up): Raw video media files, Vault encrypted videos,
 * Vault PIN hashes/salts, Android Keystore keys, or file DEKs.
 */
class BackupManager internal constructor(
    private val context: Context?,
    private val themeRepository: ThemePreferencesRepository,
    private val playbackRepository: PlaybackPreferencesRepository,
    private val subtitleRepository: SubtitlePreferencesStore,
    private val vaultSecurityStore: VaultSecurityBackupStore,
    private val playlistStore: BackupPlaylistStore,
    private val subtitleStore: LocalSubtitleStore? = null
) {

    constructor(
        context: Context,
        themeRepository: ThemePreferencesRepository = ThemePreferencesRepositoryImpl(context),
        playbackRepository: PlaybackPreferencesRepository = PlaybackPreferencesRepositoryImpl(context),
        subtitleRepository: SubtitlePreferencesRepository = SubtitlePreferencesRepository(context),
        vaultSecurityPreferences: VaultSecurityPreferences = VaultSecurityPreferences(context),
        databaseHelper: VideoHatakDatabaseHelper = VideoHatakDatabaseHelper(context),
        subtitleStore: LocalSubtitleStore = LocalSubtitleStore(context)
    ) : this(
        context = context,
        themeRepository = themeRepository,
        playbackRepository = playbackRepository,
        subtitleRepository = subtitleRepository as SubtitlePreferencesStore,
        vaultSecurityStore = vaultSecurityPreferences as VaultSecurityBackupStore,
        playlistStore = databaseHelper as BackupPlaylistStore,
        subtitleStore = subtitleStore
    )

    suspend fun exportToUri(uri: Uri): Result<BackupExportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val nonNullContext = requireNotNull(context) { "Context is required for exportToUri" }
            val payload = captureCurrentPayload()
            val json = serializeBackupJson(payload)
            val bytes = json.toByteArray(Charsets.UTF_8)
            nonNullContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: throw IllegalStateException("تعذر فتح ملف التصدير للكتابة")

            BackupExportSummary(
                playlistCount = payload.playlists.size,
                playlistItemCount = payload.playlists.sumOf { it.items.size },
                byteSize = bytes.size,
                resumePositionCount = payload.resumePositions.size,
                localSubtitleTrackCount = payload.localSubtitles.size
            )
        }
    }

    suspend fun importFromUri(uri: Uri): Result<BackupRestoreSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val nonNullContext = requireNotNull(context) { "Context is required for importFromUri" }
            val rawJson = nonNullContext.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("تعذر قراءة ملف النسخة الاحتياطية")

            when (val parsed = parseAndValidateBackupJson(rawJson)) {
                is BackupParseResult.Error -> throw IllegalArgumentException(parsed.message)
                is BackupParseResult.Success -> {
                    applyPayload(parsed.payload)
                    BackupRestoreSummary(
                        playlistCount = parsed.payload.playlists.size,
                        playlistItemCount = parsed.payload.playlists.sumOf { it.items.size },
                        themeRestored = parsed.payload.settings.selectedTheme,
                        resumePositionCount = parsed.payload.resumePositions.size,
                        localSubtitleTrackCount = parsed.payload.localSubtitles.size
                    )
                }
            }
        }
    }

    suspend fun captureCurrentPayload(createdAtMs: Long = System.currentTimeMillis()): BackupPayload {
        val selectedTheme = themeRepository.appThemeFlow.first()
        val themeMode = themeRepository.themeModeFlow.first()
        val playbackSpeed = VideoPlayerManager.clampPlaybackSpeed(playbackRepository.playbackSpeed.first())
        val resizeMode = VideoResizeMode.fromCode(playbackRepository.resizeMode.first()).storageCode
        val playbackMode = playbackRepository.playbackMode.first()
        val resumeMode = playbackRepository.resumeMode.first()
        val seekIntervalSeconds = PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(
            playbackRepository.seekIntervalSeconds.first()
        )

        val primarySub = subtitleRepository.primaryStyle.first()
        val secondarySub = subtitleRepository.secondaryStyle.first()
        val autoLock = vaultSecurityStore.autoLockTimeout.first()

        val playlists = playlistStore.getPlaylists().map { playlist ->
            val items = playlistStore.getItemsForPlaylist(playlist.id).map { item ->
                BackupPlaylistItemData(
                    videoId = item.videoId,
                    videoTitle = item.videoTitle,
                    durationMs = item.durationMs,
                    sizeBytes = item.sizeBytes,
                    dateAddedSec = item.dateAddedSec,
                    contentUri = item.contentUri,
                    isAvailable = item.isAvailable
                )
            }
            BackupPlaylistData(
                title = playlist.title,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
                items = items
            )
        }

        val savedPositions = playbackRepository.exportSavedPositions()
        val storedSubtitles = runCatching { subtitleStore?.exportAllStoredTracksForBackup() }.getOrNull().orEmpty()

        return BackupPayload(
            schemaVersion = SCHEMA_VERSION,
            createdAtEpochMs = createdAtMs,
            settings = BackupSettingsData(
                selectedTheme = selectedTheme,
                themeMode = themeMode,
                playbackSpeed = playbackSpeed,
                resizeMode = resizeMode,
                playbackMode = playbackMode,
                resumeMode = resumeMode,
                seekIntervalSeconds = seekIntervalSeconds
            ),
            subtitles = BackupSubtitleData(
                primaryFontSizeSp = primarySub.fontSizeSp,
                secondaryFontSizeSp = secondarySub.fontSizeSp
            ),
            vaultSecurity = BackupVaultSecurityData(
                autoLockTimeout = autoLock
            ),
            playlists = playlists,
            resumePositions = savedPositions,
            localSubtitles = storedSubtitles
        )
    }

    private fun toDatabaseEntries(playlists: List<BackupPlaylistData>): List<Pair<Playlist, List<PlaylistItem>>> {
        return playlists.map { pl ->
            val playlist = Playlist(
                id = 0L,
                title = pl.title,
                createdAt = pl.createdAt,
                updatedAt = pl.updatedAt
            )
            val items = pl.items.mapIndexed { idx, item ->
                PlaylistItem(
                    id = 0L,
                    playlistId = 0L,
                    videoId = item.videoId,
                    orderIndex = idx,
                    videoTitle = item.videoTitle,
                    durationMs = item.durationMs,
                    sizeBytes = item.sizeBytes,
                    dateAddedSec = item.dateAddedSec,
                    contentUri = item.contentUri,
                    isAvailable = item.isAvailable
                )
            }
            playlist to items
        }
    }

    suspend fun applyPayload(payload: BackupPayload) {
        val previousSnapshot = captureCurrentPayload()
        val targetDbEntries = toDatabaseEntries(payload.playlists)

        // Commit transactional DB replacement first: if SQLite fails or returns -1,
        // no DataStore preferences are touched.
        playlistStore.replaceAllPlaylistsAndItems(targetDbEntries)

        try {
            themeRepository.setAppTheme(payload.settings.selectedTheme)
            themeRepository.setThemeMode(payload.settings.themeMode)
            playbackRepository.setPlaybackSpeed(VideoPlayerManager.clampPlaybackSpeed(payload.settings.playbackSpeed))
            playbackRepository.setResizeMode(VideoResizeMode.fromCode(payload.settings.resizeMode).storageCode)
            playbackRepository.setPlaybackMode(payload.settings.playbackMode)
            playbackRepository.setResumeMode(payload.settings.resumeMode)
            playbackRepository.setSeekIntervalSeconds(
                PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(payload.settings.seekIntervalSeconds)
            )
            playbackRepository.replaceSavedPositions(payload.resumePositions)

            subtitleRepository.updatePrimaryFontSize(payload.subtitles.primaryFontSizeSp.coerceIn(12f, 36f))
            subtitleRepository.updateSecondaryFontSize(payload.subtitles.secondaryFontSizeSp.coerceIn(10f, 32f))
            vaultSecurityStore.setAutoLockTimeout(payload.vaultSecurity.autoLockTimeout)
            subtitleStore?.replaceAllStoredTracksFromBackup(payload.localSubtitles)
        } catch (e: Throwable) {
            try {
                playlistStore.replaceAllPlaylistsAndItems(toDatabaseEntries(previousSnapshot.playlists))
                themeRepository.setAppTheme(previousSnapshot.settings.selectedTheme)
                themeRepository.setThemeMode(previousSnapshot.settings.themeMode)
                playbackRepository.setPlaybackSpeed(previousSnapshot.settings.playbackSpeed)
                playbackRepository.setResizeMode(previousSnapshot.settings.resizeMode)
                playbackRepository.setPlaybackMode(previousSnapshot.settings.playbackMode)
                playbackRepository.setResumeMode(previousSnapshot.settings.resumeMode)
                playbackRepository.setSeekIntervalSeconds(previousSnapshot.settings.seekIntervalSeconds)
                playbackRepository.replaceSavedPositions(previousSnapshot.resumePositions)
                subtitleRepository.updatePrimaryFontSize(previousSnapshot.subtitles.primaryFontSizeSp)
                subtitleRepository.updateSecondaryFontSize(previousSnapshot.subtitles.secondaryFontSizeSp)
                vaultSecurityStore.setAutoLockTimeout(previousSnapshot.vaultSecurity.autoLockTimeout)
                subtitleStore?.replaceAllStoredTracksFromBackup(previousSnapshot.localSubtitles)
            } catch (rollbackError: Throwable) {
                e.addSuppressed(rollbackError)
            }
            throw e
        }
    }

    companion object {
        const val SCHEMA_VERSION = 3
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2, 3)

        internal fun createForTesting(
            themeRepository: ThemePreferencesRepository,
            playbackRepository: PlaybackPreferencesRepository,
            subtitleRepository: SubtitlePreferencesStore,
            vaultSecurityStore: VaultSecurityBackupStore,
            playlistStore: BackupPlaylistStore,
            subtitleStore: LocalSubtitleStore? = null
        ): BackupManager {
            return BackupManager(
                context = null,
                themeRepository = themeRepository,
                playbackRepository = playbackRepository,
                subtitleRepository = subtitleRepository,
                vaultSecurityStore = vaultSecurityStore,
                playlistStore = playlistStore,
                subtitleStore = subtitleStore
            )
        }

        fun serializeBackupJson(payload: BackupPayload): String {
            val canonicalBody = buildCanonicalBodyJson(payload)
            val checksum = sha256Hex(canonicalBody)

            return buildString {
                append("{\n")
                append("  \"schemaVersion\": ").append(payload.schemaVersion).append(",\n")
                append("  \"createdAtEpochMs\": ").append(payload.createdAtEpochMs).append(",\n")
                append("  \"sha256Checksum\": ").append(quoteJson(checksum)).append(",\n")
                append("  \"body\": ").append(canonicalBody).append("\n")
                append("}")
            }
        }

        fun parseAndValidateBackupJson(rawJson: String): BackupParseResult {
            val root = try {
                MiniJsonParser(rawJson).parseValue() as? Map<*, *>
            } catch (_: Exception) {
                null
            } ?: return BackupParseResult.Error("ملف النسخة الاحتياطية تالف أو ليس بصيغة JSON صالحة")

            val version = asJsonNumber(root["schemaVersion"])?.toInt()
                ?: return BackupParseResult.Error("إصدار النسخة الاحتياطية غير محدد")
            if (version !in SUPPORTED_SCHEMA_VERSIONS) {
                return BackupParseResult.Error("إصدار النسخة الاحتياطية ($version) غير مدعوم في هذا الإصدار")
            }

            val createdAt = asJsonNumber(root["createdAtEpochMs"])?.toLong() ?: 0L
            val declaredChecksum = (root["sha256Checksum"] as? String)?.trim()?.lowercase()
                ?: return BackupParseResult.Error("البصمة الأمنية للنسخة الاحتياطية مفقودة")

            val bodyMap = root["body"] as? Map<*, *>
                ?: return BackupParseResult.Error("بيانات النسخة الاحتياطية غير مكتملة")

            // Enforce strict separation: never allow secret fields if injected
            val vaultMap = bodyMap["vaultSecurity"] as? Map<*, *>
                ?: return BackupParseResult.Error("قسم أمان الخزنة مفقود")
            val forbiddenSecretKeys = setOf("pinhash", "pinsalt", "pin", "dek", "keystorealias", "encryptedmetabase64")
            val containsForbiddenKey = listOf(root.keys, bodyMap.keys, vaultMap.keys)
                .flatten()
                .any { it.toString().lowercase() in forbiddenSecretKeys }
            if (containsForbiddenKey) {
                return BackupParseResult.Error("ملف غير صالح: يحتوي على حقول أمان محظورة")
            }

            val settingsMap = bodyMap["settings"] as? Map<*, *>
                ?: return BackupParseResult.Error("قسم الإعدادات مفقود")
            val subtitlesMap = bodyMap["subtitles"] as? Map<*, *>
                ?: return BackupParseResult.Error("قسم الترجمة مفقود")
            val playlistsRaw = bodyMap["playlists"] as? List<*>
                ?: return BackupParseResult.Error("قسم قوائم التشغيل مفقود")

            val themeName = settingsMap["selectedTheme"] as? String
                ?: return BackupParseResult.Error("اسم الثيم مفقود")
            val selectedTheme = AppThemeId.entries.firstOrNull { it.name == themeName }
                ?: return BackupParseResult.Error("ثيم غير معروف: $themeName")

            val themeModeName = settingsMap["themeMode"] as? String
                ?: return BackupParseResult.Error("وضع السطوع مفقود")
            val themeMode = ThemeMode.entries.firstOrNull { it.name == themeModeName }
                ?: return BackupParseResult.Error("وضع سطوع غير معروف")

            val rawSpeed = asJsonNumber(settingsMap["playbackSpeed"])?.toFloat()
                ?: return BackupParseResult.Error("سرعة التشغيل مفقودة")
            val playbackSpeed = VideoPlayerManager.clampPlaybackSpeed(rawSpeed)

            val rawResize = asJsonNumber(settingsMap["resizeMode"])?.toInt()
                ?: return BackupParseResult.Error("نمط أبعاد الشاشة مفقود")
            val resizeMode = VideoResizeMode.fromCode(rawResize).storageCode

            val playbackModeName = settingsMap["playbackMode"] as? String
                ?: return BackupParseResult.Error("وضع التشغيل مفقود")
            val playbackMode = PlaybackMode.entries.firstOrNull { it.name == playbackModeName }
                ?: return BackupParseResult.Error("وضع تشغيل غير معروف")

            val resumeModeName = settingsMap["resumeMode"] as? String
                ?: return BackupParseResult.Error("وضع الاستئناف مفقود")
            val resumeMode = ResumeMode.entries.firstOrNull { it.name == resumeModeName }
                ?: return BackupParseResult.Error("وضع استئناف غير معروف")

            val rawSeekInterval = asJsonNumber(settingsMap["seekIntervalSeconds"])?.toInt()
            val seekIntervalSeconds = PlaybackPreferencesRepository.sanitizeSeekIntervalSeconds(
                rawSeekInterval ?: PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS
            )

            val rawPrimarySub = asJsonNumber(subtitlesMap["primaryFontSizeSp"])?.toFloat() ?: 18f
            val rawSecondarySub = asJsonNumber(subtitlesMap["secondaryFontSizeSp"])?.toFloat() ?: 15f
            val primarySubSize = rawPrimarySub.coerceIn(12f, 36f)
            val secondarySubSize = rawSecondarySub.coerceIn(10f, 32f)

            val autoLockName = vaultMap["autoLockTimeout"] as? String ?: VaultAutoLockTimeout.IMMEDIATE.name
            val autoLock = VaultAutoLockTimeout.entries.firstOrNull { it.name == autoLockName }
                ?: VaultAutoLockTimeout.IMMEDIATE

            val numberLexemes = HashMap<String, String>()
            asJsonLexeme(settingsMap["playbackSpeed"])?.let { numberLexemes["settings.playbackSpeed"] = it }
            asJsonLexeme(settingsMap["resizeMode"])?.let { numberLexemes["settings.resizeMode"] = it }
            asJsonLexeme(settingsMap["seekIntervalSeconds"])?.let { numberLexemes["settings.seekIntervalSeconds"] = it }
            asJsonLexeme(subtitlesMap["primaryFontSizeSp"])?.let { numberLexemes["subtitles.primaryFontSizeSp"] = it }
            asJsonLexeme(subtitlesMap["secondaryFontSizeSp"])?.let { numberLexemes["subtitles.secondaryFontSizeSp"] = it }

            val playlists = playlistsRaw.mapIndexed { idx, entry ->
                val plMap = entry as? Map<*, *>
                    ?: return BackupParseResult.Error("عنصر قائمة تشغيل غير صالح عند الموضع $idx")
                val title = (plMap["title"] as? String)?.trim().orEmpty()
                if (title.isBlank()) {
                    return BackupParseResult.Error("اسم قائمة التشغيل فارغ عند الموضع $idx")
                }
                val pCreatedAt = asJsonNumber(plMap["createdAt"])?.toLong() ?: 0L
                val pUpdatedAt = asJsonNumber(plMap["updatedAt"])?.toLong() ?: 0L
                asJsonLexeme(plMap["createdAt"])?.let { numberLexemes["playlists.$idx.createdAt"] = it }
                asJsonLexeme(plMap["updatedAt"])?.let { numberLexemes["playlists.$idx.updatedAt"] = it }

                val itemsRaw = plMap["items"] as? List<*> ?: emptyList<Any>()
                val items = itemsRaw.mapIndexed { itemIdx, itemAny ->
                    val itemMap = itemAny as? Map<*, *>
                        ?: return BackupParseResult.Error("عنصر فيديو غير صالح داخل القائمة $title")
                    asJsonLexeme(itemMap["videoId"])?.let { numberLexemes["playlists.$idx.items.$itemIdx.videoId"] = it }
                    asJsonLexeme(itemMap["durationMs"])?.let { numberLexemes["playlists.$idx.items.$itemIdx.durationMs"] = it }
                    asJsonLexeme(itemMap["sizeBytes"])?.let { numberLexemes["playlists.$idx.items.$itemIdx.sizeBytes"] = it }
                    asJsonLexeme(itemMap["dateAddedSec"])?.let { numberLexemes["playlists.$idx.items.$itemIdx.dateAddedSec"] = it }
                    BackupPlaylistItemData(
                        videoId = asJsonNumber(itemMap["videoId"])?.toLong() ?: 0L,
                        videoTitle = (itemMap["videoTitle"] as? String).orEmpty(),
                        durationMs = asJsonNumber(itemMap["durationMs"])?.toLong() ?: 0L,
                        sizeBytes = asJsonNumber(itemMap["sizeBytes"])?.toLong() ?: 0L,
                        dateAddedSec = asJsonNumber(itemMap["dateAddedSec"])?.toLong() ?: 0L,
                        contentUri = (itemMap["contentUri"] as? String).orEmpty(),
                        isAvailable = (itemMap["isAvailable"] as? Boolean) ?: true
                    )
                }
                BackupPlaylistData(
                    title = title,
                    createdAt = pCreatedAt,
                    updatedAt = pUpdatedAt,
                    items = items
                )
            }

            val rawPositions = bodyMap["resumePositions"] as? Map<*, *>
            val resumePositions = linkedMapOf<String, Long>()
            if (rawPositions != null) {
                for ((k, v) in rawPositions) {
                    val keyStr = k?.toString()?.trim().orEmpty()
                    val posMs = asJsonNumber(v)?.toLong() ?: -1L
                    if (keyStr.isNotBlank() && posMs > 0L) {
                        resumePositions[keyStr] = posMs
                        asJsonLexeme(v)?.let { numberLexemes["resumePositions.$keyStr"] = it }
                    }
                }
            }

            val rawLocalSubtitles = bodyMap["localSubtitles"] as? List<*>
            val localSubtitles = mutableListOf<LocalSubtitleStore.BackupStoredSubtitleTrack>()
            rawLocalSubtitles?.forEach { entry ->
                val map = entry as? Map<*, *> ?: return@forEach
                val hash = (map["videoKeyHash"] as? String).orEmpty()
                val trackId = (map["trackId"] as? String).orEmpty()
                val trackName = (map["trackName"] as? String).orEmpty()
                val language = (map["language"] as? String)?.takeIf { it.isNotBlank() }
                val formatName = (map["format"] as? String) ?: SubtitleFormat.SRT.name
                val format = SubtitleFormat.entries.firstOrNull { it.name == formatName } ?: SubtitleFormat.SRT
                val content = (map["content"] as? String).orEmpty()
                val updatedAtMs = asJsonNumber(map["updatedAtMs"])?.toLong() ?: 0L
                if (hash.isNotBlank() && trackId.isNotBlank() && content.isNotBlank()) {
                    val sIdx = localSubtitles.size
                    asJsonLexeme(map["updatedAtMs"])?.let { numberLexemes["localSubtitles.$sIdx.updatedAtMs"] = it }
                    localSubtitles.add(
                        LocalSubtitleStore.BackupStoredSubtitleTrack(
                            videoKeyHash = hash,
                            trackId = trackId,
                            trackName = trackName,
                            language = language,
                            format = format,
                            content = content,
                            updatedAtMs = updatedAtMs
                        )
                    )
                }
            }

            val candidate = BackupPayload(
                schemaVersion = version,
                createdAtEpochMs = createdAt,
                settings = BackupSettingsData(
                    selectedTheme = selectedTheme,
                    themeMode = themeMode,
                    playbackSpeed = rawSpeed,
                    resizeMode = rawResize,
                    playbackMode = playbackMode,
                    resumeMode = resumeMode,
                    seekIntervalSeconds = rawSeekInterval ?: PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS
                ),
                subtitles = BackupSubtitleData(
                    primaryFontSizeSp = rawPrimarySub,
                    secondaryFontSizeSp = rawSecondarySub
                ),
                vaultSecurity = BackupVaultSecurityData(autoLockTimeout = autoLock),
                playlists = playlists,
                resumePositions = resumePositions,
                localSubtitles = localSubtitles
            )

            val expectedChecksum = sha256Hex(buildCanonicalBodyJson(candidate, numberLexemes))
            if (!MessageDigest.isEqual(
                    expectedChecksum.toByteArray(Charsets.UTF_8),
                    declaredChecksum.toByteArray(Charsets.UTF_8)
                )
            ) {
                return BackupParseResult.Error("فشل التحقق من سلامة النسخة الاحتياطية: تم تعديل الملف أو تلفه")
            }

            return BackupParseResult.Success(
                candidate.copy(
                    settings = candidate.settings.copy(
                        playbackSpeed = playbackSpeed,
                        resizeMode = resizeMode,
                        seekIntervalSeconds = seekIntervalSeconds
                    ),
                    subtitles = BackupSubtitleData(
                        primaryFontSizeSp = primarySubSize,
                        secondaryFontSizeSp = secondarySubSize
                    ),
                    sha256Checksum = declaredChecksum
                )
            )
        }

        private fun asJsonNumber(value: Any?): Number? = when (value) {
            is JsonRawNumber -> value.number
            is Number -> value
            else -> null
        }

        private fun asJsonLexeme(value: Any?): String? = when (value) {
            is JsonRawNumber -> value.rawLexeme
            else -> null
        }

        private fun buildCanonicalBodyJson(
            payload: BackupPayload,
            numberLexemes: Map<String, String> = emptyMap()
        ): String {
            fun numToken(path: String, fallback: Any): String = numberLexemes[path] ?: fallback.toString()
            return buildString {
                append("{")
                append("\"settings\":{")
                append("\"selectedTheme\":").append(quoteJson(payload.settings.selectedTheme.name)).append(",")
                append("\"themeMode\":").append(quoteJson(payload.settings.themeMode.name)).append(",")
                append("\"playbackSpeed\":").append(numToken("settings.playbackSpeed", payload.settings.playbackSpeed)).append(",")
                append("\"resizeMode\":").append(numToken("settings.resizeMode", payload.settings.resizeMode)).append(",")
                append("\"playbackMode\":").append(quoteJson(payload.settings.playbackMode.name)).append(",")
                append("\"resumeMode\":").append(quoteJson(payload.settings.resumeMode.name))
                if (payload.schemaVersion >= 3 || payload.settings.seekIntervalSeconds != PlaybackPreferencesRepository.DEFAULT_SEEK_INTERVAL_SECONDS) {
                    append(",\"seekIntervalSeconds\":").append(numToken("settings.seekIntervalSeconds", payload.settings.seekIntervalSeconds))
                }
                append("},")
                append("\"subtitles\":{")
                append("\"primaryFontSizeSp\":").append(numToken("subtitles.primaryFontSizeSp", payload.subtitles.primaryFontSizeSp)).append(",")
                append("\"secondaryFontSizeSp\":").append(numToken("subtitles.secondaryFontSizeSp", payload.subtitles.secondaryFontSizeSp))
                append("},")
                append("\"vaultSecurity\":{")
                append("\"autoLockTimeout\":").append(quoteJson(payload.vaultSecurity.autoLockTimeout.name))
                append("},")
                append("\"playlists\":[")
                payload.playlists.forEachIndexed { pIdx, pl ->
                    if (pIdx > 0) append(",")
                    append("{")
                    append("\"title\":").append(quoteJson(pl.title)).append(",")
                    append("\"createdAt\":").append(numToken("playlists.$pIdx.createdAt", pl.createdAt)).append(",")
                    append("\"updatedAt\":").append(numToken("playlists.$pIdx.updatedAt", pl.updatedAt)).append(",")
                    append("\"items\":[")
                    pl.items.forEachIndexed { iIdx, item ->
                        if (iIdx > 0) append(",")
                        append("{")
                        append("\"videoId\":").append(numToken("playlists.$pIdx.items.$iIdx.videoId", item.videoId)).append(",")
                        append("\"videoTitle\":").append(quoteJson(item.videoTitle)).append(",")
                        append("\"durationMs\":").append(numToken("playlists.$pIdx.items.$iIdx.durationMs", item.durationMs)).append(",")
                        append("\"sizeBytes\":").append(numToken("playlists.$pIdx.items.$iIdx.sizeBytes", item.sizeBytes)).append(",")
                        append("\"dateAddedSec\":").append(numToken("playlists.$pIdx.items.$iIdx.dateAddedSec", item.dateAddedSec)).append(",")
                        append("\"contentUri\":").append(quoteJson(item.contentUri)).append(",")
                        append("\"isAvailable\":").append(item.isAvailable)
                        append("}")
                    }
                    append("]")
                    append("}")
                }
                append("]")
                if (payload.schemaVersion >= 2 || payload.resumePositions.isNotEmpty()) {
                    append(",\"resumePositions\":{")
                    val sortedEntries = payload.resumePositions.entries.sortedBy { it.key }
                    sortedEntries.forEachIndexed { idx, (key, value) ->
                        if (idx > 0) append(",")
                        append(quoteJson(key)).append(":").append(numToken("resumePositions.$key", value))
                    }
                    append("}")
                }
                if (payload.localSubtitles.isNotEmpty()) {
                    append(",\"localSubtitles\":[")
                    payload.localSubtitles.forEachIndexed { sIdx, track ->
                        if (sIdx > 0) append(",")
                        append("{")
                        append("\"videoKeyHash\":").append(quoteJson(track.videoKeyHash)).append(",")
                        append("\"trackId\":").append(quoteJson(track.trackId)).append(",")
                        append("\"trackName\":").append(quoteJson(track.trackName)).append(",")
                        append("\"language\":").append(quoteJson(track.language ?: "")).append(",")
                        append("\"format\":").append(quoteJson(track.format.name)).append(",")
                        append("\"content\":").append(quoteJson(track.content)).append(",")
                        append("\"updatedAtMs\":").append(numToken("localSubtitles.$sIdx.updatedAtMs", track.updatedAtMs))
                        append("}")
                    }
                    append("]")
                }
                append("}")
            }
        }

        private fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun quoteJson(value: String): String {
            return buildString(value.length + 2) {
                append('"')
                for (ch in value) {
                    when (ch) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (ch.code < 0x20) {
                            append("\\u%04x".format(ch.code))
                        } else {
                            append(ch)
                        }
                    }
                }
                append('"')
            }
        }
    }
}

private class MiniJsonParser(private val src: String) {
    private var pos = 0

    fun parseValue(): Any? {
        skipWs()
        if (pos >= src.length) throw IllegalArgumentException("Unexpected EOF")
        return when (src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> expectLiteral("true", true)
            'f' -> expectLiteral("false", false)
            'n' -> expectLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Unexpected char at $pos")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        pos++ // '{'
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return map
        }
        while (true) {
            skipWs()
            if (pos >= src.length || src[pos] != '"') throw IllegalArgumentException("Expected string key")
            val key = parseString()
            skipWs()
            if (pos >= src.length || src[pos] != ':') throw IllegalArgumentException("Expected ':'")
            pos++
            val value = parseValue()
            map[key] = value
            skipWs()
            if (pos >= src.length) throw IllegalArgumentException("Unterminated object")
            when (src[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return map
                }
                else -> throw IllegalArgumentException("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        pos++ // '['
        val list = ArrayList<Any?>()
        skipWs()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return list
        }
        while (true) {
            list.add(parseValue())
            skipWs()
            if (pos >= src.length) throw IllegalArgumentException("Unterminated array")
            when (src[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> throw IllegalArgumentException("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        pos++ // '"'
        val sb = StringBuilder()
        while (pos < src.length) {
            val ch = src[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) throw IllegalArgumentException("Bad escape")
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) throw IllegalArgumentException("Bad unicode")
                            val code = src.substring(pos, pos + 4).toInt(16)
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Unknown escape $esc")
                    }
                }
                else -> sb.append(ch)
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun parseNumber(): JsonRawNumber {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && src[pos] in '0'..'9') pos++
        var isFloat = false
        if (pos < src.length && src[pos] == '.') {
            isFloat = true
            pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        val token = src.substring(start, pos)
        val parsed: Number = if (isFloat) token.toDouble() else token.toLong()
        return JsonRawNumber(parsed, token)
    }

    private fun <T> expectLiteral(lit: String, result: T): T {
        if (!src.startsWith(lit, pos)) throw IllegalArgumentException("Expected $lit")
        pos += lit.length
        return result
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }
}

private data class JsonRawNumber(
    val number: Number,
    val rawLexeme: String
)
