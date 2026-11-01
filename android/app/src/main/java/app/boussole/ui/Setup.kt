package app.boussole.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.unit.dp
import app.boussole.R
import app.boussole.core.advice.EnvelopePlanner
import app.boussole.core.domain.Budget
import app.boussole.core.domain.Family
import app.boussole.core.domain.FixedCharge
import app.boussole.core.domain.Ledger
import app.boussole.core.domain.VaultData
import app.boussole.reminders.Reminders
import java.util.UUID

private const val STEPS = 5
private const val DRAFTS = "accueil."

/**
 * Premier lancement : le profil de vie (BUD-1), pour que l'app « comprenne » la
 * situation dès le départ. L'étape en cours et les saisies sont enregistrées
 * dans le coffre : aller vérifier un solde dans l'app de sa banque ne fait rien
 * perdre. Chaque étape peut être complétée plus tard depuis les réglages.
 */
@Composable
fun OnboardingFlow(ctx: Ctx, data: VaultData) {
    val step = data.profile.onboardingStep
    fun goTo(next: Int) = ctx.update { d -> d.copy(profile = d.profile.copy(onboardingStep = next)) }
    val later = {
        ctx.drafts.clear(DRAFTS)
        ctx.update { d -> d.copy(profile = d.profile.copy(onboardingDone = true)) }
    }
    when (step) {
        0 -> PayStep(ctx, data, next = { goTo(1) }, later = later)
        1 -> BalancesStep(ctx, data, next = { goTo(2) }, back = { goTo(0) }, later = later)
        2 -> ChargesStep(ctx, data, next = { goTo(3) }, back = { goTo(1) }, later = later)
        3 -> ConstraintsStep(ctx, data, next = { goTo(4) }, back = { goTo(2) }, later = later)
        else -> FinishStep(ctx, data, back = { goTo(3) })
    }
}

@Composable
private fun StepScreen(index: Int, title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Screen {
        Text("Étape ${index + 1} sur $STEPS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(progress = { (index + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
        Title(title, subtitle)
        if (index == 0 && booleanResource(R.bool.is_test_build)) {
            InfoCard(
                "Version de test",
                "Signée avec une clé temporaire : avant chaque mise à jour, exporte une sauvegarde chiffrée, puis restaure-la.",
                Tone.WARNING,
            )
        }
        content()
    }
}

@Composable
private fun StepButtons(next: () -> Unit, later: () -> Unit, back: (() -> Unit)? = null, enabled: Boolean = true) {
    PrimaryButton("Continuer", next, enabled = enabled, tag = "onboarding_next")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (back != null) SecondaryButton("← Étape précédente", back, tag = "onboarding_back")
    }
    SecondaryButton("Terminer plus tard", later, tag = "onboarding_later")
}

@Composable
private fun PayStep(ctx: Ctx, data: VaultData, next: () -> Unit, later: () -> Unit) {
    var pay by rememberDraft(ctx, DRAFTS + "paie", editable(data.profile.netPayCents))
    var day by rememberDraft(ctx, DRAFTS + "jour", data.profile.usualPayday.toString())
    StepScreen(0, "Ta paie", "Ton mois commencera le jour où ta paie arrive vraiment, pas le 1er.") {
        AmountInput(pay, { pay = it }, "Paie nette mensuelle habituelle (€)", tag = "onboarding_pay",
            supporting = "Sans les primes ni les aides : elles ne sont jamais comptées avant d'être reçues.")
        IntInput(day, { day = it }, "Jour habituel (31 = fin de mois)", tag = "onboarding_payday")
        StepButtons({
            val payCents = parseEuros(pay) ?: 0
            val payday = day.toIntOrNull()?.coerceIn(1, 31) ?: 28
            ctx.update { d -> d.copy(profile = d.profile.copy(netPayCents = payCents, usualPayday = payday)) }
            next()
        }, later)
    }
}

@Composable
private fun BalancesStep(ctx: Ctx, data: VaultData, next: () -> Unit, back: () -> Unit, later: () -> Unit) {
    val inputs = rememberBalanceInputs(ctx, data)
    StepScreen(1, "Tes comptes", "Ajoute ou retire des comptes (Livret A, PEL, assurance vie…), puis recopie leur solde actuel. Va le lire dans l'app de ta banque : ta saisie t'attendra.") {
        AccountsEditor(ctx, data, inputs)
        StepButtons({
            commitBalances(ctx, data, inputs)
            next()
        }, later, back)
    }
}

