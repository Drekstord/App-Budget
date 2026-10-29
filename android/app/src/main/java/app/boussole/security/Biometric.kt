package app.boussole.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

sealed interface BiometricResult {
    /** Le Cipher est désormais autorisé par la puce pour une opération. */
    class Success(val cipher: Cipher) : BiometricResult
    data object Cancelled : BiometricResult
    data class Error(val message: String) : BiometricResult
}

enum class BiometricAvailability { READY, NONE_ENROLLED, UNAVAILABLE }

fun biometricAvailability(context: Context): BiometricAvailability =
    when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.READY
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
        else -> BiometricAvailability.UNAVAILABLE
    }

/**
 * Demande l'empreinte et lie le résultat au [cipher] (CryptoObject) : ce n'est
 * pas un simple « oui/non » contournable, c'est la puce qui débloque la clé.
 */
suspend fun FragmentActivity.authenticate(cipher: Cipher, title: String, subtitle: String): BiometricResult =
    suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (continuation.isActive) {
                        continuation.resume(
                            if (authorized != null) BiometricResult.Success(authorized)
                            else BiometricResult.Error("Authentification non liée à la clé."),
                        )
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    continuation.resume(
                        if (cancelled) BiometricResult.Cancelled else BiometricResult.Error(errString.toString()),
                    )
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Annuler")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }
