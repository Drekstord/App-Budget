package app.boussole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import app.boussole.core.domain.Account
import app.boussole.core.domain.AccountKind
import app.boussole.core.domain.Category
import app.boussole.core.domain.Family
import app.boussole.core.domain.FixedCharge
import app.boussole.core.domain.Frequency
import app.boussole.core.domain.Ledger
import app.boussole.core.domain.Recurring
import app.boussole.core.domain.RuleOrigin
import app.boussole.core.domain.VaultData
import java.time.Instant
import java.util.UUID

@Composable
fun MoreTab(ctx: Ctx) {
    Page {
        SectionTitle("Ma situation")
        NavRow("Profil de vie", "Paie, contraintes, ce qui n'est pas négociable", tag = "more_profile") { ctx.nav.open(Route.Profile) }
        NavRow("Comptes et soldes", "Soldes actuels, découvert autorisé, épargne protégée", tag = "more_accounts") { ctx.nav.open(Route.Accounts) }
        NavRow("Charges fixes et abonnements", "Prélèvements prévus, détection automatique") { ctx.nav.open(Route.FixedCharges) }
        NavRow("Bilan de la semaine") { ctx.nav.open(Route.Review) }
        SectionTitle("Classement")
        NavRow("Catégories") { ctx.nav.open(Route.Categories) }
        NavRow("Règles de classement", "Ce que l'app a appris de tes corrections") { ctx.nav.open(Route.Rules) }
        SectionTitle("Capture")
        NavRow("Capture des dépenses", "Lecture des notifications bancaires, diagnostic", tag = "more_capture") { ctx.nav.open(Route.Capture) }
        NavRow("Importer un relevé", "CSV ou OFX de Boursobank, Caisse d'Épargne") { ctx.nav.open(Route.Import) }
        NavRow("Scanner un ticket") { ctx.nav.open(Route.Receipt) }
        NavRow("Rappels", "Le soir, le dimanche, sauvegardes") { ctx.nav.open(Route.Reminders) }
        SectionTitle("Protection")
        NavRow("Sauvegarde et export", tag = "more_backup") { ctx.nav.open(Route.Backup) }
        NavRow("Sécurité", "Mot de passe, verrouillage, état du téléphone", tag = "more_security") { ctx.nav.open(Route.Security) }
    }
}

// --- Profil de vie ----------------------------------------------------------------

@Composable
fun ProfilePage(ctx: Ctx, data: VaultData) {
    val p = data.profile
    var pay by remember { mutableStateOf(editable(p.netPayCents)) }
    var payday by remember { mutableStateOf(p.usualPayday.toString()) }
    var rent by remember { mutableStateOf(p.hasRent) }
    var notes by remember { mutableStateOf(p.notes) }
    var months by remember { mutableStateOf(p.emergencyMonths.toString()) }
    var cushion by remember { mutableStateOf(editable(p.savingsCushionCents)) }
    var nonNegotiable by remember { mutableStateOf(data.categories.filter { it.nonNegotiable }.map { it.id }.toSet()) }

    Page {
        Muted("Ce que tu m'expliques ici rend les conseils adaptés à ta vie.")
        AmountInput(pay, { pay = it }, "Paie nette mensuelle habituelle (€)", tag = "profile_pay",
            supporting = "Sans les primes et aides : elles ne sont jamais anticipées.")
        IntInput(payday, { payday = it }, "Jour habituel de la paie (31 = fin de mois)", tag = "profile_payday")
        LabeledSwitch("J'ai un loyer", rent, { rent = it })
        IntInput(months, { months = it }, "Épargne de précaution visée (mois de dépenses essentielles)")
        AmountInput(cushion, { cushion = it }, "Coussin à laisser sur l'épargne (€)")
        TextInput(notes, { notes = it }, "Mes contraintes et habitudes", singleLine = false,
            supporting = "Ex. : je mange à la cantine le midi, je joue de la guitare, je dois garder une voiture…")
        SectionTitle("Non négociable")
        Muted("Ce que les conseils ne proposeront jamais de couper.")
        data.categories.filter { it.family == Family.ESSENTIEL || it.family == Family.PLAISIR }.forEach { category ->
            LabeledSwitch(categoryLabel(category), category.id in nonNegotiable, { checked ->
                nonNegotiable = if (checked) nonNegotiable + category.id else nonNegotiable - category.id
            })
        }
        PrimaryButton("Enregistrer", {
            val payCents = parseEuros(pay) ?: 0
            val paydayValue = payday.toIntOrNull()?.coerceIn(1, 31)
            val hasRent = rent
            val notesValue = notes
            val monthsValue = months.toIntOrNull()?.coerceIn(0, 12)
            val cushionCents = parseEuros(cushion) ?: 0
            val protectedIds = nonNegotiable
            ctx.update { d ->
                d.copy(
                    profile = d.profile.copy(
                        netPayCents = payCents,
                        usualPayday = paydayValue ?: d.profile.usualPayday,
                        hasRent = hasRent,
                        notes = notesValue,
                        emergencyMonths = monthsValue ?: d.profile.emergencyMonths,
                        savingsCushionCents = cushionCents,
                    ),
                    categories = d.categories.map { it.copy(nonNegotiable = it.id in protectedIds) },
                )
            }
            ctx.nav.back()
        }, tag = "profile_save")
    }
}

