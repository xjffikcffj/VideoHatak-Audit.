package com.videohatak.player.feature.vault

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.model.VaultHiddenOriginal
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultBatchOriginalDeleteOutcome
import com.videohatak.player.core.vault.repository.VaultDeleteOutcome
import com.videohatak.player.core.vault.repository.VaultImportOutcome
import com.videohatak.player.core.vault.repository.VaultOriginalDeleteOutcome
import com.videohatak.player.core.vault.repository.VaultRepository
import com.videohatak.player.core.vault.repository.VaultRestoreOutcome
import com.videohatak.player.core.vault.security.PinChangeResult
import com.videohatak.player.core.vault.security.PinVerifyResult
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultPinHasher
import com.videohatak.player.core.vault.security.VaultSecurityPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface VaultUiEvent {
    data class ShowMessage(val message: String) : VaultUiEvent
    data object LibraryMediaUpdated : VaultUiEvent
    data class RequestLegacyWritePermission(
        val permission: String,
        val pendingRestoreIds: List<Long>
    ) : VaultUiEvent
    data class RequestOriginalDeleteConsent(
        val intentSender: IntentSender,
        val vaultIds: List<Long>
    ) : VaultUiEvent
    data class RequestLegacyOriginalDeletePermission(
        val permission: String,
        val vaultIds: List<Long>
    ) : VaultUiEvent
}

sealed interface VaultRestoreState {
    data object Idle : VaultRestoreState
    data class Restoring(
        val currentTitle: String = "",
        val completedCount: Int = 0,
        val totalCount: Int = 1
    ) : VaultRestoreState
}

