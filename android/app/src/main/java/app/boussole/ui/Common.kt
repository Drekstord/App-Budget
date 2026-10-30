package app.boussole.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.boussole.core.domain.Account
import app.boussole.core.domain.Category
import app.boussole.core.domain.Family
import app.boussole.core.domain.Money
import app.boussole.core.domain.VaultData
import app.boussole.core.importer.StatementImport
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- Mise en forme --------------------------------------------------------------

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
private val FULL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

fun shortDate(date: LocalDate): String = SHORT_DATE.format(date)
fun shortDate(iso: String): String = shortDate(LocalDate.parse(iso))
fun fullDate(date: LocalDate): String = FULL_DATE.format(date)
fun parseUserDate(input: String): LocalDate? = StatementImport.parseDate(input)

fun euros(cents: Long): String = Money.format(cents)

/** Montant saisi (« 12,50 ») → centimes positifs, ou null. */
fun parseEuros(input: String): Long? = Money.parse(input)?.takeIf { it >= 0 }

/** Centimes → texte modifiable (« 12,50 »). */
fun editable(cents: Long): String = if (cents == 0L) "" else "%d,%02d".format(cents / 100, kotlin.math.abs(cents % 100))

fun familyLabel(family: Family): String = when (family) {
    Family.ESSENTIEL -> "Essentiel"
    Family.PLAISIR -> "Plaisir"
    Family.AVENIR -> "Avenir"
    Family.REVENU -> "Revenus"
    Family.INTERNE -> "Mouvements internes"
}

fun accountLabel(account: Account?): String = account?.let { "${it.institution} · ${it.name}" } ?: "Sans compte"

fun categoryLabel(category: Category?): String = category?.let { "${it.icon} ${it.name}".trim() } ?: "À classer"

// --- Mise en page ----------------------------------------------------------------

/** Page défilante à l'intérieur de la coquille (barres déjà gérées par elle). */
@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AmountText(cents: Long, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        euros(cents),
        modifier = modifier,
        style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        color = if (cents < 0) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
}

/** Jauge de consommation (rouge au-delà de 100 %). */
@Composable
fun Gauge(ratio: Double) {
    val over = ratio > 1.0
    LinearProgressIndicator(
        progress = { ratio.coerceIn(0.0, 1.0).toFloat() },
        modifier = Modifier.fillMaxWidth(),
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun ClickableRow(onClick: () -> Unit, tag: String = "", content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).tagged(tag).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun NavRow(label: String, detail: String? = null, tag: String = "", onClick: () -> Unit) {
    ClickableRow(onClick, tag) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Muted(detail)
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
    HorizontalDivider()
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null, tag: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Muted(detail)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.tagged(tag))
    }
}

/** Choix exclusif sous forme de pastilles défilantes. */
@Composable
fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, tagPrefix: String = "") {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.tagged(if (tagPrefix.isEmpty()) "" else "${tagPrefix}_$i"),
            )
        }
    }
}

// --- Champs ------------------------------------------------------------------------

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    supporting: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().tagged(tag),
    )
}

@Composable
fun AmountInput(value: String, onChange: (String) -> Unit, label: String, tag: String = "", supporting: String? = null) =
    TextInput(
        value, onChange, label, tag, KeyboardType.Decimal,
        supporting = supporting,
        isError = value.isNotBlank() && parseEuros(value) == null,
    )

// --- Sélecteurs -----------------------------------------------------------------------

/** Choix d'une catégorie, groupées par famille. [income] : null = toutes. */
@Composable
fun CategoryPicker(data: VaultData, income: Boolean?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val families = when (income) {
        true -> listOf(Family.REVENU, Family.INTERNE)
        false -> listOf(Family.ESSENTIEL, Family.PLAISIR, Family.AVENIR, Family.INTERNE)
        null -> Family.entries
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Catégorie") },
        text = {
            Column(Modifier.exposeTags().verticalScroll(rememberScrollState())) {
                for (family in families) {
                    val categories = data.categories.filter { it.family == family && !it.archived }
                    if (categories.isEmpty()) continue
                    SectionTitle(familyLabel(family))
                    categories.forEach { category ->
                        ClickableRow({ onPick(category.id) }, tag = "pick_${category.id}") {
                            Text(categoryLabel(category))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun AccountPicker(data: VaultData, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compte") },
        text = {
            Column {
                data.accounts.forEach { account ->
                    ClickableRow({ onPick(account.id) }) { Text(accountLabel(account)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Petite boîte de dialogue de confirmation. */
@Composable
fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, tag: String = "") {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.dialogTag(tag)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
