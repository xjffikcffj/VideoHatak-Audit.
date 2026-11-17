package com.videohatak.player.core.subtitle.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import com.videohatak.player.core.subtitle.model.SubtitleTrack
import com.videohatak.player.core.subtitle.parser.SubtitleParser
import com.videohatak.player.core.vault.crypto.VaultCryptoManager
import com.videohatak.player.core.vault.db.VaultDatabaseHelper
import com.videohatak.player.core.vault.model.VaultVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Persists imported, locally edited, and translated subtitles inside app-private storage
 * indexed per video URI without touching or altering original external subtitle files.
 *
 * Security & Migration guarantees:
 * - Vault videos (`vault://...`, Vault DB entries, and Vault storage paths) are isolated via `.vault_private`,
 *   encrypted at rest (`*.vsub` + `index.vsub`), and excluded from public backups.
 * - Legacy plaintext Vault subtitle directories are discovered dynamically and migrated via a crash-resumable
 *   two-phase flow with mandatory read-back + decrypt verification BEFORE deleting legacy plaintext files.
 * - Legacy directories that lack both Vault markers/matches and `.public_verified` are treated cautiously
 *   as unclassified: kept untouched on disk, logged in [lastMigrationReport], and strictly excluded from
 *   public backup/export to guarantee zero Vault leakage.
 */
