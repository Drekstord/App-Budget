package app.boussole.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import app.boussole.core.domain.Money
import app.boussole.core.domain.Transaction
import app.boussole.core.domain.TransactionStatus
import app.boussole.security.HardwareKey
import app.boussole.vault.VaultService
import app.boussole.vault.VaultState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Écran d'accueil de la phase 1 : il sert à éprouver le socle (coffre, verrouillage, sauvegarde). */
@Composable
fun HomeScreen(state: VaultState.Unlocked, vault: VaultService, activity: FragmentActivity) {
    val scope = rememberCoroutineScope()
    var exportError by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    Screen {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Boussole", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = vault::lock) { Text("Verrouiller") }
        }
        InfoCard(
            "Coffre ouvert",
            "Chiffré, sans accès Internet. Clé protégée par : " + when (state.keyLevel) {
                HardwareKey.Level.STRONGBOX -> "la puce StrongBox."
                HardwareKey.Level.TEE -> "l'environnement sécurisé (TEE)."
                HardwareKey.Level.OTHER -> "niveau inconnu."
            },
        )

        Text("Comptes", style = MaterialTheme.typography.titleMedium)
        state.data.accounts.forEach { LabeledRow(it.institution, it.name) }

        HorizontalDivider()
        Text("Opérations d'essai", style = MaterialTheme.typography.titleMedium)
        AddExpenseForm { amount, label ->
            scope.launch {
                vault.update { data ->
                    val transaction = Transaction(
                        id = UUID.randomUUID().toString(),
                        createdAt = Instant.now().toString(),
                        date = LocalDate.now().toString(),
                        amountCents = -amount,
                        label = label,
                        accountId = data.accounts.firstOrNull()?.id,
                    )
                    data.copy(transactions = listOf(transaction) + data.transactions)
                }
            }
        }
        state.data.transactions.forEach { transaction ->
            TransactionRow(transaction) {
                scope.launch {
                    vault.update { data ->
                        data.copy(transactions = data.transactions.map { t ->
                            if (t.id != transaction.id) t else t.copy(
                                status = if (t.status == TransactionStatus.VALIDATED) TransactionStatus.TO_VALIDATE else TransactionStatus.VALIDATED,
                            )
                        })
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Sauvegarde", style = MaterialTheme.typography.titleMedium)
        Text(
            "Le fichier est chiffré avant de quitter l'app. Envoie-le vers Proton Drive : seule ta phrase de " +
                "récupération (sur papier) peut l'ouvrir. L'app se verrouille pendant le partage.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ErrorText(exportError)
        PrimaryButton("Exporter une sauvegarde chiffrée", {
            scope.launch {
                exporting = true
                exportError = null
                try {
                    val file = vault.exportBackup()
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.backups", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(send, "Envoyer la sauvegarde chiffrée"))
                } catch (e: Exception) {
                    exportError = "Échec de l'export : ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    exporting = false
                }
            }
        }, busy = exporting)
    }
}

@Composable
private fun AddExpenseForm(onAdd: (amountCents: Long, label: String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val cents = Money.parse(amount)?.takeIf { it > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Montant") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Libellé") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
        }
        PrimaryButton("Ajouter une dépense fictive", {
            onAdd(checkNotNull(cents), label.trim().ifEmpty { "Dépense" })
            amount = ""
            label = ""
        }, enabled = cents != null)
    }
}

@Composable
private fun TransactionRow(transaction: Transaction, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(transaction.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                transaction.date + if (transaction.status == TransactionStatus.TO_VALIDATE) " · à valider (toucher)" else " · validée",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Money.format(transaction.amountCents), style = MaterialTheme.typography.bodyLarge)
    }
}