// --- Comptes -----------------------------------------------------------------------

@Composable
fun AccountsPage(ctx: Ctx, data: VaultData) {
    var editing by remember { mutableStateOf<Account?>(null) }
    Page {
        Muted("Saisis le solde actuel de chaque compte (lu dans ton app bancaire) : l'app le fait ensuite évoluer avec tes opérations.")
        data.accounts.forEach { account ->
            ClickableRow({ editing = account }, tag = "account_${account.institution}_${account.name}") {
                Column(Modifier.weight(1f)) {
                    Text(accountLabel(account))
                    Muted(
                        when (account.kind) {
                            AccountKind.CHECKING -> "Compte courant" + if (account.overdraftLimitCents > 0) " · découvert autorisé ${euros(account.overdraftLimitCents)}" else ""
                            AccountKind.SAVINGS -> "Épargne" + if (account.protected) " · protégée" else ""
                            AccountKind.CASH -> "Espèces"
                        } + if (account.openingDate == null) " · solde à saisir" else "",
                    )
                }
                AmountText(Ledger.balance(account, data.transactions))
            }
            HorizontalDivider()
        }
        OutlinedButton(onClick = {
            editing = Account(UUID.randomUUID().toString(), "Nouveau compte", AccountKind.CHECKING, "")
        }, modifier = Modifier.fillMaxWidth()) { Text("+ Ajouter un compte") }
    }
    editing?.let { account ->
        AccountDialog(ctx, data, account) { editing = null }
    }
}

@Composable
private fun AccountDialog(ctx: Ctx, data: VaultData, account: Account, onDone: () -> Unit) {
    val isNew = data.accounts.none { it.id == account.id }
    val current = Ledger.balance(account, data.transactions)
    var name by remember { mutableStateOf(account.name) }
    var institution by remember { mutableStateOf(account.institution) }
    var kind by remember { mutableStateOf(account.kind) }
    var balance by remember { mutableStateOf(if (account.openingDate == null && current == 0L) "" else signedEditable(current)) }
    var overdraft by remember { mutableStateOf(editable(account.overdraftLimitCents)) }
    var protectedSavings by remember { mutableStateOf(account.protected) }
    var rate by remember { mutableStateOf(if (account.ratePercent == 0.0) "" else account.ratePercent.toString().replace('.', ',')) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (isNew) "Nouveau compte" else accountLabel(account)) },
        text = {
            Column(Modifier.exposeTags(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(institution, { institution = it }, "Banque")
                TextInput(name, { name = it }, "Nom")
                Choices(listOf(AccountKind.CHECKING to "Courant", AccountKind.SAVINGS to "Épargne", AccountKind.CASH to "Espèces"), kind, { kind = it })
                TextInput(balance, { balance = it }, "Solde actuel (€, « - » si négatif)", tag = "account_balance",
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Text)
                if (kind == AccountKind.CHECKING) AmountInput(overdraft, { overdraft = it }, "Découvert autorisé (€)")
                if (kind == AccountKind.SAVINGS) LabeledSwitch("Ne jamais y toucher", protectedSavings, { protectedSavings = it })
                TextInput(rate, { rate = it }, if (kind == AccountKind.SAVINGS) "Taux d'intérêt (%)" else "Taux des agios (%)")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entered = app.boussole.core.domain.Money.parse(balance)
                val now = Instant.now().toString()
                val updated = account.copy(
                    name = name.trim().ifEmpty { "Compte" },
                    institution = institution.trim(),
                    kind = kind,
                    overdraftLimitCents = parseEuros(overdraft) ?: 0,
                    protected = protectedSavings,
                    ratePercent = rate.replace(',', '.').toDoubleOrNull() ?: 0.0,
                ).let { a ->
                    if (entered != null && entered != current) {
                        a.copy(openingBalanceCents = entered, openingDate = ctx.today.toString(), openingSetAt = now)
                    } else if (entered != null && a.openingDate == null) {
                        a.copy(openingBalanceCents = entered, openingDate = ctx.today.toString(), openingSetAt = now)
                    } else a
                }
                ctx.update { d -> d.copy(accounts = if (isNew) d.accounts + updated else d.accounts.map { if (it.id == updated.id) updated else it }) }
                onDone()
            }, modifier = Modifier.dialogTag("account_save")) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Annuler") } },
    )
}

