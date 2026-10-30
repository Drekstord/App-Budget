package app.boussole.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import app.boussole.core.backup.BackupCodec
import app.boussole.core.backup.InvalidBackup
import app.boussole.core.backup.RestoredBackup
import app.boussole.core.crypto.RecoveryPhrase
import app.boussole.vault.VaultService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Welcome { HOME, CREATE, RESTORE }

@Composable
fun WelcomeFlow(vault: VaultService, activity: FragmentActivity) {
    var screen by remember { mutableStateOf(Welcome.HOME) }
    when (screen) {
        Welcome.HOME -> Screen {
            Title("Boussole", "Ton budget, ton cap. Sur ce téléphone et nulle part ailleurs.")
            InfoCard(
                "Ce qui protège tes données",
                "• L'app n'a pas accès à Internet : rien ne peut sortir.\n" +
                    "• Tout est chiffré ; l'ouverture exige ton empreinte ET ton mot de passe.\n" +
                    "• Une phrase de 24 mots, à garder en lieu sûr, permet de tout récupérer.",
            )
            PrimaryButton("Créer mon coffre", { screen = Welcome.CREATE }, tag = "welcome_create")
            SecondaryButton("Restaurer une sauvegarde", { screen = Welcome.RESTORE })
        }
        Welcome.CREATE -> CreateFlow(vault, activity, onCancel = { screen = Welcome.HOME })
        Welcome.RESTORE -> RestoreFlow(vault, activity, onCancel = { screen = Welcome.HOME })
    }
}

@Composable
private fun CreateFlow(vault: VaultService, activity: FragmentActivity, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf("") }
    val phrase = remember { RecoveryPhrase.generate() }
    when (step) {
        0 -> ChooseSecretStep(
            "Choisis ton mot de passe",
            "Au moins 12 caractères, ou un code d'au moins 6 chiffres.",
            onValid = { password = it; step = 1 },
            onBack = onCancel,
        )
        1 -> ShowPhraseStep(phrase.words, onNext = { step = 2 }, onBack = { step = 0 })
        2 -> VerifyPhraseStep(phrase.words, onVerified = { step = 3 }, onBack = { step = 1 })
        else -> BiometricStep(
            activity,
            title = "Dernière étape : ton empreinte",
            explanation = "Une clé est créée dans la puce sécurisée du téléphone. Elle ne s'utilise qu'avec ton empreinte " +
                "et ne peut pas être copiée.",
            buttonLabel = "Créer mon coffre",
            prepare = vault::prepareNewHardwareKey,
            action = { cipher -> vault.create(password, phrase, cipher) },
            onBack = { step = 2 },
        )
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)

@Composable
private fun RestoreFlow(vault: VaultService, activity: FragmentActivity, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var fileDate by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf<RestoredBackup?>(null) }
    var password by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw InvalidBackup("Fichier illisible.")
            val created = Instant.parse(BackupCodec.readHeader(bytes).createdAt)
            file = bytes
            fileDate = dateFormat.format(created.atZone(ZoneId.systemDefault()))
            fileError = null
        } catch (e: Exception) {
            file = null
            fileError = if (e is InvalidBackup) e.message else "Ce fichier n'est pas une sauvegarde valide."
        }
    }

    when (step) {
        0 -> Screen {
            Title("Restaurer une sauvegarde", "Choisis le fichier .boussole (depuis Proton Drive par exemple).")
            PrimaryButton("Choisir le fichier", { picker.launch(arrayOf("*/*")) })
            fileDate?.let { InfoCard("Sauvegarde trouvée", "Créée le $it.") }
            ErrorText(fileError)
            PrimaryButton("Continuer", { step = 1 }, enabled = file != null)
            SecondaryButton("Retour", onCancel)
        }
        1 -> EnterPhraseStep(
            "Ta phrase de récupération",
            "Elle seule peut ouvrir la sauvegarde.",
            check = { phrase ->
                try {
                    restored = vault.readBackup(checkNotNull(file), phrase)
                    step = 2
                    null
                } catch (e: InvalidBackup) {
                    e.message
                }
            },
            onBack = { step = 0 },
        )
        2 -> ChooseSecretStep(
            "Nouveau mot de passe",
            "Il protégera le coffre sur ce téléphone.",
            onValid = { password = it; step = 3 },
            onBack = { step = 1 },
        )
        else -> BiometricStep(
            activity,
            title = "Ton empreinte",
            explanation = "Une nouvelle clé matérielle scelle le coffre restauré sur ce téléphone.",
            buttonLabel = "Restaurer",
            prepare = vault::prepareNewHardwareKey,
            action = { cipher -> vault.restore(checkNotNull(restored), password, cipher) },
            onBack = { step = 2 },
        )
    }
}
