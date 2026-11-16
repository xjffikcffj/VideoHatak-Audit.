package com.videohatak.player.core.subtitle.parser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

sealed class SubtitleParseResult {
    data class Success(
        val cues: List<SubtitleCue>,
        val format: SubtitleFormat,
        val detectedEncoding: String,
        val resolvedFileName: String
    ) : SubtitleParseResult()

    data class Error(val message: String) : SubtitleParseResult()
}

object SubtitleParser {

    const val MAX_SUBTITLE_FILE_BYTES = 10L * 1024L * 1024L // 10 MB safety cap against OOM

    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    private val SSA_STYLE_REGEX = Regex("\\{[^}]*\\}")
    private val EXTRA_SPACES_REGEX = Regex("[ \\t]{2,}")

    fun detectFormat(fileName: String, content: String? = null): SubtitleFormat {
        val lowerName = fileName.lowercase(Locale.US)
        if (lowerName.endsWith(".vtt") || lowerName.endsWith(".webvtt")) {
            return SubtitleFormat.WEBVTT
        }
        if (lowerName.endsWith(".ass")) {
            return SubtitleFormat.ASS
        }
        if (lowerName.endsWith(".ssa")) {
            return SubtitleFormat.SSA
        }
        if (lowerName.endsWith(".srt")) {
            return SubtitleFormat.SRT
        }
        if (!content.isNullOrBlank()) {
            val trimmed = content.removePrefix("\uFEFF").trimStart()
            if (trimmed.startsWith("WEBVTT", ignoreCase = true)) {
                return SubtitleFormat.WEBVTT
            }
            if (trimmed.contains("[Script Info]", ignoreCase = true) ||
                trimmed.contains("Dialogue:", ignoreCase = true)
            ) {
                return if (trimmed.contains("ScriptType: v4.00+", ignoreCase = true)) {
                    SubtitleFormat.ASS
                } else {
                    SubtitleFormat.SSA
                }
            }
        }
        return SubtitleFormat.SRT
    }

    fun parse(content: String, format: SubtitleFormat): List<SubtitleCue> {
        val cleanContent = content.removePrefix("\uFEFF") // Remove UTF-8 BOM
        val lines = cleanContent.lines()
        val parsed = when (format) {
            SubtitleFormat.WEBVTT -> parseVttLines(lines)
            SubtitleFormat.SRT -> parseSrtLines(lines)
            SubtitleFormat.ASS, SubtitleFormat.SSA -> parseAssLines(lines)
        }
        return parsed.sortedBy { it.startTimeMs }
    }

    suspend fun parse(context: Context, uri: Uri): List<SubtitleCue> {
        return when (val result = parseSafely(context, uri)) {
            is SubtitleParseResult.Success -> result.cues
            is SubtitleParseResult.Error -> emptyList()
        }
    }