fun signedEditable(cents: Long): String = (if (cents < 0) "-" else "") + editable(kotlin.math.abs(cents)).ifEmpty { "0" }

// --- Catégories ----------------------------------------------------------------------

@Composable
fun CategoriesPage(ctx: Ctx, data: VaultData) {
    var editing by remember { mutableStateOf<Category?>(null) }
    Page {
        for (family in Family.entries) {
            val categories = data.categories.filter { it.family == family }
            if (categories.isEmpty()) continue
            SectionTitle("${familyIcon(family)} ${familyLabel(family)}")
            categories.forEach { category ->
                ClickableRow({ editing = category }) {
                    Text(categoryLabel(category), Modifier.weight(1f))
                    if (category.nonNegotiable) Muted("non négociable")
                    if (category.archived) Muted("archivée")
                }
            }
        }
        OutlinedButton(onClick = {
            editing = Category("cat.perso-${UUID.randomUUID().toString().take(8)}", "", Family.PLAISIR, "🏷️")
        }, modifier = Modifier.fillMaxWidth()) { Text("+ Nouvelle catégorie") }
    }
    editing?.let { category ->
        val isNew = data.categories.none { it.id == category.id }
        var name by remember(category.id) { mutableStateOf(category.name) }
        var icon by remember(category.id) { mutableStateOf(category.icon) }
        var family by remember(category.id) { mutableStateOf(category.family) }
        var archived by remember(category.id) { mutableStateOf(category.archived) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "Nouvelle catégorie" else "Catégorie") },
            text = {
                Column(Modifier.exposeTags(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(icon, { icon = it.take(2) }, "Emoji")
                    TextInput(name, { name = it }, "Nom")
                    if (isNew) Choices(Family.entries.map { it to familyLabel(it) }, family, { family = it })
                    if (!isNew) LabeledSwitch("Archivée (plus proposée)", archived, { archived = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = category.copy(name = name.trim().ifEmpty { "Catégorie" }, icon = icon, family = family, archived = archived)
                    ctx.update { d -> d.copy(categories = if (isNew) d.categories + updated else d.categories.map { if (it.id == updated.id) updated else it }) }
                    editing = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }
}

// --- Règles de classement --------------------------------------------------------------

@Composable
fun RulesPage(ctx: Ctx, data: VaultData) {
    var changing by remember { mutableStateOf<String?>(null) }
    Page {
        Muted("Chaque validation ou correction apprend une règle « marchand → catégorie ». Tu peux les corriger ou les supprimer.")
        if (data.rules.isEmpty()) Muted("Aucune règle pour l'instant : elles apparaîtront quand tu valideras des dépenses.")
        data.rules.sortedBy { it.merchantKey }.forEach { rule ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.merchantKey)
                    Muted(categoryLabel(data.category(rule.categoryId)) + " · ${rule.hits} fois" + if (rule.origin == RuleOrigin.USER) " · manuelle" else "")
                }
                TextButton(onClick = { changing = rule.id }) { Text("Changer") }
                TextButton(onClick = { ctx.update { d -> d.copy(rules = d.rules.filter { it.id != rule.id }) } }) { Text("✕") }
            }
            HorizontalDivider()
        }
    }
    changing?.let { ruleId ->
        CategoryPicker(data, income = null, onPick = { categoryId ->
            ctx.update { d -> d.copy(rules = d.rules.map { if (it.id == ruleId) it.copy(categoryId = categoryId, origin = RuleOrigin.USER) else it }) }
            changing = null
        }, onDismiss = { changing = null })
    }
}

// --- Charges fixes ---------------------------------------------------------------------

@Composable
fun FixedChargesPage(ctx: Ctx, data: VaultData) {
    var editing by remember { mutableStateOf<FixedCharge?>(null) }
    val suggestions = remember(data) { Recurring.detect(data, ctx.today) }
    Page {
        Muted("Abonnements, assurances, téléphone… Ils sont réservés dans le budget avant de passer, et les non essentiels deviennent des leviers d'économie.")
        val total = data.fixedCharges.filter { it.active }.sumOf { it.monthlyCents }
        Text("Total mensuel : ${euros(total)}")
        data.fixedCharges.forEach { charge ->
            ClickableRow({ editing = charge }) {
                Column(Modifier.weight(1f)) {
                    Text(charge.name + if (!charge.active) " (en pause)" else "")
                    Muted(
                        (if (charge.frequency == Frequency.YEARLY) "Annuel" else "Le ${charge.dayOfMonth} de chaque mois") +
                            " · " + (if (charge.essential) "indispensable" else "non essentiel") +
                            " · " + categoryLabel(data.category(charge.categoryId)),
                    )
                }
                AmountText(-charge.amountCents)
            }
            HorizontalDivider()
        }
        OutlinedButton(onClick = {
            editing = FixedCharge(UUID.randomUUID().toString(), "", 0, 5)
        }, modifier = Modifier.fillMaxWidth().testTag("charges_add")) { Text("+ Ajouter une charge") }

        if (suggestions.isNotEmpty()) {
            SectionTitle("Détectées dans tes opérations")
            suggestions.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name)
                        Muted("${euros(s.amountCents)} vers le ${s.dayOfMonth} · vu ${s.monthsSeen} mois")
                    }
                    TextButton(onClick = {
                        val essential = data.category(s.categoryId)?.family == Family.ESSENTIEL
                        ctx.update { d ->
                            d.copy(fixedCharges = d.fixedCharges + FixedCharge(UUID.randomUUID().toString(), s.name, s.amountCents, s.dayOfMonth, s.categoryId, s.accountId, essential))
                        }
                    }) { Text("Ajouter") }
                }
            }
        }
    }
    editing?.let { charge -> FixedChargeDialog(ctx, data, charge) { editing = null } }
}

