package app.boussole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.boussole.core.crypto.InvalidPhrase
import app.boussole.core.crypto.RecoveryPhrase
import app.boussole.security.BiometricAvailability
import app.boussole.security.BiometricResult
import app.boussole.security.authenticate
import app.boussole.security.biometricAvailability
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/** Choix (ou changement) du mot de passe, avec confirmation. */
@Composable
fun ChooseSecretStep(title: String, subtitle: String, onValid: (String) -> Unit, onBack: () -> Unit) {
    var secret by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        error = secretProblem(secret) ?: if (secret != confirmation) "Les deux saisies ne correspondent pas." else null
        if (error == null) onValid(secret)
    }
    Screen {
        Title(title, subtitle)
        InfoCard(
            "Conseil",
            "Laisse Proton Pass générer et retenir un mot de passe long. Il ne suffit pas à lui seul : " +
                "il faudra aussi ton empreinte, sur ce téléphone précis.",
        )
        SecretField(secret, { secret = it }, "Mot de passe ou code", isNewPassword = true, tag = "secret_new")
        SecretField(confirmation, { confirmation = it }, "Confirmation", isNewPassword = true, tag = "secret_confirm", onDone = submit)
        ErrorText(error)
        PrimaryButton("Continuer", submit, enabled = secret.isNotEmpty() && confirmation.isNotEmpty(), tag = "secret_submit")
        SecondaryButton("Retour", onBack)
    }
}

/** Affiche les 24 mots à recopier sur papier. */
@Composable
fun ShowPhraseStep(words: List<String>, onNext: () -> Unit, onBack: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Screen {
        Title("Ta phrase de récupération", "La seule façon de retrouver tes données si tu perds ce téléphone ou si tes empreintes changent.")
        InfoCard(
            "Sur papier, uniquement",
            "Recopie ces 24 mots à la main, dans l'ordre. Ne les photographie pas et ne les stocke JAMAIS chez " +
                "Proton (Pass, Drive, mail) ni dans aucun cloud : tes sauvegardes y sont, et cette phrase les ouvre.",
            Tone.DANGER,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (column in 0 until 2) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in column * 12 until column * 12 + 12) {
                        Text(
                            "${i + 1}. ${words[i]}",
                            Modifier.testTag("phrase_word_$i"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().testTag("phrase_copied").toggleable(value = copied, onValueChange = { copied = it }, role = Role.Checkbox),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = copied, onCheckedChange = null)
            Text("J'ai recopié les 24 mots sur papier.")
        }
        PrimaryButton("Continuer", onNext, enabled = copied, tag = "phrase_next")
        SecondaryButton("Retour", onBack)
    }
}

/** Vérifie 3 mots tirés au hasard, pour s'assurer que la copie papier est juste. */
@Composable
fun VerifyPhraseStep(words: List<String>, onVerified: () -> Unit, onBack: () -> Unit) {
    val positions = remember { (0 until RecoveryPhrase.WORD_COUNT).shuffled().take(3).sorted() }
    val answers = remember { mutableStateListOf("", "", "") }
    var error by remember { mutableStateOf<String?>(null) }
    Screen {
        Title("Vérifions ta copie", "Saisis les mots demandés en lisant ta feuille.")
        positions.forEachIndexed { i, position ->
            OutlinedTextField(
                value = answers[i],
                onValueChange = { answers[i] = it },
                label = { Text("Mot n° ${position + 1}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("verify_word_$position"),
            )
        }
        ErrorText(error)
        PrimaryButton("Vérifier", {
            val ok = positions.indices.all { RecoveryPhrase.sameWord(answers[it], words[positions[it]]) }
            if (ok) {
                onVerified()
            } else {
                error = "Au moins un mot ne correspond pas : vérifie ta copie."
            }
        }, tag = "verify_submit")
        SecondaryButton("Revoir la phrase", onBack)
    }
}

/** Saisie de la phrase complète ; [check] rend une erreur à afficher, ou null si elle est acceptée. */
@Composable
fun EnterPhraseStep(title: String, subtitle: String, check: (RecoveryPhrase) -> String?, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Screen {
        Title(title, subtitle)
        PhraseField(text) { text = it }
        ErrorText(error)
        PrimaryButton("Vérifier la phrase", {
            error = try {
                check(RecoveryPhrase.parse(text))
            } catch (e: InvalidPhrase) {
                e.message
            }
        }, enabled = text.isNotBlank(), tag = "phrase_submit")
        SecondaryButton("Retour", onBack)
    }
}

/**
 * Étape finale commune : crée la clé matérielle, demande l'empreinte, puis
 * exécute [action] avec le Cipher autorisé (chiffrement de l'enveloppe).
 */
@Composable
fun BiometricStep(
    activity: FragmentActivity,
    title: String,
    explanation: String,
    buttonLabel: String,
    prepare: () -> Cipher,
    action: suspend (Cipher) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val availability = biometricAvailability(LocalContext.current)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Screen {
        Title(title, explanation)
        when (availability) {
            BiometricAvailability.READY -> Unit
            BiometricAvailability.NONE_ENROLLED -> InfoCard(
                "Aucune empreinte enregistrée",
                "Ajoute une empreinte dans Paramètres → Sécurité et confidentialité → Biométrie, puis reviens ici.",
                Tone.WARNING,
            )
            BiometricAvailability.UNAVAILABLE -> InfoCard(
                "Biométrie forte indisponible",
                "Ce téléphone ne propose pas de capteur biométrique de classe 3 utilisable.",
                Tone.DANGER,
            )
        }
        ErrorText(error)
        PrimaryButton(buttonLabel, {
            scope.launch {
                busy = true
                error = null
                try {
                    when (val result = activity.authenticate(prepare(), "Boussole", "Pose ton doigt pour sceller ton coffre")) {
                        is BiometricResult.Success -> action(result.cipher)
                        BiometricResult.Cancelled -> Unit
                        is BiometricResult.Error -> error = result.message
                    }
                } catch (e: Exception) {
                    error = "Échec : ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    busy = false
                }
            }
        }, enabled = availability == BiometricAvailability.READY, busy = busy, tag = "biometric_action")
        SecondaryButton("Retour", onBack)
    }
}
