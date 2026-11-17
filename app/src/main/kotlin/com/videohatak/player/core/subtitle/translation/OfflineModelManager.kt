package com.videohatak.player.core.subtitle.translation

import android.content.Context
import android.os.StatFs
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

interface MlKitModelBridge {
    suspend fun getDownloadedLanguageCodes(): Set<String>
    suspend fun downloadLanguageModel(mlKitCode: String)
    suspend fun deleteLanguageModel(mlKitCode: String)
}

class DefaultMlKitModelBridge : MlKitModelBridge {
    private fun remoteManager(): RemoteModelManager = RemoteModelManager.getInstance()

    override suspend fun getDownloadedLanguageCodes(): Set<String> = withContext(Dispatchers.IO) {
        val models = remoteManager()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .awaitTaskResult()
        models.mapNotNull { it.language }.toSet()
    }

    override suspend fun downloadLanguageModel(mlKitCode: String) {
        withContext(Dispatchers.IO) {
            val resolvedCode = TranslateLanguage.fromLanguageTag(mlKitCode) ?: mlKitCode
            val model = TranslateRemoteModel.Builder(resolvedCode).build()
            val conditions = DownloadConditions.Builder().build()
            remoteManager().download(model, conditions).awaitTaskResult()
        }
    }

    override suspend fun deleteLanguageModel(mlKitCode: String) {
        withContext(Dispatchers.IO) {
            val resolvedCode = TranslateLanguage.fromLanguageTag(mlKitCode) ?: mlKitCode
            val model = TranslateRemoteModel.Builder(resolvedCode).build()
            remoteManager().deleteDownloadedModel(model).awaitTaskResult()
        }
    }
}

data class ModelOperationResult(
    val success: Boolean,
    val message: String
)

class OfflineModelManager private constructor(
    private val rootDir: File,
    private val mlKitBridge: MlKitModelBridge,
    private val isTestMode: Boolean,
    private val streamDownloader: ((url: String, destFile: File, expectedBytes: Long, onProgress: suspend (Long, Long) -> Unit) -> Unit)? = null
) {
    constructor(context: Context) : this(
        rootDir = File(context.applicationContext.filesDir, "offline_models"),
        mlKitBridge = DefaultMlKitModelBridge(),
        isTestMode = false
    )

    internal constructor(
        testRootDir: File,
        mlKitBridge: MlKitModelBridge,
        streamDownloader: ((url: String, destFile: File, expectedBytes: Long, onProgress: suspend (Long, Long) -> Unit) -> Unit)? = null
    ) : this(
        rootDir = testRootDir,
        mlKitBridge = mlKitBridge,
        isTestMode = true,
        streamDownloader = streamDownloader
    )

    companion object {
        private const val CATALOG_FILE_NAME = "catalog.json"
        private const val VOSK_DIR_NAME = "vosk"
        private const val TMP_DIR_NAME = "tmp"
        private const val MAX_ZIP_ENTRIES = 2_000
        private const val MAX_SINGLE_ENTRY_BYTES = 750L * 1024L * 1024L

        @Volatile
        private var instance: OfflineModelManager? = null

        fun getInstance(context: Context): OfflineModelManager {
            return instance ?: synchronized(this) {
                instance ?: OfflineModelManager(context.applicationContext).also { instance = it }
            }
        }

        private val VALID_FST_NAMES = setOf("hclg.fst", "hclr.fst", "gr.fst")

        internal fun hasValidVoskStructure(dir: File, minExpectedBytes: Long = 0L): Boolean {
            if (!dir.exists() || !dir.isDirectory) return false
            val amFinalMdl = File(dir, "am/final.mdl")
            if (!amFinalMdl.exists() || !amFinalMdl.isFile || amFinalMdl.length() <= 0L) {
                return false
            }
            if (minExpectedBytes > 0L && amFinalMdl.length() < 100_000L) {
                return false
            }

            val confDir = File(dir, "conf")
            if (!confDir.exists() || !confDir.isDirectory) return false
            val mfccConf = File(confDir, "mfcc.conf")
            val modelConf = File(confDir, "model.conf")
            val hasValidConf = isValidKaldiConfigFile(mfccConf) || isValidKaldiConfigFile(modelConf)
            if (!hasValidConf) return false

            val graphDir = File(dir, "graph")
            if (!graphDir.exists() || !graphDir.isDirectory) return false
            val graphFiles = graphDir.walkTopDown().filter { it.isFile && it.length() > 0L }.toList()
            val hasDecodingFst = graphFiles.any { it.name.lowercase() in VALID_FST_NAMES }
            val hasVocabularyOrPhones = graphFiles.any { f ->
                when (f.name.lowercase()) {
                    "words.txt", "phones.txt" -> isValidPlainTextFile(f)
                    "word_boundary.int", "disambig.int" -> true
                    else -> false
                }
            }
            if (!hasDecodingFst || !hasVocabularyOrPhones) return false

            val ivectorDir = File(dir, "ivector")
            val hasValidIvector = !ivectorDir.exists() ||
                (ivectorDir.isDirectory && ivectorDir.walkTopDown().any { it.isFile && it.length() > 0L })
            if (!hasValidIvector) return false

            if (minExpectedBytes > 0L && calculateDirectoryBytes(dir) < minExpectedBytes) {
                return false
            }
            return true
        }

        private fun isValidKaldiConfigFile(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() <= 0L || file.length() > 1024L * 1024L) {
                return false
            }
            return runCatching {
                val bytes = file.readBytes()
                if (bytes.any { it == 0.toByte() }) return false
                val text = bytes.toString(Charsets.UTF_8)
                text.lineSequence().any { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() && !trimmed.startsWith("#") && (trimmed.contains("--") || trimmed.contains("="))
                }
            }.getOrDefault(false)
        }

        private fun isValidPlainTextFile(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() <= 0L) return false
            return runCatching {
                FileInputStream(file).use { fis ->
                    val sample = ByteArray(minOf(4096, file.length().toInt().coerceAtLeast(1)))
                    val read = fis.read(sample)
                    if (read <= 0) return false
                    for (i in 0 until read) {
                        if (sample[i] == 0.toByte()) return false
                    }
                }
                true
            }.getOrDefault(false)
        }

        internal fun calculateDirectoryBytes(dir: File): Long {
            if (!dir.exists()) return 0L
            if (dir.isFile) return dir.length()
            var total = 0L
            dir.walkTopDown().forEach { f ->
                if (f.isFile) total += f.length()
            }
            return total
        }

        internal fun verifyZipSignature(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() < 22L) return false
            return runCatching {
                FileInputStream(file).use { fis ->
                    val header = ByteArray(4)
                    if (fis.read(header) != 4) return false
                    header[0] == 0x50.toByte() &&
                        header[1] == 0x4B.toByte() &&
                        ((header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                            (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()))
                }
            }.getOrDefault(false)
        }

        internal fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = fis.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogMutex = Mutex()
    private val speechDownloadMutex = Mutex()
    private val mlKitMutexes = ConcurrentHashMap<SubtitleTranslationLanguage, Mutex>()
    private val inFlightMlKitOps = ConcurrentHashMap<SubtitleTranslationLanguage, kotlinx.coroutines.Deferred<Unit>>()

    private fun mlKitMutexFor(language: SubtitleTranslationLanguage): Mutex =
        mlKitMutexes.computeIfAbsent(language) { Mutex() }

    private val activeSpeechJobs = ConcurrentHashMap<SubtitleTranslationLanguage, Job>()
    private val activeMlKitJobs = ConcurrentHashMap<SubtitleTranslationLanguage, Job>()
    private val speechUseCounts = ConcurrentHashMap<SubtitleTranslationLanguage, Int>()
    private val translationUseCounts = ConcurrentHashMap<SubtitleTranslationLanguage, Int>()

    private val voskBaseDir: File
        get() = File(rootDir, VOSK_DIR_NAME)

    private val tmpBaseDir: File
        get() = File(rootDir, TMP_DIR_NAME)

    private val catalogFile: File
        get() = File(rootDir, CATALOG_FILE_NAME)

    private val _languageStates = MutableStateFlow<Map<SubtitleTranslationLanguage, LanguageModelResourceState>>(
        buildInitialStateMap()
    )
    val languageStates: StateFlow<Map<SubtitleTranslationLanguage, LanguageModelResourceState>> =
        _languageStates.asStateFlow()

    private fun buildInitialStateMap(): Map<SubtitleTranslationLanguage, LanguageModelResourceState> {
        return SubtitleTranslationLanguage.TARGET_LANGUAGES.associateWith { lang ->
            LanguageModelResourceState(language = lang)
        }
    }

    suspend fun refreshAllStates() = withContext(Dispatchers.IO) {
        ensureDirectories()
        val persistedRecords = readCatalogRecords()
        val mlKitQueryResult = runCatching { mlKitBridge.getDownloadedLanguageCodes() }
        val mlKitQueryFailed = mlKitQueryResult.isFailure
        val downloadedMlKitCodes = mlKitQueryResult.getOrNull().orEmpty()

        val nextMap = SubtitleTranslationLanguage.TARGET_LANGUAGES.associateWith { lang ->
            val current = _languageStates.value[lang] ?: LanguageModelResourceState(language = lang)
            val spec = VoskModelCatalog.specFor(lang)
            val isSpeechDownloading = activeSpeechJobs[lang]?.isActive == true
            val isMlKitDownloading = activeMlKitJobs[lang]?.isActive == true

            val (speechStatus, installedVer, diskBytes) = if (isSpeechDownloading) {
                SpeechDiskInspection(
                    status = OfflineModelDownloadStatus.DOWNLOADING,
                    version = current.speechInstalledVersion,
                    bytes = current.speechDiskBytes
                )
            } else if (spec == null) {
                SpeechDiskInspection(
                    status = OfflineModelDownloadStatus.NOT_DOWNLOADED,
                    version = null,
                    bytes = 0L
                )
            } else {
                inspectVoskModelOnDisk(spec, persistedRecords[lang.code])
            }

            val mlKitStatus = when {
                isMlKitDownloading -> OfflineModelDownloadStatus.DOWNLOADING
                lang == SubtitleTranslationLanguage.ENGLISH -> OfflineModelDownloadStatus.READY
                mlKitQueryFailed -> OfflineModelDownloadStatus.ERROR
                lang.mlKitCode != null && lang.mlKitCode in downloadedMlKitCodes -> OfflineModelDownloadStatus.READY
                current.translationStatus == OfflineModelDownloadStatus.ERROR -> OfflineModelDownloadStatus.ERROR
                else -> OfflineModelDownloadStatus.NOT_DOWNLOADED
            }
            val mlKitErrorMsg = when {
                lang == SubtitleTranslationLanguage.ENGLISH -> null
                mlKitQueryFailed && !isMlKitDownloading -> "تعذر الاستعلام عن حزم الترجمة المثبتة من خدمة Google Play"
                mlKitStatus == OfflineModelDownloadStatus.READY || mlKitStatus == OfflineModelDownloadStatus.NOT_DOWNLOADED -> null
                else -> current.translationErrorMessage
            }

            current.copy(
                speechSpec = spec,
                speechStatus = speechStatus,
                speechInstalledVersion = installedVer,
                speechDiskBytes = diskBytes,
                speechDownloadProgress = if (speechStatus == OfflineModelDownloadStatus.READY) 1f else if (isSpeechDownloading) current.speechDownloadProgress else 0f,
                isSpeechInUse = (speechUseCounts[lang] ?: 0) > 0,
                translationStatus = mlKitStatus,
                translationErrorMessage = mlKitErrorMsg,
                translationStatusNote = if (mlKitStatus == OfflineModelDownloadStatus.READY) null else current.translationStatusNote,
                isTranslationInUse = (translationUseCounts[lang] ?: 0) > 0
            )
        }
        _languageStates.value = nextMap
    }

    private data class SpeechDiskInspection(
        val status: OfflineModelDownloadStatus,
        val version: String?,
        val bytes: Long
    )

    private fun productionMinExpectedBytes(spec: VoskSpeechModelSpec): Long {
        return if (isTestMode) {
            0L
        } else {
            maxOf(1024L * 1024L, spec.approximateDiskExtractedMb.toLong() * 1024L * 1024L / 3L)
        }
    }

    private fun inspectVoskModelOnDisk(
        spec: VoskSpeechModelSpec,
        record: JSONObject?
    ): SpeechDiskInspection {
        val modelDir = File(voskBaseDir, spec.modelId)
        val hasDir = modelDir.exists() && modelDir.isDirectory
        val validStructure = hasDir && hasValidVoskStructure(modelDir, productionMinExpectedBytes(spec))
        val recordedVer = record?.optString("version")?.takeIf { it.isNotBlank() }

        return when {
            validStructure && (recordedVer == null || recordedVer == spec.version) -> {
                SpeechDiskInspection(
                    status = OfflineModelDownloadStatus.READY,
                    version = recordedVer ?: spec.version,
                    bytes = calculateDirectoryBytes(modelDir)
                )
            }
            validStructure && recordedVer != spec.version -> {
                SpeechDiskInspection(
                    status = OfflineModelDownloadStatus.NEEDS_UPDATE,
                    version = recordedVer,
                    bytes = calculateDirectoryBytes(modelDir)
                )
            }
            hasDir || record != null -> {
                SpeechDiskInspection(
                    status = OfflineModelDownloadStatus.ERROR,
                    version = recordedVer,
                    bytes = if (hasDir) calculateDirectoryBytes(modelDir) else 0L
                )
            }
            else -> {
                SpeechDiskInspection(
                    status = OfflineModelDownloadStatus.NOT_DOWNLOADED,
                    version = null,
                    bytes = 0L
                )
            }
        }
    }

    suspend fun getVerifiedSpeechModelDirectory(language: SubtitleTranslationLanguage): File? {
        val spec = VoskModelCatalog.specFor(language) ?: return null
        val modelDir = File(voskBaseDir, spec.modelId)
        if (hasValidVoskStructure(modelDir, productionMinExpectedBytes(spec))) {
            return modelDir
        }
        refreshAllStates()
        return null
    }

    fun startSpeechModelDownload(language: SubtitleTranslationLanguage): Job {
        val existing = activeSpeechJobs[language]
        if (existing != null && existing.isActive) return existing

        val job = scope.launch {
            downloadSpeechModelBlocking(language)
        }
        activeSpeechJobs[language] = job
        return job
    }

    suspend fun downloadSpeechModelBlocking(language: SubtitleTranslationLanguage): ModelOperationResult =
        withContext(Dispatchers.IO) {
            val spec = VoskModelCatalog.specFor(language)
                ?: return@withContext ModelOperationResult(false, "لا يوجد نموذج تفريغ صوتي لهذه اللغة")

            speechDownloadMutex.withLock {
                ensureDirectories()
                val modelDir = File(voskBaseDir, spec.modelId)
                val backupDir = File(voskBaseDir, "${spec.modelId}.bak")
                val minExpectedBytes = productionMinExpectedBytes(spec)

                if (hasValidVoskStructure(modelDir, minExpectedBytes)) {
                    persistSpeechRecord(language.code, spec)
                    refreshAllStates()
                    return@withContext ModelOperationResult(true, "نموذج ${language.arabicName} الصوتي جاهز بالفعل")
                }

                // Check available disk space in internal app storage
                val availableBytes = readAvailableStorageBytes(rootDir)

                if (availableBytes < spec.requiredFreeBytesWithMargin) {
                    val needMb = spec.requiredFreeBytesWithMargin / (1024L * 1024L)
                    val availMb = availableBytes / (1024L * 1024L)
                    val msg = "المساحة التخزينية غير كافية لتنزيل نموذج ${language.arabicName} (يتطلب ~$needMb MB، المتاح $availMb MB)"
                    updateLanguageState(language) {
                        it.copy(
                            speechStatus = OfflineModelDownloadStatus.ERROR,
                            speechErrorMessage = msg
                        )
                    }
                    return@withContext ModelOperationResult(false, msg)
                }

                val tempZip = File(tmpBaseDir, "${spec.modelId}.zip.part")
                val stagingDir = File(tmpBaseDir, "${spec.modelId}_staging")
                tempZip.delete()
                stagingDir.deleteRecursively()
                backupDir.deleteRecursively()

                updateLanguageState(language) {
                    it.copy(
                        speechStatus = OfflineModelDownloadStatus.DOWNLOADING,
                        speechDownloadProgress = 0.02f,
                        speechDownloadedBytes = 0L,
                        speechTotalBytes = spec.expectedZipBytes,
                        speechErrorMessage = null
                    )
                }

                var movedExistingToBackup = false
                var replaceSucceeded = false
                try {
                    val maxDownloadBytes = maxOf(spec.expectedZipBytes * 3L, 640L * 1024L * 1024L)
                    if (streamDownloader != null) {
                        streamDownloader.invoke(spec.downloadUrl, tempZip, spec.expectedZipBytes) { read, total ->
                            coroutineContext.ensureActive()
                            val effectiveTotal = if (total > 0L) total else spec.expectedZipBytes
                            val dlFraction = (read.toFloat() / effectiveTotal.toFloat()).coerceIn(0f, 1f) * 0.85f
                            updateLanguageState(language) {
                                it.copy(
                                    speechStatus = OfflineModelDownloadStatus.DOWNLOADING,
                                    speechDownloadProgress = dlFraction.coerceAtLeast(0.02f),
                                    speechDownloadedBytes = read,
                                    speechTotalBytes = effectiveTotal
                                )
                            }
                        }
                    } else {
                        downloadFileWithRedirects(
                            urlString = spec.downloadUrl,
                            destFile = tempZip,
                            fallbackTotalBytes = spec.expectedZipBytes,
                            maxDownloadBytes = maxDownloadBytes
                        ) { read, total ->
                            val dlFraction = (read.toFloat() / total.toFloat()).coerceIn(0f, 1f) * 0.85f
                            updateLanguageState(language) {
                                it.copy(
                                    speechStatus = OfflineModelDownloadStatus.DOWNLOADING,
                                    speechDownloadProgress = dlFraction.coerceAtLeast(0.02f),
                                    speechDownloadedBytes = read,
                                    speechTotalBytes = total
                                )
                            }
                        }
                    }

                    coroutineContext.ensureActive()
                    if (!verifyZipSignature(tempZip)) {
                        throw IOException("الملف المنزّل ليس أرشيف ZIP صالحًا لنموذج ${language.arabicName}")
                    }
                    val expectedHash = spec.expectedSha256?.trim()
                    if (!expectedHash.isNullOrEmpty()) {
                        val actualHash = computeFileSha256(tempZip)
                        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                            throw SecurityException("فشل التحقق من بصمة SHA-256 لأرشيف نموذج ${language.arabicName}")
                        }
                    }

                    val extractAvailableBytes = readAvailableStorageBytes(rootDir)
                    val requiredExtractMargin = maxOf(spec.approximateDiskExtractedMb.toLong() * 1024L * 1024L, 64L * 1024L * 1024L)
                    if (extractAvailableBytes < requiredExtractMargin) {
                        throw IOException("المساحة التخزينية المتاحة غير كافية لفك ضغط نموذج ${language.arabicName}")
                    }

                    updateLanguageState(language) {
                        it.copy(
                            speechStatus = OfflineModelDownloadStatus.DOWNLOADING,
                            speechDownloadProgress = 0.90f
                        )
                    }

                    stagingDir.mkdirs()
                    val maxExtractedBytes = maxOf(1_500L * 1024L * 1024L, spec.requiredFreeBytesWithMargin * 2L)
                    extractZipSafely(tempZip, stagingDir, maxExtractedBytes = maxExtractedBytes)
                    val locatedModelRoot = locateVoskRootInExtracted(stagingDir, minExpectedBytes)
                        ?: throw IOException("أرشيف النموذج الصوتي غير مكتمل أو تالف (ملفات الصوت am/conf/graph غير مكتملة)")

                    if (!hasValidVoskStructure(locatedModelRoot, minExpectedBytes)) {
                        throw IOException("فشل التحقق من سلامة ملفات نموذج ${language.arabicName}")
                    }

                    if (modelDir.exists()) {
                        movedExistingToBackup = modelDir.renameTo(backupDir) ||
                            (modelDir.copyRecursively(backupDir, overwrite = true) && modelDir.deleteRecursively())
                        if (!movedExistingToBackup) {
                            throw IOException("تعذر تجهيز النسخة السابقة للاستبدال الآمن لنموذج ${language.arabicName}")
                        }
                    }

                    if (!locatedModelRoot.renameTo(modelDir)) {
                        locatedModelRoot.copyRecursively(modelDir, overwrite = true)
                    }
                    if (!hasValidVoskStructure(modelDir, minExpectedBytes)) {
                        throw IOException("تعذر تثبيت ملفات نموذج ${language.arabicName} داخل ذاكرة التطبيق")
                    }

                    replaceSucceeded = true
                    backupDir.deleteRecursively()
                    persistSpeechRecord(language.code, spec)
                    val finalBytes = calculateDirectoryBytes(modelDir)
                    updateLanguageState(language) {
                        it.copy(
                            speechStatus = OfflineModelDownloadStatus.READY,
                            speechDownloadProgress = 1f,
                            speechInstalledVersion = spec.version,
                            speechDiskBytes = finalBytes,
                            speechErrorMessage = null
                        )
                    }
                    ModelOperationResult(true, "تم تنزيل وتثبيت نموذج التفريغ الصوتي لـ ${language.arabicName} بنجاح")
                } catch (ce: CancellationException) {
                    withContext(NonCancellable) {
                        modelDir.deleteRecursively()
                        if (movedExistingToBackup && backupDir.exists()) {
                            if (backupDir.renameTo(modelDir)) {
                                refreshAllStates()
                            } else if (backupDir.copyRecursively(modelDir, overwrite = true) && hasValidVoskStructure(modelDir, minExpectedBytes)) {
                                backupDir.deleteRecursively()
                                refreshAllStates()
                            }
                        } else {
                            updateLanguageState(language) {
                                it.copy(
                                    speechStatus = OfflineModelDownloadStatus.NOT_DOWNLOADED,
                                    speechDownloadProgress = 0f,
                                    speechDownloadedBytes = 0L,
                                    speechErrorMessage = null
                                )
                            }
                        }
                    }
                    throw ce
                } catch (e: Exception) {
                    modelDir.deleteRecursively()
                    if (movedExistingToBackup && backupDir.exists()) {
                        if (!backupDir.renameTo(modelDir)) {
                            if (backupDir.copyRecursively(modelDir, overwrite = true) && hasValidVoskStructure(modelDir, minExpectedBytes)) {
                                backupDir.deleteRecursively()
                            }
                        }
                    }
                    val msg = "تعذر تنزيل نموذج ${language.arabicName}: ${e.localizedMessage ?: "خطأ في الشبكة"}"
                    updateLanguageState(language) {
                        it.copy(
                            speechStatus = OfflineModelDownloadStatus.ERROR,
                            speechDownloadProgress = 0f,
                            speechErrorMessage = msg
                        )
                    }
                    ModelOperationResult(false, msg)
                } finally {
                    tempZip.delete()
                    stagingDir.deleteRecursively()
                    if (replaceSucceeded) {
                        backupDir.deleteRecursively()
                    }
                    activeSpeechJobs.remove(language)
                }
            }
        }

    fun cancelSpeechModelDownload(language: SubtitleTranslationLanguage) {
        val cancelledJob = activeSpeechJobs.remove(language)
        cancelledJob?.cancel()
        updateLanguageState(language) {
            it.copy(
                speechStatus = OfflineModelDownloadStatus.NOT_DOWNLOADED,
                speechDownloadProgress = 0f,
                speechDownloadedBytes = 0L,
                speechErrorMessage = null
            )
        }
        scope.launch {
            runCatching { cancelledJob?.join() }
            runCatching { refreshAllStates() }
        }
    }

    suspend fun deleteSpeechModel(language: SubtitleTranslationLanguage): ModelOperationResult =
        withContext(Dispatchers.IO) {
            if ((speechUseCounts[language] ?: 0) > 0) {
                return@withContext ModelOperationResult(
                    false,
                    "لا يمكن حذف نموذج ${language.arabicName} الصوتي أثناء استخدامه في مهمة جارية"
                )
            }
            activeSpeechJobs.remove(language)?.cancel()
            val spec = VoskModelCatalog.specFor(language)
                ?: return@withContext ModelOperationResult(false, "النموذج غير موجود")

            val modelDir = File(voskBaseDir, spec.modelId)
            modelDir.deleteRecursively()
            removeCatalogRecord(language.code)
            refreshAllStates()
            ModelOperationResult(true, "تم حذف نموذج التفريغ الصوتي لـ ${language.arabicName}")
        }

    fun startTranslationModelDownload(language: SubtitleTranslationLanguage): Job {
        val existing = activeMlKitJobs[language]
        if (existing != null && existing.isActive) return existing

        val job = scope.launch {
            downloadTranslationModelBlocking(language)
        }
        activeMlKitJobs[language] = job
        return job
    }

    fun cancelTranslationModelDownload(language: SubtitleTranslationLanguage) {
        activeMlKitJobs.remove(language)?.cancel()
        val hasBackgroundOp = inFlightMlKitOps[language]?.isActive == true
        updateLanguageState(language) { current ->
            if (current.translationStatus == OfflineModelDownloadStatus.READY) {
                current
            } else {
                current.copy(
                    translationStatus = OfflineModelDownloadStatus.NOT_DOWNLOADED,
                    translationErrorMessage = null,
                    translationStatusNote = if (hasBackgroundOp) {
                        "تم إيقاف متابعة التنزيل داخل التطبيق؛ قد تواصل خدمة Google Play التنزيل في الخلفية وسيتم تحديث الحالة تلقائيًا."
                    } else {
                        null
                    }
                )
            }
        }
    }

    suspend fun downloadTranslationModelBlocking(language: SubtitleTranslationLanguage): ModelOperationResult =
        withContext(Dispatchers.IO) {
            if (language == SubtitleTranslationLanguage.ENGLISH) {
                updateLanguageState(language) {
                    it.copy(
                        translationStatus = OfflineModelDownloadStatus.READY,
                        translationErrorMessage = null,
                        translationStatusNote = null
                    )
                }
                return@withContext ModelOperationResult(true, "نموذج الإنجليزية مدمج وجاهز دائمًا")
            }
            val mlKitCode = language.mlKitCode
                ?: return@withContext ModelOperationResult(false, "هذه اللغة غير مدعومة في الترجمة النصية المحلية")

            mlKitMutexFor(language).withLock {
                val checkResult = runCatching { mlKitBridge.getDownloadedLanguageCodes() }
                val alreadyDownloaded = checkResult.getOrNull().orEmpty()
                if (mlKitCode in alreadyDownloaded) {
                    updateLanguageState(language) {
                        it.copy(
                            translationStatus = OfflineModelDownloadStatus.READY,
                            translationErrorMessage = null,
                            translationStatusNote = null
                        )
                    }
                    return@withContext ModelOperationResult(true, "حزمة ترجمة ${language.arabicName} جاهزة بالفعل محليًا")
                }

                updateLanguageState(language) {
                    it.copy(
                        translationStatus = OfflineModelDownloadStatus.DOWNLOADING,
                        translationErrorMessage = null,
                        translationStatusNote = null
                    )
                }

                val existingOp = inFlightMlKitOps[language]?.takeIf { it.isActive }
                val downloadOp = existingOp ?: scope.async {
                    mlKitBridge.downloadLanguageModel(mlKitCode)
                }.also { createdOp ->
                    inFlightMlKitOps[language] = createdOp
                    createdOp.invokeOnCompletion { throwable ->
                        inFlightMlKitOps.remove(language, createdOp)
                        if (throwable == null) {
                            scope.launch {
                                val confirmed = runCatching { mlKitBridge.getDownloadedLanguageCodes() }
                                    .getOrNull()
                                    ?.contains(mlKitCode) ?: true
                                if (confirmed) {
                                    updateLanguageState(language) { state ->
                                        state.copy(
                                            translationStatus = OfflineModelDownloadStatus.READY,
                                            translationErrorMessage = null,
                                            translationStatusNote = null
                                        )
                                    }
                                }
                            }
                        } else if (activeMlKitJobs[language] == null && throwable !is CancellationException) {
                            updateLanguageState(language) { state ->
                                if (state.translationStatus == OfflineModelDownloadStatus.READY) state else {
                                    state.copy(
                                        translationStatus = OfflineModelDownloadStatus.NOT_DOWNLOADED,
                                        translationStatusNote = null
                                    )
                                }
                            }
                        }
                    }
                }

                try {
                    downloadOp.await()
                    updateLanguageState(language) {
                        it.copy(
                            translationStatus = OfflineModelDownloadStatus.READY,
                            translationErrorMessage = null,
                            translationStatusNote = null
                        )
                    }
                    ModelOperationResult(true, "تم تنزيل حزمة ترجمة ${language.arabicName} للعمل دون إنترنت")
                } catch (ce: CancellationException) {
                    withContext(NonCancellable) {
                        val downloadedAfterCancel = runCatching { mlKitBridge.getDownloadedLanguageCodes() }
                            .getOrNull()
                            .orEmpty()
                        val stillInFlight = inFlightMlKitOps[language]?.isActive == true
                        val resolvedStatus = if (mlKitCode in downloadedAfterCancel) {
                            OfflineModelDownloadStatus.READY
                        } else {
                            OfflineModelDownloadStatus.NOT_DOWNLOADED
                        }
                        updateLanguageState(language) {
                            it.copy(
                                translationStatus = resolvedStatus,
                                translationErrorMessage = null,
                                translationStatusNote = if (resolvedStatus != OfflineModelDownloadStatus.READY && stillInFlight) {
                                    "تم إيقاف متابعة التنزيل داخل التطبيق؛ قد تواصل خدمة Google Play التنزيل في الخلفية وسيتم تحديث الحالة تلقائيًا."
                                } else {
                                    null
                                }
                            )
                        }
                    }
                    throw ce
                } catch (e: Exception) {
                    val msg = "تعذر تنزيل حزمة ترجمة ${language.arabicName}: ${e.localizedMessage ?: "تحقق من اتصال الإنترنت"}"
                    updateLanguageState(language) {
                        it.copy(
                            translationStatus = OfflineModelDownloadStatus.ERROR,
                            translationErrorMessage = msg,
                            translationStatusNote = null
                        )
                    }
                    ModelOperationResult(false, msg)
                } finally {
                    activeMlKitJobs.remove(language)
                }
            }
        }

    suspend fun deleteTranslationModel(language: SubtitleTranslationLanguage): ModelOperationResult =
        withContext(Dispatchers.IO) {
            if (language == SubtitleTranslationLanguage.ENGLISH) {
                return@withContext ModelOperationResult(false, "حزمة الإنجليزية مدمجة في المحرك الأساسي ولا تحتاج لحذف")
            }
            if ((translationUseCounts[language] ?: 0) > 0) {
                return@withContext ModelOperationResult(
                    false,
                    "لا يمكن حذف حزمة ترجمة ${language.arabicName} أثناء استخدامها"
                )
            }
            val mlKitCode = language.mlKitCode
                ?: return@withContext ModelOperationResult(false, "لغة غير مدعومة")

            return@withContext try {
                mlKitBridge.deleteLanguageModel(mlKitCode)
                refreshAllStates()
                ModelOperationResult(true, "تم حذف حزمة ترجمة ${language.arabicName} لتحرير المساحة")
            } catch (e: Exception) {
                ModelOperationResult(false, "تعذر حذف حزمة ${language.arabicName}: ${e.localizedMessage ?: "خطأ"}")
            }
        }

    suspend fun ensureTranslationPairReady(
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ): ModelOperationResult {
        if (sourceLanguage == targetLanguage) {
            return ModelOperationResult(true, "لغة المصدر مطابقة للهدف")
        }
        val toDownload = buildList {
            if (sourceLanguage != SubtitleTranslationLanguage.AUTO &&
                sourceLanguage != SubtitleTranslationLanguage.ENGLISH
            ) {
                add(sourceLanguage)
            }
            if (targetLanguage != SubtitleTranslationLanguage.AUTO &&
                targetLanguage != SubtitleTranslationLanguage.ENGLISH
            ) {
                add(targetLanguage)
            }
        }.distinct()

        refreshAllStates()
        val states = _languageStates.value
        for (lang in toDownload) {
            if (states[lang]?.translationStatus != OfflineModelDownloadStatus.READY) {
                val res = downloadTranslationModelBlocking(lang)
                if (!res.success) return res
            }
        }
        return ModelOperationResult(true, "نماذج الترجمة النصية جاهزة محليًا")
    }

    fun computePairReadiness(
        sourceMode: SubtitleSourceMode,
        sourceLanguage: SubtitleTranslationLanguage,
        targetLanguage: SubtitleTranslationLanguage
    ): TranslationPairReadiness {
        val states = _languageStates.value
        val isSpeechReq = sourceMode == SubtitleSourceMode.VIDEO_AUDIO_EXTRACTION
        val speechLang = if (isSpeechReq && sourceLanguage != SubtitleTranslationLanguage.AUTO) {
            sourceLanguage
        } else {
            null
        }
        val speechReady = if (!isSpeechReq) {
            true
        } else if (speechLang != null) {
            states[speechLang]?.speechStatus == OfflineModelDownloadStatus.READY
        } else {
            false
        }

        val neededMlKit = mutableListOf<SubtitleTranslationLanguage>()
        if (sourceLanguage != targetLanguage) {
            if (sourceLanguage != SubtitleTranslationLanguage.AUTO &&
                sourceLanguage != SubtitleTranslationLanguage.ENGLISH &&
                states[sourceLanguage]?.translationStatus != OfflineModelDownloadStatus.READY
            ) {
                neededMlKit.add(sourceLanguage)
            }
            if (targetLanguage != SubtitleTranslationLanguage.AUTO &&
                targetLanguage != SubtitleTranslationLanguage.ENGLISH &&
                states[targetLanguage]?.translationStatus != OfflineModelDownloadStatus.READY &&
                targetLanguage !in neededMlKit
            ) {
                neededMlKit.add(targetLanguage)
            }
        }

        val speechMb = if (isSpeechReq && !speechReady && speechLang != null) {
            VoskModelCatalog.specFor(speechLang)?.approximateZipMb ?: 0
        } else {
            0
        }
        val mlKitMb = neededMlKit.sumOf { if (it == SubtitleTranslationLanguage.ENGLISH) 0 else 30 }

        return TranslationPairReadiness(
            isSpeechRequired = isSpeechReq,
            speechLanguage = speechLang,
            isSpeechReady = speechReady,
            missingMlKitLanguages = neededMlKit,
            estimatedDownloadMb = speechMb + mlKitMb
        )
    }

    fun markSpeechInUse(language: SubtitleTranslationLanguage, active: Boolean) {
        speechUseCounts.compute(language) { _, prev ->
            val next = (prev ?: 0) + if (active) 1 else -1
            next.coerceAtLeast(0)
        }
        updateLanguageState(language) {
            it.copy(isSpeechInUse = (speechUseCounts[language] ?: 0) > 0)
        }
    }

    fun markTranslationInUse(language: SubtitleTranslationLanguage, active: Boolean) {
        if (language == SubtitleTranslationLanguage.AUTO) return
        translationUseCounts.compute(language) { _, prev ->
            val next = (prev ?: 0) + if (active) 1 else -1
            next.coerceAtLeast(0)
        }
        updateLanguageState(language) {
            it.copy(isTranslationInUse = (translationUseCounts[language] ?: 0) > 0)
        }
    }

    private suspend fun downloadFileWithRedirects(
        urlString: String,
        destFile: File,
        fallbackTotalBytes: Long,
        maxDownloadBytes: Long,
        onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val maxAttempts = 3
        var lastError: IOException? = null

        for (attempt in 1..maxAttempts) {
            coroutineContext.ensureActive()
            try {
                downloadFileSingleAttempt(
                    urlString = urlString,
                    destFile = destFile,
                    fallbackTotalBytes = fallbackTotalBytes,
                    maxDownloadBytes = maxDownloadBytes,
                    onProgress = onProgress
                )
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                throw se
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay(attempt * 1_500L)
                }
            }
        }
        throw lastError ?: IOException("فشل تنزيل ملف النموذج الصوتي بعد عدة محاولات")
    }

    private suspend fun downloadFileSingleAttempt(
        urlString: String,
        destFile: File,
        fallbackTotalBytes: Long,
        maxDownloadBytes: Long,
        onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        var currentUrl = urlString
        var redirects = 0
        var conn: HttpURLConnection? = null
        val existingBytes = if (destFile.exists() && destFile.length() > 0L && destFile.length() < maxDownloadBytes) {
            destFile.length()
        } else {
            0L
        }

        while (true) {
            coroutineContext.ensureActive()
            val parsedUrl = URL(currentUrl)
            if (!parsedUrl.protocol.equals("https", ignoreCase = true)) {
                throw SecurityException("تم حظر التنزيل: يُسمح بروابط HTTPS المشفرة فقط")
            }
            val connection = (parsedUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/zip, application/octet-stream, */*")
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }
            conn = connection
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || redirects >= 5) {
                    throw IOException("فشل تتبع رابط التنزيل (رمز $code)")
                }
                val redirectedUrl = URL(parsedUrl, location)
                if (!redirectedUrl.protocol.equals("https", ignoreCase = true)) {
                    throw SecurityException("تم رفض إعادة توجيه غير آمنة (غير HTTPS) أثناء التنزيل")
                }
                currentUrl = redirectedUrl.toString()
                redirects++
                continue
            }
            if (code == 416 && existingBytes > 0L) {
                connection.disconnect()
                destFile.delete()
                throw IOException("إعادة محاولة التنزيل من البداية بعد عدم تطابق نطاق الاستئناف")
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("استجابة خادم التنزيل غير صالحة (رمز الحالة: $code)")
            }

            val isResuming = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            val baseOffset = if (isResuming) existingBytes else 0L
            val reportedBytes = connection.contentLengthLong
            val expectedTotalBytes = when {
                isResuming && reportedBytes > 0L -> baseOffset + reportedBytes
                reportedBytes > 0L -> reportedBytes
                else -> fallbackTotalBytes
            }
            if (expectedTotalBytes > maxDownloadBytes) {
                connection.disconnect()
                throw SecurityException("حجم الملف المُبلغ من الخادم يتجاوز الحد الأقصى الآمن المسموح به")
            }

            try {
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(destFile, isResuming).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var copied = baseOffset
                        var lastUiUpdateBytes = baseOffset
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            copied += read
                            if (copied > maxDownloadBytes) {
                                throw SecurityException("تجاوز حجم التنزيل الحد الأقصى المسموح به للنموذج")
                            }
                            output.write(buffer, 0, read)
                            if (copied - lastUiUpdateBytes >= 256 * 1024 || copied >= expectedTotalBytes) {
                                lastUiUpdateBytes = copied
                                onProgress(copied, expectedTotalBytes)
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                conn?.disconnect()
            }
            return
        }
    }

    private suspend fun extractZipSafely(
        zipFile: File,
        targetDir: File,
        maxExtractedBytes: Long
    ) {
        val canonicalTarget = targetDir.canonicalPath
        var entryCount = 0
        var totalExtractedBytes = 0L

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            val buffer = ByteArray(32 * 1024)
            var entry = zis.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw SecurityException("تجاوز عدد الملفات داخل الأرشيف الحد الأقصى المسموح به ($MAX_ZIP_ENTRIES)")
                }
                val outFile = File(targetDir, entry.name)
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalTarget + File.separator)) {
                    throw SecurityException("تم رفض مسار غير آمن داخل الأرشيف: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    var entryBytes = 0L
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = zis.read(buffer)
                            if (count == -1) break
                            entryBytes += count
                            totalExtractedBytes += count
                            if (entryBytes > MAX_SINGLE_ENTRY_BYTES || totalExtractedBytes > maxExtractedBytes) {
                                throw SecurityException("تجاوز حجم فك ضغط الأرشيف الحد الآمن المسموح به")
                            }
                            fos.write(buffer, 0, count)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun locateVoskRootInExtracted(stagingDir: File, minExpectedBytes: Long): File? {
        if (hasValidVoskStructure(stagingDir, minExpectedBytes)) return stagingDir
        stagingDir.listFiles()?.forEach { child ->
            if (child.isDirectory && hasValidVoskStructure(child, minExpectedBytes)) {
                return child
            }
        }
        return null
    }

    private fun readAvailableStorageBytes(dir: File): Long {
        return runCatching {
            val stat = StatFs(dir.absolutePath)
            if (stat.blockCountLong > 0L && stat.totalBytes > 0L) {
                stat.availableBytes
            } else if (dir.totalSpace > 0L) {
                dir.usableSpace
            } else {
                Long.MAX_VALUE
            }
        }.getOrElse {
            if (dir.totalSpace > 0L) dir.usableSpace else Long.MAX_VALUE
        }
    }

    private fun ensureDirectories() {
        rootDir.mkdirs()
        voskBaseDir.mkdirs()
        tmpBaseDir.mkdirs()
        recoverInterruptedBackups()
    }

    private fun recoverInterruptedBackups() {
        val entries = voskBaseDir.listFiles() ?: return
        for (entry in entries) {
            if (!entry.isDirectory || !entry.name.endsWith(".bak")) continue
            val modelId = entry.name.removeSuffix(".bak")
            if (modelId.isBlank()) continue
            val targetDir = File(voskBaseDir, modelId)
            val matchingSpec: VoskSpeechModelSpec? = VoskModelCatalog.officialModels.values.firstOrNull { it.modelId == modelId }
            val minBytes: Long = matchingSpec?.let { productionMinExpectedBytes(it) } ?: 0L
            val targetValid = targetDir.exists() && hasValidVoskStructure(targetDir, minBytes)
            if (targetValid) {
                entry.deleteRecursively()
            } else if (hasValidVoskStructure(entry, minBytes)) {
                targetDir.deleteRecursively()
                if (!entry.renameTo(targetDir)) {
                    if (entry.copyRecursively(targetDir, overwrite = true) && hasValidVoskStructure(targetDir, minBytes)) {
                        entry.deleteRecursively()
                    }
                }
            }
        }
    }

    private suspend fun readCatalogRecords(): Map<String, JSONObject> = catalogMutex.withLock {
        if (!catalogFile.exists()) return@withLock emptyMap()
        return@withLock runCatching {
            val rootJson = JSONObject(catalogFile.readText(Charsets.UTF_8))
            val speechJson = rootJson.optJSONObject("speech_models") ?: return@runCatching emptyMap()
            buildMap {
                val keys = speechJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    speechJson.optJSONObject(key)?.let { put(key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private suspend fun persistSpeechRecord(langCode: String, spec: VoskSpeechModelSpec) {
        catalogMutex.withLock {
            ensureDirectories()
            val rootJson = runCatching {
                if (catalogFile.exists()) JSONObject(catalogFile.readText(Charsets.UTF_8)) else JSONObject()
            }.getOrElse { JSONObject() }

            val speechJson = rootJson.optJSONObject("speech_models") ?: JSONObject()
            speechJson.put(
                langCode,
                JSONObject().apply {
                    put("modelId", spec.modelId)
                    put("version", spec.version)
                    put("installedAtMs", System.currentTimeMillis())
                }
            )
            rootJson.put("speech_models", speechJson)
            catalogFile.writeText(rootJson.toString(2), Charsets.UTF_8)
        }
    }

    private suspend fun removeCatalogRecord(langCode: String) {
        catalogMutex.withLock {
            if (!catalogFile.exists()) return@withLock
            runCatching {
                val rootJson = JSONObject(catalogFile.readText(Charsets.UTF_8))
                val speechJson = rootJson.optJSONObject("speech_models") ?: return@runCatching
                speechJson.remove(langCode)
                rootJson.put("speech_models", speechJson)
                catalogFile.writeText(rootJson.toString(2), Charsets.UTF_8)
            }
        }
    }

    private inline fun updateLanguageState(
        language: SubtitleTranslationLanguage,
        transform: (LanguageModelResourceState) -> LanguageModelResourceState
    ) {
        _languageStates.update { current ->
            val existing = current[language] ?: LanguageModelResourceState(language = language)
            current + (language to transform(existing))
        }
    }
}
