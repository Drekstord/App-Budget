package app.boussole.core.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class EnvelopeStatus(
    val category: Category,
    val budgetCents: Long,
    val movedInCents: Long,
    val movedOutCents: Long,
    val spentCents: Long,
) {
    val availableCents: Long get() = budgetCents + movedInCents - movedOutCents - spentCents
    val over: Boolean get() = availableCents < 0
    /** Part consommée (0 si pas de budget). */
    val ratio: Double get() = if (budgetCents + movedInCents - movedOutCents <= 0) 0.0
    else spentCents.toDouble() / (budgetCents + movedInCents - movedOutCents)
}

data class FamilySummary(val family: Family, val budgetCents: Long, val spentCents: Long)

data class UpcomingCharge(val charge: FixedCharge, val due: LocalDate)

data class BudgetView(
    val period: Period,
    val envelopes: List<EnvelopeStatus>,
    val families: List<FamilySummary>,
    /** Dépenses sans enveloppe (catégorie sans budget ou non classées), par catégorie. */
    val unbudgeted: Map<String?, Long>,
    val incomeCents: Long,
    /** Vrai si le revenu affiché est la paie de référence, faute de paie reçue. */
    val incomeEstimated: Boolean,
    val upcoming: List<UpcomingCharge>,
    /** Disponible réel jusqu'à la prochaine paie (BUD-7). */
    val freeCents: Long,
    val daysLeft: Long,
    val dailyCents: Long,
    val checkingCents: Long,
    val savingsCents: Long,
) {
    val toCover: List<EnvelopeStatus> get() = envelopes.filter { it.over }
}

/** Enveloppes strictes, disponible réel et proposition de répartition. */
object Budget {
    fun view(data: VaultData, today: LocalDate): BudgetView {
        val period = Periods.containing(data, today)
        val spent = Ledger.spentByCategory(data, period)
        val periodKey = period.start.toString()
        val moves = data.envelopeMoves.filter { it.periodStart == periodKey }

        val envelopes = data.envelopes.mapNotNull { envelope ->
            val category = data.category(envelope.categoryId) ?: return@mapNotNull null
            EnvelopeStatus(
                category = category,
                budgetCents = envelope.monthlyCents,
                movedInCents = moves.filter { it.toCategoryId == category.id }.sumOf { it.cents },
                movedOutCents = moves.filter { it.fromCategoryId == category.id }.sumOf { it.cents },
                spentCents = spent[category.id] ?: 0,
            )
        }.sortedWith(compareBy({ it.category.family.ordinal }, { it.category.name }))

        val budgeted = envelopes.map { it.category.id }.toSet()
        val unbudgeted = spent.filterKeys { it !in budgeted }.filterValues { it != 0L }

        val families = listOf(Family.ESSENTIEL, Family.PLAISIR, Family.AVENIR).map { family ->
            FamilySummary(
                family,
                envelopes.filter { it.category.family == family }.sumOf { it.budgetCents },
                spent.filterKeys { data.category(it)?.family == family }.values.sum(),
            )
        }

        val received = Ledger.income(data, period)
        val income = if (received > 0) received else Ledger.referencePay(data)
        val upcoming = upcomingCharges(data, period, today)
        // Les échéances dont la catégorie a une enveloppe sont déjà réservées par elle.
        val upcomingOutsideEnvelopes = upcoming.filter { it.charge.categoryId !in budgeted }.sumOf { it.charge.amountCents }
        val totalSpent = spent.values.sum()
        val reserved = envelopes.sumOf { maxOf(0, it.availableCents) }
        val free = income - totalSpent - reserved - upcomingOutsideEnvelopes

        val daysLeft = maxOf(1, ChronoUnit.DAYS.between(today, period.endExclusive))
        return BudgetView(
            period = period,
            envelopes = envelopes,
            families = families,
            unbudgeted = unbudgeted,
            incomeCents = income,
            incomeEstimated = received <= 0,
            upcoming = upcoming,
            freeCents = free,
            daysLeft = daysLeft,
            dailyCents = maxOf(0, free / daysLeft),
            checkingCents = Ledger.total(data, AccountKind.CHECKING) + Ledger.total(data, AccountKind.CASH),
            savingsCents = Ledger.total(data, AccountKind.SAVINGS),
        )
    }

    /**
     * Échéances de charges fixes qui tombent dans la période et n'ont pas encore
     * été vues passer (opération de montant proche autour de la date).
     */
    fun upcomingCharges(data: VaultData, period: Period, today: LocalDate): List<UpcomingCharge> {
        val result = mutableListOf<UpcomingCharge>()
        var month = period.start.withDayOfMonth(1)
        while (month.isBefore(period.endExclusive)) {
            for (charge in data.fixedCharges.filter { it.active }) {
                if (charge.frequency == Frequency.YEARLY && charge.month != month.monthValue) continue
                val due = month.withDayOfMonth(minOf(charge.dayOfMonth, month.lengthOfMonth()))
                if (due !in period) continue
                if (!alreadyPaid(data, charge, due)) result += UpcomingCharge(charge, due)
            }
            month = month.plusMonths(1)
        }
        return result.filter { !it.due.isBefore(today.minusDays(7)) }.sortedBy { it.due }
    }

    fun alreadyPaid(data: VaultData, charge: FixedCharge, due: LocalDate): Boolean {
        val chargeKey = Merchant.key(charge.name)
        return data.transactions.any { t ->
            if (!Ledger.counted(t) || t.amountCents >= 0) return@any false
            val date = LocalDate.parse(t.date)
            val close = kotlin.math.abs(-t.amountCents - charge.amountCents) <= charge.amountCents / 10
            val near = kotlin.math.abs(ChronoUnit.DAYS.between(due, date)) <= 6
            val same = (chargeKey.isNotEmpty() && Merchant.key(t.label) == chargeKey) ||
                (charge.categoryId != null && t.categoryId == charge.categoryId)
            close && near && same
        }
    }

    /** Couvre un dépassement en prenant dans une autre enveloppe (BUD-3). */
    fun cover(data: VaultData, period: Period, fromCategoryId: String, toCategoryId: String, cents: Long): VaultData {
        require(cents > 0 && fromCategoryId != toCategoryId)
        val move = EnvelopeMove(UUID.randomUUID().toString(), period.start.toString(), fromCategoryId, toCategoryId, cents)
        return data.copy(envelopeMoves = data.envelopeMoves + move)
    }

    fun setEnvelope(data: VaultData, categoryId: String, monthlyCents: Long): VaultData {
        val others = data.envelopes.filter { it.categoryId != categoryId }
        return data.copy(envelopes = if (monthlyCents > 0) others + Envelope(categoryId, monthlyCents) else others)
    }
}
