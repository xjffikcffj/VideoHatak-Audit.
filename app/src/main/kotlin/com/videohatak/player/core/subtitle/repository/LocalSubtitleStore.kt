package com.videohatak.player.core.subtitle.repository

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.subtitle.parser.SubtitleParser
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
 */
class LocalSubtitleStore(
    private val subtitlesRootDir: File
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "subtitles"))

    private val mutex = Mutex()

    private fun videoDir(videoUri: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(videoUri.toByteArray(Charsets.UTF_8))
        val hash = digest.take(16).joinToString("") { "%02x".format(it) }
        return File(subtitlesRootDir, hash)
    }

    suspend fun loadTracksForVideo(videoUri: String): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        if (videoUri.isBlank()) return@withContext emptyList()
        mutex.withLock {
            val dir = videoDir(videoUri)
            val entries = readIndex(dir)
            val result = mutableListOf<SubtitleTrack>()
            for (entry in entries) {
                val file = File(dir, entry.fileName)
                if (!file.exists()) continue
                val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
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

            val targetFormat = if (format == SubtitleFormat.WEBVTT) SubtitleFormat.WEBVTT else SubtitleFormat.SRT
            val ext = if (targetFormat == SubtitleFormat.WEBVTT) "vtt" else "srt"
            val trackId = existingTrackId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val fileName = "${trackId}.$ext"
            val outFile = File(dir, fileName)
            val tmpFile = File(dir, "$fileName.tmp")

            val serialized = SubtitleParser.export(cues, targetFormat)
            tmpFile.writeText(serialized, Charsets.UTF_8)
            if (!tmpFile.renameTo(outFile)) {
                outFile.writeText(serialized, Charsets.UTF_8)
                tmpFile.delete()
            }

            val currentEntries = readIndex(dir).filterNot { it.id == trackId }.toMutableList()
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
            writeIndex(dir, currentEntries)

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
            val entries = readIndex(dir)
            val target = entries.firstOrNull { it.id == trackId } ?: return@withContext null
            val outFile = File(dir, target.fileName)
            val tmpFile = File(dir, "${target.fileName}.tmp")
            val serialized = SubtitleParser.export(updatedCues, target.format)
            tmpFile.writeText(serialized, Charsets.UTF_8)
            if (!tmpFile.renameTo(outFile)) {
                outFile.writeText(serialized, Charsets.UTF_8)
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
            val entries = readIndex(dir)
            val removed = entries.filter { it.id == trackId }
            removed.forEach { File(dir, it.fileName).delete() }
            writeIndex(dir, entries.filterNot { it.id == trackId })
        }
    }

    private data class StoredTrackEntry(
        val id: String,
        val name: String,
        val language: String?,
        val format: SubtitleFormat,
        val fileName: String,
        val updatedAtMs: Long
    )

    private fun readIndex(dir: File): List<StoredTrackEntry> {
        val indexFile = File(dir, "index.json")
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
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

    private fun writeIndex(dir: File, entries: List<StoredTrackEntry>) {
        if (!dir.exists()) dir.mkdirs()
        val indexFile = File(dir, "index.json")
        val tmpFile = File(dir, "index.json.tmp")
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
        tmpFile.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmpFile.renameTo(indexFile)) {
            indexFile.writeText(arr.toString(), Charsets.UTF_8)
            tmpFile.delete()
        }
    }
}
