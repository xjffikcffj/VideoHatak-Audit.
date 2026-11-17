package com.videohatak.player.core.subtitle.repository

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.subtitle.parser.SubtitleParser
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Persists imported, locally edited, and AI-translated subtitles inside app-private storage
 * indexed per video URI without touching or altering original external subtitle files.
 *
 * Vault videos (`vault://...`) are strictly isolated via `.vault_private`, encrypted at rest
 * when [cryptoManager] is provided, and excluded from backup/restore operations.
 */
class LocalSubtitleStore(
    private val subtitlesRootDir: File,
    private val cryptoManager: VaultCryptoManager? = null
) {
    constructor(context: Context) : this(
        subtitlesRootDir = File(context.applicationContext.filesDir, "subtitles"),
        cryptoManager = VaultCryptoManager()
    )

    companion object {
        private const val VAULT_MARKER_FILE = ".vault_private"
        private const val PLAIN_INDEX_FILE = "index.json"
        private const val ENCRYPTED_INDEX_FILE = "index.vsub"
        private const val ENCRYPTED_SUB_EXT = "vsub"

        fun isVaultVideoUri(videoUri: String): Boolean {
            val trimmed = videoUri.trim()
            if (trimmed.isEmpty()) return false
            return trimmed.startsWith("vault://", ignoreCase = true)
        }
    }

    private val mutex = Mutex()

    private fun videoDir(videoUri: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(videoUri.toByteArray(Charsets.UTF_8))
        val hash = digest.take(16).joinToString("") { "%02x".format(it) }
        return File(subtitlesRootDir, hash)
    }

    private fun isVaultDirectory(dir: File): Boolean {
        return File(dir, VAULT_MARKER_FILE).exists() || File(dir, ENCRYPTED_INDEX_FILE).exists()
    }

    private fun ensureVaultMarker(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        val marker = File(dir, VAULT_MARKER_FILE)
        if (!marker.exists()) {
            marker.writeText("vault", Charsets.UTF_8)
        }
    }

    private fun encodeText(plaintext: String, encryptForVault: Boolean): String {
        val mgr = if (encryptForVault) cryptoManager else null
        return if (mgr != null) mgr.encryptMetadata(plaintext) else plaintext
    }

    private fun decodeText(stored: String, isVault: Boolean): String? {
        if (!isVault) return stored
        val mgr = cryptoManager
        return if (mgr != null && stored.startsWith("VHTKMETA1:")) {
            runCatching { mgr.decryptMetadata(stored) }.getOrNull()
        } else if (stored.startsWith("VHTKMETA1:") && mgr == null) {
            null
        } else {
            stored
        }
    }

    suspend fun loadTracksForVideo(videoUri: String): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        if (videoUri.isBlank()) return@withContext emptyList()
        mutex.withLock {
            val dir = videoDir(videoUri)
            val isVault = isVaultVideoUri(videoUri) || isVaultDirectory(dir)
            val entries = readIndex(dir, isVault)
            val result = mutableListOf<SubtitleTrack>()
            for (entry in entries) {
                val file = File(dir, entry.fileName)
                if (!file.exists()) continue
                val rawContent = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                val content = decodeText(rawContent, isVault) ?: continue
                val format = entry.format
                val cues = SubtitleParser.parse(content, format)
                if (cues.isEmpty()) continue
                result.add(
                    SubtitleTrack(
                        id = entry.id,
                        name = entry.name,
                        uri = Uri.fromFile(file),
                        language = entry.language,
                        cues = cues,
                        isExternal = true
                    )
                )
            }
            result
        }
    }

    suspend fun saveTrackForVideo(
        videoUri: String,
        trackName: String,
        language: String? = null,
        cues: List<SubtitleCue>,
        format: SubtitleFormat = SubtitleFormat.SRT,
        existingTrackId: String? = null
    ): SubtitleTrack = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = videoDir(videoUri)
            if (!dir.exists()) dir.mkdirs()

            val isVault = isVaultVideoUri(videoUri) || isVaultDirectory(dir)
            if (isVault) {
                ensureVaultMarker(dir)
            }

            val targetFormat = if (format == SubtitleFormat.WEBVTT) SubtitleFormat.WEBVTT else SubtitleFormat.SRT
            val ext = when {
                isVault && cryptoManager != null -> ENCRYPTED_SUB_EXT
                targetFormat == SubtitleFormat.WEBVTT -> "vtt"
                else -> "srt"
            }
            val trackId = existingTrackId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val fileName = "$trackId.$ext"
            val outFile = File(dir, fileName)
            val tmpFile = File(dir, "$fileName.tmp")

            val serialized = SubtitleParser.export(cues, targetFormat)
            val payloadToWrite = encodeText(serialized, isVault)
            tmpFile.writeText(payloadToWrite, Charsets.UTF_8)
            if (!tmpFile.renameTo(outFile)) {
                outFile.writeText(payloadToWrite, Charsets.UTF_8)
                tmpFile.delete()
            }

            val currentEntries = readIndex(dir, isVault).filterNot { it.id == trackId }.toMutableList()
            currentEntries.add(
                StoredTrackEntry(
                    id = trackId,
                    name = trackName.ifBlank { "ترجمة محفوظة" },
                    language = language,
                    format = targetFormat,
                    fileName = fileName,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            writeIndex(dir, currentEntries, isVault)

            SubtitleTrack(
                id = trackId,
                name = trackName.ifBlank { "ترجمة محفوظة" },
                uri = Uri.fromFile(outFile),
                language = language,
                cues = cues,
                isExternal = true
            )
        }
    }

    suspend fun updateTrackCues(
        videoUri: String,
        trackId: String,
        updatedCues: List<SubtitleCue>
    ): SubtitleTrack? = withContext(Dispatchers.IO) {
        if (videoUri.isBlank() || trackId.isBlank()) return@withContext null
        mutex.withLock {
            val dir = videoDir(videoUri)
            val isVault = isVaultVideoUri(videoUri) || isVaultDirectory(dir)
            val entries = readIndex(dir, isVault)
            val target = entries.firstOrNull { it.id == trackId } ?: return@withContext null
            val outFile = File(dir, target.fileName)
            val tmpFile = File(dir, "${target.fileName}.tmp")
            val serialized = SubtitleParser.export(updatedCues, target.format)
            val payloadToWrite = encodeText(serialized, isVault)
            tmpFile.writeText(payloadToWrite, Charsets.UTF_8)
            if (!tmpFile.renameTo(outFile)) {
                outFile.writeText(payloadToWrite, Charsets.UTF_8)
                tmpFile.delete()
            }
            SubtitleTrack(
                id = target.id,
                name = target.name,
                uri = Uri.fromFile(outFile),
                language = target.language,
                cues = updatedCues,
                isExternal = true
            )
        }
    }

    suspend fun removeTrackForVideo(videoUri: String, trackId: String) = withContext(Dispatchers.IO) {
        if (videoUri.isBlank() || trackId.isBlank()) return@withContext
        mutex.withLock {
            val dir = videoDir(videoUri)
            if (!dir.exists()) return@withContext
            val isVault = isVaultVideoUri(videoUri) || isVaultDirectory(dir)
            val entries = readIndex(dir, isVault)
            val removed = entries.filter { it.id == trackId }
            removed.forEach { File(dir, it.fileName).delete() }
            writeIndex(dir, entries.filterNot { it.id == trackId }, isVault)
        }
    }

    data class BackupStoredSubtitleTrack(
        val videoKeyHash: String,
        val trackId: String,
        val trackName: String,
        val language: String?,
        val format: SubtitleFormat,
        val content: String,
        val updatedAtMs: Long
    )

    suspend fun exportAllStoredTracksForBackup(): List<BackupStoredSubtitleTrack> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!subtitlesRootDir.exists() || !subtitlesRootDir.isDirectory) return@withLock emptyList()
            val collected = mutableListOf<BackupStoredSubtitleTrack>()
            subtitlesRootDir.listFiles()?.forEach { dir ->
                if (!dir.isDirectory || dir.name.endsWith(".tmp")) return@forEach
                // Strictly exclude Vault private subtitle directories from public backups
                if (isVaultDirectory(dir)) return@forEach

                val entries = readIndex(dir, isVault = false)
                for (entry in entries) {
                    if (entry.fileName.endsWith(".$ENCRYPTED_SUB_EXT", ignoreCase = true)) continue
                    val file = File(dir, entry.fileName)
                    if (!file.exists() || !file.isFile || file.length() > 2L * 1024L * 1024L) continue
                    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                    if (content.isBlank() || content.startsWith("VHTKMETA1:")) continue
                    collected.add(
                        BackupStoredSubtitleTrack(
                            videoKeyHash = dir.name,
                            trackId = entry.id,
                            trackName = entry.name,
                            language = entry.language,
                            format = entry.format,
                            content = content,
                            updatedAtMs = entry.updatedAtMs
                        )
                    )
                }
            }
            collected.sortedWith(compareBy({ it.videoKeyHash }, { it.trackId }))
        }
    }

    internal var beforeCommitWriteHook: (() -> Unit)? = null

    suspend fun replaceAllStoredTracksFromBackup(tracks: List<BackupStoredSubtitleTrack>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safeHexRegex = Regex("^[a-fA-F0-9]{16,64}$")
            val safeIdRegex = Regex("^[a-zA-Z0-9._-]{1,80}$")

            if (!subtitlesRootDir.exists()) {
                subtitlesRootDir.mkdirs()
            }

            // Identify Vault directories to preserve unconditionally during public backup restore
            val vaultDirNames = subtitlesRootDir.listFiles()
                ?.filter { it.isDirectory && !it.name.endsWith(".tmp") && isVaultDirectory(it) }
                ?.map { it.name }
                ?.toSet()
                .orEmpty()

            val validTracks = tracks.filter { track ->
                track.videoKeyHash !in vaultDirNames &&
                    safeHexRegex.matches(track.videoKeyHash) &&
                    safeIdRegex.matches(track.trackId) &&
                    track.content.isNotBlank() &&
                    !track.content.startsWith("VHTKMETA1:")
            }

            // Snapshot only PUBLIC subtitle directories so mid-restore failure rolls back cleanly
            // while Vault directories stay untouched on disk.
            val snapshot = LinkedHashMap<String, List<Pair<String, ByteArray>>>()
            subtitlesRootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && !dir.name.endsWith(".tmp") && !isVaultDirectory(dir)) {
                    val fileBytes = dir.listFiles()
                        ?.filter { it.isFile }
                        ?.map { it.name to it.readBytes() }
                        .orEmpty()
                    snapshot[dir.name] = fileBytes
                }
            }

            try {
                // Wipe only public stored subtitle directories before restoring payload tracks
                subtitlesRootDir.listFiles()?.forEach { child ->
                    if (child.isDirectory && !child.name.endsWith(".tmp") && !isVaultDirectory(child)) {
                        if (!child.deleteRecursively()) {
                            throw IllegalStateException("تعذر مسح مجلد الترجمات السابق: ${child.name}")
                        }
                    }
                }

                beforeCommitWriteHook?.invoke()

                validTracks.groupBy { it.videoKeyHash }.forEach { (hash, group) ->
                    val dir = File(subtitlesRootDir, hash)
                    if (isVaultDirectory(dir)) return@forEach
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw IllegalStateException("تعذر إنشاء مجلد استعادة الترجمات: $hash")
                    }
                    val entries = mutableListOf<StoredTrackEntry>()
                    group.forEach { track ->
                        val targetFormat = if (track.format == SubtitleFormat.WEBVTT) SubtitleFormat.WEBVTT else SubtitleFormat.SRT
                        val ext = if (targetFormat == SubtitleFormat.WEBVTT) "vtt" else "srt"
                        val fileName = "${track.trackId}.$ext"
                        val outFile = File(dir, fileName)
                        val tmpFile = File(dir, "$fileName.tmp")
                        tmpFile.writeText(track.content, Charsets.UTF_8)
                        if (!tmpFile.renameTo(outFile)) {
                            outFile.writeText(track.content, Charsets.UTF_8)
                            tmpFile.delete()
                        }
                        entries.removeAll { it.id == track.trackId }
                        entries.add(
                            StoredTrackEntry(
                                id = track.trackId,
                                name = track.trackName.ifBlank { "ترجمة محفوظة" },
                                language = track.language,
                                format = targetFormat,
                                fileName = fileName,
                                updatedAtMs = track.updatedAtMs
                            )
                        )
                    }
                    writeIndex(dir, entries, isVault = false)
                }
            } catch (t: Throwable) {
                try {
                    subtitlesRootDir.listFiles()?.forEach { child ->
                        if (child.isDirectory && !child.name.endsWith(".tmp") && !isVaultDirectory(child)) {
                            child.deleteRecursively()
                        }
                    }
                    snapshot.forEach { (dirName, files) ->
                        val dir = File(subtitlesRootDir, dirName)
                        dir.mkdirs()
                        files.forEach { (fileName, bytes) ->
                            File(dir, fileName).writeBytes(bytes)
                        }
                    }
                } catch (rollbackErr: Throwable) {
                    t.addSuppressed(rollbackErr)
                }
                throw t
            }
        }
    }

    suspend fun restoreStoredTracksFromBackup(tracks: List<BackupStoredSubtitleTrack>) =
        replaceAllStoredTracksFromBackup(tracks)

    private data class StoredTrackEntry(
        val id: String,
        val name: String,
        val language: String?,
        val format: SubtitleFormat,
        val fileName: String,
        val updatedAtMs: Long
    )

    private fun readIndex(dir: File, isVault: Boolean): List<StoredTrackEntry> {
        val indexFile = if (isVault && File(dir, ENCRYPTED_INDEX_FILE).exists()) {
            File(dir, ENCRYPTED_INDEX_FILE)
        } else {
            File(dir, PLAIN_INDEX_FILE)
        }
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val raw = indexFile.readText(Charsets.UTF_8)
            val decoded = decodeText(raw, isVault) ?: return@runCatching emptyList()
            val arr = JSONArray(decoded)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val formatName = obj.optString("format", SubtitleFormat.SRT.name)
                    val format = SubtitleFormat.entries.firstOrNull { it.name == formatName } ?: SubtitleFormat.SRT
                    add(
                        StoredTrackEntry(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            language = obj.optString("language").takeIf { it.isNotBlank() },
                            format = format,
                            fileName = obj.getString("fileName"),
                            updatedAtMs = obj.optLong("updatedAtMs", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(dir: File, entries: List<StoredTrackEntry>, isVault: Boolean) {
        if (!dir.exists()) dir.mkdirs()
        val useEncryptedIndex = isVault && cryptoManager != null
        val indexFile = File(dir, if (useEncryptedIndex) ENCRYPTED_INDEX_FILE else PLAIN_INDEX_FILE)
        val tmpFile = File(dir, "${indexFile.name}.tmp")
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("name", entry.name)
                    put("language", entry.language ?: "")
                    put("format", entry.format.name)
                    put("fileName", entry.fileName)
                    put("updatedAtMs", entry.updatedAtMs)
                }
            )
        }
        val payloadToWrite = encodeText(arr.toString(), useEncryptedIndex)
        tmpFile.writeText(payloadToWrite, Charsets.UTF_8)
        if (!tmpFile.renameTo(indexFile)) {
            indexFile.writeText(payloadToWrite, Charsets.UTF_8)
            tmpFile.delete()
        }
        if (useEncryptedIndex) {
            File(dir, PLAIN_INDEX_FILE).delete()
        }
    }
}

