package app.boussole.core.advice

import app.boussole.core.domain.AccountKind
import app.boussole.core.domain.Budget
import app.boussole.core.domain.BudgetView
import app.boussole.core.domain.Defaults
import app.boussole.core.domain.Family
import app.boussole.core.domain.Goal
import app.boussole.core.domain.Ledger
import app.boussole.core.domain.Money
import app.boussole.core.domain.Pace
import app.boussole.core.domain.TransactionStatus
import app.boussole.core.domain.VaultData
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Level { CRITIQUE, TENDU, STABLE, CONFORTABLE }

enum class Severity { CRITICAL, WARNING, INFO, GOOD }

/** Photographie de la situation, base de tous les conseils. */
data class Situation(
    val level: Level,
    val payCents: Long,
    val essentialsCents: Long,
    val plaisirCents: Long,
    /** Ce qui reste chaque mois après dépenses habituelles (peut être négatif). */
    val capacityCents: Long,
    val checkingCents: Long,
    /** Découvert à résorber (0 si les comptes courants sont positifs). */
    val overdraftCents: Long,
    /** Épargne mobilisable (non protégée, coussin déduit). */
    val usableSavingsCents: Long,
    val savingsCents: Long,
    val emergencyTargetCents: Long,
    val hasHistory: Boolean,
)

enum class LeverKind { SUSPEND_CHARGE, REDUCE_CATEGORY }

data class Lever(
    val id: String,
    val kind: LeverKind,
    val title: String,
    val detail: String,
    val monthlyGainCents: Long,
    val categoryId: String? = null,
    val chargeId: String? = null,
)

data class Step(val title: String, val detail: String, val done: Boolean, val directive: Boolean = false)

/** Ce que donne un rythme : effort mensuel et dates d'arrivée. */
data class PaceOutcome(
    val pace: Pace,
    val monthlyCents: Long,
    val levers: List<Lever>,
    /** Mois (date) où le découvert est résorbé ; null si jamais (effort nul). */
    val debtFreeOn: LocalDate?,
    val emergencyReachedOn: LocalDate?,
    /** Date d'atteinte de chaque objectif, par identifiant. */
    val goalsReachedOn: Map<String, LocalDate?>,
)

data class GoalVerdict(
    val goal: Goal,
    val remainingCents: Long,
    val recommended: Pace,
    /** Explication : faisabilité à l'échéance, effort nécessaire. */
    val text: String,
    val reachableByDeadline: Boolean?,
)

data class AdviceItem(val id: String, val severity: Severity, val title: String, val text: String)

data class Advice(
    val situation: Situation,
    val steps: List<Step>,
    val levers: List<Lever>,
    val outcomes: List<PaceOutcome>,
    val recommendedPace: Pace,
    val goals: List<GoalVerdict>,
    val items: List<AdviceItem>,
)

/**
 * Moteur de conseils (CON) : déterministe, explicable, prudent. Les montants
 * habituels viennent de l'historique (médianes) ; les revenus variables ne sont
 * jamais anticipés ; ce que le profil marque « non négociable » n'est jamais
 * proposé à la coupe.
 */
object Advisor {
    private const val MAX_MONTHS = 120

    fun analyze(data: VaultData, today: LocalDate): Advice {
        val view = Budget.view(data, today)
        val situation = situation(data, view)
        val levers = levers(data, view)
        val outcomes = Pace.entries.map { simulate(data, situation, levers, it, today) }
        val recommended = Pace.EQUILIBRE
        return Advice(
            situation = situation,
            steps = steps(data, situation, outcomes.first { it.pace == recommended }),
            levers = levers,
            outcomes = outcomes,
            recommendedPace = recommended,
            goals = goalVerdicts(data, situation, outcomes, today),
            items = items(data, view, situation, levers, today),
        )
    }

    // --- Situation ----------------------------------------------------------

    /** Dépense mensuelle habituelle d'une catégorie (historique, sinon enveloppe), charges fixes comprises. */
    fun baseline(data: VaultData, view: BudgetView, categoryId: String): Long {
        val usual = Ledger.usualSpend(data, categoryId, view.period)
            ?: data.envelopes.firstOrNull { it.categoryId == categoryId }?.monthlyCents
            ?: 0
        val fixed = data.fixedCharges.filter { it.active && it.categoryId == categoryId }.sumOf { it.monthlyCents }
        return maxOf(usual, fixed)
    }