class LocalSubtitleStore internal constructor(
    private val subtitlesRootDir: File,
    private val cryptoManager: VaultCryptoManager?,
    private val vaultCandidateUrisProvider: (() -> Collection<String>)?
) {
    constructor(
        subtitlesRootDir: File,
        cryptoManager: VaultCryptoManager? = null
    ) : this(
        subtitlesRootDir = subtitlesRootDir,
        cryptoManager = cryptoManager,
        vaultCandidateUrisProvider = null
    )

    constructor(context: Context) : this(
        subtitlesRootDir = File(context.applicationContext.filesDir, "subtitles"),
        cryptoManager = VaultCryptoManager(),
        vaultCandidateUrisProvider = null
    ) {
        val appContext = context.applicationContext
        defaultVaultCandidateProvider = {
            discoverVaultSubtitleCandidateUris(appContext, cryptoManager)
        }
    }

    private var defaultVaultCandidateProvider: (() -> Collection<String>)? = null

    internal var migrationFaultHook: ((stage: String, dir: File) -> Unit)? = null
    internal var beforeCommitWriteHook: (() -> Unit)? = null

    @Volatile
    var lastMigrationReport: LegacyVaultMigrationReport = LegacyVaultMigrationReport()
        private set

    data class LegacyVaultMigrationReport(
        val scannedDirectories: Int = 0,
        val migratedDirectories: List<String> = emptyList(),
        val alreadyEncryptedDirectories: List<String> = emptyList(),
        val publicVerifiedDirectories: List<String> = emptyList(),
        val unclassifiedDirectories: List<String> = emptyList(),
        val failedDirectories: Map<String, String> = emptyMap()
    )

    data class BackupStoredSubtitleTrack(
        val videoKeyHash: String,
        val trackId: String,
        val trackName: String,
        val language: String?,
        val format: SubtitleFormat,
        val content: String,
        val updatedAtMs: Long
    )

    companion object {
        private const val TAG = "LocalSubtitleStore"
        private const val VAULT_MARKER_FILE = ".vault_private"
        private const val PUBLIC_MARKER_FILE = ".public_verified"
        private const val PLAIN_INDEX_FILE = "index.json"
        private const val ENCRYPTED_INDEX_FILE = "index.vsub"
        private const val ENCRYPTED_SUB_EXT = "vsub"

        fun isVaultVideoUri(videoUri: String): Boolean {
            val trimmed = videoUri.trim()
            if (trimmed.isEmpty()) return false
            return trimmed.startsWith("vault://", ignoreCase = true)
        }

        fun computeVideoKeyHash(videoUri: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(videoUri.toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        internal fun buildCandidateUrisForVaultRow(
            row: VaultVideo,
            vaultDir: File,
            cryptoManager: VaultCryptoManager? = null
        ): Set<String> {
            val candidates = linkedSetOf<String>()
            if (row.id > 0L) {
                candidates.add("vault://${row.id}")
                candidates.add(row.id.toString())
            }

            fun addPathForms(rawPath: String) {
                val cleaned = rawPath.trim()
                if (cleaned.isEmpty()) return
                candidates.add(cleaned)
                candidates.add("vault://${cleaned.trimStart('/')}")
                val fileObj = File(cleaned)
                val fileName = fileObj.name
                if (fileName.isNotBlank()) {
                    candidates.add(fileName)
                    candidates.add("vault://$fileName")
                }
                val resolvedFile = if (fileObj.isAbsolute) fileObj else File(vaultDir, fileName)
                candidates.add(resolvedFile.absolutePath)
                candidates.add("file://${resolvedFile.absolutePath}")
                runCatching { candidates.add(Uri.fromFile(resolvedFile).toString()) }
            }

            addPathForms(row.relativeEncryptedPath)

            row.encryptedMetaBase64?.takeIf { it.isNotBlank() }?.let { encMeta ->
                if (cryptoManager != null && encMeta.startsWith("VHTKMETA1:")) {
                    runCatching {
                        val json = JSONObject(cryptoManager.decryptMetadata(encMeta))
                        val innerFile = json.optString("file")
                        if (innerFile.isNotBlank()) {
                            addPathForms(innerFile)
                        }
                    }
                }
            }
            return candidates
        }

        internal fun discoverVaultSubtitleCandidateUris(
            context: Context,
            cryptoManager: VaultCryptoManager? = null
        ): Set<String> {
            val candidates = linkedSetOf<String>()
            val vaultDir = File(context.filesDir, "vault_encrypted")

            runCatching {
                val dbFile = context.getDatabasePath("videohatak_vault.db")
                if (dbFile.exists()) {
                    val helper = VaultDatabaseHelper(context)
                    val rows = try {
                        helper.getAllVideos()
                    } finally {
                        runCatching { helper.close() }
                    }
                    rows.forEach { row ->
                        candidates.addAll(buildCandidateUrisForVaultRow(row, vaultDir, cryptoManager))
                    }
                }
            }

            runCatching {
                if (vaultDir.exists() && vaultDir.isDirectory) {
                    vaultDir.listFiles()?.forEach { file ->
                        if (file.isFile && (file.extension.equals("enc", ignoreCase = true) ||
                                file.name.contains("vault", ignoreCase = true))
                        ) {
                            candidates.add(file.name)
                            candidates.add("vault://${file.name}")
                            candidates.add(file.absolutePath)
                            candidates.add("file://${file.absolutePath}")
                            runCatching { candidates.add(Uri.fromFile(file).toString()) }
                        }
                    }
                }
            }
            return candidates
        }
    }

    private val mutex = Mutex()

    private fun videoDir(videoUri: String): File {
        return File(subtitlesRootDir, computeVideoKeyHash(videoUri))
    }

    private fun resolveVaultCandidateHashesLocked(): Set<String> {
        val provider = vaultCandidateUrisProvider ?: defaultVaultCandidateProvider
        val uris = runCatching { provider?.invoke().orEmpty() }.getOrDefault(emptyList())
        if (uris.isEmpty()) return emptySet()
        return uris.mapNotNull { candidate ->
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) null else computeVideoKeyHash(trimmed)
        }.toSet()
    }

    private fun isVaultDirectory(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        if (File(dir, VAULT_MARKER_FILE).exists() || File(dir, ENCRYPTED_INDEX_FILE).exists()) {
            return true
        }
        return dir.listFiles()?.any { it.isFile && it.extension.equals(ENCRYPTED_SUB_EXT, ignoreCase = true) } == true
    }

    private fun isPublicVerifiedDirectory(dir: File): Boolean {
        return dir.exists() && dir.isDirectory && !isVaultDirectory(dir) && File(dir, PUBLIC_MARKER_FILE).exists()
    }

    private fun hasLegacyPlaintextData(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        if (File(dir, PLAIN_INDEX_FILE).exists()) return true
        return dir.listFiles()?.any {
            it.isFile && (it.extension.equals("srt", ignoreCase = true) || it.extension.equals("vtt", ignoreCase = true))
        } == true
    }

    private fun isVaultDirectoryOrUriLocked(videoUri: String, dir: File, vaultHashes: Set<String>? = null): Boolean {
        if (isVaultVideoUri(videoUri) || isVaultDirectory(dir)) return true
        val hashes = vaultHashes ?: resolveVaultCandidateHashesLocked()
        return dir.name in hashes
    }

    private fun ensureVaultMarker(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        File(dir, PUBLIC_MARKER_FILE).delete()
        val marker = File(dir, VAULT_MARKER_FILE)
        if (!marker.exists()) {
            marker.writeText("vault", Charsets.UTF_8)
        }
    }

    private fun ensurePublicVerifiedMarker(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        if (isVaultDirectory(dir)) return
        val marker = File(dir, PUBLIC_MARKER_FILE)
        if (!marker.exists()) {
            marker.writeText("public", Charsets.UTF_8)
        }
    }

    private fun writeTextDurably(targetFile: File, content: String) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, "${targetFile.name}.tmp")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            runCatching { fos.fd.sync() }
        }
        if (!tmpFile.renameTo(targetFile)) {
            FileOutputStream(targetFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            tmpFile.delete()
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

    suspend fun migrateLegacyVaultSubtitles(): LegacyVaultMigrationReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            migrateLegacyVaultSubtitlesLocked()
        }
    }

    private fun migrateLegacyVaultSubtitlesLocked(): LegacyVaultMigrationReport {
        if (!subtitlesRootDir.exists() || !subtitlesRootDir.isDirectory) {
            return LegacyVaultMigrationReport().also { lastMigrationReport = it }
        }

        val vaultHashes = resolveVaultCandidateHashesLocked()
        val dirs = subtitlesRootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
            .orEmpty()

        val migrated = mutableListOf<String>()
        val alreadyEncrypted = mutableListOf<String>()
        val publicVerified = mutableListOf<String>()
        val unclassified = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()

        for (dir in dirs) {
            val isKnownVault = isVaultDirectory(dir) || (dir.name in vaultHashes)
            if (isKnownVault) {
                if (dir.name in vaultHashes && hasAnySubtitleFiles(dir)) {
                    ensureVaultMarker(dir)
                }
                if (hasLegacyPlaintextData(dir)) {
                    val failureReason = migrateSingleVaultDirectoryLocked(dir)
                    if (failureReason == null) {
                        migrated.add(dir.name)
                    } else {
                        failed[dir.name] = failureReason
                        runCatching {
                            Log.w(TAG, "Vault subtitle migration failed for ${dir.name}: $failureReason")
                        }
                    }
                } else if (File(dir, ENCRYPTED_INDEX_FILE).exists() || File(dir, VAULT_MARKER_FILE).exists()) {
                    alreadyEncrypted.add(dir.name)
                }
            } else if (isPublicVerifiedDirectory(dir)) {
                publicVerified.add(dir.name)
            } else {
                // Legacy directory without Vault match or .public_verified:
                // Never assume public; preserve on disk and exclude from backup export.
                unclassified.add(dir.name)
                runCatching {
                    Log.w(
                        TAG,
                        "Unclassified legacy subtitle directory '${dir.name}' excluded from public backup and preserved untouched."
                    )
                }
            }
        }

        return LegacyVaultMigrationReport(
            scannedDirectories = dirs.size,
            migratedDirectories = migrated,
            alreadyEncryptedDirectories = alreadyEncrypted,
            publicVerifiedDirectories = publicVerified,
            unclassifiedDirectories = unclassified,
            failedDirectories = failed
        ).also { lastMigrationReport = it }
    }

    private fun hasAnySubtitleFiles(dir: File): Boolean {
        return dir.listFiles()?.any { f ->
            f.isFile && (f.name == PLAIN_INDEX_FILE ||
                f.name == ENCRYPTED_INDEX_FILE ||
                f.extension.equals("srt", true) ||
                f.extension.equals("vtt", true) ||
                f.extension.equals(ENCRYPTED_SUB_EXT, true))
        } == true
    }

    /**
     * Migrates a single Vault directory from legacy plaintext (`index.json` + `.srt`/`.vtt`) to
     * encrypted storage (`.vault_private` + `index.vsub` + `*.vsub`).
     *
     * Returns `null` on success, or an error description on failure.
     * NEVER deletes any plaintext file unless read-back + decryption verification passes 100%.
     */
    private fun migrateSingleVaultDirectoryLocked(dir: File): String? {
        val mgr = cryptoManager ?: return "تعذر ترحيل ترجمة الخزنة: مدير التشفير غير متاح"
        if (!dir.exists() || !dir.isDirectory) return null

        // Remove stale incomplete .tmp writes left by interrupted migrations
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp")) {
                file.delete()
            }
        }

        ensureVaultMarker(dir)

        val plainIndexFile = File(dir, PLAIN_INDEX_FILE)
        val encryptedIndexFile = File(dir, ENCRYPTED_INDEX_FILE)

        val legacyEntriesFromJson: List<StoredTrackEntry>? = if (plainIndexFile.exists()) {
            parseIndexEntriesStrict(plainIndexFile, encrypted = false, mgr = mgr)
                ?: return "ملف الفهرس القديم غير صالح ولا يمكن حذفه بأمان"
        } else {
            null
        }

        val existingEncryptedEntries: List<StoredTrackEntry> = if (encryptedIndexFile.exists()) {
            parseIndexEntriesStrict(encryptedIndexFile, encrypted = true, mgr = mgr).orEmpty()
        } else {
            emptyList()
        }

        val expectedPlaintextById = linkedMapOf<String, String>()
        val targetEntriesById = linkedMapOf<String, StoredTrackEntry>()
        val legacyFilesToDeleteAfterVerify = linkedSetOf<File>()

        // 1. Collect tracks declared in legacy index.json
        legacyEntriesFromJson?.forEach { entry ->
            val candidateSourceFiles = listOf(
                File(dir, entry.fileName),
                File(dir, "${entry.id}.srt"),
                File(dir, "${entry.id}.vtt")
            ).distinctBy { it.name }

            val existingPlainFile = candidateSourceFiles.firstOrNull { f ->
                f.exists() && f.isFile && !f.extension.equals(ENCRYPTED_SUB_EXT, true)
            }
            val encryptedFileName = "${entry.id}.$ENCRYPTED_SUB_EXT"
            val existingVsubFile = File(dir, encryptedFileName)

            val expectedPlaintext: String = when {
                existingPlainFile != null -> {
                    val raw = runCatching { existingPlainFile.readText(Charsets.UTF_8) }.getOrNull()
                        ?: return "تعذر قراءة ملف الترجمة القديم: ${existingPlainFile.name}"
                    legacyFilesToDeleteAfterVerify.add(existingPlainFile)
                    if (raw.startsWith("VHTKMETA1:")) {
                        runCatching { mgr.decryptMetadata(raw) }.getOrNull()
                            ?: return "تعذر فك تشفير الملف المرحلي: ${existingPlainFile.name}"
                    } else {
                        raw
                    }
                }
                existingVsubFile.exists() && existingVsubFile.isFile -> {
                    val rawEnc = runCatching { existingVsubFile.readText(Charsets.UTF_8) }.getOrNull()
                        ?: return "تعذر قراءة ملف الترجمة المشفر: $encryptedFileName"
                    if (!rawEnc.startsWith("VHTKMETA1:")) {
                        return "ملف الترجمة المشفر لا يطابق الصيغة المعتمدة: $encryptedFileName"
                    }
                    runCatching { mgr.decryptMetadata(rawEnc) }.getOrNull()
                        ?: return "فشل التحقق من فك تشفير الملف: $encryptedFileName"
                }
                else -> {
                    return "ملف الترجمة المرجعي مفقود أثناء الترحيل: ${entry.fileName}"
                }
            }

            expectedPlaintextById[entry.id] = expectedPlaintext
            targetEntriesById[entry.id] = entry.copy(fileName = encryptedFileName)
        }

        // 2. Include any already-encrypted tracks present in index.vsub (crash-resume consistency)
        existingEncryptedEntries.forEach { encEntry ->
            if (encEntry.id !in targetEntriesById) {
                val encFile = File(dir, encEntry.fileName)
                if (encFile.exists() && encFile.isFile) {
                    val rawEnc = runCatching { encFile.readText(Charsets.UTF_8) }.getOrNull()
                        ?: return "تعذر قراءة ملف الترجمة المشفر السابق: ${encEntry.fileName}"
                    if (!rawEnc.startsWith("VHTKMETA1:")) {
                        return "ملف مشفر غير صالح في فهرس الخزنة: ${encEntry.fileName}"
                    }
                    val decrypted = runCatching { mgr.decryptMetadata(rawEnc) }.getOrNull()
                        ?: return "فشل التحقق من فك تشفير المسار السابق: ${encEntry.fileName}"
                    expectedPlaintextById[encEntry.id] = decrypted
                    targetEntriesById[encEntry.id] = encEntry
                }
            }
        }

        // 3. Collect any leftover plaintext .srt/.vtt files not yet listed above
        val remainingPlainFiles = dir.listFiles()?.filter { file ->
            file.isFile &&
                (file.extension.equals("srt", true) || file.extension.equals("vtt", true)) &&
                file !in legacyFilesToDeleteAfterVerify
        }.orEmpty()

        for (plainFile in remainingPlainFiles) {
            val baseId = plainFile.nameWithoutExtension.ifBlank { UUID.randomUUID().toString() }
            val plainContent = runCatching { plainFile.readText(Charsets.UTF_8) }.getOrNull()
                ?: return "تعذر قراءة ملف الترجمة المتبقي: ${plainFile.name}"
            val decodedContent = if (plainContent.startsWith("VHTKMETA1:")) {
                runCatching { mgr.decryptMetadata(plainContent) }.getOrNull()
                    ?: return "تعذر فك تشفير ملف الترجمة المتبقي: ${plainFile.name}"
            } else {
                plainContent
            }
            val alreadyTracked = targetEntriesById[baseId]
            if (alreadyTracked != null) {
                val trackedText = expectedPlaintextById[baseId]
                if (trackedText != null && trackedText != decodedContent) {
                    // Conflict between leftover plaintext and indexed track; keep safely as separate entry
                    val newId = "${baseId}_legacy"
                    val format = if (plainFile.extension.equals("vtt", true)) SubtitleFormat.WEBVTT else SubtitleFormat.SRT
                    expectedPlaintextById[newId] = decodedContent
                    targetEntriesById[newId] = StoredTrackEntry(
                        id = newId,
                        name = plainFile.nameWithoutExtension.ifBlank { "ترجمة خزنة" },
                        language = null,
                        format = format,
                        fileName = "$newId.$ENCRYPTED_SUB_EXT",
                        updatedAtMs = plainFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                }
                legacyFilesToDeleteAfterVerify.add(plainFile)
            } else {
                val format = if (plainFile.extension.equals("vtt", true)) SubtitleFormat.WEBVTT else SubtitleFormat.SRT
                expectedPlaintextById[baseId] = decodedContent
                targetEntriesById[baseId] = StoredTrackEntry(
                    id = baseId,
                    name = plainFile.nameWithoutExtension.ifBlank { "ترجمة خزنة" },
                    language = null,
                    format = format,
                    fileName = "$baseId.$ENCRYPTED_SUB_EXT",
                    updatedAtMs = plainFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                )
                legacyFilesToDeleteAfterVerify.add(plainFile)
            }
        }

        if (targetEntriesById.isEmpty()) {
            // Empty index.json with no tracks
            if (plainIndexFile.exists()) {
                writeEncryptedIndexWithoutDeletingPlain(dir, emptyList(), mgr)
                val verifiedEmpty = verifyMigratedFilesLocked(dir, emptyMap(), mgr)
                if (!verifiedEmpty) {
                    return "فشل التحقق من فهرس الخزنة المشفر الفارغ"
                }
                plainIndexFile.delete()
            }
            return null
        }

        return try {
            // Phase 1: Encrypt every track to *.vsub (idempotent: skip if valid encrypted copy already matches)
            targetEntriesById.values.forEach { entry ->
                val expectedPlaintext = expectedPlaintextById.getValue(entry.id)
                val targetFile = File(dir, entry.fileName)
                val alreadyMatches = targetFile.exists() && targetFile.isFile && runCatching {
                    val existingRaw = targetFile.readText(Charsets.UTF_8)
                    existingRaw.startsWith("VHTKMETA1:") && mgr.decryptMetadata(existingRaw) == expectedPlaintext
                }.getOrDefault(false)

                if (!alreadyMatches) {
                    val encryptedPayload = mgr.encryptMetadata(expectedPlaintext)
                    writeTextDurably(targetFile, encryptedPayload)
                }
            }
            migrationFaultHook?.invoke("after_track_encrypt", dir)

            // Phase 2: Write encrypted index.vsub WITHOUT deleting index.json yet
            writeEncryptedIndexWithoutDeletingPlain(dir, targetEntriesById.values.toList(), mgr)
            migrationFaultHook?.invoke("after_index_write", dir)

            // Phase 3: Mandatory read-back + decrypt verification of index.vsub AND all *.vsub files
            if (!verifyMigratedFilesLocked(dir, expectedPlaintextById, mgr)) {
                return "فشل التحقق التطابقي للترجمات المشفرة بعد الكتابة؛ تم الإبقاء على الملفات الأصلية دون حذف"
            }
            migrationFaultHook?.invoke("after_verify", dir)

            // Phase 4: Delete legacy plaintext index & subtitle files ONLY after full verification
            if (plainIndexFile.exists()) {
                plainIndexFile.delete()
            }
            legacyFilesToDeleteAfterVerify.forEach { plainFile ->
                if (plainFile.exists() && !plainFile.extension.equals(ENCRYPTED_SUB_EXT, true)) {
                    plainFile.delete()
                }
            }
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".tmp")) {
                    file.delete()
                }
            }
            null
        } catch (t: Throwable) {
            t.message ?: "خطأ غير متوقع أثناء ترحيل ترجمة الخزنة"
        }
    }

    private fun verifyMigratedFilesLocked(
        dir: File,
        expectedPlaintextById: Map<String, String>,
        mgr: VaultCryptoManager
    ): Boolean {
        val encryptedIndexFile = File(dir, ENCRYPTED_INDEX_FILE)
        if (!encryptedIndexFile.exists() || !encryptedIndexFile.isFile) return false
        val rawIndex = runCatching { encryptedIndexFile.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        if (!rawIndex.startsWith("VHTKMETA1:")) return false

        val verifiedEntries = parseIndexEntriesStrict(encryptedIndexFile, encrypted = true, mgr = mgr)
            ?: return false

        if (verifiedEntries.size != expectedPlaintextById.size) return false
        val verifiedById = verifiedEntries.associateBy { it.id }

        for ((trackId, expectedPlaintext) in expectedPlaintextById) {
            val entry = verifiedById[trackId] ?: return false
            if (!entry.fileName.endsWith(".$ENCRYPTED_SUB_EXT", ignoreCase = true)) return false
            val vsubFile = File(dir, entry.fileName)
            if (!vsubFile.exists() || !vsubFile.isFile) return false
            val diskRaw = runCatching { vsubFile.readText(Charsets.UTF_8) }.getOrNull() ?: return false
            if (!diskRaw.startsWith("VHTKMETA1:")) return false
            val decrypted = runCatching { mgr.decryptMetadata(diskRaw) }.getOrNull() ?: return false
            if (decrypted != expectedPlaintext) return false
        }
        return true
    }

    suspend fun loadTracksForVideo(videoUri: String): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        if (videoUri.isBlank()) return@withContext emptyList()
        mutex.withLock {
            val dir = videoDir(videoUri)
            val vaultHashes = resolveVaultCandidateHashesLocked()
            val isVault = isVaultDirectoryOrUriLocked(videoUri, dir, vaultHashes)
            if (isVault && hasLegacyPlaintextData(dir)) {
                migrateSingleVaultDirectoryLocked(dir)
            }
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

            val vaultHashes = resolveVaultCandidateHashesLocked()
            val isVault = isVaultDirectoryOrUriLocked(videoUri, dir, vaultHashes)
            if (isVault) {
                ensureVaultMarker(dir)
                if (hasLegacyPlaintextData(dir)) {
                    migrateSingleVaultDirectoryLocked(dir)
                }
            } else {
                ensurePublicVerifiedMarker(dir)
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

            val serialized = SubtitleParser.export(cues, targetFormat)
            val payloadToWrite = encodeText(serialized, isVault)
            writeTextDurably(outFile, payloadToWrite)

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
            val vaultHashes = resolveVaultCandidateHashesLocked()
            val isVault = isVaultDirectoryOrUriLocked(videoUri, dir, vaultHashes)
            if (isVault && hasLegacyPlaintextData(dir)) {
                migrateSingleVaultDirectoryLocked(dir)
            }
            val entries = readIndex(dir, isVault)
            val target = entries.firstOrNull { it.id == trackId } ?: return@withContext null
            val outFile = File(dir, target.fileName)
            val serialized = SubtitleParser.export(updatedCues, target.format)
            val payloadToWrite = encodeText(serialized, isVault)
            writeTextDurably(outFile, payloadToWrite)
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
            val vaultHashes = resolveVaultCandidateHashesLocked()
            val isVault = isVaultDirectoryOrUriLocked(videoUri, dir, vaultHashes)
            if (isVault && hasLegacyPlaintextData(dir)) {
                migrateSingleVaultDirectoryLocked(dir)
            }
            val entries = readIndex(dir, isVault)
            val removed = entries.filter { it.id == trackId }
            removed.forEach { File(dir, it.fileName).delete() }
            writeIndex(dir, entries.filterNot { it.id == trackId }, isVault)
        }
    }

    suspend fun exportAllStoredTracksForBackup(): List<BackupStoredSubtitleTrack> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!subtitlesRootDir.exists() || !subtitlesRootDir.isDirectory) return@withLock emptyList()

            // Auto-migrate known legacy Vault subtitle dirs before gathering backup tracks
            migrateLegacyVaultSubtitlesLocked()

            val vaultHashes = resolveVaultCandidateHashesLocked()
            val collected = mutableListOf<BackupStoredSubtitleTrack>()
            subtitlesRootDir.listFiles()?.forEach { dir ->
                if (!dir.isDirectory || dir.name.endsWith(".tmp")) return@forEach
                // Strictly exclude Vault dirs, matched Vault hashes, AND unclassified legacy dirs
                if (isVaultDirectory(dir) || dir.name in vaultHashes) return@forEach
                if (!isPublicVerifiedDirectory(dir)) return@forEach

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

    suspend fun replaceAllStoredTracksFromBackup(tracks: List<BackupStoredSubtitleTrack>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safeHexRegex = Regex("^[a-fA-F0-9]{16,64}$")
            val safeIdRegex = Regex("^[a-zA-Z0-9._-]{1,80}$")

            if (!subtitlesRootDir.exists()) {
                subtitlesRootDir.mkdirs()
            }

            migrateLegacyVaultSubtitlesLocked()
            val vaultHashes = resolveVaultCandidateHashesLocked()

            fun isProtectedDirectoryLocked(dir: File): Boolean {
                return isVaultDirectory(dir) || dir.name in vaultHashes || !isPublicVerifiedDirectory(dir)
            }

            // Preserve all Vault and unclassified legacy directories unconditionally during public backup restore
            val protectedDirNames = subtitlesRootDir.listFiles()
                ?.filter { it.isDirectory && !it.name.endsWith(".tmp") && isProtectedDirectoryLocked(it) }
                ?.map { it.name }
                ?.toSet()
                .orEmpty() + vaultHashes

            val validTracks = tracks.filter { track ->
                track.videoKeyHash !in protectedDirNames &&
                    safeHexRegex.matches(track.videoKeyHash) &&
                    safeIdRegex.matches(track.trackId) &&
                    track.content.isNotBlank() &&
                    !track.content.startsWith("VHTKMETA1:")
            }

            // Snapshot only PUBLIC verified subtitle directories for transactional rollback
            val snapshot = LinkedHashMap<String, List<Pair<String, ByteArray>>>()
            subtitlesRootDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && !dir.name.endsWith(".tmp") && !isProtectedDirectoryLocked(dir)) {
                    val fileBytes = dir.listFiles()
                        ?.filter { it.isFile }
                        ?.map { it.name to it.readBytes() }
                        .orEmpty()
                    snapshot[dir.name] = fileBytes
                }
            }

            try {
                subtitlesRootDir.listFiles()?.forEach { child ->
                    if (child.isDirectory && !child.name.endsWith(".tmp") && !isProtectedDirectoryLocked(child)) {
                        if (!child.deleteRecursively()) {
                            throw IllegalStateException("تعذر مسح مجلد الترجمات السابق: ${child.name}")
                        }
                    }
                }

                beforeCommitWriteHook?.invoke()

                validTracks.groupBy { it.videoKeyHash }.forEach { (hash, group) ->
                    val dir = File(subtitlesRootDir, hash)
                    if (isProtectedDirectoryLocked(dir) && dir.exists()) return@forEach
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw IllegalStateException("تعذر إنشاء مجلد استعادة الترجمات: $hash")
                    }
                    ensurePublicVerifiedMarker(dir)
                    val entries = mutableListOf<StoredTrackEntry>()
                    group.forEach { track ->
                        val targetFormat = if (track.format == SubtitleFormat.WEBVTT) SubtitleFormat.WEBVTT else SubtitleFormat.SRT
                        val ext = if (targetFormat == SubtitleFormat.WEBVTT) "vtt" else "srt"
                        val fileName = "${track.trackId}.$ext"
                        val outFile = File(dir, fileName)
                        writeTextDurably(outFile, track.content)
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
                        if (child.isDirectory && !child.name.endsWith(".tmp") && !isProtectedDirectoryLocked(child)) {
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

    private fun parseIndexEntriesStrict(
        indexFile: File,
        encrypted: Boolean,
        mgr: VaultCryptoManager?
    ): List<StoredTrackEntry>? {
        if (!indexFile.exists() || !indexFile.isFile) return null
        return runCatching {
            val raw = indexFile.readText(Charsets.UTF_8)
            val decoded = if (encrypted) {
                if (mgr == null || !raw.startsWith("VHTKMETA1:")) return null
                mgr.decryptMetadata(raw)
            } else {
                if (raw.startsWith("VHTKMETA1:")) {
                    if (mgr == null) return null
                    mgr.decryptMetadata(raw)
                } else {
                    raw
                }
            }
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
        }.getOrNull()
    }

    private fun readIndex(dir: File, isVault: Boolean): List<StoredTrackEntry> {
        val encryptedIndex = File(dir, ENCRYPTED_INDEX_FILE)
        val plainIndex = File(dir, PLAIN_INDEX_FILE)
        if (isVault && encryptedIndex.exists()) {
            val parsedEncrypted = parseIndexEntriesStrict(encryptedIndex, encrypted = true, mgr = cryptoManager)
            if (parsedEncrypted != null) {
                return parsedEncrypted
            }
        }
        if (!plainIndex.exists()) return emptyList()
        return runCatching {
            val raw = plainIndex.readText(Charsets.UTF_8)
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

    private fun serializeEntriesJson(entries: List<StoredTrackEntry>): String {
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
        return arr.toString()
    }

    private fun writeEncryptedIndexWithoutDeletingPlain(
        dir: File,
        entries: List<StoredTrackEntry>,
        mgr: VaultCryptoManager
    ) {
        if (!dir.exists()) dir.mkdirs()
        val indexFile = File(dir, ENCRYPTED_INDEX_FILE)
        val encryptedPayload = mgr.encryptMetadata(serializeEntriesJson(entries))
        writeTextDurably(indexFile, encryptedPayload)
    }

    private fun writeIndex(dir: File, entries: List<StoredTrackEntry>, isVault: Boolean) {
        if (!dir.exists()) dir.mkdirs()
        val useEncryptedIndex = isVault && cryptoManager != null
        val indexFile = File(dir, if (useEncryptedIndex) ENCRYPTED_INDEX_FILE else PLAIN_INDEX_FILE)
        val payloadToWrite = encodeText(serializeEntriesJson(entries), useEncryptedIndex)
        writeTextDurably(indexFile, payloadToWrite)
        if (useEncryptedIndex) {
            File(dir, PLAIN_INDEX_FILE).delete()
        }
    }
}


