package app.boussole.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.boussole.core.advice.EnvelopePlanner
import app.boussole.core.domain.AccountKind
import app.boussole.core.domain.Budget
import app.boussole.core.domain.Family
import app.boussole.core.domain.FixedCharge
import app.boussole.core.domain.Ledger
import app.boussole.core.domain.Money
import app.boussole.core.domain.VaultData
import app.boussole.reminders.Reminders
import java.time.Instant
import java.util.UUID

/**
 * Premier lancement après la création du coffre : le profil de vie (BUD-1),
 * pour que l'app « comprenne » la situation dès le départ. Chaque étape peut
 * être passée et complétée plus tard depuis l'onglet Plus.
 */
@Composable
fun OnboardingFlow(ctx: Ctx, data: VaultData) {
    var step by remember { mutableIntStateOf(0) }
    val later = {
        ctx.update { d -> d.copy(profile = d.profile.copy(onboardingDone = true)) }
    }
    when (step) {
        0 -> PayStep(ctx, data, next = { step = 1 }, later = later)
        1 -> BalancesStep(ctx, data, next = { step = 2 }, later = later)
        2 -> ChargesStep(ctx, data, next = { step = 3 }, later = later)
        3 -> ConstraintsStep(ctx, data, next = { step = 4 }, later = later)
        else -> FinishStep(ctx, data)
    }
}

@Composable
private fun StepButtons(next: () -> Unit, later: () -> Unit, enabled: Boolean = true) {
    PrimaryButton("Continuer", next, enabled = enabled, tag = "onboarding_next")
    SecondaryButton("Terminer plus tard", later, tag = "onboarding_later")
}

@Composable
private fun PayStep(ctx: Ctx, data: VaultData, next: () -> Unit, later: () -> Unit) {
    var pay by remember { mutableStateOf(editable(data.profile.netPayCents)) }
    var day by remember { mutableStateOf(data.profile.usualPayday.toString()) }
    Screen {
        Title("1/5 · Ta paie", "Ton mois budgétaire commencera quand ta paie arrive vraiment, pas le 1er du mois.")
        AmountInput(pay, { pay = it }, "Paie nette mensuelle habituelle (€)", tag = "onboarding_pay",
            supporting = "Sans les primes ni les aides : elles ne seront jamais comptées avant d'être reçues.")
        IntInput(day, { day = it }, "Jour habituel (31 = fin de mois)", tag = "onboarding_payday")
        StepButtons({
            ctx.update { d ->
                d.copy(profile = d.profile.copy(netPayCents = parseEuros(pay) ?: 0, usualPayday = day.toIntOrNull()?.coerceIn(1, 31) ?: 28))
            }
            next()
        }, later)
    }
}

@Composable
private fun BalancesStep(ctx: Ctx, data: VaultData, next: () -> Unit, later: () -> Unit) {
    val balances = remember { mutableStateMapOf<String, String>() }
    val overdrafts = remember { mutableStateMapOf<String, String>() }
    Screen {
        Title("2/5 · Tes comptes", "Recopie le solde actuel de chaque compte depuis ton app bancaire (« - » s'il est négatif).")
        data.accounts.forEachIndexed { index, account ->
            TextInput(balances[account.id] ?: "", { balances[account.id] = it }, "Solde ${accountLabel(account)} (€)", tag = "onboarding_balance_$index")
            if (account.kind == AccountKind.CHECKING) {
                AmountInput(overdrafts[account.id] ?: "", { overdrafts[account.id] = it }, "Découvert autorisé ${account.institution} (€)")
            }
        }
        StepButtons({
            val now = Instant.now().toString()
            ctx.update { d ->
                d.copy(accounts = d.accounts.map { a ->
                    val balance = balances[a.id]?.let(Money::parse)
                    val overdraft = overdrafts[a.id]?.let(::parseEuros)
                    a.copy(
                        openingBalanceCents = balance ?: a.openingBalanceCents,
                        openingDate = if (balance != null) ctx.today.toString() else a.openingDate,
                        openingSetAt = if (balance != null) now else a.openingSetAt,
                        overdraftLimitCents = overdraft ?: a.overdraftLimitCents,
                    )
                })
            }
            next()
        }, later)
    }
}

@Composable
private fun ChargesStep(ctx: Ctx, data: VaultData, next: () -> Unit, later: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var essential by remember { mutableStateOf(true) }
    Screen {
        Title("3/5 · Tes charges fixes", "Assurance, téléphone, abonnements, participation au foyer… Elles seront réservées avant de passer.")
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
            val cents = parseEuros(amount) ?: return@OutlinedButton
            ctx.update { d ->
                d.copy(fixedCharges = d.fixedCharges + FixedCharge(UUID.randomUUID().toString(), name.trim(), cents, day.toIntOrNull()?.coerceIn(1, 31) ?: 1, essential = essential))
            }
            name = ""
            amount = ""
            day = ""
        }, enabled = name.isNotBlank() && parseEuros(amount) != null, modifier = Modifier.fillMaxWidth().tagged("onboarding_charge_add")) { Text("+ Ajouter cette charge") }
        StepButtons(next, later)
    }
}

@Composable
private fun ConstraintsStep(ctx: Ctx, data: VaultData, next: () -> Unit, later: () -> Unit) {
    var notes by remember { mutableStateOf(data.profile.notes) }
    var protected by remember { mutableStateOf(data.categories.filter { it.nonNegotiable }.map { it.id }.toSet()) }
    Screen {
        Title("4/5 · Ta vie", "Explique tes contraintes et ce qui compte pour toi : les conseils les respecteront.")
        TextInput(notes, { notes = it }, "Contraintes et habitudes", singleLine = false)
        Text("Ce qui n'est pas négociable")
        data.categories.filter { it.family == Family.PLAISIR || it.family == Family.ESSENTIEL }.forEach { category ->
            LabeledSwitch(categoryLabel(category), category.id in protected, { on ->
                protected = if (on) protected + category.id else protected - category.id
            })
        }
        StepButtons({
            ctx.update { d ->
                d.copy(
                    profile = d.profile.copy(notes = notes),
                    categories = d.categories.map { it.copy(nonNegotiable = it.id in protected) },
                )
            }
            next()
        }, later)
    }
}

@Composable
private fun FinishStep(ctx: Ctx, data: VaultData) {
    var envelopes by remember { mutableStateOf(data.envelopes.isEmpty() && Ledger.referencePay(data) > 0) }
    var notifications by remember { mutableStateOf(Reminders.canNotify(ctx.activity)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    Screen {
        Title("5/5 · C'est parti", "Deux réglages pour que l'app vienne à toi plutôt que l'inverse.")
        if (!notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Autoriser les rappels (notifications)")
            }
        }
        Muted("La lecture des notifications bancaires s'active dans Plus → Capture des dépenses (Android demande une autorisation spéciale).")
        if (Ledger.referencePay(data) > 0) {
            LabeledSwitch("Créer mes enveloppes avec la répartition proposée", envelopes, { envelopes = it },
                "50/30/20 adapté à ta situation ; modifiable ensuite.", tag = "onboarding_envelopes")
        }
        PrimaryButton("Terminer", {
            ctx.update { d ->
                val withEnvelopes = if (envelopes) {
                    EnvelopePlanner.propose(d, ctx.today)?.amounts?.entries?.fold(d) { acc, (id, cents) -> Budget.setEnvelope(acc, id, cents) } ?: d
                } else d
                withEnvelopes.copy(profile = withEnvelopes.profile.copy(onboardingDone = true))
            }
        }, tag = "onboarding_finish")
    }
}