    fun situation(data: VaultData, view: BudgetView): Situation {
        val pay = Ledger.referencePay(data)
        fun familyTotal(family: Family, essential: Boolean) =
            data.categories.filter { it.family == family && !it.archived }.sumOf { baseline(data, view, it.id) } +
                data.fixedCharges.filter { it.active && it.categoryId == null && it.essential == essential }.sumOf { it.monthlyCents }
        val essentials = familyTotal(Family.ESSENTIEL, essential = true)
        val plaisir = familyTotal(Family.PLAISIR, essential = false)
        val capacity = pay - essentials - plaisir
        val checking = view.checkingCents
        val overdraft = maxOf(0, -checking)
        // Épargne disponible : ni protégée, ni bloquée (PEL, assurance vie, PEA…).
        val usable = data.accounts.filter { it.kind == AccountKind.SAVINGS && !it.protected && !it.effectiveProduct.illiquid }
            .sumOf { maxOf(0, Ledger.balance(it, data.transactions) - data.profile.savingsCushionCents) }
        val emergencyTarget = essentials * data.profile.emergencyMonths
        val level = when {
            checking < 0 && capacity <= 0 -> Level.CRITIQUE
            checking < 0 || capacity < pay / 10 -> Level.TENDU
            view.savingsCents >= emergencyTarget && emergencyTarget > 0 -> Level.CONFORTABLE
            else -> Level.STABLE
        }
        return Situation(
            level = level,
            payCents = pay,
            essentialsCents = essentials,
            plaisirCents = plaisir,
            capacityCents = capacity,
            checkingCents = checking,
            overdraftCents = overdraft,
            usableSavingsCents = usable,
            savingsCents = view.savingsCents,
            emergencyTargetCents = emergencyTarget,
            hasHistory = data.transactions.any { it.status == TransactionStatus.VALIDATED },
        )
    }

    // --- Leviers --------------------------------------------------------------

    fun levers(data: VaultData, view: BudgetView): List<Lever> {
        val result = mutableListOf<Lever>()
        for (charge in data.fixedCharges.filter { it.active && !it.essential }) {
            if (data.category(charge.categoryId)?.nonNegotiable == true) continue
            result += Lever(
                id = "suspend-${charge.id}",
                kind = LeverKind.SUSPEND_CHARGE,
                title = "Suspendre « ${charge.name} »",
                detail = "${Money.format(charge.monthlyCents)} par mois. Tu pourras le reprendre une fois l'objectif atteint.",
                monthlyGainCents = charge.monthlyCents,
                chargeId = charge.id,
                categoryId = charge.categoryId,
            )
        }
        val suspended = data.fixedCharges.filter { it.active && !it.essential }
        for (category in data.categories.filter { it.family == Family.PLAISIR && !it.nonNegotiable && !it.archived }) {
            val base = baseline(data, view, category.id) -
                suspended.filter { it.categoryId == category.id }.sumOf { it.monthlyCents }
            val gain = roundDown5(base / 4)
            if (gain < 1_000) continue
            result += Lever(
                id = "reduce-${category.id}",
                kind = LeverKind.REDUCE_CATEGORY,
                title = "Réduire « ${category.name} » d'un quart",
                detail = "De ${Money.format(base)} à ${Money.format(base - gain)} par mois, d'après tes habitudes.",
                monthlyGainCents = gain,
                categoryId = category.id,
            )
        }
        return result.sortedByDescending { it.monthlyGainCents }
    }

    // --- Rythmes ---------------------------------------------------------------

    /** Effort mensuel d'un rythme et leviers qu'il suppose. */
    fun effort(situation: Situation, levers: List<Lever>, pace: Pace): Pair<Long, List<Lever>> {
        val capacity = maxOf(0, situation.capacityCents)
        val total = levers.sumOf { it.monthlyGainCents }
        return when (pace) {
            // Tranquille : la moitié de la marge, aucun sacrifice.
            Pace.TRANQUILLE -> roundDown5(capacity / 2) to emptyList()
            // Équilibré : 80 % de la marge et les plus gros leviers jusqu'à la moitié du potentiel.
            Pace.EQUILIBRE -> {
                val chosen = mutableListOf<Lever>()
                var sum = 0L
                for (lever in levers) {
                    if (sum >= total / 2) break
                    chosen += lever
                    sum += lever.monthlyGainCents
                }
                roundDown5(capacity * 8 / 10 + sum) to chosen
            }
            // Rapide : toute la marge et tous les leviers.
            Pace.RAPIDE -> roundDown5(capacity + total) to levers
        }
    }

