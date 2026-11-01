package app.boussole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.boussole.core.advice.Advisor
import app.boussole.core.advice.Severity
import app.boussole.core.domain.Goal
import app.boussole.core.domain.Pace
import app.boussole.core.domain.VaultData
import java.time.Instant
import java.util.UUID

/**
 * Onglet Objectifs (CON) : où tu en es, ton plan, ton rythme, tes envies. Les
 * détails (leviers, alertes, chiffres) restent repliés pour ne pas noyer l'essentiel.
 */
@Composable
fun GoalsTab(ctx: Ctx, data: VaultData) {
    val advice = remember(data) { Advisor.analyze(data, ctx.today) }
    var pace by remember { mutableStateOf(advice.recommendedPace) }
    var saving by remember { mutableStateOf<Goal?>(null) }
    var showLevers by remember { mutableStateOf(false) }
    var showFigures by remember { mutableStateOf(false) }
    val s = advice.situation
    val outcome = advice.outcomes.first { it.pace == pace }
    val tour = ctx.tour

    Page {
        Card(Modifier.fillMaxWidth().tourTarget(tour, "advice_level")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Situation ${levelLabel(s.level)}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("advice_level"),
                )
                Text(
                    if (s.capacityCents > 0) "Il te reste en moyenne ${euros(s.capacityCents)} par mois après tes dépenses habituelles."
                    else "Tes dépenses habituelles dépassent ta paie : le plan ci-dessous t'aide à rééquilibrer.",
                )
                TextButton(onClick = { showFigures = !showFigures }) { Text(if (showFigures) "Masquer les chiffres" else "Voir les chiffres") }
                if (showFigures) {
                    Figure("Paie de référence", s.payCents)
                    Figure("Dépenses essentielles habituelles", -s.essentialsCents)
                    Figure("Dépenses plaisir habituelles", -s.plaisirCents)
                    if (s.overdraftCents > 0) Figure("Découvert à résorber", -s.overdraftCents)
                    Figure("Épargne", s.savingsCents)
                    if (!s.hasHistory) Muted("Peu d'historique : les chiffres s'affineront avec tes opérations validées.")
                }
            }
        }

        advice.items.filter { it.severity == Severity.CRITICAL }.forEach { InfoCard(it.title, it.text, Tone.DANGER) }

        Column(Modifier.tourTarget(tour, "goals_plan"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Ton plan")
            advice.steps.forEachIndexed { i, step ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (step.done) "✅" else "${i + 1}.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(step.title, style = MaterialTheme.typography.titleSmall)
                        if (!step.done) Text(step.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Column(Modifier.tourTarget(tour, "goals_pace"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Ton rythme")
            Choices(
                Pace.entries.map { it to Advisor.paceLabel(it).replaceFirstChar(Char::uppercase) + if (it == advice.recommendedPace) " ★" else "" },
                pace, { pace = it }, tagPrefix = "advice_pace",
            )
            Text(
                if (outcome.monthlyCents > 0) "${euros(outcome.monthlyCents)} par mois pour ton avenir" else "Pas de marge pour l'instant : regarde les leviers.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("advice_monthly"),
            )
            if (s.overdraftCents > 0) {
                Text(outcome.debtFreeOn?.let { "Sortie du rouge vers ${Advisor.monthLabel(it)}" } ?: "Sortie du rouge : effort insuffisant")
            }
            outcome.emergencyReachedOn?.let { Muted("Épargne de précaution atteinte vers ${Advisor.monthLabel(it)}") }
        }

        SectionTitle("Tes objectifs")
        advice.goals.forEach { verdict ->
            val goal = verdict.goal
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(goal.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("goal_${goal.name}"))
                    Text("${euros(goal.savedCents)} / ${euros(goal.targetCents)}")
                    Gauge(goal.savedCents.toDouble() / goal.targetCents.coerceAtLeast(1))
                    val reached = outcome.goalsReachedOn[goal.id]
                    Text(reached?.let { "Au rythme ${Advisor.paceLabel(pace)} : atteint vers ${Advisor.monthLabel(it)}" } ?: verdict.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { saving = goal }) { Text("J'ai mis de côté") }
                        TextButton(onClick = { ctx.nav.open(Route.GoalEdit(goal.id)) }) { Text("Modifier") }
                    }
                }
            }
        }
        data.goals.filter { it.done }.forEach { Muted("✅ ${it.name} atteint (${euros(it.targetCents)})") }
        Box(Modifier.tourTarget(tour, "advice_new_goal")) {
            PrimaryButton("+ Nouvel objectif", { ctx.nav.open(Route.GoalEdit(null)) }, tag = "advice_new_goal")
        }

        if (advice.levers.isNotEmpty()) {
            TextButton(onClick = { showLevers = !showLevers }, modifier = Modifier.testTag("goals_levers")) {
                Text((if (showLevers) "▾ " else "▸ ") + "Leviers d'économie (${advice.levers.size})")
            }
            if (showLevers) {
                Muted("Du plus efficace au moins efficace. Jamais ce que tu as marqué « non négociable ».")
                advice.levers.forEach { lever ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(lever.title)
                            Muted(lever.detail)
                        }
                        Text("+${euros(lever.monthlyGainCents)}/mois", color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        val others = advice.items.filter { it.severity != Severity.CRITICAL }
        if (others.isNotEmpty()) {
            SectionTitle("Bon à savoir")
            others.forEach { InfoCard(it.title, it.text, toneOf(it.severity)) }
        }
    }

    saving?.let { goal ->
        var amount by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = null },
            title = { Text("Mis de côté pour « ${goal.name} »") },
            text = { Column(Modifier.exposeTags()) { AmountInput(amount, { amount = it }, "Montant (€)") } },
            confirmButton = {
                TextButton(onClick = {
                    val cents = parseEuros(amount) ?: 0
                    ctx.update { d ->
                        d.copy(goals = d.goals.map {
                            if (it.id != goal.id) it else {
                                val total = it.savedCents + cents
                                it.copy(savedCents = total, done = total >= it.targetCents)
                            }
                        })
                    }
                    saving = null
                }) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { saving = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun Figure(label: String, cents: Long) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AmountText(cents)
    }
}

@Composable
fun GoalPage(ctx: Ctx, data: VaultData, id: String?) {
    val existing = id?.let { goalId -> data.goals.firstOrNull { it.id == goalId } }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var target by remember { mutableStateOf(existing?.let { editable(it.targetCents) } ?: "") }
    var saved by remember { mutableStateOf(existing?.let { editable(it.savedCents) } ?: "") }
    var deadline by remember { mutableStateOf(existing?.deadline?.let { fullDate(java.time.LocalDate.parse(it)) } ?: "") }
    var pace by remember { mutableStateOf(existing?.pace ?: Pace.EQUILIBRE) }
    val targetCents = parseEuros(target)?.takeIf { it > 0 }
    val deadlineDate = deadline.takeIf { it.isNotBlank() }?.let(::parseUserDate)

    Page {
        Muted("Dis-moi ce que tu veux t'offrir ; je calcule comment y arriver sereinement, en tenant compte de tes dépenses.")
        TextInput(name, { name = it }, "Objectif (ex. guitare)", tag = "goal_name")
        AmountInput(target, { target = it }, "Montant visé (€)", tag = "goal_target")
        AmountInput(saved, { saved = it }, "Déjà mis de côté (€)")
        TextInput(deadline, { deadline = it }, "Échéance souhaitée (JJ/MM/AAAA, facultative)", tag = "goal_deadline",
            isError = deadline.isNotBlank() && deadlineDate == null)
        Text("Rythme préféré")
        Choices(Pace.entries.map { it to Advisor.paceLabel(it) }, pace, { pace = it })
        PrimaryButton("Enregistrer", {
            val goal = (existing ?: Goal(UUID.randomUUID().toString(), name.trim(), targetCents!!, createdAt = Instant.now().toString())).copy(
                name = name.trim(),
                targetCents = targetCents!!,
                savedCents = parseEuros(saved) ?: 0,
                deadline = deadlineDate?.toString(),
                pace = pace,
            )
            ctx.update { d -> d.copy(goals = if (existing == null) d.goals + goal else d.goals.map { if (it.id == goal.id) goal else it }) }
            ctx.nav.back()
        }, enabled = name.isNotBlank() && targetCents != null && (deadline.isBlank() || deadlineDate != null), tag = "goal_save")
        if (existing != null) {
            SecondaryButton("Marquer comme atteint", {
                ctx.update { d -> d.copy(goals = d.goals.map { if (it.id == existing.id) it.copy(done = true) else it }) }
                ctx.nav.back()
            })
            SecondaryButton("Supprimer", {
                ctx.update { d -> d.copy(goals = d.goals.filter { it.id != existing.id }) }
                ctx.nav.back()
            })
        }
    }
}
