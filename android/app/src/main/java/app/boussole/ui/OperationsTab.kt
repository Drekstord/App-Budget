package app.boussole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.boussole.core.domain.Classifier
import app.boussole.core.domain.Confidence
import app.boussole.core.domain.Merchant
import app.boussole.core.domain.Transaction
import app.boussole.core.domain.TransactionSource
import app.boussole.core.domain.TransactionStatus
import app.boussole.core.domain.VaultData
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private enum class Filter { ALL, PENDING, EXPENSES, INCOME }

@Composable
fun OperationsTab(ctx: Ctx, data: VaultData) {
    var filter by remember { mutableStateOf(Filter.ALL) }
    var search by remember { mutableStateOf("") }
    val shown = data.transactions
        .filter { t ->
            when (filter) {
                Filter.ALL -> t.status != TransactionStatus.IGNORED
                Filter.PENDING -> t.status == TransactionStatus.TO_VALIDATE
                Filter.EXPENSES -> t.amountCents < 0 && t.status != TransactionStatus.IGNORED
                Filter.INCOME -> t.amountCents > 0 && t.status != TransactionStatus.IGNORED
            }
        }
        .filter { search.isBlank() || it.label.contains(search, ignoreCase = true) || it.note.contains(search, ignoreCase = true) }
        .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.createdAt })

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ctx.nav.open(Route.Edit(null)) }, modifier = Modifier.weight(1f).testTag("ops_add")) { Text("+ Ajouter") }
                    OutlinedButton(onClick = { ctx.nav.open(Route.Import) }, modifier = Modifier.weight(1f)) { Text("Importer") }
                }
                Choices(
                    listOf(Filter.ALL to "Toutes", Filter.PENDING to "À valider", Filter.EXPENSES to "Dépenses", Filter.INCOME to "Revenus"),
                    filter, { filter = it }, tagPrefix = "ops_filter",
                )
                TextInput(search, { search = it }, "Rechercher")
            }
        }
        if (shown.isEmpty()) item { Muted("Aucune opération.") }
        items(shown, key = { it.id }) { t ->
            TransactionRow(data, t) { ctx.nav.open(Route.Edit(t.id)) }
            HorizontalDivider()
        }
    }
}

@Composable
fun TransactionRow(data: VaultData, t: Transaction, onClick: () -> Unit) {
    ClickableRow(onClick, tag = "op_${t.label.take(20)}") {
        Column(Modifier.weight(1f)) {
            Text(t.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Muted(
                shortDate(t.date) + " · " + categoryLabel(data.category(t.categoryId)) +
                    if (t.status == TransactionStatus.TO_VALIDATE) " · à valider" else "",
            )
        }
        AmountText(t.amountCents)
    }
}

// --- Validation ---------------------------------------------------------------------

/**
 * File de validation (CLA-1, CLA-2) : chaque dépense captée arrive avec une
 * catégorie proposée ; valider ou corriger apprend une règle pour la suite.
 */
@Composable
fun ValidationPage(ctx: Ctx, data: VaultData) {
    val pending = data.transactions.filter { it.status == TransactionStatus.TO_VALIDATE }.sortedByDescending { it.date }
    var picking by remember { mutableStateOf<Transaction?>(null) }

    fun validate(t: Transaction, categoryId: String?) = ctx.update { d ->
        val withCategory = t.copy(categoryId = categoryId, status = TransactionStatus.VALIDATED)
        val updated = d.copy(transactions = d.transactions.map { if (it.id == t.id) withCategory else it })
        if (categoryId != null) Classifier.learn(updated, t.label, categoryId) else updated
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("Vérifie montant et catégorie, puis valide. Une correction devient une règle pour les prochaines fois.")
                val confident = pending.filter { t ->
                    t.categoryId != null && Classifier.suggest(t.label, t.amountCents, data).confidence == Confidence.HIGH
                }
                if (confident.size > 1) {
                    OutlinedButton(onClick = { confident.forEach { validate(it, it.categoryId) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Valider les ${confident.size} propositions sûres")
                    }
                }
            }
        }
        if (pending.isEmpty()) item { InfoCard("Tout est à jour", "Aucune dépense à valider. 👏") }
        items(pending.size, key = { pending[it].id }) { index ->
            val t = pending[index]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("pending_${index}_label"))
                            Muted("${shortDate(t.date)} · ${accountLabel(data.account(t.accountId))} · ${sourceLabel(t.source)}")
                        }
                        AmountText(t.amountCents, strong = true)
                    }
                    TextButton(onClick = { picking = t }, modifier = Modifier.testTag("pending_${index}_category")) {
                        Text("Catégorie : " + categoryLabel(data.category(t.categoryId)))
                    }
                    t.suggestionReason?.let { Muted(it) }
                    t.sourceText?.let { Muted("Texte d'origine : $it") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { validate(t, t.categoryId) },
                            enabled = t.categoryId != null,
                            modifier = Modifier.weight(1f).testTag("pending_${index}_validate"),
                        ) { Text("Valider") }
                        OutlinedButton(onClick = { ctx.nav.open(Route.Edit(t.id)) }, modifier = Modifier.weight(1f)) { Text("Modifier") }
                        TextButton(
                            onClick = { ctx.update { d -> d.copy(transactions = d.transactions.map { if (it.id == t.id) it.copy(status = TransactionStatus.IGNORED) else it }) } },
                            modifier = Modifier.testTag("pending_${index}_ignore"),
                        ) { Text("Ignorer") }
                    }
                }
            }
        }
    }
    picking?.let { t ->
        CategoryPicker(data, income = if (t.amountCents > 0) null else false, onPick = { id ->
            picking = null
            validate(t, id)
        }, onDismiss = { picking = null })
    }
}