    /**
     * Simulation mois par mois : l'effort va d'abord au découvert (après
     * mobilisation de l'épargne disponible), puis se partage entre épargne de
     * précaution et objectifs (moitié-moitié), puis aux objectifs seuls.
     */
    fun simulate(data: VaultData, situation: Situation, levers: List<Lever>, pace: Pace, today: LocalDate): PaceOutcome {
        val (monthly, used) = effort(situation, levers, pace)
        var debt = maxOf(0, situation.overdraftCents - situation.usableSavingsCents)
        val savingsAfterTransfer = situation.savingsCents - minOf(situation.overdraftCents, situation.usableSavingsCents)
        var emergency = maxOf(0, situation.emergencyTargetCents - savingsAfterTransfer)
        val goals = openGoals(data)
        val remaining = goals.associate { it.id to maxOf(0, it.targetCents - it.savedCents) }.toMutableMap()
        val reached = goals.associate { it.id to (if (remaining[it.id] == 0L) today else null) }.toMutableMap()
        var debtFreeOn: LocalDate? = if (debt == 0L) today else null
        var emergencyOn: LocalDate? = if (emergency == 0L) today else null

        if (monthly > 0) {
            var month = 0
            while (month < MAX_MONTHS && (debt > 0 || emergency > 0 || remaining.values.any { it > 0 })) {
                month++
                val date = today.plusMonths(month.toLong())
                var budget = monthly
                val toDebt = minOf(debt, budget)
                debt -= toDebt
                budget -= toDebt
                if (debt == 0L && debtFreeOn == null) debtFreeOn = date
                if (budget > 0) {
                    val goalsLeft = goals.any { (remaining[it.id] ?: 0) > 0 }
                    var toEmergency = if (goalsLeft) minOf(emergency, budget / 2) else minOf(emergency, budget)
                    budget -= toEmergency
                    for (goal in goals) {
                        val need = remaining[goal.id] ?: 0
                        if (need == 0L || budget == 0L) continue
                        val give = minOf(need, budget)
                        remaining[goal.id] = need - give
                        budget -= give
                        if (need - give == 0L) reached[goal.id] = date
                    }
                    // Ce que les objectifs n'ont pas pris revient à l'épargne de précaution.
                    val extra = minOf(emergency - toEmergency, budget)
                    toEmergency += extra
                    emergency -= toEmergency
                    if (emergency == 0L && emergencyOn == null) emergencyOn = date
                }
            }
        }
        return PaceOutcome(pace, monthly, used, debtFreeOn, emergencyOn, reached)
    }

    private fun openGoals(data: VaultData): List<Goal> =
        data.goals.filter { !it.done }.sortedWith(compareBy({ it.deadline ?: "9999" }, { it.createdAt }))

    // --- Marche à suivre --------------------------------------------------------

    fun steps(data: VaultData, s: Situation, outcome: PaceOutcome): List<Step> {
        val steps = mutableListOf<Step>()
        val transfer = minOf(s.overdraftCents, s.usableSavingsCents)
        steps += when {
            s.overdraftCents == 0L -> Step("Stopper les frais", "Tes comptes courants ne sont pas à découvert : pas d'agios.", done = true)
            transfer > 0 -> Step(
                "Stopper les frais",
                "Je te recommande de virer ${Money.format(transfer)} de ton épargne vers ton compte courant. " +
                    "Le découvert coûte bien plus cher (agios) que ce que l'épargne rapporte.",
                done = false,
                directive = true,
            )
            else -> Step(
                "Stopper les frais",
                "Vérifie le montant de ton découvert autorisé et ses frais auprès de ta banque : rester dans " +
                    "l'autorisé évite les commissions d'intervention.",
                done = false,
            )
        }
        val essentialEnvelopes = data.envelopes.count { data.category(it.categoryId)?.family == Family.ESSENTIEL }
        steps += Step(
            "Sécuriser l'essentiel",
            if (essentialEnvelopes > 0) "Tes dépenses essentielles ont leurs enveloppes."
            else "Crée tes enveloppes : je te propose une répartition adaptée à ta paie et à tes habitudes.",
            done = essentialEnvelopes > 0,
        )
        val debt = s.overdraftCents - transfer
        steps += Step(
            "Résorber le découvert",
            when {
                debt <= 0 -> "Aucun découvert à rembourser."
                outcome.debtFreeOn == null -> "Ta marge actuelle ne suffit pas : commence par les leviers proposés."
                else -> "${Money.format(outcome.monthlyCents)} par mois : découvert résorbé vers ${monthLabel(outcome.debtFreeOn)}."
            },
            done = debt <= 0,
        )
        steps += Step(
            "Constituer une épargne de précaution",
            if (s.emergencyTargetCents == 0L) "Renseigne tes dépenses (ou ta paie) pour la calculer."
            else "Objectif : ${Money.format(s.emergencyTargetCents)} (${data.profile.emergencyMonths} mois de dépenses essentielles)" +
                (outcome.emergencyReachedOn?.let { ", atteint vers ${monthLabel(it)}." } ?: "."),
            done = s.emergencyTargetCents > 0 && s.savingsCents >= s.emergencyTargetCents,
        )
        steps += Step(
            "Financer tes projets",
            if (data.goals.none { !it.done }) "Ajoute un objectif (une guitare, un voyage…) : je te dirai comment l'atteindre."
            else "Tes objectifs avancent avec le reste de l'effort mensuel.",
            done = false,
        )
        return steps
    }

