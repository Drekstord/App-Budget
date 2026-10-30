package app.boussole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.boussole.core.advice.EnvelopePlanner
import app.boussole.core.domain.Budget
import app.boussole.core.domain.EnvelopeStatus
import app.boussole.core.domain.Family
import app.boussole.core.domain.Ledger
import app.boussole.core.domain.VaultData

@Composable
fun BudgetTab(ctx: Ctx, data: VaultData) {
    val view = remember(data) { Budget.view(data, ctx.today) }
    var covering by remember { mutableStateOf<EnvelopeStatus?>(null) }

    Page {
        Muted(
            "Du ${shortDate(view.period.start)} au ${shortDate(view.period.endExclusive.minusDays(1))}" +
                if (view.period.estimatedEnd) " (jusqu'à la prochaine paie, estimée)" else "",
        )
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Muted("Revenus" + if (view.incomeEstimated) " (estimés)" else "")
                AmountText(view.incomeCents, strong = true)
            }
            Column(Modifier.weight(1f)) {
                Muted("Disponible réel")
                AmountText(view.freeCents, strong = true)
            }
        }

        if (view.envelopes.isEmpty()) {
            InfoCard("Pas encore d'enveloppes", "Chaque catégorie reçoit un montant mensuel strict. Je peux te proposer une répartition.")
            PrimaryButton("Créer mes enveloppes", { ctx.nav.open(Route.Envelopes) }, tag = "budget_envelopes")
            return@Page
        }

        if (view.toCover.isNotEmpty()) {
            SectionTitle("À couvrir")
            Muted("Enveloppes strictes : un dépassement se compense en prenant dans une autre enveloppe.")
            view.toCover.forEach { envelope ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(categoryLabel(envelope.category), Modifier.weight(1f))
                    AmountText(envelope.availableCents)
                    TextButton(onClick = { covering = envelope }, modifier = Modifier.testTag("cover_${envelope.category.id}")) { Text("Couvrir") }
                }
            }
        }

        for (family in listOf(Family.ESSENTIEL, Family.PLAISIR, Family.AVENIR)) {
            val envelopes = view.envelopes.filter { it.category.family == family }
            if (envelopes.isEmpty()) continue
            val summary = view.families.first { it.family == family }
            SectionTitle("${familyIcon(family)} ${familyLabel(family)} · ${euros(summary.spentCents)} / ${euros(summary.budgetCents)}")
            envelopes.forEach { envelope ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(categoryLabel(envelope.category), Modifier.weight(1f))
                        Text("${euros(envelope.spentCents)} / ${euros(envelope.budgetCents + envelope.movedInCents - envelope.movedOutCents)}")
                    }
                    Gauge(envelope.ratio)
                    Muted(if (envelope.over) "Dépassée de ${euros(-envelope.availableCents)}" else "Reste ${euros(envelope.availableCents)}")
                }
            }
        }

        if (view.unbudgeted.isNotEmpty()) {
            SectionTitle("Hors enveloppe")
            view.unbudgeted.forEach { (categoryId, cents) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(categoryLabel(data.category(categoryId)), Modifier.weight(1f))
                    AmountText(-cents)
                }
            }
        }
        if (view.upcoming.isNotEmpty()) {
            SectionTitle("Prélèvements à venir")
            view.upcoming.forEach { upcoming ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${shortDate(upcoming.due)} · ${upcoming.charge.name}", Modifier.weight(1f))
                    AmountText(-upcoming.charge.amountCents)
                }
            }
        }
        OutlinedButton(onClick = { ctx.nav.open(Route.Envelopes) }, modifier = Modifier.fillMaxWidth().testTag("budget_envelopes")) {
            Text("Modifier mes enveloppes")
        }
    }

    covering?.let { target ->
        val sources = view.envelopes.filter { it.availableCents > 0 && it.category.id != target.category.id }
        AlertDialog(
            onDismissRequest = { covering = null },
            title = { Text("Couvrir « ${target.category.name} »") },
            text = {
                Column(Modifier.exposeTags()) {
                    Muted("Dépassement : ${euros(-target.availableCents)}. Prendre dans :")
                    if (sources.isEmpty()) Muted("Aucune enveloppe n'a de reste disponible.")
                    sources.forEach { source ->
                        val cents = minOf(-target.availableCents, source.availableCents)
                        ClickableRow({
                            ctx.update { d -> Budget.cover(d, view.period, source.category.id, target.category.id, cents) }
                            covering = null
                        }, tag = "cover_from_${source.category.id}") {
                            Text("${categoryLabel(source.category)} : ${euros(cents)}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { covering = null }) { Text("Fermer") } },
        )
    }
}

/** Modification des enveloppes, avec proposition « 50/30/20 adaptée ». */
@Composable
fun EnvelopesPage(ctx: Ctx, data: VaultData) {
    val categories = data.categories.filter { it.family in listOf(Family.ESSENTIEL, Family.PLAISIR, Family.AVENIR) && !it.archived }
    val amounts = remember {
        mutableStateMapOf<String, String>().apply {
            categories.forEach { c -> put(c.id, editable(data.envelopes.firstOrNull { it.categoryId == c.id }?.monthlyCents ?: 0)) }
        }
    }
    var explanation by remember { mutableStateOf<String?>(null) }
    val total = amounts.values.sumOf { parseEuros(it) ?: 0 }
    val pay = Ledger.referencePay(data)

    Page {
        Muted("Montant mensuel de chaque enveloppe. Laisse vide pour ne pas budgéter une catégorie.")
        OutlinedButton(onClick = {
            val proposal = EnvelopePlanner.propose(data, ctx.today)
            if (proposal == null) {
                explanation = "Indique d'abord ta paie dans ton profil de vie (onglet Plus)."
            } else {
                categories.forEach { amounts[it.id] = editable(proposal.amounts[it.id] ?: 0) }
                explanation = proposal.explanation
            }
        }, modifier = Modifier.fillMaxWidth().testTag("envelopes_propose")) { Text("Me proposer une répartition") }
        explanation?.let { InfoCard("Proposition", it) }
        Text("Total : ${euros(total)}" + if (pay > 0) " sur ${euros(pay)} de paie" else "")
        if (pay > 0 && total > pay) Muted("Attention : le total dépasse ta paie.")

        for (family in listOf(Family.ESSENTIEL, Family.PLAISIR, Family.AVENIR)) {
            SectionTitle("${familyIcon(family)} ${familyLabel(family)}")
            categories.filter { it.family == family }.forEach { category ->
                AmountInput(amounts[category.id].orEmpty(), { amounts[category.id] = it }, categoryLabel(category), tag = "envelope_${category.id}")
            }
        }
        PrimaryButton("Enregistrer", {
            ctx.update { d ->
                categories.fold(d) { acc, category -> Budget.setEnvelope(acc, category.id, parseEuros(amounts[category.id].orEmpty()) ?: 0) }
            }
            ctx.nav.back()
        }, tag = "envelopes_save")
    }
}
