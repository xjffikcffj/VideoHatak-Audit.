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
import kotlinx.coroutines.SupervisorJob
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
    private val streamDownloader: ((url: String, destFile: File, expectedBytes: Long, onProgress: suspend (Long, Long) -> Unit) -> Unit)? = null
) {
    constructor(context: Context) : this(
        rootDir = File(context.applicationContext.filesDir, "offline_models"),
        mlKitBridge = DefaultMlKitModelBridge()
    )

    internal constructor(
        testRootDir: File,
        mlKitBridge: MlKitModelBridge,
        streamDownloader: ((url: String, destFile: File, expectedBytes: Long, onProgress: suspend (Long, Long) -> Unit) -> Unit)? = null
    ) : this(
        rootDir = testRootDir,
        mlKitBridge = mlKitBridge,
        streamDownloader = streamDownloader
    )

    companion object {
        private const val CATALOG_FILE_NAME = "catalog.json"
        private const val VOSK_DIR_NAME = "vosk"
        private const val TMP_DIR_NAME = "tmp"

        @Volatile
        private var instance: OfflineModelManager? = null

        fun getInstance(context: Context): OfflineModelManager {
            return instance ?: synchronized(this) {
                instance ?: OfflineModelManager(context.applicationContext).also { instance = it }
            }
        }

        internal fun hasValidVoskStructure(dir: File): Boolean {
            if (!dir.exists() || !dir.isDirectory) return false
            val amFinalMdl = File(dir, "am/final.mdl")
            val confDir = File(dir, "conf")
            val mfccConf = File(confDir, "mfcc.conf")
            val modelConf = File(confDir, "model.conf")
            return amFinalMdl.exists() && amFinalMdl.length() > 0L &&
                confDir.exists() && confDir.isDirectory &&
                (mfccConf.exists() || modelConf.exists())
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogMutex = Mutex()
    private val speechDownloadMutex = Mutex()
    private val mlKitDownloadMutex = Mutex()

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
        val downloadedMlKitCodes = runCatching { mlKitBridge.getDownloadedLanguageCodes() }
            .getOrDefault(emptySet())

        val nextMap = SubtitleTranslationLanguage.TARGET_LANGUAGES.associateWith { lang ->
            val current = _languageStates.value[lang] ?: LanguageModelResourceState(language = lang)
            val spec = VoskModelCatalog.specFor(lang)
            val isSpeechDownloading = activeSpeechJobs[lang]?.isActive == true
            val isMlKitDownloading = activeMlKitJobs[lang]?.isActive == true

            val (speechStatus, installedVer, diskBytes) = if (isSpeechDownloading) {
                Triple(
                    OfflineModelDownloadStatus.DOWNLOADING,
                    current.speechInstalledVersion,
                    current.speechDiskBytes
                )
            } else if (spec == null) {
                Triple(OfflineModelDownloadStatus.NOT_DOWNLOADED, null, 0L)
            } else {
                inspectVoskModelOnDisk(spec, persistedRecords[lang.code])
            }

            val mlKitStatus = when {
                isMlKitDownloading -> OfflineModelDownloadStatus.DOWNLOADING
                lang == SubtitleTranslationLanguage.ENGLISH -> OfflineModelDownloadStatus.READY
                lang.mlKitCode != null && lang.mlKitCode in downloadedMlKitCodes -> OfflineModelDownloadStatus.READY
                current.translationStatus == OfflineModelDownloadStatus.ERROR -> OfflineModelDownloadStatus.ERROR
                else -> OfflineModelDownloadStatus.NOT_DOWNLOADED
            }

            current.copy(
                speechSpec = spec,
                speechStatus = speechStatus,
                speechInstalledVersion = installedVer,
                speechDiskBytes = diskBytes,
                speechDownloadProgress = if (speechStatus == OfflineModelDownloadStatus.READY) 1f else if (isSpeechDownloading) current.speechDownloadProgress else 0f,
                isSpeechInUse = (speechUseCounts[lang] ?: 0) > 0,
                translationStatus = mlKitStatus,
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

    private fun inspectVoskModelOnDisk(
        spec: VoskSpeechModelSpec,
        record: JSONObject?
    ): SpeechDiskInspection {
        val modelDir = File(voskBaseDir, spec.modelId)
        val hasDir = modelDir.exists() && modelDir.isDirectory
        val validStructure = hasDir && hasValidVoskStructure(modelDir)
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
                    status = OfflineModelDownloadStatus.NEEDS_UPDATE,
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
        if (hasValidVoskStructure(modelDir)) {
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
                if (hasValidVoskStructure(modelDir)) {
                    persistSpeechRecord(language.code, spec)
                    refreshAllStates()
                    return@withContext ModelOperationResult(true, "نموذج ${language.arabicName} الصوتي جاهز بالفعل")
                }

                // Check available disk space in internal app storage
                val availableBytes = runCatching {
                    val stat = StatFs(rootDir.absolutePath)
                    stat.availableBytes
                }.getOrDefault(Long.MAX_VALUE)

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

                updateLanguageState(language) {
                    it.copy(
                        speechStatus = OfflineModelDownloadStatus.DOWNLOADING,
                        speechDownloadProgress = 0.02f,
                        speechDownloadedBytes = 0L,
                        speechTotalBytes = spec.expectedZipBytes,
                        speechErrorMessage = null
                    )
                }

                try {
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
                            fallbackTotalBytes = spec.expectedZipBytes
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
                    updateLanguageState(language) {
                        it.copy(
                            speechStatus = OfflineModelDownloadStatus.DOWNLOADING,
                            speechDownloadProgress = 0.90f
                        )
                    }

                    stagingDir.mkdirs()
                    extractZipSafely(tempZip, stagingDir)
                    val locatedModelRoot = locateVoskRootInExtracted(stagingDir)
                        ?: throw IOException("أرشيف النموذج الصوتي غير مكتمل أو تالف (ملفات الصوت am/conf غير موجودة)")

                    if (!hasValidVoskStructure(locatedModelRoot)) {
                        throw IOException("فشل التحقق من سلامة ملفات نموذج ${language.arabicName}")
                    }

                    modelDir.deleteRecursively()
                    if (!locatedModelRoot.renameTo(modelDir)) {
                        locatedModelRoot.copyRecursively(modelDir, overwrite = true)
                    }
                    if (!hasValidVoskStructure(modelDir)) {
                        throw IOException("تعذر تثبيت ملفات نموذج ${language.arabicName} داخل ذاكرة التطبيق")
                    }

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
                    modelDir.deleteRecursively()
                    updateLanguageState(language) {
                        it.copy(
                            speechStatus = OfflineModelDownloadStatus.NOT_DOWNLOADED,
                            speechDownloadProgress = 0f,
                            speechDownloadedBytes = 0L,
                            speechErrorMessage = null
                        )
                    }
                    throw ce
                } catch (e: Exception) {
                    modelDir.deleteRecursively()
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
                    activeSpeechJobs.remove(language)
                }
            }
        }

    fun cancelSpeechModelDownload(language: SubtitleTranslationLanguage) {
        activeSpeechJobs.remove(language)?.cancel()
        updateLanguageState(language) {
            it.copy(
                speechStatus = OfflineModelDownloadStatus.NOT_DOWNLOADED,
                speechDownloadProgress = 0f,
                speechDownloadedBytes = 0L,
                speechErrorMessage = null
            )
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

    suspend fun downloadTranslationModelBlocking(language: SubtitleTranslationLanguage): ModelOperationResult =
        withContext(Dispatchers.IO) {
            if (language == SubtitleTranslationLanguage.ENGLISH) {
                updateLanguageState(language) {
                    it.copy(translationStatus = OfflineModelDownloadStatus.READY, translationErrorMessage = null)
                }
                return@withContext ModelOperationResult(true, "نموذج الإنجليزية مدمج وجاهز دائمًا")
            }
            val mlKitCode = language.mlKitCode
                ?: return@withContext ModelOperationResult(false, "هذه اللغة غير مدعومة في الترجمة النصية المحلية")

            mlKitDownloadMutex.withLock {
                updateLanguageState(language) {
                    it.copy(
                        translationStatus = OfflineModelDownloadStatus.DOWNLOADING,
                        translationErrorMessage = null
                    )
                }
                try {
                    mlKitBridge.downloadLanguageModel(mlKitCode)
                    updateLanguageState(language) {
                        it.copy(
                            translationStatus = OfflineModelDownloadStatus.READY,
                            translationErrorMessage = null
                        )
                    }
                    ModelOperationResult(true, "تم تنزيل حزمة ترجمة ${language.arabicName} للعمل دون إنترنت")
                } catch (ce: CancellationException) {
                    updateLanguageState(language) {
                        it.copy(translationStatus = OfflineModelDownloadStatus.NOT_DOWNLOADED)
                    }
                    throw ce
                } catch (e: Exception) {
                    val msg = "تعذر تنزيل حزمة ترجمة ${language.arabicName}: ${e.localizedMessage ?: "تحقق من اتصال الإنترنت"}"
                    updateLanguageState(language) {
                        it.copy(
                            translationStatus = OfflineModelDownloadStatus.ERROR,
                            translationErrorMessage = msg
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
        onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        var currentUrl = urlString
        var redirects = 0
        var conn: HttpURLConnection? = null

        while (true) {
            coroutineContext.ensureActive()
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/zip, application/octet-stream, */*")
            }
            conn = connection
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || redirects >= 5) {
                    throw IOException("فشل تتبع رابط التنزيل (رمز $code)")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("استجابة خادم التنزيل غير صالحة (رمز الحالة: $code)")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: fallbackTotalBytes
            try {
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var copied = 0L
                        var lastUiUpdateBytes = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied - lastUiUpdateBytes >= 256 * 1024 || copied >= totalBytes) {
                                lastUiUpdateBytes = copied
                                onProgress(copied, totalBytes)
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

    private suspend fun extractZipSafely(zipFile: File, targetDir: File) {
        val canonicalTarget = targetDir.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            val buffer = ByteArray(32 * 1024)
            var entry = zis.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                val outFile = File(targetDir, entry.name)
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalTarget + File.separator)) {
                    throw SecurityException("تم رفض مسار غير آمن داخل الأرشيف: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = zis.read(buffer)
                            if (count == -1) break
                            fos.write(buffer, 0, count)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun locateVoskRootInExtracted(stagingDir: File): File? {
        if (hasValidVoskStructure(stagingDir)) return stagingDir
        stagingDir.listFiles()?.forEach { child ->
            if (child.isDirectory && hasValidVoskStructure(child)) {
                return child
            }
        }
        return null
    }

    private fun ensureDirectories() {
        rootDir.mkdirs()
        voskBaseDir.mkdirs()
        tmpBaseDir.mkdirs()
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