    // --- Objectifs ----------------------------------------------------------------

    fun goalVerdicts(data: VaultData, s: Situation, outcomes: List<PaceOutcome>, today: LocalDate): List<GoalVerdict> =
        openGoals(data).map { goal ->
            val remaining = maxOf(0, goal.targetCents - goal.savedCents)
            val deadline = goal.deadline?.let(LocalDate::parse)
            val byPace = outcomes.associate { it.pace to it.goalsReachedOn[goal.id] }
            if (deadline == null) {
                val date = byPace[goal.pace]
                return@map GoalVerdict(
                    goal, remaining, goal.pace,
                    if (date == null) "Pas atteignable avec la marge actuelle : active des leviers ou augmente l'effort."
                    else "Au rythme ${paceLabel(goal.pace)}, atteint vers ${monthLabel(date)}.",
                    null,
                )
            }
            val fitting = Pace.entries.firstOrNull { pace -> byPace[pace]?.let { !it.isAfter(deadline) } == true }
            val months = maxOf(1, ChronoUnit.MONTHS.between(today.withDayOfMonth(1), deadline.withDayOfMonth(1)))
            val needed = (remaining + months - 1) / months
            if (fitting != null) {
                GoalVerdict(
                    goal, remaining, fitting,
                    "Atteignable pour le ${deadline.dayOfMonth}/${deadline.monthValue}/${deadline.year} au rythme ${paceLabel(fitting)}" +
                        (if (fitting == Pace.TRANQUILLE) ", en toute tranquillité." else "."),
                    true,
                )
            } else {
                GoalVerdict(
                    goal, remaining, Pace.RAPIDE,
                    "Hors de portée à cette échéance : il faudrait environ ${Money.format(needed)} par mois en plus du reste. " +
                        "Repousse la date, réduis le montant, ou garde les primes pour cet objectif.",
                    false,
                )
            }
        }

    // --- Conseils ponctuels ----------------------------------------------------------