fun sourceLabel(source: TransactionSource): String = when (source) {
    TransactionSource.MANUAL -> "saisie"
    TransactionSource.NOTIFICATION -> "notification"
    TransactionSource.IMPORT -> "relevé"
    TransactionSource.RECEIPT -> "ticket"
}

// --- Saisie et modification ------------------------------------------------------------

@Composable
fun EditTransactionPage(ctx: Ctx, data: VaultData, route: Route.Edit) {
    val existing = route.id?.let { id -> data.transactions.firstOrNull { it.id == id } }
    val base = existing ?: route.draft
    var income by remember { mutableStateOf((base?.amountCents ?: -1) > 0) }
    var amount by remember { mutableStateOf(base?.let { editable(kotlin.math.abs(it.amountCents)) } ?: "") }
    var label by remember { mutableStateOf(base?.label ?: "") }
    var date by remember { mutableStateOf(base?.date?.let { fullDate(LocalDate.parse(it)) } ?: fullDate(ctx.today)) }
    var accountId by remember { mutableStateOf(base?.accountId ?: data.accounts.firstOrNull()?.id) }
    var categoryId by remember { mutableStateOf(base?.categoryId) }
    var note by remember { mutableStateOf(base?.note ?: "") }
    var pickCategory by remember { mutableStateOf(false) }
    var pickAccount by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val cents = parseEuros(amount)?.takeIf { it > 0 }
    val parsedDate = parseUserDate(date)
    // Suggestion en direct pendant la saisie du libellé.
    val suggestion = if (categoryId == null && label.isNotBlank() && cents != null) {
        Classifier.suggest(label, if (income) cents else -cents, data)
    } else null

    Page {
        Choices(listOf(false to "Dépense", true to "Revenu"), income, { income = it }, tagPrefix = "edit_kind")
        AmountInput(amount, { amount = it }, "Montant (€)", tag = "edit_amount")
        TextInput(label, { label = it }, "Libellé ou marchand", tag = "edit_label")
        TextInput(date, { date = it }, "Date (JJ/MM/AAAA)", tag = "edit_date", isError = parsedDate == null)
        NavRow("Compte", accountLabel(data.account(accountId))) { pickAccount = true }
        NavRow(
            "Catégorie",
            categoryLabel(data.category(categoryId ?: suggestion?.categoryId)) +
                if (categoryId == null && suggestion?.categoryId != null) " (proposée)" else "",
            tag = "edit_category",
        ) { pickCategory = true }
        TextInput(note, { note = it }, "Note (facultative)", singleLine = false)
        base?.sourceText?.let { Muted("Texte d'origine : $it") }

        PrimaryButton(if (existing?.status == TransactionStatus.TO_VALIDATE) "Valider" else "Enregistrer", {
            val finalCategory = categoryId ?: suggestion?.categoryId
            val signed = if (income) cents!! else -cents!!
            val saved = (existing ?: Transaction(
                id = UUID.randomUUID().toString(),
                createdAt = Instant.now().toString(),
                date = parsedDate.toString(),
                amountCents = signed,
                label = label.trim(),
                source = route.draft?.source ?: TransactionSource.MANUAL,
                sourceText = route.draft?.sourceText,
            )).copy(
                date = parsedDate.toString(),
                amountCents = signed,
                label = label.trim().ifEmpty { if (income) "Revenu" else "Dépense" },
                accountId = accountId,
                categoryId = finalCategory,
                note = note,
                status = TransactionStatus.VALIDATED,
            )
            ctx.update { d ->
                val list = if (existing != null) d.transactions.map { if (it.id == saved.id) saved else it } else d.transactions + saved
                val updated = d.copy(transactions = list)
                if (finalCategory != null && Merchant.key(saved.label).isNotEmpty()) Classifier.learn(updated, saved.label, finalCategory) else updated
            }
            ctx.nav.back()
        }, enabled = cents != null && parsedDate != null, tag = "edit_save")

        if (existing != null) {
            SecondaryButton("Supprimer", { confirmDelete = true })
        }
    }

    if (pickCategory) {
        CategoryPicker(data, income = if (income) null else false, onPick = {
            categoryId = it
            pickCategory = false
        }, onDismiss = { pickCategory = false })
    }
    if (pickAccount) {
        AccountPicker(data, onPick = {
            accountId = it
            pickAccount = false
        }, onDismiss = { pickAccount = false })
    }
    if (confirmDelete && existing != null) {
        Confirm("Supprimer l'opération ?", "« ${existing.label} » sera supprimée.", "Supprimer", onConfirm = {
            confirmDelete = false
            ctx.update { d -> d.copy(transactions = d.transactions.filter { it.id != existing.id }) }
            ctx.nav.back()
        }, onDismiss = { confirmDelete = false })
    }
}

/** Champ numérique entier simple. */
@Composable
fun IntInput(value: String, onChange: (String) -> Unit, label: String, tag: String = "") =
    TextInput(value.filter(Char::isDigit), { onChange(it.filter(Char::isDigit).take(3)) }, label, tag, KeyboardType.Number)