class VaultViewModel(
    private val repository: VaultRepository,
    private val biometricHelper: VaultBiometricHelper,
    private val securityPreferences: VaultSecurityPreferences? = null,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    beforeDeleteVideo: (suspend (Long) -> Unit)? = null
) : ViewModel() {

    @Suppress("UNCHECKED_CAST")
    val vaultVideos: StateFlow<List<VaultVideo>> =
        (repository.vaultVideos as? StateFlow<List<VaultVideo>>)
            ?: repository.vaultVideos.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val videos: StateFlow<List<VaultVideo>> = vaultVideos

    val vaultCount: StateFlow<Int> = repository.vaultCount

    val hiddenOriginalEntries: StateFlow<List<VaultHiddenOriginal>> = repository.hiddenOriginalEntries

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    val hasPinConfigured: StateFlow<Boolean> = securityPreferences?.hasPin
        ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        ?: MutableStateFlow(false)

    val autoLockTimeout: StateFlow<VaultAutoLockTimeout> = securityPreferences?.autoLockTimeout
        ?.stateIn(viewModelScope, SharingStarted.Eagerly, VaultAutoLockTimeout.IMMEDIATE)
        ?: MutableStateFlow(VaultAutoLockTimeout.IMMEDIATE)

    private val _actionError = MutableStateFlow<String?>(null)
    val vaultError: StateFlow<String?> = combine(repository.vaultError, _actionError) { repoErr, actionErr ->
        actionErr ?: repoErr
    }.stateIn(viewModelScope, SharingStarted.Eagerly, repository.vaultError.value)

    private var beforeDeleteVideoHook: (suspend (Long) -> Unit)? = beforeDeleteVideo

    private val _importState = MutableStateFlow<VaultImportState>(VaultImportState.Idle)
    val importState: StateFlow<VaultImportState> = _importState.asStateFlow()

    private val _restoreState = MutableStateFlow<VaultRestoreState>(VaultRestoreState.Idle)
    val restoreState: StateFlow<VaultRestoreState> = _restoreState.asStateFlow()

    private val _biometricError = MutableStateFlow<String?>(null)
    val biometricError: StateFlow<String?> = _biometricError.asStateFlow()

    private val _events = MutableSharedFlow<VaultUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<VaultUiEvent> = _events.asSharedFlow()

    val errorMessage: StateFlow<String?> = biometricError
    val isImporting: StateFlow<Boolean> = importState
        .map { it is VaultImportState.Encrypting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var importJob: Job? = null
    private var inactivityJob: Job? = null
    private var isImportCancelled = false

    init {
        viewModelScope.launch {
            combine(repository.isUnlocked, autoLockTimeout) { unlocked, timeout ->
                unlocked to timeout
            }.collect { (unlocked, timeout) ->
                _isUnlocked.value = unlocked
                if (unlocked && timeout.timeoutMs > 0L) {
                    startInactivityMonitor()
                } else {
                    inactivityJob?.cancel()
                    inactivityJob = null
                }
            }
        }
        refresh()
    }

    private fun startInactivityMonitor() {
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            while (isActive && repository.isUnlocked.value) {
                val timeout = autoLockTimeout.value
                if (timeout.timeoutMs <= 0L) break
                delay(5000L)
                if (!isActive || !repository.isUnlocked.value) break
                val latestTimeout = autoLockTimeout.value
                if (latestTimeout.timeoutMs <= 0L) break
                if (latestTimeout.isInactivityExpired(repository.lastActivityTimestampMs, nowMsProvider())) {
                    lockVault()
                    break
                }
            }
        }
    }

    fun recordUserActivity() {
        repository.recordActivity(nowMsProvider())
    }

    fun onAppBackgrounded() {
        if (repository.isUnlocked.value && autoLockTimeout.value.shouldAutoLockOnBackground()) {
            lockVault()
        }
    }

    fun onVaultTabExited() {
        if (repository.isUnlocked.value && autoLockTimeout.value.shouldAutoLockOnBackground()) {
            lockVault()
        }
    }

    fun checkTimeoutOnForeground(nowMs: Long = nowMsProvider()): Boolean {
        if (!repository.isUnlocked.value) return false
        val expired = autoLockTimeout.value.isInactivityExpired(repository.lastActivityTimestampMs, nowMs)
        if (expired) {
            lockVault()
            return true
        }
        return false
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
        }
    }

    fun canUseBiometrics(): Boolean = biometricHelper.canAuthenticate()

    fun authenticateWithBiometrics(
        activity: FragmentActivity,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        unlockVault(activity, onSuccess, onError)
    }

    fun unlockVault(
        activity: FragmentActivity,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val prefs = securityPreferences
            if (prefs != null && !prefs.hasPinConfigured()) {
                val msg = "أنشئ رمز قفل مستقل للخزنة أولًا قبل تفعيل أو استخدام البصمة"
                _biometricError.value = msg
                onError(msg)
                return@launch
            }
            biometricHelper.promptBiometric(
                activity = activity,
                title = "الخزنة المشفرة",
                subtitle = "تأكيد إضافي بالبصمة (يمكنك استخدام رمز قفل الخزنة)",
                onSuccess = {
                    viewModelScope.launch { securityPreferences?.resetPinLockout() }
                    val now = nowMsProvider()
                    repository.setUnlocked(true)
                    repository.recordActivity(now)
                    _isUnlocked.value = true
                    _biometricError.value = null
                    refresh()
                    onSuccess()
                },
                onError = { err ->
                    _biometricError.value = err
                    onError(err)
                }
            )
        }
    }

    fun unlockWithPin(
        pin: String,
        nowMs: Long = nowMsProvider(),
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val prefs = securityPreferences
            if (prefs == null) {
                _biometricError.value = "رمز قفل الخزنة غير مهيأ على هذا الجهاز"
                onResult(false)
                return@launch
            }
            when (val result = prefs.verifyPinWithRateLimit(pin, nowMs)) {
                is PinVerifyResult.Success -> {
                    repository.setUnlocked(true)
                    repository.recordActivity(nowMs)
                    _isUnlocked.value = true
                    _biometricError.value = null
                    refresh()
                    onResult(true)
                }
                is PinVerifyResult.InvalidPin -> {
                    _biometricError.value = "رمز قفل الخزنة غير صحيح (المحاولة ${result.failedAttempts})"
                    onResult(false)
                }
                is PinVerifyResult.LockedOut -> {
                    val waitSec = ((result.remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                    _biometricError.value = "تم تعليق المحاولة مؤقتًا بعد عدة أخطاء — انتظر $waitSec ثانية"
                    onResult(false)
                }
                is PinVerifyResult.StorageFailure -> {
                    _biometricError.value = "تعذر قراءة بيانات أمان الخزنة من التخزين"
                    onResult(false)
                }
            }
        }
    }

    fun setupInitialVaultPinAndUnlock(
        pin: String,
        nowMs: Long = nowMsProvider(),
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (!VaultPinHasher.isValidPinFormat(pin)) {
            val msg = "يجب أن يتكون رمز قفل الخزنة من 4 أحرف على الأقل (يدعم الحروف العربية والإنجليزية والأرقام والرموز)"
            _biometricError.value = msg
            onComplete(false, msg)
            return
        }
        viewModelScope.launch {
            val prefs = securityPreferences
            if (prefs == null) {
                onComplete(false, "تعذر الوصول إلى إعدادات أمان الخزنة")
                return@launch
            }
            if (prefs.hasPinConfigured()) {
                onComplete(false, "رمز قفل الخزنة مُعيَّن بالفعل، أدخل الرمز لفتح الخزنة")
                return@launch
            }
            val saved = prefs.setPin(pin)
            if (saved) {
                repository.setUnlocked(true)
                repository.recordActivity(nowMs)
                _isUnlocked.value = true
                _biometricError.value = null
                refresh()
                val okMsg = "تم إنشاء رمز قفل الخزنة المستقل بنجاح"
                _events.tryEmit(VaultUiEvent.ShowMessage(okMsg))
                onComplete(true, okMsg)
            } else {
                onComplete(false, "تعذر حفظ رمز قفل الخزنة")
            }
        }
    }

    fun setVaultPin(pin: String, onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!VaultPinHasher.isValidPinFormat(pin)) {
            onComplete(false, "يجب أن يتكون رمز قفل الخزنة من 4 أحرف على الأقل (يدعم الحروف والأرقام والرموز)")
            return
        }
        viewModelScope.launch {
            val prefs = securityPreferences
            if (prefs == null) {
                onComplete(false, "تعذر حفظ رمز قفل الخزنة")
                return@launch
            }
            if (prefs.hasPinConfigured()) {
                onComplete(false, "لتغيير الرمز يجب إدخال رمز قفل الخزنة الحالي أولًا")
                return@launch
            }
            val saved = prefs.setPin(pin)
            repository.recordActivity(nowMsProvider())
            if (saved) {
                val okMsg = "تم حفظ رمز قفل الخزنة بأمان"
                _events.tryEmit(VaultUiEvent.ShowMessage(okMsg))
                onComplete(true, okMsg)
            } else {
                onComplete(false, "تعذر حفظ رمز قفل الخزنة")
            }
        }
    }

    fun changeVaultPin(
        currentPin: String,
        newPin: String,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (!VaultPinHasher.isValidPinFormat(newPin)) {
            onComplete(false, "يجب أن يتكون الرمز الجديد من 4 أحرف على الأقل (يدعم الحروف العربية والإنجليزية والأرقام والرموز)")
            return
        }
        viewModelScope.launch {
            val prefs = securityPreferences
            if (prefs == null) {
                onComplete(false, "تعذر الوصول إلى إعدادات أمان الخزنة")
                return@launch
            }
            val changeResult = try {
                prefs.changePin(currentPin, newPin, nowMsProvider())
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                PinChangeResult.StorageFailure
            }
            when (changeResult) {
                is PinChangeResult.Success -> {
                    repository.recordActivity(nowMsProvider())
                    val okMsg = "تم تغيير رمز قفل الخزنة بنجاح"
                    _events.tryEmit(VaultUiEvent.ShowMessage(okMsg))
                    onComplete(true, okMsg)
                }
                is PinChangeResult.InvalidNewPinFormat -> {
                    onComplete(false, "يجب أن يتكون الرمز الجديد من 4 أحرف على الأقل (يدعم الحروف العربية والإنجليزية والأرقام والرموز)")
                }
                is PinChangeResult.InvalidCurrentPin -> {
                    onComplete(false, "رمز قفل الخزنة الحالي غير صحيح (المحاولة ${changeResult.failedAttempts})")
                }
                is PinChangeResult.LockedOut -> {
                    val waitSec = ((changeResult.remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                    onComplete(false, "تم تعليق المحاولة مؤقتًا بعد عدة أخطاء — انتظر $waitSec ثانية")
                }
                is PinChangeResult.StorageFailure -> {
                    onComplete(false, "تعذر تحديث رمز قفل الخزنة")
                }
            }
        }
    }

    fun removeVaultPin(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            // Vault always enforces an independent PIN; keep current PIN unless explicitly reset safely.
            onComplete()
        }
    }

    fun setAutoLockTimeout(timeout: VaultAutoLockTimeout) {
        viewModelScope.launch {
            securityPreferences?.setAutoLockTimeout(timeout)
            repository.recordActivity(nowMsProvider())
        }
    }

    fun lockVault() {
        inactivityJob?.cancel()
        inactivityJob = null
        repository.setUnlocked(false)
        _isUnlocked.value = false
    }

    fun importVideoFromUri(
        sourceUri: Uri,
        displayName: String? = null,
        deleteOriginal: Boolean = true,
        onComplete: (Boolean) -> Unit = {}
    ) {
        importJob?.cancel()
        isImportCancelled = false
        repository.recordActivity(nowMsProvider())

        importJob = viewModelScope.launch {
            _importState.value = VaultImportState.Encrypting(
                currentBytes = 0L,
                totalBytes = 0L,
                progress = 0f
            )

            when (
                val outcome = repository.importVideoDetailed(
                    sourceUri = sourceUri,
                    displayName = displayName,
                    onProgress = { progress ->
                        _importState.update {
                            if (it is VaultImportState.Encrypting) {
                                it.copy(progress = progress)
                            } else it
                        }
                    },
                    isCancelled = { isImportCancelled }
                )
            ) {
                is VaultImportOutcome.Imported -> {
                    _importState.value = VaultImportState.Success
                    refresh()
                    if (deleteOriginal) {
                        deleteOriginalsInternal(listOf(outcome.video.id), encryptedFailedCount = 0)
                    } else {
                        _events.tryEmit(VaultUiEvent.ShowMessage("تمت إضافة الفيديو إلى الخزنة وإخفاؤه من المكتبة بنجاح"))
                    }
                    onComplete(true)
                }
                is VaultImportOutcome.AlreadyInVault -> {
                    if (outcome.integrityVerified) {
                        _importState.value = VaultImportState.Success
                        refresh()
                        if (deleteOriginal && !outcome.existingVideo.isOriginalDeletedProtected) {
                            deleteOriginalsInternal(listOf(outcome.existingVideo.id), encryptedFailedCount = 0)
                        } else if (!deleteOriginal) {
                            _events.tryEmit(VaultUiEvent.ShowMessage("هذا الفيديو محفوظ ومشفّر مسبقًا داخل الخزنة"))
                        } else {
                            _events.tryEmit(VaultUiEvent.ShowMessage("هذا الفيديو محمي مسبقًا داخل الخزنة الخاصة"))
                        }
                        onComplete(true)
                    } else {
                        _importState.value = VaultImportState.Error(
                            "هذا الفيديو موجود مسبقًا لكن تعذر التحقق من سلامة النسخة المشفرة — لم يتم حذف الأصل"
                        )
                        onComplete(false)
                    }
                }
                VaultImportOutcome.Cancelled -> {
                    _importState.value = VaultImportState.Idle
                    onComplete(false)
                }
                is VaultImportOutcome.Failed -> {
                    _importState.value = if (isImportCancelled) {
                        VaultImportState.Idle
                    } else {
                        VaultImportState.Error(outcome.reason)
                    }
                    onComplete(false)
                }
            }
        }
    }

    fun importVideoFromUri(
        uri: Uri,
        title: String,
        durationMs: Long,
        onComplete: (Boolean) -> Unit = {}
    ) {
        importVideoFromUri(
            sourceUri = uri,
            displayName = title,
            deleteOriginal = true,
            onComplete = onComplete
        )
    }

    fun importVideosToPrivateVault(
        uris: List<Uri>,
        onComplete: () -> Unit = {}
    ) {
        if (uris.isEmpty()) {
            onComplete()
            return
        }
        importJob?.cancel()
        isImportCancelled = false
        repository.recordActivity(nowMsProvider())

        importJob = viewModelScope.launch {
            val total = uris.size
            val vaultIdsToDeleteOriginal = mutableListOf<Long>()
            var alreadyProtectedCount = 0
            var failedCount = 0
            var lastError: String? = null

            for ((index, uri) in uris.withIndex()) {
                if (isImportCancelled) break
                _importState.value = VaultImportState.Encrypting(
                    currentBytes = 0L,
                    totalBytes = 0L,
                    progress = (index.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 0.99f)
                )
                when (
                    val outcome = repository.importVideoDetailed(
                        sourceUri = uri,
                        displayName = null,
                        onProgress = { itemProgress ->
                            val overall = ((index + itemProgress) / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                            _importState.update {
                                if (it is VaultImportState.Encrypting) {
                                    it.copy(progress = overall)
                                } else it
                            }
                        },
                        isCancelled = { isImportCancelled }
                    )
                ) {
                    is VaultImportOutcome.Imported -> {
                        vaultIdsToDeleteOriginal.add(outcome.video.id)
                    }
                    is VaultImportOutcome.AlreadyInVault -> {
                        if (outcome.integrityVerified) {
                            if (outcome.existingVideo.isOriginalDeletedProtected) {
                                alreadyProtectedCount++
                            } else {
                                vaultIdsToDeleteOriginal.add(outcome.existingVideo.id)
                            }
                        } else {
                            failedCount++
                            lastError = "تعذر التحقق من سلامة النسخة الموجودة في الخزنة — لم يتم المساس بالملف الأصلي"
                        }
                    }
                    VaultImportOutcome.Cancelled -> break
                    is VaultImportOutcome.Failed -> {
                        failedCount++
                        lastError = outcome.reason
                    }
                }
            }

            if (isImportCancelled) {
                _importState.value = VaultImportState.Idle
                refresh()
                onComplete()
                return@launch
            }

            if (vaultIdsToDeleteOriginal.isNotEmpty()) {
                _importState.value = VaultImportState.Success
                deleteOriginalsInternal(vaultIdsToDeleteOriginal, encryptedFailedCount = failedCount)
            } else if (alreadyProtectedCount > 0 && failedCount == 0) {
                _importState.value = VaultImportState.Success
                refresh()
                _events.tryEmit(VaultUiEvent.ShowMessage("الفيديو المختار محمي ومشفّر مسبقًا داخل الخزنة الخاصة"))
            } else if (failedCount > 0) {
                _importState.value = VaultImportState.Error(
                    lastError ?: "فشل تشفير ونقل الفيديو إلى الخزنة — بقيت الملفات الأصلية سليمة"
                )
            } else {
                _importState.value = VaultImportState.Idle
            }
            onComplete()
        }
    }

    fun deleteOriginalsForVaultEntries(
        vaultIds: List<Long>,
        encryptedFailedCount: Int = 0
    ) {
        if (vaultIds.isEmpty()) {
            if (encryptedFailedCount > 0) {
                _events.tryEmit(
                    VaultUiEvent.ShowMessage("تعذر تشفير ونقل الفيديو إلى الخزنة — بقيت الملفات الأصلية سليمة")
                )
            }
            return
        }
        viewModelScope.launch {
            deleteOriginalsInternal(vaultIds, encryptedFailedCount)
        }
    }

    private suspend fun deleteOriginalsInternal(
        vaultIds: List<Long>,
        encryptedFailedCount: Int
    ) {
        val outcome = repository.deleteOriginalsForVaultEntries(vaultIds)
        emitBatchOriginalDeleteFeedback(outcome, encryptedFailedCount)
    }

    fun onOriginalDeleteConsentResult(granted: Boolean, vaultIds: List<Long>) {
        if (vaultIds.isEmpty()) return
        viewModelScope.launch {
            val outcome = repository.finalizeOriginalsAfterConsent(vaultIds, granted)
            emitBatchOriginalDeleteFeedback(outcome, encryptedFailedCount = 0)
        }
    }

    fun onLegacyOriginalDeletePermissionResult(granted: Boolean, vaultIds: List<Long>) {
        if (vaultIds.isEmpty()) return
        if (!granted) {
            val msg = "لم يُمنح إذن تعديل التخزين لحذف الأصل — بقيت النسخة المشفرة محمية في الخزنة ويمكنك إعادة المحاولة لاحقًا"
            _actionError.value = msg
            _events.tryEmit(VaultUiEvent.ShowMessage(msg))
            return
        }
        deleteOriginalsForVaultEntries(vaultIds)
    }

    fun retryDeleteOriginal(vaultId: Long) {
        viewModelScope.launch {
            _actionError.value = null
            when (val outcome = repository.deleteOriginalForVaultEntry(vaultId)) {
                is VaultOriginalDeleteOutcome.DeletedVerified -> {
                    _events.tryEmit(VaultUiEvent.LibraryMediaUpdated)
                }
                is VaultOriginalDeleteOutcome.RequiresUserConsent -> {
                    _events.tryEmit(
                        VaultUiEvent.RequestOriginalDeleteConsent(
                            intentSender = outcome.intentSender,
                            vaultIds = listOf(vaultId)
                        )
                    )
                }
                is VaultOriginalDeleteOutcome.RequiresLegacyWritePermission -> {
                    _events.tryEmit(
                        VaultUiEvent.RequestLegacyOriginalDeletePermission(
                            permission = outcome.permission,
                            vaultIds = listOf(vaultId)
                        )
                    )
                }
                is VaultOriginalDeleteOutcome.Failed -> {
                    _actionError.value = outcome.reason
                    _events.tryEmit(VaultUiEvent.ShowMessage(outcome.reason))
                }
            }
        }
    }

    private fun emitBatchOriginalDeleteFeedback(
        outcome: VaultBatchOriginalDeleteOutcome,
        encryptedFailedCount: Int
    ) {
        val deletedCount = outcome.deletedVerifiedIds.size + outcome.alreadyProtectedIds.size
        if (deletedCount > 0 || outcome.awaitingConsentIds.isNotEmpty() || outcome.failedReasons.isNotEmpty()) {
            _events.tryEmit(VaultUiEvent.LibraryMediaUpdated)
        }
        val consentSender = outcome.consentIntentSender
        if (consentSender != null && outcome.awaitingConsentIds.isNotEmpty()) {
            _events.tryEmit(
                VaultUiEvent.RequestOriginalDeleteConsent(
                    intentSender = consentSender,
                    vaultIds = outcome.awaitingConsentIds
                )
            )
            return
        }
        val legacyPerm = outcome.legacyPermissionRequired
        if (legacyPerm != null && outcome.legacyPermissionVaultIds.isNotEmpty()) {
            _events.tryEmit(
                VaultUiEvent.RequestLegacyOriginalDeletePermission(
                    permission = legacyPerm,
                    vaultIds = outcome.legacyPermissionVaultIds
                )
            )
            return
        }

        val failedDeleteCount = outcome.failedReasons.size
        val firstFailure = outcome.failedReasons.values.firstOrNull()
        val msg = when {
            deletedCount > 0 && failedDeleteCount == 0 && encryptedFailedCount == 0 -> null
            deletedCount > 0 && failedDeleteCount > 0 ->
                "تم نقل $deletedCount فيديو بنجاح، وتعذر حذف أصل $failedDeleteCount وبقيت نسختها المشفرة محمية"
            failedDeleteCount > 0 -> {
                _actionError.value = firstFailure
                firstFailure ?: "تم حفظ النسخة المشفرة في الخزنة، وتعذر حذف النسخة الأصلية — يمكنك إعادة المحاولة من داخل الخزنة"
            }
            encryptedFailedCount > 0 ->
                "تعذر تشفير ونقل $encryptedFailedCount فيديو — بقيت الملفات الأصلية سليمة"
            else -> null
        }
        if (msg != null) {
            _events.tryEmit(VaultUiEvent.ShowMessage(msg))
        }
    }

    fun cancelImport() {
        isImportCancelled = true
        importJob?.cancel()
        _importState.value = VaultImportState.Idle
    }

    fun dismissImportState() {
        _importState.value = VaultImportState.Idle
    }

    fun setBeforeDeleteVideoHandler(handler: (suspend (Long) -> Unit)?) {
        beforeDeleteVideoHook = handler
    }

    fun renameVideo(videoId: Long, newTitle: String) {
        viewModelScope.launch {
            _actionError.value = null
            val renamed = repository.renameVideo(videoId, newTitle)
            if (!renamed && repository.vaultError.value.isNullOrBlank()) {
                _actionError.value = "تعذر إعادة تسمية الفيديو داخل الخزنة"
            }
        }
    }

    fun restoreVideoFromVault(
        videoId: Long,
        onComplete: (VaultRestoreOutcome) -> Unit = {}
    ) {
        if (_restoreState.value is VaultRestoreState.Restoring) {
            onComplete(VaultRestoreOutcome.Failed("توجد عملية إزالة من الخزنة قيد التنفيذ حاليًا"))
            return
        }
        viewModelScope.launch {
            _actionError.value = null
            val targetTitle = vaultVideos.value.firstOrNull { it.id == videoId }?.displayName.orEmpty()
            _restoreState.value = VaultRestoreState.Restoring(
                currentTitle = targetTitle,
                completedCount = 0,
                totalCount = 1
            )
            val outcome = try {
                repository.restoreVaultVideo(videoId)
            } finally {
                _restoreState.value = VaultRestoreState.Idle
            }
            when (outcome) {
                is VaultRestoreOutcome.Restored -> {
                    _events.tryEmit(VaultUiEvent.LibraryMediaUpdated)
                    _events.tryEmit(VaultUiEvent.ShowMessage("تمت إزالة الفيديو من الخزنة واستعادته إلى المكتبة بنجاح"))
                }
                is VaultRestoreOutcome.OriginalMissing -> {
                    _actionError.value = outcome.reason
                    _events.tryEmit(VaultUiEvent.ShowMessage(outcome.reason))
                }
                is VaultRestoreOutcome.OriginalChanged -> {
                    _actionError.value = outcome.reason
                    _events.tryEmit(VaultUiEvent.ShowMessage(outcome.reason))
                }
                is VaultRestoreOutcome.RequiresUriPermission -> {
                    _actionError.value = outcome.reason
                    _events.tryEmit(VaultUiEvent.ShowMessage(outcome.reason))
                }
                is VaultRestoreOutcome.RequiresLegacyWritePermission -> {
                    _events.tryEmit(
                        VaultUiEvent.RequestLegacyWritePermission(
                            permission = outcome.permission,
                            pendingRestoreIds = listOf(videoId)
                        )
                    )
                }
                is VaultRestoreOutcome.Failed -> {
                    if (repository.vaultError.value.isNullOrBlank()) {
                        _actionError.value = outcome.reason
                    }
                    _events.tryEmit(VaultUiEvent.ShowMessage(outcome.reason))
                }
            }
            onComplete(outcome)
        }
    }

    fun restoreVideosFromVault(
        videoIds: Set<Long>,
        onComplete: (succeededCount: Int, failedCount: Int) -> Unit = { _, _ -> }
    ) {
        if (videoIds.isEmpty()) {
            onComplete(0, 0)
            return
        }
        if (_restoreState.value is VaultRestoreState.Restoring) {
            onComplete(0, videoIds.size)
            return
        }
        viewModelScope.launch {
            _actionError.value = null
            val orderedIds = videoIds.toList()
            val total = orderedIds.size
            var succeeded = 0
            var failed = 0
            var lastFailureReason: String? = null
            var permissionRequested = false

            try {
                for ((index, videoId) in orderedIds.withIndex()) {
                    val targetTitle = vaultVideos.value.firstOrNull { it.id == videoId }?.displayName.orEmpty()
                    _restoreState.value = VaultRestoreState.Restoring(
                        currentTitle = targetTitle,
                        completedCount = index,
                        totalCount = total
                    )
                    when (val outcome = repository.restoreVaultVideo(videoId)) {
                        is VaultRestoreOutcome.Restored -> {
                            succeeded++
                        }
                        is VaultRestoreOutcome.OriginalMissing -> {
                            failed++
                            lastFailureReason = outcome.reason
                        }
                        is VaultRestoreOutcome.OriginalChanged -> {
                            failed++
                            lastFailureReason = outcome.reason
                        }
                        is VaultRestoreOutcome.RequiresUriPermission -> {
                            failed++
                            lastFailureReason = outcome.reason
                        }
                        is VaultRestoreOutcome.RequiresLegacyWritePermission -> {
                            permissionRequested = true
                            val remaining = orderedIds.drop(index)
                            _events.tryEmit(
                                VaultUiEvent.RequestLegacyWritePermission(
                                    permission = outcome.permission,
                                    pendingRestoreIds = remaining
                                )
                            )
                            break
                        }
                        is VaultRestoreOutcome.Failed -> {
                            failed++
                            lastFailureReason = outcome.reason
                        }
                    }
                }
            } finally {
                _restoreState.value = VaultRestoreState.Idle
            }

            if (succeeded > 0) {
                _events.tryEmit(VaultUiEvent.LibraryMediaUpdated)
            }
            if (!permissionRequested) {
                if (failed > 0) {
                    _actionError.value = lastFailureReason
                        ?: repository.vaultError.value
                        ?: "تمت إزالة $succeeded وتعذر إزالة $failed — بقيت الفيديوهات غير المستعادة محمية في الخزنة"
                }
                if (succeeded > 0) {
                    val msg = when {
                        failed == 0 && succeeded == 1 -> "تمت إزالة الفيديو من الخزنة واستعادته إلى المكتبة بنجاح"
                        failed == 0 -> "تمت إزالة $succeeded فيديو من الخزنة واستعادتها للظهور في المكتبة بنجاح"
                        else -> "تمت إزالة $succeeded فيديو من الخزنة، وتعذر إزالة $failed وبقيت محفوظة في الخزنة"
                    }
                    _events.tryEmit(VaultUiEvent.ShowMessage(msg))
                } else if (failed > 0) {
                    _events.tryEmit(
                        VaultUiEvent.ShowMessage(
                            lastFailureReason ?: "تعذر إعادة إظهار الفيديوهات في المكتبة — بقيت محفوظة داخل الخزنة"
                        )
                    )
                }
            }
            onComplete(succeeded, failed)
        }
    }

    fun onLegacyRestoreWritePermissionResult(granted: Boolean, pendingRestoreIds: List<Long>) {
        if (!granted) {
            val msg = "تم رفض إذن التخزين المطلوب لإعادة إظهار الفيديو في المكتبة — بقي الفيديو محفوظًا داخل الخزنة"
            _actionError.value = msg
            _events.tryEmit(VaultUiEvent.ShowMessage(msg))
            return
        }
        if (pendingRestoreIds.isNotEmpty()) {
            restoreVideosFromVault(pendingRestoreIds.toSet())
        }
    }

    fun deleteVideo(videoId: Long) {
        viewModelScope.launch {
            _actionError.value = null
            try {
                beforeDeleteVideoHook?.invoke(videoId)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
            when (val outcome = repository.deleteVaultVideoDetailed(videoId)) {
                VaultDeleteOutcome.Deleted -> {
                    _events.tryEmit(VaultUiEvent.ShowMessage("تمت إزالة الفيديو من الخزنة بنجاح"))
                }
                is VaultDeleteOutcome.BlockedSoleRemainingCopy -> {
                    _actionError.value = outcome.reason
                    _events.tryEmit(VaultUiEvent.ShowMessage(outcome.reason))
                }
                is VaultDeleteOutcome.Failed -> {
                    if (repository.vaultError.value.isNullOrBlank()) {
                        _actionError.value = outcome.reason
                    }
                    _events.tryEmit(VaultUiEvent.ShowMessage(outcome.reason))
                }
            }
        }
    }

    fun deleteVideos(
        videoIds: Set<Long>,
        onComplete: (succeededCount: Int, failedCount: Int) -> Unit = { _, _ -> }
    ) {
        if (videoIds.isEmpty()) {
            onComplete(0, 0)
            return
        }
        viewModelScope.launch {
            _actionError.value = null
            var succeeded = 0
            var failed = 0
            var lastReason: String? = null
            for (videoId in videoIds) {
                try {
                    beforeDeleteVideoHook?.invoke(videoId)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
                when (val outcome = repository.deleteVaultVideoDetailed(videoId)) {
                    VaultDeleteOutcome.Deleted -> succeeded++
                    is VaultDeleteOutcome.BlockedSoleRemainingCopy -> {
                        failed++
                        lastReason = outcome.reason
                    }
                    is VaultDeleteOutcome.Failed -> {
                        failed++
                        lastReason = outcome.reason
                    }
                }
            }
            if (failed > 0) {
                _actionError.value = lastReason
                    ?: repository.vaultError.value
                    ?: "تم حذف $succeeded وتعذر حذف $failed من الخزنة"
            }
            if (succeeded > 0) {
                val msg = when {
                    failed == 0 && succeeded == 1 -> "تمت إزالة الفيديو من الخزنة بنجاح"
                    failed == 0 -> "تمت إزالة $succeeded فيديو من الخزنة بنجاح"
                    else -> "تمت إزالة $succeeded فيديو من الخزنة، وتعذر حذف $failed"
                }
                _events.tryEmit(VaultUiEvent.ShowMessage(msg))
            } else if (failed > 0 && lastReason != null) {
                _events.tryEmit(VaultUiEvent.ShowMessage(lastReason))
            }
            onComplete(succeeded, failed)
        }
    }

    fun clearError() {
        _biometricError.value = null
        _actionError.value = null
        repository.clearError()
    }

    override fun onCleared() {
        inactivityJob?.cancel()
        inactivityJob = null
        super.onCleared()
    }

    companion object {
        fun provideFactory(
            repository: VaultRepository,
            context: Context
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val biometricHelper = VaultBiometricHelper(context)
                val securityPrefs = VaultSecurityPreferences(context)
                return VaultViewModel(repository, biometricHelper, securityPrefs) as T
            }
        }
    }
}
