package app.boussole.core.domain

import java.time.LocalDate

/** Prélèvement récurrent repéré dans l'historique, proposé comme charge fixe. */
data class RecurringSuggestion(
    val name: String,
    val amountCents: Long,
    val dayOfMonth: Int,
    val categoryId: String?,
    val accountId: String?,
    val monthsSeen: Int,
)

object Recurring {
    /**
     * Même marchand, montant stable (±10 %), au moins deux mois différents sur
     * les six derniers, à des jours proches : c'est probablement un abonnement ou
     * une échéance. Les charges fixes déjà déclarées sont écartées.
     */
    fun detect(data: VaultData, today: LocalDate): List<RecurringSuggestion> {
        val since = today.minusMonths(6).toString()
        val known = data.fixedCharges.map { Merchant.key(it.name) }.toSet()
        return data.transactions
            .filter { Ledger.counted(it) && it.amountCents < 0 && it.date >= since }
            .filter { data.category(it.categoryId)?.family != Family.INTERNE }
            .groupBy { Merchant.key(it.label) }
            .filterKeys { it.isNotEmpty() && it !in known }
            .mapNotNull { (key, items) ->
                val months = items.map { it.date.substring(0, 7) }.toSet()
                if (months.size < 2) return@mapNotNull null
                val amounts = items.map { -it.amountCents }
                val median = Ledger.median(amounts)
                if (amounts.any { kotlin.math.abs(it - median) > median / 10 }) return@mapNotNull null
                val days = items.map { it.date.substring(8, 10).toInt() }
                if (days.max() - days.min() > 5) return@mapNotNull null
                // Une seule opération par mois : sinon c'est une habitude, pas une échéance.
                if (items.size > months.size) return@mapNotNull null
                val latest = items.maxBy { it.date }
                RecurringSuggestion(
                    name = key.split(' ').joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) },
                    amountCents = median,
                    dayOfMonth = Ledger.median(days.map(Int::toLong)).toInt(),
                    categoryId = latest.categoryId,
                    accountId = latest.accountId,
                    monthsSeen = months.size,
                )
            }
            .sortedByDescending { it.amountCents }
    }
}