@Composable
private fun ChargesStep(ctx: Ctx, data: VaultData, next: () -> Unit, back: () -> Unit, later: () -> Unit) {
    var name by rememberDraft(ctx, DRAFTS + "charge.nom", "")
    var amount by rememberDraft(ctx, DRAFTS + "charge.montant", "")
    var day by rememberDraft(ctx, DRAFTS + "charge.jour", "")
    var essential by remember { mutableStateOf(true) }
    StepScreen(2, "Tes charges fixes", "Assurance, téléphone, abonnements, participation au foyer… Elles seront mises de côté avant de passer.") {
        data.fixedCharges.forEach { charge ->
            Row(Modifier.fillMaxWidth()) {
                Text("${charge.name} · le ${charge.dayOfMonth}", Modifier.weight(1f))
                Text(euros(charge.amountCents))
            }
        }
        TextInput(name, { name = it }, "Nom", tag = "onboarding_charge_name")
        AmountInput(amount, { amount = it }, "Montant (€)", tag = "onboarding_charge_amount")
        IntInput(day, { day = it }, "Jour du prélèvement")
        LabeledSwitch("Indispensable", essential, { essential = it }, "Sinon (loisir), l'app pourra proposer de le suspendre.")
        OutlinedButton(onClick = {
            // Valeurs figées avant l'enregistrement (différé) : les champs sont vidés juste après.
            val cents = parseEuros(amount) ?: return@OutlinedButton
            val charge = FixedCharge(UUID.randomUUID().toString(), name.trim(), cents, day.toIntOrNull()?.coerceIn(1, 31) ?: 1, essential = essential)
            ctx.update { d -> d.copy(fixedCharges = d.fixedCharges + charge) }
            name = ""
            amount = ""
            day = ""
        }, enabled = name.isNotBlank() && parseEuros(amount) != null, modifier = Modifier.fillMaxWidth().tagged("onboarding_charge_add")) { Text("+ Ajouter cette charge") }
        StepButtons(next, later, back)
    }
}

@Composable
private fun ConstraintsStep(ctx: Ctx, data: VaultData, next: () -> Unit, back: () -> Unit, later: () -> Unit) {
    var notes by rememberDraft(ctx, DRAFTS + "notes", data.profile.notes)
    var protected by remember { mutableStateOf(data.categories.filter { it.nonNegotiable }.map { it.id }.toSet()) }
    StepScreen(3, "Ta vie", "Explique tes contraintes et ce qui compte pour toi : les conseils les respecteront.") {
        TextInput(notes, { notes = it }, "Contraintes et habitudes", singleLine = false)
        Text("Ce qui n'est pas négociable", style = MaterialTheme.typography.titleMedium)
        data.categories.filter { it.family == Family.PLAISIR || it.family == Family.ESSENTIEL }.forEach { category ->
            LabeledSwitch(categoryLabel(category), category.id in protected, { on ->
                protected = if (on) protected + category.id else protected - category.id
            })
        }
        StepButtons({
            val notesValue = notes
            val protectedIds = protected
            ctx.update { d ->
                d.copy(
                    profile = d.profile.copy(notes = notesValue),
                    categories = d.categories.map { it.copy(nonNegotiable = it.id in protectedIds) },
                )
            }
            next()
        }, later, back)
    }
}

@Composable
private fun FinishStep(ctx: Ctx, data: VaultData, back: () -> Unit) {
    var envelopes by remember { mutableStateOf(data.envelopes.isEmpty() && Ledger.referencePay(data) > 0) }
    var notifications by remember { mutableStateOf(Reminders.canNotify(ctx.activity)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    StepScreen(4, "C'est parti", "Deux réglages pour que l'app vienne à toi plutôt que l'inverse. Une visite guidée t'attend ensuite.") {
        if (!notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Autoriser les rappels (notifications)")
            }
        }
        Muted("La capture automatique de tes paiements s'active ensuite depuis l'accueil (Android demande une autorisation spéciale).")
        if (Ledger.referencePay(data) > 0) {
            LabeledSwitch("Créer mes enveloppes avec la répartition proposée", envelopes, { envelopes = it },
                "50/30/20 adapté à ta situation ; modifiable ensuite.", tag = "onboarding_envelopes")
        }
        PrimaryButton("Terminer", {
            ctx.drafts.clear(DRAFTS)
            ctx.update { d ->
                val withEnvelopes = if (envelopes) {
                    EnvelopePlanner.propose(d, ctx.today)?.amounts?.entries?.fold(d) { acc, (id, cents) -> Budget.setEnvelope(acc, id, cents) } ?: d
                } else d
                withEnvelopes.copy(profile = withEnvelopes.profile.copy(onboardingDone = true))
            }
            ctx.nav.home(Tab.HOME)
        }, tag = "onboarding_finish")
        SecondaryButton("← Étape précédente", back, tag = "onboarding_back")
    }
}
