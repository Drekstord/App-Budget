package app.boussole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import app.boussole.core.advice.Advisor
import app.boussole.core.advice.Level
import app.boussole.core.advice.Severity
import app.boussole.core.domain.Budget
import app.boussole.core.domain.Family
import app.boussole.core.domain.TransactionStatus
import app.boussole.core.domain.VaultData
import app.boussole.vault.VaultState

/** L'action la plus utile du moment, une seule à la fois. */
private class NextAction(val icon: String, val title: String, val text: String, val button: String, val tag: String, val go: (Ctx) -> Unit)

private fun nextAction(ctx: Ctx, data: VaultData): NextAction {
    val pending = data.transactions.count { it.status == TransactionStatus.TO_VALIDATE }
    if (pending > 0) {
        return NextAction("🧾", "$pending dépense(s) à valider", "Vérifie le montant et la catégorie : 30 secondes, et ton budget reste juste.", "Valider", "home_validate") {
            it.nav.open(Route.Validation)
        }
    }
    val listening = NotificationManagerCompat.getEnabledListenerPackages(ctx.activity).contains(ctx.activity.packageName)
    if (!listening) {
        return NextAction("📡", "Active la capture automatique", "Pour que tes paiements arrivent tout seuls depuis les notifications de ta banque.", "Activer", "home_capture") {
            it.nav.open(Route.Capture)
        }
    }
    val view = Budget.view(data, ctx.today)
    view.toCover.firstOrNull()?.let { envelope ->
        return NextAction("✉️", "« ${envelope.category.name} » déborde", "Dépassement de ${euros(-envelope.availableCents)} : couvre-le avec une autre enveloppe.", "Couvrir", "home_cover") {
            it.nav.open(Route.Budget)
        }
    }
    val advice = Advisor.analyze(data, ctx.today)
    advice.steps.firstOrNull { !it.done && it.directive }?.let { step ->
        return NextAction("🗺️", step.title, step.detail, "Voir mon plan", "home_plan") { it.nav.home(Tab.GOALS) }
    }
    if (data.envelopes.isEmpty()) {
        return NextAction("✉️", "Crée tes enveloppes", "Je te propose une répartition adaptée à ta paie.", "Créer", "home_envelopes") { it.nav.open(Route.Envelopes) }
    }
    if (data.goals.none { !it.done }) {
        return NextAction("🎸", "Une envie ?", "Ajoute un objectif : je te dis comment l'atteindre sans repasser dans le rouge.", "Ajouter", "home_goal") {
            it.nav.open(Route.GoalEdit(null))
        }
    }
    return NextAction("👌", "Tout est en ordre", "Rien à faire pour l'instant. Reviens ce soir valider tes dépenses du jour.", "Mes objectifs", "home_ok") {
        it.nav.home(Tab.GOALS)
    }
}

@Composable
fun HomeTab(ctx: Ctx, state: VaultState.Unlocked) {
    val data = state.data
    val view = remember(data) { Budget.view(data, ctx.today) }
    val level = remember(data) { Advisor.analyze(data, ctx.today).situation.level }
    val action = nextAction(ctx, data)
    val tour = ctx.tour

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Le chiffre clé.
            Column(Modifier.fillMaxWidth().tourTarget(tour, "home_free"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Il te reste", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    euros(view.freeCents),
                    style = MaterialTheme.typography.displayLarge,
                    color = if (view.freeCents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("home_free"),
                )
                Text(
                    "jusqu'à la paie · ${view.daysLeft} jour(s)" + if (view.dailyCents > 0) " · ${euros(view.dailyCents)} par jour" else "",
                    style = MaterialTheme.typography.bodyLarge,
                )
                val spent = view.families.sumOf { it.spentCents }
                if (view.incomeCents > 0) {
                    Spacer(Modifier.height(4.dp))
                    Gauge(spent.toDouble() / view.incomeCents)
                    Muted("${euros(spent)} dépensés sur ${euros(view.incomeCents)}" + if (view.incomeEstimated) " (paie estimée)" else "")
                }
            }

            // Une seule chose à faire.
            Card(
                Modifier.fillMaxWidth().tourTarget(tour, "home_todo"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("À faire maintenant", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(action.icon, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(action.title, style = MaterialTheme.typography.titleMedium)
                            Text(action.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    PrimaryButton(action.button, { action.go(ctx) }, tag = action.tag)
                }
            }

            TextButton(
                onClick = { ctx.nav.open(Route.Budget) },
                modifier = Modifier.fillMaxWidth().testTag("home_detail").tourTarget(tour, "home_detail"),
            ) { Text("Voir le détail du mois  ›") }

            Muted("Situation " + levelLabel(level) + " · comptes courants " + euros(view.checkingCents) + " · épargne " + euros(view.savingsCents))
            Spacer(Modifier.height(72.dp))
        }
        ExtendedFloatingActionButton(
            onClick = { ctx.nav.open(Route.Edit(null)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("home_add").tourTarget(tour, "home_add"),
        ) { Text("+  Dépense") }
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

fun familyIcon(family: Family): String = when (family) {
    Family.ESSENTIEL -> "🧱"
    Family.PLAISIR -> "🍻"
    Family.AVENIR -> "🌱"
    Family.REVENU -> "💰"
    Family.INTERNE -> "🔁"
}
