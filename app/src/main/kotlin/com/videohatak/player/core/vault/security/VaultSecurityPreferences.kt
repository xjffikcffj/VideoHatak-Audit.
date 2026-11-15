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
    MINUTES_5(300_000L, "بعد 5 دقائق");

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

object VaultPinHasher {
    private const val ITERATIONS = 65536
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private val secureRandom = SecureRandom()

    fun isValidPinFormat(pin: String): Boolean {
        return pin.length in 4..12 && pin.all { it.isDigit() }
    }

    fun hashPin(pin: String): Pair<String, String> {
        require(isValidPinFormat(pin)) { "PIN must be 4 to 12 numeric digits" }
        val salt = ByteArray(SALT_BYTES).apply { secureRandom.nextBytes(this) }
        val derived = derive(pin, salt)
        return try {
            VaultBase64.encodeNoWrap(derived) to VaultBase64.encodeNoWrap(salt)
        } finally {
            derived.fill(0)
        }
    }

    fun verifyPin(pin: String, storedHashBase64: String, storedSaltBase64: String): Boolean {
        if (pin.isEmpty() || storedHashBase64.isBlank() || storedSaltBase64.isBlank()) return false
        return try {
            val expectedHash = VaultBase64.decode(storedHashBase64)
            val salt = VaultBase64.decode(storedSaltBase64)
            val candidateHash = derive(pin, salt)
            try {
                MessageDigest.isEqual(candidateHash, expectedHash)
            } finally {
                candidateHash.fill(0)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
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

class VaultSecurityPreferences(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.vaultSecurityDataStore)

    private val pinHashKey = stringPreferencesKey("vault_pin_hash_v1")
    private val pinSaltKey = stringPreferencesKey("vault_pin_salt_v1")
    private val autoLockKey = stringPreferencesKey("vault_auto_lock_timeout_v1")
    private val failedAttemptsKey = intPreferencesKey("vault_pin_failed_attempts_v1")
    private val lockoutUntilKey = longPreferencesKey("vault_pin_lockout_until_ms_v1")

    val hasPin: Flow<Boolean> = dataStore.data.map { prefs ->
        !prefs[pinHashKey].isNullOrBlank() && !prefs[pinSaltKey].isNullOrBlank()
    }

    val autoLockTimeout: Flow<VaultAutoLockTimeout> = dataStore.data.map { prefs ->
        VaultAutoLockTimeout.fromName(prefs[autoLockKey])
    }

    suspend fun hasPinConfigured(): Boolean {
        val prefs = dataStore.data.first()
        return !prefs[pinHashKey].isNullOrBlank() && !prefs[pinSaltKey].isNullOrBlank()
    }

    suspend fun setPin(pin: String): Boolean {
        if (!VaultPinHasher.isValidPinFormat(pin)) return false
        val (hash, salt) = VaultPinHasher.hashPin(pin)
        dataStore.edit { prefs ->
            prefs[pinHashKey] = hash
            prefs[pinSaltKey] = salt
            prefs[failedAttemptsKey] = 0
            prefs[lockoutUntilKey] = 0L
        }
        return true
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

        val isMatch = VaultPinHasher.verifyPin(pin, storedHash, storedSalt)
        if (isMatch) {
            if (currentAttempts > 0 || lockoutUntil > 0L) {
                resetPinLockout()
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

    suspend fun clearPin() {
        dataStore.edit { prefs ->
            prefs.remove(pinHashKey)
            prefs.remove(pinSaltKey)
            prefs.remove(failedAttemptsKey)
            prefs.remove(lockoutUntilKey)
        }
    }

    suspend fun setAutoLockTimeout(timeout: VaultAutoLockTimeout) {
        dataStore.edit { prefs ->
            prefs[autoLockKey] = timeout.name
        }
    }

    suspend fun getAutoLockTimeoutSnapshot(): VaultAutoLockTimeout {
        val prefs = dataStore.data.first()
        return VaultAutoLockTimeout.fromName(prefs[autoLockKey])
    }
}
