package app.boussole.core.domain

import java.time.LocalDate

/** Période budgétaire : de l'arrivée d'une paie (incluse) à la suivante (exclue). */
data class Period(
    val start: LocalDate,
    val endExclusive: LocalDate,
    /** Vrai si la fin est une estimation (la paie suivante n'est pas encore arrivée). */
    val estimatedEnd: Boolean,
) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)
    operator fun contains(iso: String): Boolean = contains(LocalDate.parse(iso))
}

/**
 * Le mois budgétaire commence le jour où la paie arrive vraiment (BUD-4), pas à
 * date fixe. Les paies sont les revenus classés « Salaire » ; tant qu'il n'y en a
 * pas, on s'appuie sur le jour habituel déclaré dans le profil.
 */
object Periods {
    /** Deux versements à moins de 10 jours d'écart appartiennent à la même paie. */
    private const val SAME_PAY_DAYS = 10L

    fun paydays(data: VaultData): List<LocalDate> {
        val dates = data.transactions
            .filter { it.categoryId == Defaults.SALARY && it.amountCents > 0 && it.status != TransactionStatus.IGNORED }
            .map { LocalDate.parse(it.date) }
            .sorted()
        val result = mutableListOf<LocalDate>()
        for (date in dates) {
            if (result.isEmpty() || result.last().plusDays(SAME_PAY_DAYS).isBefore(date)) result += date
        }
        return result
    }

    fun containing(data: VaultData, date: LocalDate): Period {
        val paydays = paydays(data)
        val usual = data.profile.usualPayday
        val start = paydays.lastOrNull { !it.isAfter(date) } ?: usualPaydayOnOrBefore(date, usual)
        val next = paydays.firstOrNull { it.isAfter(start) }
        if (next != null) return Period(start, next, estimatedEnd = false)
        // Paie suivante pas encore arrivée : estimation, prolongée si elle est en retard.
        val estimated = nextUsualPayday(start, usual)
        val end = if (estimated.isAfter(date)) estimated else date.plusDays(1)
        return Period(start, end, estimatedEnd = true)
    }

    /** Les [count] périodes complètes précédant [period], de la plus récente à la plus ancienne. */
    fun previous(data: VaultData, period: Period, count: Int): List<Period> {
        val result = mutableListOf<Period>()
        var cursor = period
        repeat(count) {
            val before = containing(data, cursor.start.minusDays(1))
            result += before.copy(endExclusive = cursor.start, estimatedEnd = false)
            cursor = before
        }
        return result
    }

    fun usualPaydayOnOrBefore(date: LocalDate, usual: Int): LocalDate {
        val thisMonth = dayIn(date.year, date.monthValue, usual)
        return if (!thisMonth.isAfter(date)) thisMonth else {
            val previous = date.minusMonths(1)
            dayIn(previous.year, previous.monthValue, usual)
        }
    }

    fun nextUsualPayday(after: LocalDate, usual: Int): LocalDate {
        val next = after.plusMonths(1)
        return dayIn(next.year, next.monthValue, usual)
    }

    /** Jour [day] du mois, ramené au dernier jour si le mois est plus court (31 = fin de mois). */
    private fun dayIn(year: Int, month: Int, day: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        return first.withDayOfMonth(minOf(day, first.lengthOfMonth()))
    }
}

/** Soldes et sommes : tout ce qui se calcule à partir des opérations. */
object Ledger {
    fun counted(t: Transaction): Boolean = t.status != TransactionStatus.IGNORED

    /**
     * Solde d'un compte : solde saisi, plus les opérations postérieures à sa date
     * (et, ce jour-là, celles enregistrées après la saisie).
     */
    fun balance(account: Account, transactions: List<Transaction>): Long {
        val opening = account.openingDate
        return account.openingBalanceCents + transactions
            .filter { it.accountId == account.id && counted(it) }
            .filter { t ->
                opening == null || t.date > opening ||
                    (t.date == opening && account.openingSetAt != null && t.createdAt > account.openingSetAt)
            }
            .sumOf { it.amountCents }
    }

    fun total(data: VaultData, kind: AccountKind): Long =
        data.accounts.filter { it.kind == kind }.sumOf { balance(it, data.transactions) }

    fun inPeriod(data: VaultData, period: Period): List<Transaction> =
        data.transactions.filter { counted(it) && it.date in period }

    /** Dépenses nettes par catégorie (positif = dépensé), hors revenus et mouvements internes. */
    fun spentByCategory(data: VaultData, period: Period): Map<String?, Long> {
        val result = mutableMapOf<String?, Long>()
        for (t in inPeriod(data, period)) {
            val family = data.category(t.categoryId)?.family
            if (family == Family.REVENU || family == Family.INTERNE) continue
            if (family == null && t.amountCents > 0) continue // revenu non classé
            result[t.categoryId] = (result[t.categoryId] ?: 0) - t.amountCents
        }
        return result
    }

    /** Revenus de la période (classés en famille Revenu). */
    fun income(data: VaultData, period: Period): Long =
        inPeriod(data, period)
            .filter { data.category(it.categoryId)?.family == Family.REVENU }
            .sumOf { it.amountCents }

    /** Médiane (0 si vide). */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * Dépense habituelle d'une catégorie : médiane des 3 dernières périodes
     * complètes où l'historique existe ; null sans historique.
     */
    fun usualSpend(data: VaultData, categoryId: String, current: Period): Long? {
        val earliest = data.transactions.minOfOrNull { it.date } ?: return null
        val periods = Periods.previous(data, current, 3).filter { it.start.toString() >= earliest }
        if (periods.isEmpty()) return null
        return median(periods.map { spentByCategory(data, it)[categoryId] ?: 0 })
    }

    /** Paie de référence : profil, sinon médiane des dernières paies reçues. */
    fun referencePay(data: VaultData): Long {
        if (data.profile.netPayCents > 0) return data.profile.netPayCents
        val salaries = data.transactions
            .filter { it.categoryId == Defaults.SALARY && it.amountCents > 0 && counted(it) }
            .sortedByDescending { it.date }
            .take(3)
            .map { it.amountCents }
        return median(salaries)
    }
}
