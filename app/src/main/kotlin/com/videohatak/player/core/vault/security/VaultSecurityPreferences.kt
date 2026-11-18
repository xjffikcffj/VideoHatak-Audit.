package com.videohatak.player.core.vault.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.videohatak.player.core.vault.crypto.VaultBase64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

val Context.vaultSecurityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "videohatak_vault_security"
)

enum class VaultAutoLockTimeout(
    val timeoutMs: Long,
    val titleArabic: String
) {
    IMMEDIATE(0L, "فوري عند مغادرة التطبيق أو الخزنة"),
    SECONDS_30(30_000L, "بعد 30 ثانية من عدم النشاط"),
    MINUTE_1(60_000L, "بعد دقيقة واحدة"),
    MINUTES_5(300_000L, "بعد 5 دقائق"),
    MINUTES_10(600_000L, "بعد 10 دقائق");

    fun shouldAutoLockOnBackground(): Boolean = this == IMMEDIATE

    fun isInactivityExpired(lastActivityMs: Long, nowMs: Long): Boolean {
        if (timeoutMs <= 0L) return false
        if (lastActivityMs <= 0L) return false
        return (nowMs - lastActivityMs) >= timeoutMs
    }

    companion object {
        fun fromName(name: String?): VaultAutoLockTimeout {
            return entries.firstOrNull { it.name == name } ?: IMMEDIATE
        }
    }
}

data class PinHashRecord(
    val hashBase64: String,
    val saltBase64: String,
    val algorithm: String
)

object VaultPinHasher {
    private const val ITERATIONS = 65536
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    const val ALGORITHM_SHA256 = "PBKDF2WithHmacSHA256"
    const val ALGORITHM_SHA1 = "PBKDF2WithHmacSHA1"

    private val secureRandom = SecureRandom()

    const val MIN_PASSCODE_LENGTH = 4
    const val MAX_PASSCODE_LENGTH = 128

    fun isValidPinFormat(pin: String): Boolean {
        return pin.length in MIN_PASSCODE_LENGTH..MAX_PASSCODE_LENGTH &&
            pin.any { !it.isWhitespace() } &&
            pin.none { it == '\n' || it == '\r' }
    }

    fun isAlgorithmSupported(algorithm: String): Boolean {
        return try {
            SecretKeyFactory.getInstance(algorithm)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun selectPreferredAlgorithm(
        isSupported: (String) -> Boolean = ::isAlgorithmSupported
    ): String {
        return if (isSupported(ALGORITHM_SHA256)) ALGORITHM_SHA256 else ALGORITHM_SHA1
    }

    fun hashPinWithAlgorithm(
        pin: String,
        preferredAlgorithm: String = selectPreferredAlgorithm()
    ): PinHashRecord {
        require(isValidPinFormat(pin)) { "Passcode must be 4 to 128 characters and not blank" }
        val salt = ByteArray(SALT_BYTES).apply { secureRandom.nextBytes(this) }
        var effectiveAlgorithm = preferredAlgorithm
        val derived = try {
            derive(pin, salt, effectiveAlgorithm)
        } catch (e: Exception) {
            if (effectiveAlgorithm != ALGORITHM_SHA1) {
                effectiveAlgorithm = ALGORITHM_SHA1
                derive(pin, salt, effectiveAlgorithm)
            } else {
                throw e
            }
        }
        return try {
            PinHashRecord(
                hashBase64 = VaultBase64.encodeNoWrap(derived),
                saltBase64 = VaultBase64.encodeNoWrap(salt),
                algorithm = effectiveAlgorithm
            )
        } finally {
            derived.fill(0)
        }
    }

    fun hashPin(pin: String): Pair<String, String> {
        val record = hashPinWithAlgorithm(pin)
        return record.hashBase64 to record.saltBase64
    }

    fun verifyPin(
        pin: String,
        storedHashBase64: String,
        storedSaltBase64: String,
        algorithm: String = selectPreferredAlgorithm()
    ): Boolean {
        if (pin.isEmpty() || storedHashBase64.isBlank() || storedSaltBase64.isBlank()) return false
        return try {
            val expectedHash = VaultBase64.decode(storedHashBase64)
            val salt = VaultBase64.decode(storedSaltBase64)
            val candidateHash = derive(pin, salt, algorithm)
            try {
                MessageDigest.isEqual(candidateHash, expectedHash)
            } finally {
                candidateHash.fill(0)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun derive(pin: String, salt: ByteArray, algorithm: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(algorithm)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

sealed interface PinVerifyResult {
    data object Success : PinVerifyResult
    data class InvalidPin(val failedAttempts: Int) : PinVerifyResult
    data class LockedOut(val remainingMs: Long, val failedAttempts: Int) : PinVerifyResult
}

sealed interface PinChangeResult {
    data object Success : PinChangeResult
    data object InvalidNewPinFormat : PinChangeResult
    data class InvalidCurrentPin(val failedAttempts: Int) : PinChangeResult
    data class LockedOut(val remainingMs: Long, val failedAttempts: Int) : PinChangeResult
    data object StorageFailure : PinChangeResult
}

object VaultPinLockoutPolicy {
    const val LOCKOUT_THRESHOLD = 3

    fun computeLockoutDurationMs(failedAttempts: Int): Long {
        return when {
            failedAttempts < LOCKOUT_THRESHOLD -> 0L
            failedAttempts == 3 -> 30_000L
            failedAttempts == 4 -> 60_000L
            else -> 300_000L
        }
    }
}

interface VaultSecurityBackupStore {
    val autoLockTimeout: Flow<VaultAutoLockTimeout>
    suspend fun setAutoLockTimeout(timeout: VaultAutoLockTimeout)
}

class VaultSecurityPreferences(
    private val dataStore: DataStore<Preferences>
) : VaultSecurityBackupStore {
    constructor(context: Context) : this(context.vaultSecurityDataStore)

    private val pinHashKey = stringPreferencesKey("vault_pin_hash_v1")
    private val pinSaltKey = stringPreferencesKey("vault_pin_salt_v1")
    private val pinAlgoKey = stringPreferencesKey("vault_pin_algo_v1")
    private val autoLockKey = stringPreferencesKey("vault_auto_lock_timeout_v1")
    private val failedAttemptsKey = intPreferencesKey("vault_pin_failed_attempts_v1")
    private val lockoutUntilKey = longPreferencesKey("vault_pin_lockout_until_ms_v1")

    val hasPin: Flow<Boolean> = dataStore.data.map { prefs ->
        !prefs[pinHashKey].isNullOrBlank() && !prefs[pinSaltKey].isNullOrBlank()
    }

    override val autoLockTimeout: Flow<VaultAutoLockTimeout> = dataStore.data.map { prefs ->
        VaultAutoLockTimeout.fromName(prefs[autoLockKey])
    }

    suspend fun hasPinConfigured(): Boolean {
        val prefs = dataStore.data.first()
        return !prefs[pinHashKey].isNullOrBlank() && !prefs[pinSaltKey].isNullOrBlank()
    }

    suspend fun setPin(pin: String): Boolean {
        if (!VaultPinHasher.isValidPinFormat(pin)) return false
        val record = try {
            VaultPinHasher.hashPinWithAlgorithm(pin)
        } catch (_: Exception) {
            return false
        }
        return try {
            dataStore.edit { prefs ->
                prefs[pinHashKey] = record.hashBase64
                prefs[pinSaltKey] = record.saltBase64
                prefs[pinAlgoKey] = record.algorithm
                prefs[failedAttemptsKey] = 0
                prefs[lockoutUntilKey] = 0L
            }
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getRemainingLockoutMs(nowMs: Long = System.currentTimeMillis()): Long {
        val prefs = dataStore.data.first()
        val lockoutUntil = prefs[lockoutUntilKey] ?: 0L
        return (lockoutUntil - nowMs).coerceAtLeast(0L)
    }

    suspend fun resetPinLockout() {
        dataStore.edit { prefs ->
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilKey] = 0L
        }
    }

    suspend fun verifyPinWithRateLimit(
        pin: String,
        nowMs: Long = System.currentTimeMillis()
    ): PinVerifyResult {
        val prefs = dataStore.data.first()
        val storedHash = prefs[pinHashKey] ?: return PinVerifyResult.InvalidPin(0)
        val storedSalt = prefs[pinSaltKey] ?: return PinVerifyResult.InvalidPin(0)
        val currentAttempts = prefs[failedAttemptsKey] ?: 0
        val lockoutUntil = prefs[lockoutUntilKey] ?: 0L

        if (lockoutUntil > nowMs) {
            return PinVerifyResult.LockedOut(
                remainingMs = lockoutUntil - nowMs,
                failedAttempts = currentAttempts
            )
        }

        val storedAlgo = prefs[pinAlgoKey] ?: VaultPinHasher.ALGORITHM_SHA256
        val isMatch = VaultPinHasher.verifyPin(pin, storedHash, storedSalt, storedAlgo)
        if (isMatch) {
            if (currentAttempts > 0 || lockoutUntil > 0L) {
                try {
                    resetPinLockout()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                }
            }
            return PinVerifyResult.Success
        }

        val newAttempts = currentAttempts + 1
        val lockoutDurationMs = VaultPinLockoutPolicy.computeLockoutDurationMs(newAttempts)
        val newLockoutUntil = if (lockoutDurationMs > 0L) nowMs + lockoutDurationMs else 0L
        dataStore.edit { editPrefs ->
            editPrefs[failedAttemptsKey] = newAttempts
            editPrefs[lockoutUntilKey] = newLockoutUntil
        }

        return if (lockoutDurationMs > 0L) {
            PinVerifyResult.LockedOut(remainingMs = lockoutDurationMs, failedAttempts = newAttempts)
        } else {
            PinVerifyResult.InvalidPin(failedAttempts = newAttempts)
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return verifyPinWithRateLimit(pin) is PinVerifyResult.Success
    }

    suspend fun changePin(
        currentPin: String,
        newPin: String,
        nowMs: Long = System.currentTimeMillis()
    ): PinChangeResult {
        if (!VaultPinHasher.isValidPinFormat(newPin)) {
            return PinChangeResult.InvalidNewPinFormat
        }
        if (!hasPinConfigured()) {
            return if (setPin(newPin)) PinChangeResult.Success else PinChangeResult.StorageFailure
        }
        return when (val verify = verifyPinWithRateLimit(currentPin, nowMs)) {
            is PinVerifyResult.Success -> {
                if (setPin(newPin)) {
                    PinChangeResult.Success
                } else {
                    PinChangeResult.StorageFailure
                }
            }
            is PinVerifyResult.InvalidPin -> PinChangeResult.InvalidCurrentPin(verify.failedAttempts)
            is PinVerifyResult.LockedOut -> PinChangeResult.LockedOut(verify.remainingMs, verify.failedAttempts)
        }
    }

    suspend fun clearPin() {
        dataStore.edit { prefs ->
            prefs.remove(pinHashKey)
            prefs.remove(pinSaltKey)
            prefs.remove(pinAlgoKey)
            prefs.remove(failedAttemptsKey)
            prefs.remove(lockoutUntilKey)
        }
    }

    override suspend fun setAutoLockTimeout(timeout: VaultAutoLockTimeout) {
        dataStore.edit { prefs ->
            prefs[autoLockKey] = timeout.name
        }
    }

    suspend fun getAutoLockTimeoutSnapshot(): VaultAutoLockTimeout {
        val prefs = dataStore.data.first()
        return VaultAutoLockTimeout.fromName(prefs[autoLockKey])
    }
}
