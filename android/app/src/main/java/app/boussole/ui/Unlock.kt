package app.boussole.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import app.boussole.security.BiometricResult
import app.boussole.security.authenticate
import app.boussole.vault.UnlockResult
import app.boussole.vault.VaultService
import app.boussole.vault.VaultState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LockedFlow(state: VaultState.Locked, vault: VaultService, activity: FragmentActivity) {
    var recovering by remember { mutableStateOf(false) }
    if (recovering || state.keyInvalidated) {
        RecoveryFlow(
            vault,
            activity,
            invalidated = state.keyInvalidated,
            onCancel = { recovering = false },
        )
    } else {
        UnlockScreen(state, vault, activity, onForgot = { recovering = true })
    }
}

@Composable
private fun UnlockScreen(state: VaultState.Locked, vault: VaultService, activity: FragmentActivity, onForgot: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.lockedUntil) {
        while (System.currentTimeMillis() < state.lockedUntil) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    val waitSeconds = ((state.lockedUntil - now).coerceAtLeast(0) + 999) / 1_000

    val submit: () -> Unit = {
        scope.launch {
            busy = true
            error = null
            try {
                val cipher = vault.prepareUnlock() ?: return@launch
                when (val auth = activity.authenticate(cipher, "Butin", "Pose ton doigt pour ouvrir ton coffre")) {
                    is BiometricResult.Success -> {
                        error = when (val result = vault.unlock(password, auth.cipher)) {
                            UnlockResult.Success -> null
                            is UnlockResult.WrongPassword -> "Mot de passe incorrect (${result.failures} échec(s) d'affilée)."
                            is UnlockResult.TooEarly -> "Patiente avant un nouvel essai."
                            UnlockResult.KeyInvalidated -> null
                        }
                    }
                    BiometricResult.Cancelled -> Unit
                    is BiometricResult.Error -> error = auth.message
                }
            } catch (e: Exception) {
                error = "Échec : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    Screen {
        Title("Coffre verrouillé", "Mot de passe, puis empreinte.")
        SecretField(password, { password = it }, "Mot de passe ou code", isNewPassword = false, tag = "secret_unlock", onDone = submit)
        if (waitSeconds > 0) {
            InfoCard(
                "Trop d'échecs",
                "Nouvel essai possible dans ${formatWait(waitSeconds)}.",
                Tone.WARNING,
            )
        }
        ErrorText(error)
        PrimaryButton("Déverrouiller", submit, enabled = password.isNotEmpty() && waitSeconds == 0L, busy = busy, tag = "unlock_submit")
        SecondaryButton("Mot de passe oublié : utiliser ma phrase de récupération", onForgot, tag = "unlock_forgot")
    }
}

private fun formatWait(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3_600 -> "${seconds / 60} min ${seconds % 60} s"
    else -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
}

/**
 * Rouvre le coffre avec la phrase de récupération, puis le rescelle avec un
 * nouveau mot de passe et une nouvelle clé matérielle (empreintes modifiées,
 * mot de passe oublié).
 */
@Composable
private fun RecoveryFlow(vault: VaultService, activity: FragmentActivity, invalidated: Boolean, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var dek by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    when (step) {
        0 -> EnterPhraseStep(
            title = if (invalidated) "Tes empreintes ont changé" else "Récupération",
            subtitle = if (invalidated) {
                "Par sécurité, l'ajout d'une empreinte a invalidé la clé matérielle. Ta phrase de récupération rouvre le coffre."
            } else {
                "Ta phrase de récupération rouvre le coffre ; tu choisiras ensuite un nouveau mot de passe."
            },
            check = { phrase ->
                val recovered = vault.checkRecoveryPhrase(phrase)
                if (recovered == null) {
                    "Cette phrase n'ouvre pas ce coffre."
                } else {
                    dek = recovered
                    step = 1
                    null
                }
            },
            onBack = onCancel,
        )
        1 -> ChooseSecretStep(
            "Nouveau mot de passe",
            "Il remplace l'ancien.",
            onValid = { password = it; step = 2 },
            onBack = { step = 0 },
        )
        else -> BiometricStep(
            activity,
            title = "Ton empreinte",
            explanation = "Une nouvelle clé matérielle remplace l'ancienne.",
            buttonLabel = "Rouvrir mon coffre",
            prepare = vault::prepareNewHardwareKey,
            action = { cipher -> vault.rekey(checkNotNull(dek), password, cipher) },
            onBack = { step = 1 },
        )
    }
}
