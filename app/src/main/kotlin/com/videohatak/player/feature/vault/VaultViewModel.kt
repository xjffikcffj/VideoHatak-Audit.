package com.videohatak.player.feature.vault

import android.content.Context
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultRepository
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
}

class VaultViewModel(
    private val repository: VaultRepository,
    private val biometricHelper: VaultBiometricHelper,
    private val securityPreferences: VaultSecurityPreferences? = null,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    beforeDeleteVideo: (suspend (Long) -> Unit)? = null
) : ViewModel() {

    val vaultVideos: StateFlow<List<VaultVideo>> = repository.vaultVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val videos: StateFlow<List<VaultVideo>> = vaultVideos

    val vaultCount: StateFlow<Int> = repository.vaultCount

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    val hasPinConfigured: StateFlow<Boolean> = securityPreferences?.hasPin
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false)

    val autoLockTimeout: StateFlow<VaultAutoLockTimeout> = securityPreferences?.autoLockTimeout
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VaultAutoLockTimeout.IMMEDIATE)
        ?: MutableStateFlow(VaultAutoLockTimeout.IMMEDIATE)

    private val _actionError = MutableStateFlow<String?>(null)
    val vaultError: StateFlow<String?> = combine(repository.vaultError, _actionError) { repoErr, actionErr ->
        actionErr ?: repoErr
    }.stateIn(viewModelScope, SharingStarted.Eagerly, repository.vaultError.value)

    private var beforeDeleteVideoHook: (suspend (Long) -> Unit)? = beforeDeleteVideo

    private val _importState = MutableStateFlow<VaultImportState>(VaultImportState.Idle)
    val importState: StateFlow<VaultImportState> = _importState.asStateFlow()

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
            when (val changeResult = prefs.changePin(currentPin, newPin, nowMsProvider())) {
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
        deleteOriginal: Boolean = false,
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

            val result = repository.importVideo(
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

            if (result != null) {
                _importState.value = VaultImportState.Success
                refresh()
                if (!deleteOriginal) {
                    _events.tryEmit(VaultUiEvent.ShowMessage("تمت إضافة الفيديو إلى الخزنة بنجاح"))
                }
                onComplete(true)
            } else {
                _importState.value = if (isImportCancelled) {
                    VaultImportState.Idle
                } else {
                    VaultImportState.Error("فشل تشفير واستيراد الفيديو إلى الخزنة")
                }
                onComplete(false)
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
            deleteOriginal = false,
            onComplete = onComplete
        )
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

    fun deleteVideo(videoId: Long) {
        viewModelScope.launch {
            _actionError.value = null
            try {
                beforeDeleteVideoHook?.invoke(videoId)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
            val deleted = repository.deleteVaultVideo(videoId)
            if (deleted) {
                _events.tryEmit(VaultUiEvent.ShowMessage("تمت إزالة الفيديو من الخزنة بنجاح"))
            } else if (repository.vaultError.value.isNullOrBlank()) {
                _actionError.value = "تعذر إزالة الفيديو من الخزنة"
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
            for (videoId in videoIds) {
                try {
                    beforeDeleteVideoHook?.invoke(videoId)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
                if (repository.deleteVaultVideo(videoId)) {
                    succeeded++
                } else {
                    failed++
                }
            }
            if (failed > 0 && repository.vaultError.value.isNullOrBlank()) {
                _actionError.value = "تمت إزالة $succeeded وتعذر إزالة $failed من الخزنة"
            }
            if (succeeded > 0) {
                val msg = when {
                    failed == 0 && succeeded == 1 -> "تمت إزالة الفيديو من الخزنة بنجاح"
                    failed == 0 -> "تمت إزالة $succeeded فيديو من الخزنة بنجاح"
                    else -> "تمت إزالة $succeeded فيديو من الخزنة، وتعذر إزالة $failed"
                }
                _events.tryEmit(VaultUiEvent.ShowMessage(msg))
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
