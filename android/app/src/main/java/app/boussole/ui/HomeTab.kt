package app.boussole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.boussole.R
import app.boussole.core.advice.Advisor
import app.boussole.core.advice.Level
import app.boussole.core.advice.Severity
import app.boussole.core.domain.Budget
import app.boussole.core.domain.Family
import app.boussole.core.domain.TransactionStatus
import app.boussole.vault.VaultState
import java.time.DayOfWeek

@Composable
fun HomeTab(ctx: Ctx, state: VaultState.Unlocked) {
    val data = state.data
    val today = ctx.today
    val view = remember(data) { Budget.view(data, today) }
    val advice = remember(data) { Advisor.analyze(data, today) }
    val pending = data.transactions.count { it.status == TransactionStatus.TO_VALIDATE }

    Page {
        if (booleanResource(R.bool.is_test_build)) {
            InfoCard(
                "Version de test",
                "Signée avec une clé temporaire : avant chaque mise à jour, exporte une sauvegarde (Plus → Sauvegarde), " +
                    "puis restaure-la après l'installation.",
                Tone.WARNING,
            )
        }
        if (pending > 0) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$pending dépense(s) à valider", style = MaterialTheme.typography.titleMedium)
                    Muted("Valide-les pour que ton budget reste juste. L'app apprend de tes corrections.")
                    PrimaryButton("Valider maintenant", { ctx.nav.open(Route.Validation) }, tag = "home_validate")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Muted("Disponible jusqu'à la paie" + if (view.incomeEstimated) " (paie estimée)" else "")
                Text(
                    euros(view.freeCents),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (view.freeCents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("home_free"),
                )
                Muted(
                    "${view.daysLeft} jour(s) avant la paie" + (if (view.period.estimatedEnd) " (estimée)" else "") +
                        " · ${euros(view.dailyCents)} par jour",
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Muted("Comptes courants")
                    AmountText(view.checkingCents, strong = true)
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Muted("Épargne")
                    AmountText(view.savingsCents, strong = true)
                }
            }
        }

        SectionTitle("Ce mois-ci")
        for (family in view.families) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(familyLabel(family.family), Modifier.weight(1f))
                    Text(
                        if (family.budgetCents > 0) "${euros(family.spentCents)} / ${euros(family.budgetCents)}" else euros(family.spentCents),
                    )
                }
                if (family.budgetCents > 0) Gauge(family.spentCents.toDouble() / family.budgetCents)
            }
        }
        if (view.envelopes.isEmpty()) {
            OutlinedButton(onClick = { ctx.nav.open(Route.Envelopes) }, modifier = Modifier.fillMaxWidth()) {
                Text("Créer mes enveloppes")
            }
        }

        SectionTitle("Situation : " + levelLabel(advice.situation.level))
        advice.items.filter { it.severity != Severity.INFO || it.id == "variable-income" }.take(3).forEach { item ->
            InfoCard(item.title, item.text, toneOf(item.severity))
        }
        advice.steps.firstOrNull { !it.done }?.let { step ->
            InfoCard("Prochaine étape : ${step.title}", step.detail, if (step.directive) Tone.WARNING else Tone.NEUTRAL)
        }
        OutlinedButton(onClick = { ctx.nav.tab = Tab.ADVICE }, modifier = Modifier.fillMaxWidth()) { Text("Voir tous les conseils") }

        SectionTitle("Actions rapides")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ctx.nav.open(Route.Edit(null)) }, modifier = Modifier.weight(1f).testTag("home_add")) { Text("+ Dépense") }
            OutlinedButton(onClick = { ctx.nav.open(Route.Receipt) }, modifier = Modifier.weight(1f)) { Text("Ticket") }
            OutlinedButton(onClick = { ctx.nav.open(Route.Import) }, modifier = Modifier.weight(1f)) { Text("Relevé") }
        }
        if (today.dayOfWeek == DayOfWeek.SUNDAY || today.dayOfWeek == DayOfWeek.MONDAY) {
            NavRow("Bilan de la semaine", "Deux minutes pour faire le point") { ctx.nav.open(Route.Review) }
        }
    }
}

fun levelLabel(level: Level): String = when (level) {
    Level.CRITIQUE -> "critique"
    Level.TENDU -> "tendue"
    Level.STABLE -> "stable"
    Level.CONFORTABLE -> "confortable"
}

fun toneOf(severity: Severity): Tone = when (severity) {
    Severity.CRITICAL -> Tone.DANGER
    Severity.WARNING -> Tone.WARNING
    Severity.INFO, Severity.GOOD -> Tone.NEUTRAL
}

/** Couleur conventionnelle d'une famille (pour les libellés). */
fun familyIcon(family: Family): String = when (family) {
    Family.ESSENTIEL -> "🧱"
    Family.PLAISIR -> "🎈"
    Family.AVENIR -> "🌱"
    Family.REVENU -> "💶"
    Family.INTERNE -> "🔁"
}
