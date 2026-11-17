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
import com.videohatak.player.core.vault.security.PinVerifyResult
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultPinHasher
import com.videohatak.player.core.vault.security.VaultSecurityPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        biometricHelper.promptBiometric(
            activity = activity,
            title = "الخزنة المشفرة",
            subtitle = "يرجى تأكيد هويتك لفتح مقاطع الفيديو المحمية",
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

    fun unlockWithPin(
        pin: String,
        nowMs: Long = nowMsProvider(),
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val prefs = securityPreferences
            if (prefs == null) {
                _biometricError.value = "رمز PIN غير مهيأ على هذا الجهاز"
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
                    _biometricError.value = "رمز PIN غير صحيح (المحاولة ${result.failedAttempts})"
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

    fun setVaultPin(pin: String, onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!VaultPinHasher.isValidPinFormat(pin)) {
            onComplete(false, "يجب أن يتكون الرمز من 4 إلى 12 رقمًا فقط")
            return
        }
        viewModelScope.launch {
            val saved = securityPreferences?.setPin(pin) == true
            repository.recordActivity(nowMsProvider())
            if (saved) {
                onComplete(true, "تم حفظ رمز PIN للخزنة بأمان (تشفير PBKDF2)")
            } else {
                onComplete(false, "تعذر حفظ رمز PIN")
            }
        }
    }

    fun removeVaultPin(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            securityPreferences?.clearPin()
            repository.recordActivity(nowMsProvider())
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
            runCatching { beforeDeleteVideoHook?.invoke(videoId) }
            val deleted = repository.deleteVaultVideo(videoId)
            if (!deleted && repository.vaultError.value.isNullOrBlank()) {
                _actionError.value = "تعذر حذف الفيديو من الخزنة"
            }
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
