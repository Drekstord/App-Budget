package app.boussole.ui

import androidx.compose.foundation.layout.Arrangement
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
import app.boussole.core.domain.Goal
import app.boussole.core.domain.Pace
import app.boussole.core.domain.VaultData
import java.time.Instant
import java.util.UUID

/**
 * Conseils (CON) : situation, marche à suivre priorisée, rythme au choix
 * (tranquille / équilibré / rapide), leviers et objectifs.
 */
@Composable
fun AdviceTab(ctx: Ctx, data: VaultData) {
    val advice = remember(data) { Advisor.analyze(data, ctx.today) }
    var pace by remember { mutableStateOf(advice.recommendedPace) }
    var saving by remember { mutableStateOf<Goal?>(null) }
    val s = advice.situation
    val outcome = advice.outcomes.first { it.pace == pace }

    Page {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Situation ${levelLabel(s.level)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("advice_level"))
                Figure("Paie de référence", s.payCents)
                Figure("Dépenses essentielles habituelles", -s.essentialsCents)
                Figure("Dépenses plaisir habituelles", -s.plaisirCents)
                Figure("Marge mensuelle", s.capacityCents)
                if (s.overdraftCents > 0) Figure("Découvert à résorber", -s.overdraftCents)
                Figure("Épargne", s.savingsCents)
                if (!s.hasHistory) Muted("Peu d'historique : les chiffres s'affineront avec tes opérations validées.")
            }
        }

        advice.items.forEach { InfoCard(it.title, it.text, toneOf(it.severity)) }

        SectionTitle("Ton rythme")
        Choices(
            Pace.entries.map { it to Advisor.paceLabel(it).replaceFirstChar(Char::uppercase) + if (it == advice.recommendedPace) " ★" else "" },
            pace, { pace = it }, tagPrefix = "advice_pace",
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (outcome.monthlyCents > 0) "${euros(outcome.monthlyCents)} par mois pour ton avenir" else "Pas de marge pour l'instant",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("advice_monthly"),
                )
                if (pace == advice.recommendedPace) Muted("Je te recommande ce rythme : un vrai progrès, sans te priver de tout.")
                if (s.overdraftCents > 0) {
                    Text(outcome.debtFreeOn?.let { "Découvert résorbé vers ${Advisor.monthLabel(it)}" } ?: "Découvert : effort insuffisant")
                }
                outcome.emergencyReachedOn?.let { Text("Épargne de précaution atteinte vers ${Advisor.monthLabel(it)}") }
                if (outcome.levers.isNotEmpty()) {
                    Muted("Ce rythme suppose : " + outcome.levers.joinToString(" ; ") { it.title.lowercase() })
                }
            }
        }

        SectionTitle("Marche à suivre")
        advice.steps.forEachIndexed { i, step ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (step.done) "✅" else "${i + 1}.", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall)
                    Text(step.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (advice.levers.isNotEmpty()) {
            SectionTitle("Leviers d'économie")
            Muted("Classés du plus efficace au moins efficace. Rien de ce que tu as marqué « non négociable ».")
            advice.levers.forEach { lever ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(lever.title)
                        Muted(lever.detail)
                    }
                    Text("+${euros(lever.monthlyGainCents)}/mois")
                }
            }
        }

        SectionTitle("Objectifs")
        advice.goals.forEach { verdict ->
            val goal = verdict.goal
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(goal.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("goal_${goal.name}"))
                    Text("${euros(goal.savedCents)} / ${euros(goal.targetCents)}")
                    Gauge(goal.savedCents.toDouble() / goal.targetCents.coerceAtLeast(1))
                    Text(verdict.text, style = MaterialTheme.typography.bodyMedium)
                    val reached = outcome.goalsReachedOn[goal.id]
                    Muted("Au rythme ${Advisor.paceLabel(pace)} : " + (reached?.let { "atteint vers ${Advisor.monthLabel(it)}" } ?: "pas atteignable pour l'instant"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { saving = goal }) { Text("J'ai mis de côté") }
                        TextButton(onClick = { ctx.nav.open(Route.GoalEdit(goal.id)) }) { Text("Modifier") }
                    }
                }
            }
        }
        data.goals.filter { it.done }.forEach { Muted("✅ ${it.name} atteint (${euros(it.targetCents)})") }
        PrimaryButton("+ Nouvel objectif", { ctx.nav.open(Route.GoalEdit(null)) }, tag = "advice_new_goal")
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
                                val saved = it.savedCents + cents
                                it.copy(savedCents = saved, done = saved >= it.targetCents)
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
