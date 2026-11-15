package com.videohatak.player.core.vault.biometric

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

data class BiometricCapability(
    val isAvailable: Boolean,
    val allowedAuthenticators: Int,
    val requiresNegativeButton: Boolean,
    val unavailableReasonArabic: String? = null
)

object VaultBiometricSupport {
    fun resolveCapability(
        sdkInt: Int,
        checkAuth: (Int) -> Int
    ): BiometricCapability {
        if (sdkInt >= Build.VERSION_CODES.R) {
            val strongOrCred = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val strongCredResult = safeCheckAuth(checkAuth, strongOrCred)
            if (strongCredResult == BiometricManager.BIOMETRIC_SUCCESS) {
                return BiometricCapability(
                    isAvailable = true,
                    allowedAuthenticators = strongOrCred,
                    requiresNegativeButton = false
                )
            }

            val weakOrCred = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val weakCredResult = safeCheckAuth(checkAuth, weakOrCred)
            if (weakCredResult == BiometricManager.BIOMETRIC_SUCCESS) {
                return BiometricCapability(
                    isAvailable = true,
                    allowedAuthenticators = weakOrCred,
                    requiresNegativeButton = false
                )
            }

            return BiometricCapability(
                isAvailable = false,
                allowedAuthenticators = 0,
                requiresNegativeButton = false,
                unavailableReasonArabic = mapStatusToArabicMessage(strongCredResult)
            )
        }

        // API 24..29 (including Android 9 / API 28 and Android 10 / API 29):
        // Avoid combining BIOMETRIC_STRONG with DEVICE_CREDENTIAL which is unsupported/throws on API 28-29.
        val weakResult = safeCheckAuth(checkAuth, BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (weakResult == BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricCapability(
                isAvailable = true,
                allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK,
                requiresNegativeButton = true
            )
        }

        val strongResult = safeCheckAuth(checkAuth, BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (strongResult == BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricCapability(
                isAvailable = true,
                allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG,
                requiresNegativeButton = true
            )
        }

        return BiometricCapability(
            isAvailable = false,
            allowedAuthenticators = 0,
            requiresNegativeButton = true,
            unavailableReasonArabic = mapStatusToArabicMessage(weakResult)
        )
    }

    private fun safeCheckAuth(checkAuth: (Int) -> Int, authenticators: Int): Int {
        return try {
            checkAuth(authenticators)
        } catch (_: Exception) {
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        }
    }

    fun mapStatusToArabicMessage(status: Int): String {
        return when (status) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "لا توجد بصمة مسجّلة على الجهاز حاليًا؛ يمكنك استخدام رمز PIN الاحتياطي."
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
                "التحقق الحيوي غير مدعوم على هذا الإصدار أو الجهاز؛ استخدم رمز PIN."
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "مستشعر البصمة غير متاح حاليًا؛ حاول مجددًا أو استخدم رمز PIN."
            else ->
                "البصمة غير مفعّلة أو غير متاحة على هذا الجهاز حاليًا."
        }
    }
}

class VaultBiometricHelper(
    private val context: Context,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) {

    fun getCapability(): BiometricCapability {
        val biometricManager = BiometricManager.from(context)
        return VaultBiometricSupport.resolveCapability(
            sdkInt = sdkIntProvider(),
            checkAuth = { authenticators -> biometricManager.canAuthenticate(authenticators) }
        )
    }

    fun canAuthenticate(): Boolean = getCapability().isAvailable

    fun promptBiometric(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val capability = getCapability()
        if (!capability.isAvailable) {
            onError(
                capability.unavailableReasonArabic
                    ?: "البصمة غير مفعّلة أو غير متاحة على هذا الجهاز حاليًا."
            )
            return
        }

        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        val message = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                                "تم إلغاء التحقق الحيوي. يمكنك المحاولة مجددًا أو استخدام رمز PIN."
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                                "تم تقييد التحقق الحيوي مؤقتًا لكثرة المحاولات؛ استخدم رمز PIN أو قفل الجهاز."
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                                "البصمة غير مفعّلة أو غير متاحة على هذا الجهاز حاليًا."
                            else -> errString.toString().ifBlank { "تعذر إتمام التحقق الحيوي." }
                        }
                        onError(message)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onError("لم تتطابق البصمة، حاول مرة أخرى")
                    }
                }
            )

            val promptBuilder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(capability.allowedAuthenticators)

            if (capability.requiresNegativeButton) {
                promptBuilder.setNegativeButtonText("إلغاء")
            }

            prompt.authenticate(promptBuilder.build())
        } catch (_: Exception) {
            onError("تعذر تشغيل التحقق الحيوي على هذا الجهاز؛ استخدم رمز PIN.")
        }
    }
}