@Composable
private fun FixedChargeDialog(ctx: Ctx, data: VaultData, charge: FixedCharge, onDone: () -> Unit) {
    val isNew = data.fixedCharges.none { it.id == charge.id }
    var name by remember { mutableStateOf(charge.name) }
    var amount by remember { mutableStateOf(editable(charge.amountCents)) }
    var day by remember { mutableStateOf(charge.dayOfMonth.toString()) }
    var essential by remember { mutableStateOf(charge.essential) }
    var yearly by remember { mutableStateOf(charge.frequency == Frequency.YEARLY) }
    var month by remember { mutableStateOf(charge.month?.toString() ?: "") }
    var active by remember { mutableStateOf(charge.active) }
    var categoryId by remember { mutableStateOf(charge.categoryId) }
    var pickCategory by remember { mutableStateOf(false) }
    val cents = parseEuros(amount)?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (isNew) "Nouvelle charge fixe" else charge.name) },
        text = {
            Column(Modifier.exposeTags(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(name, { name = it }, "Nom (ex. Netflix, assurance auto)", tag = "charge_name")
                AmountInput(amount, { amount = it }, "Montant (€)", tag = "charge_amount")
                IntInput(day, { day = it }, "Jour du prélèvement")
                LabeledSwitch("Annuel", yearly, { yearly = it })
                if (yearly) IntInput(month, { month = it }, "Mois (1 à 12)")
                LabeledSwitch("Indispensable", essential, { essential = it }, "Sinon, l'app pourra proposer de le suspendre.")
                if (!isNew) LabeledSwitch("Actif", active, { active = it })
                TextButton(onClick = { pickCategory = true }) { Text("Catégorie : " + categoryLabel(data.category(categoryId))) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = charge.copy(
                    name = name.trim(),
                    amountCents = cents ?: 0,
                    dayOfMonth = day.toIntOrNull()?.coerceIn(1, 31) ?: 1,
                    essential = essential,
                    frequency = if (yearly) Frequency.YEARLY else Frequency.MONTHLY,
                    month = if (yearly) month.toIntOrNull()?.coerceIn(1, 12) else null,
                    active = active,
                    categoryId = categoryId,
                )
                ctx.update { d -> d.copy(fixedCharges = if (isNew) d.fixedCharges + updated else d.fixedCharges.map { if (it.id == updated.id) updated else it }) }
                onDone()
            }, enabled = name.isNotBlank() && cents != null, modifier = Modifier.dialogTag("charge_save")) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = {
                    ctx.update { d -> d.copy(fixedCharges = d.fixedCharges.filter { it.id != charge.id }) }
                    onDone()
                }) { Text("Supprimer") }
                TextButton(onClick = onDone) { Text("Annuler") }
            }
        },
    )
    if (pickCategory) {
        CategoryPicker(data, income = false, onPick = {
            categoryId = it
            pickCategory = false
        }, onDismiss = { pickCategory = false })
    }
}