    fun items(data: VaultData, view: BudgetView, s: Situation, levers: List<Lever>, today: LocalDate): List<AdviceItem> {
        val items = mutableListOf<AdviceItem>()
        if (s.payCents == 0L) {
            items += AdviceItem("no-pay", Severity.INFO, "Indique ta paie", "Renseigne ta paie nette dans ton profil de vie : les conseils en dépendent.")
        }
        if (s.payCents > 0 && s.capacityCents <= 0 && s.hasHistory) {
            items += AdviceItem(
                "deficit", Severity.CRITICAL, "Tes dépenses habituelles dépassent ta paie",
                "Il manque environ ${Money.format(-s.capacityCents)} chaque mois. Les leviers ci-dessous peuvent combler l'écart. " +
                    "Un Point conseil budget (service public gratuit et confidentiel) peut aussi t'aider à faire le point.",
            )
        }
        for (account in data.accounts.filter { it.kind == AccountKind.CHECKING && it.overdraftLimitCents > 0 }) {
            val balance = Ledger.balance(account, data.transactions)
            if (balance < -account.overdraftLimitCents) {
                items += AdviceItem(
                    "over-limit-${account.id}", Severity.CRITICAL, "Découvert autorisé dépassé (${account.institution})",
                    "Solde ${Money.format(balance)} pour un découvert autorisé de ${Money.format(account.overdraftLimitCents)} : " +
                        "chaque opération peut coûter des frais. Remets-y de l'argent en priorité.",
                )
            }
        }
        for (envelope in view.toCover) {
            items += AdviceItem(
                "cover-${envelope.category.id}", Severity.WARNING, "Enveloppe « ${envelope.category.name} » dépassée",
                "Dépassement de ${Money.format(-envelope.availableCents)} : couvre-le en prenant dans une autre enveloppe.",
            )
        }
        val elapsed = ChronoUnit.DAYS.between(view.period.start, today) + 1
        val length = ChronoUnit.DAYS.between(view.period.start, view.period.endExclusive)
        if (elapsed >= 7) {
            for (envelope in view.envelopes.filter { !it.over && it.budgetCents > 0 }) {
                val projected = envelope.spentCents * length / elapsed
                if (projected > envelope.budgetCents + envelope.movedInCents - envelope.movedOutCents + 1_000) {
                    items += AdviceItem(
                        "pace-${envelope.category.id}", Severity.WARNING, "« ${envelope.category.name} » va trop vite",
                        "À ce rythme, ${Money.format(projected)} d'ici la paie pour ${Money.format(envelope.budgetCents)} prévus.",
                    )
                }
            }
        }
        val pending = data.transactions.count { it.status == TransactionStatus.TO_VALIDATE }
        if (pending > 0) {
            items += AdviceItem("pending", Severity.INFO, "$pending opération(s) à valider", "Valide-les pour que budget et conseils restent justes.")
        }
        if (data.envelopes.isEmpty()) {
            items += AdviceItem("no-envelopes", Severity.INFO, "Pas encore d'enveloppes", "Je peux te proposer une répartition 50/30/20 adaptée à ta situation.")
        }
        variableIncome(data, s, today)?.let { items += it }
        if (s.level == Level.CONFORTABLE) {
            items += AdviceItem("good", Severity.GOOD, "Situation saine", "Épargne de précaution constituée et comptes positifs. Bravo.")
        }
        if (levers.isNotEmpty() && s.overdraftCents > 0) {
            val top = levers.first()
            items += AdviceItem("lever", Severity.INFO, "Le levier le plus efficace", "${top.title} : ${Money.format(top.monthlyGainCents)} par mois.")
        }
        return items.sortedBy { it.severity.ordinal }
    }

    /** Revenu variable reçu récemment (prime, aide…) : proposition de répartition (BUD-5). */
    private fun variableIncome(data: VaultData, s: Situation, today: LocalDate): AdviceItem? {
        val since = today.minusDays(14).toString()
        val received = data.transactions
            .filter { Ledger.counted(it) && it.amountCents > 0 && it.date >= since }
            .filter { data.category(it.categoryId)?.family == Family.REVENU && it.categoryId != Defaults.SALARY }
        val total = received.sumOf { it.amountCents }
        if (total <= 0) return null
        val (debt, emergency, free) = when {
            s.overdraftCents > 0 -> Triple(70, 20, 10)
            s.savingsCents < s.emergencyTargetCents -> Triple(0, 60, 40)
            else -> Triple(0, 0, 100)
        }
        fun part(pct: Int) = Money.format(total * pct / 100)
        val text = buildList {
            if (debt > 0) add("${part(debt)} contre le découvert")
            if (emergency > 0) add("${part(emergency)} sur l'épargne de précaution")
            add(if (free == 100) "à répartir entre tes objectifs et un plaisir" else "${part(free)} pour te faire plaisir")
        }.joinToString(", ")
        return AdviceItem("variable-income", Severity.INFO, "Tu as reçu ${Money.format(total)} en plus de ta paie", "Je te propose : $text.")
    }

    fun paceLabel(pace: Pace): String = when (pace) {
        Pace.TRANQUILLE -> "tranquille"
        Pace.EQUILIBRE -> "équilibré"
        Pace.RAPIDE -> "rapide"
    }

    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )

    fun monthLabel(date: LocalDate): String = "${MONTHS[date.monthValue - 1]} ${date.year}"

    private fun roundDown5(cents: Long): Long = if (cents <= 0) 0 else cents / 500 * 500
}
