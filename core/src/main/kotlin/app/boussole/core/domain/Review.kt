package app.boussole.core.domain

import java.time.LocalDate

data class WeeklyReview(
    val spentByFamily: Map<Family, Long>,
    val previousSpentByFamily: Map<Family, Long>,
    val topCategories: List<Pair<Category, Long>>,
    val validatedCount: Int,
    val pendingCount: Int,
)

/** Bilan des 7 derniers jours comparé aux 7 précédents (NOT-2). */
object Review {
    fun week(data: VaultData, today: LocalDate): WeeklyReview {
        val start = today.minusDays(6)
        val previousStart = start.minusDays(7)
        fun spent(from: LocalDate, to: LocalDate): Map<String?, Long> {
            val period = Period(from, to.plusDays(1), estimatedEnd = false)
            return Ledger.spentByCategory(data, period)
        }
        val current = spent(start, today)
        val previous = spent(previousStart, start.minusDays(1))
        fun byFamily(spent: Map<String?, Long>) = listOf(Family.ESSENTIEL, Family.PLAISIR, Family.AVENIR)
            .associateWith { family -> spent.filterKeys { data.category(it)?.family == family }.values.sum() }
        val inWeek = data.transactions.filter { it.date >= start.toString() && it.date <= today.toString() }
        return WeeklyReview(
            spentByFamily = byFamily(current),
            previousSpentByFamily = byFamily(previous),
            topCategories = current.mapNotNull { (id, cents) -> data.category(id)?.let { it to cents } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(3),
            validatedCount = inWeek.count { it.status == TransactionStatus.VALIDATED },
            pendingCount = data.transactions.count { it.status == TransactionStatus.TO_VALIDATE },
        )
    }
}