    suspend fun parseSafely(
        context: Context,
        uri: Uri,
        fallbackName: String = "ملف ترجمة"
    ): SubtitleParseResult = withContext(Dispatchers.IO) {
        try {
            val (resolvedName, reportedSize) = queryDisplayNameAndSize(context, uri, fallbackName)
            if (reportedSize > MAX_SUBTITLE_FILE_BYTES) {
                return@withContext SubtitleParseResult.Error(
                    "حجم الملف كبير جدًا (يتجاوز 10 ميجابايت). يرجى اختيار ملف ترجمة نصي (.srt أو .vtt)."
                )
            }

            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                readBoundedBytes(input, MAX_SUBTITLE_FILE_BYTES)
            } ?: return@withContext SubtitleParseResult.Error(
                "تعذر فتح ملف الترجمة المحدد أو انتهت صلاحية الوصول إليه."
            )

            if (bytes.isEmpty()) {
                return@withContext SubtitleParseResult.Error("ملف الترجمة المختار فارغ.")
            }

            if (looksLikeBinaryNonSubtitle(bytes)) {
                return@withContext SubtitleParseResult.Error(
                    "الملف المختار ليس ملف ترجمة نصي صالحًا (يرجى اختيار .srt أو .vtt)."
                )
            }

            val (decodedText, encodingName) = decodeBytesSmartly(bytes)
            val format = detectFormat(resolvedName, decodedText)
            val cues = parse(decodedText, format)

            if (cues.isEmpty()) {
                return@withContext SubtitleParseResult.Error(
                    "لم يتم العثور على جمل أو توقيتات ترجمة صالحة داخل \"$resolvedName\"."
                )
            }

            SubtitleParseResult.Success(
                cues = cues,
                format = format,
                detectedEncoding = encodingName,
                resolvedFileName = resolvedName
            )
        } catch (oom: OutOfMemoryError) {
            SubtitleParseResult.Error("الملف أكبر من الذاكرة المتاحة للقراءة.")
        } catch (sec: SecurityException) {
            SubtitleParseResult.Error("لا توجد صلاحية لقراءة ملف الترجمة من هذا المزوّد.")
        } catch (e: FileTooLargeException) {
            SubtitleParseResult.Error("حجم الملف يتجاوز الحد الأقصى المسموح (10 ميجابايت).")
        } catch (e: Exception) {
            SubtitleParseResult.Error(
                "تعذر قراءة ملف الترجمة: ${e.localizedMessage ?: "خطأ غير متوقع"}"
            )
        }
    }

    fun decodeBytesSmartly(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8 (BOM)"
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        }

        // Strict UTF-8 check first
        val strictUtf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (strictUtf8 != null) {
            return strictUtf8 to "UTF-8"
        }

        // Common Arabic ANSI legacy encoding fallback (Windows-1256)
        val win1256 = runCatching {
            String(bytes, Charset.forName("windows-1256"))
        }.getOrNull()
        if (win1256 != null) {
            return win1256 to "Windows-1256"
        }

        return String(bytes, Charsets.UTF_8) to "UTF-8"
    }

    fun export(cues: List<SubtitleCue>, format: SubtitleFormat): String {
        return when (format) {
            SubtitleFormat.WEBVTT -> exportVtt(cues)
            SubtitleFormat.SRT, SubtitleFormat.ASS, SubtitleFormat.SSA -> exportSrt(cues)
        }
    }

    fun exportSrt(cues: List<SubtitleCue>): String {
        val builder = StringBuilder()
        cues.forEachIndexed { idx, cue ->
            if (idx > 0) builder.append("\n")
            val number = if (cue.index > 0) cue.index else idx + 1
            builder.append(number).append("\n")
            builder.append(formatTimestamp(cue.startTimeMs, ','))
                .append(" --> ")
                .append(formatTimestamp(cue.endTimeMs.coerceAtLeast(cue.startTimeMs), ','))
                .append("\n")
            builder.append(cue.text.trim()).append("\n")
        }
        return builder.toString()
    }

    fun exportVtt(cues: List<SubtitleCue>): String {
        val builder = StringBuilder("WEBVTT\n\n")
        cues.forEachIndexed { idx, cue ->
            if (idx > 0) builder.append("\n")
            builder.append(formatTimestamp(cue.startTimeMs, '.'))
                .append(" --> ")
                .append(formatTimestamp(cue.endTimeMs.coerceAtLeast(cue.startTimeMs), '.'))
                .append("\n")
            builder.append(cue.text.trim()).append("\n")
        }
        return builder.toString()
    }

    fun findActiveCues(cues: List<SubtitleCue>, positionMs: Long): List<SubtitleCue> {
        return cues.filter { positionMs in it.startTimeMs..it.endTimeMs }
    }

    private fun formatTimestamp(ms: Long, decimalSeparator: Char): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d%c%03d",
            hours,
            minutes,
            seconds,
            decimalSeparator,
            millis
        )
    }

    private class FileTooLargeException : Exception()

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw FileTooLargeException()
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun looksLikeBinaryNonSubtitle(bytes: ByteArray): Boolean {
        val isUtf16Bom = bytes.size >= 2 && (
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            )
        if (isUtf16Bom) return false

        val sampleLen = minOf(bytes.size, 512)
        var nulCount = 0
        for (i in 0 until sampleLen) {
            if (bytes[i] == 0.toByte()) nulCount++
        }
        return nulCount > 4
    }

    private fun queryDisplayNameAndSize(
        context: Context,
        uri: Uri,
        fallbackName: String
    ): Pair<String, Long> {
        var resolvedName = uri.lastPathSegment?.substringAfterLast('/') ?: fallbackName
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        val queriedName = cursor.getString(nameIdx)
                        if (!queriedName.isNullOrBlank()) resolvedName = queriedName
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        return resolvedName.ifBlank { fallbackName } to size
    }

    private fun parseSrtLines(lines: List<String>): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var i = 0
        var cueIndex = 1

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }

            var parsedIndex = cueIndex
            if (line.all { it.isDigit() }) {
                parsedIndex = line.toIntOrNull() ?: cueIndex
                i++
                if (i >= lines.size) break
            }

            val timeLine = lines[i].trim()
            if (timeLine.contains("-->")) {
                val parts = timeLine.split("-->")
                if (parts.size == 2) {
                    val startMs = parseTimestamp(parts[0].trim().split(" ")[0])
                    val endMs = parseTimestamp(parts[1].trim().split(" ")[0])

                    i++
                    val textBuilder = StringBuilder()
                    while (i < lines.size && lines[i].trim().isNotEmpty()) {
                        val cleaned = sanitizeText(lines[i].trim())
                        if (cleaned.isNotEmpty()) {
                            if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                            textBuilder.append(cleaned)
                        }
                        i++
                    }

                    if (startMs >= 0 && endMs >= startMs && textBuilder.isNotEmpty()) {
                        cues.add(
                            SubtitleCue(
                                index = parsedIndex,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                text = textBuilder.toString()
                            )
                        )
                        cueIndex++
                    }
                }
            }
            i++
        }
        return cues
    }

    private fun parseVttLines(lines: List<String>): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var i = 0
        var cueIndex = 1

        while (
            i < lines.size &&
            (lines[i].startsWith("WEBVTT", ignoreCase = true) ||
                lines[i].isBlank() ||
                lines[i].startsWith("NOTE") ||
                lines[i].startsWith("STYLE") ||
                lines[i].startsWith("REGION"))
        ) {
            if (lines[i].startsWith("STYLE") || lines[i].startsWith("NOTE") || lines[i].startsWith("REGION")) {
                while (i < lines.size && lines[i].isNotBlank()) {
                    i++
                }
            }
            i++
        }

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("NOTE")) {
                i++
                continue
            }

            var parsedIndex = cueIndex
            var currentLine = line

            if (!currentLine.contains("-->")) {
                parsedIndex = currentLine.toIntOrNull() ?: cueIndex
                i++
                if (i >= lines.size) break
                currentLine = lines[i].trim()
            }

            if (currentLine.contains("-->")) {
                val parts = currentLine.split("-->")
                if (parts.size == 2) {
                    val startMs = parseTimestamp(parts[0].trim().split(" ")[0])
                    val endMs = parseTimestamp(parts[1].trim().split(" ")[0])

                    i++
                    val textBuilder = StringBuilder()
                    while (i < lines.size && lines[i].trim().isNotEmpty() && !lines[i].contains("-->")) {
                        val cleaned = sanitizeText(lines[i].trim())
                        if (cleaned.isNotEmpty()) {
                            if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                            textBuilder.append(cleaned)
                        }
                        i++
                    }

                    if (startMs >= 0 && endMs >= startMs && textBuilder.isNotEmpty()) {
                        cues.add(
                            SubtitleCue(
                                index = parsedIndex,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                text = textBuilder.toString()
                            )
                        )
                        cueIndex++
                    }
                }
            }
            i++
        }
        return cues
    }

    private fun parseAssLines(lines: List<String>): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var inEvents = false
        var startCol = 1
        var endCol = 2
        var textCol = 9
        var cueIndex = 1

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";")) continue

            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }

            if (line.startsWith("Format:", ignoreCase = true) && inEvents) {
                val cols = line.substringAfter(":").split(",").map { it.trim().lowercase(Locale.US) }
                cols.indexOf("start").takeIf { it >= 0 }?.let { startCol = it }
                cols.indexOf("end").takeIf { it >= 0 }?.let { endCol = it }
                cols.indexOf("text").takeIf { it >= 0 }?.let { textCol = it }
                continue
            }

            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = line.substringAfter(":").trimStart()
                val splitLimit = maxOf(startCol, endCol, textCol) + 1
                val parts = payload.split(",", limit = splitLimit)
                if (parts.size > maxOf(startCol, endCol, textCol)) {
                    val startMs = parseAssTimestamp(parts[startCol].trim())
                    val endMs = parseAssTimestamp(parts[endCol].trim())
                    val rawText = parts[textCol]
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace("\\h", " ")
                    val cleanedLines = rawText.lines()
                        .map { sanitizeText(it) }
                        .filter { it.isNotEmpty() }
                    val cleanText = cleanedLines.joinToString("\n")

                    if (startMs >= 0 && endMs >= startMs && cleanText.isNotBlank()) {
                        cues.add(
                            SubtitleCue(
                                index = cueIndex++,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                text = cleanText
                            )
                        )
                    }
                }
            }
        }
        return cues
    }

    private fun parseAssTimestamp(raw: String): Long {
        return try {
            val parts = raw.trim().replace(',', '.').split(":")
            if (parts.size != 3) return -1L
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val secParts = parts[2].split(".")
            val seconds = secParts[0].toLong()
            val fractionRaw = if (secParts.size > 1) secParts[1] else "0"
            val millis = when (fractionRaw.length) {
                0 -> 0L
                1 -> fractionRaw.toLong() * 100L
                2 -> fractionRaw.toLong() * 10L
                else -> fractionRaw.take(3).toLong()
            }
            (hours * 3600L + minutes * 60L + seconds) * 1000L + millis
        } catch (_: Exception) {
            -1L
        }
    }

    private fun sanitizeText(raw: String): String {
        return raw
            .replace(HTML_TAG_REGEX, "")
            .replace(SSA_STYLE_REGEX, "")
            .replace(EXTRA_SPACES_REGEX, " ")
            .trim()
    }

    private fun parseTimestamp(raw: String): Long {
        return try {
            val normalized = raw.replace(',', '.')
            val parts = normalized.split(":")
            when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secParts = parts[2].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val secParts = parts[1].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    (minutes * 60 + seconds) * 1000 + millis
                }
                else -> -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
