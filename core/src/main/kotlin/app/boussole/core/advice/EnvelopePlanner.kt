package app.boussole.core.advice

import app.boussole.core.domain.Budget
import app.boussole.core.domain.Defaults
import app.boussole.core.domain.Family
import app.boussole.core.domain.Ledger
import app.boussole.core.domain.Money
import app.boussole.core.domain.VaultData
import java.time.LocalDate

data class EnvelopeProposal(
    /** Montant mensuel proposé par catégorie. */
    val amounts: Map<String, Long>,
    val essentialCents: Long,
    val plaisirCents: Long,
    val avenirCents: Long,
    val payCents: Long,
    val explanation: String,
)

/**
 * Répartition « 50/30/20 adaptée » (BUD-2) : sans loyer, l'essentiel pèse moins
 * que 50 %. On part des dépenses essentielles réelles, on réserve l'Avenir
 * (plus fort tant qu'il y a un découvert), et le Plaisir prend le reste, avec un
 * plancher pour que le budget reste vivable — c'est ce qui le rend tenable.
 */
object EnvelopePlanner {
    private val ESSENTIAL_DEFAULT_SHARES = mapOf(
        "cat.courses" to 15, "cat.transport" to 8, "cat.assurances" to 3, "cat.telephone" to 2, "cat.sante" to 2,
    )
    private val PLAISIR_DEFAULT_SHARES = mapOf(
        "cat.restos" to 30, "cat.sorties" to 20, "cat.shopping" to 20, "cat.abonnements" to 10, "cat.musique" to 10, "cat.divers" to 10,
    )

    fun propose(data: VaultData, today: LocalDate): EnvelopeProposal? {
        val pay = Ledger.referencePay(data)
        if (pay <= 0) return null
        val view = Budget.view(data, today)
        val situation = Advisor.situation(data, view)
        val amounts = mutableMapOf<String, Long>()

        // Essentiel : le réel (charges fixes comprises), à défaut des parts usuelles.
        val essentials = data.categories.filter { it.family == Family.ESSENTIEL && !it.archived }
        for (category in essentials) {
            val base = Advisor.baseline(data, view, category.id)
            val amount = if (base > 0) base else pay * (ESSENTIAL_DEFAULT_SHARES[category.id] ?: 0) / 100
            if (amount > 0) amounts[category.id] = roundUp5(amount)
        }
        val essentialTotal = amounts.values.sum()

        // Avenir : 20 %, 25 % tant qu'un découvert est à résorber.
        var avenir = pay * (if (situation.overdraftCents > 0) 25 else 20) / 100
        var plaisir = pay - essentialTotal - avenir
        val floor = pay / 10
        if (plaisir < floor) {
            avenir = maxOf(0, avenir - (floor - plaisir))
            plaisir = pay - essentialTotal - avenir
        }
        val deficit = plaisir < 0
        if (deficit) plaisir = 0

        // Plaisir : au prorata des habitudes, à défaut des parts usuelles.
        val plaisirCategories = data.categories.filter { it.family == Family.PLAISIR && !it.archived }
        val habits = plaisirCategories.associate { it.id to Advisor.baseline(data, view, it.id) }
        val habitsTotal = habits.values.sum()
        for (category in plaisirCategories) {
            val share = if (habitsTotal > 0) plaisir * habits.getValue(category.id) / habitsTotal
            else plaisir * (PLAISIR_DEFAULT_SHARES[category.id] ?: 0) / 100
            val amount = roundDown5(share)
            if (amount > 0) amounts[category.id] = amount
        }

        // Avenir : d'abord le découvert, puis l'épargne de précaution, puis les projets.
        when {
            situation.overdraftCents > 0 -> amounts[Defaults.DEBT_REPAYMENT] = roundDown5(avenir)
            data.goals.any { !it.done } -> {
                amounts[Defaults.EMERGENCY_FUND] = roundDown5(avenir * 6 / 10)
                amounts[Defaults.PROJECTS] = roundDown5(avenir * 4 / 10)
            }
            else -> amounts[Defaults.EMERGENCY_FUND] = roundDown5(avenir)
        }
        amounts.values.removeAll { it <= 0 }

        val pct = { cents: Long -> "${cents * 100 / pay} %" }
        val explanation = buildString {
            append("Sur ${Money.format(pay)} : essentiel ${pct(essentialTotal)}, plaisir ${pct(plaisir)}, avenir ${pct(avenir)}. ")
            append(if (data.profile.hasRent) "" else "Sans loyer, l'essentiel pèse moins que les 50 % habituels. ")
            if (situation.overdraftCents > 0) append("Tant qu'il y a un découvert, l'avenir sert d'abord à le rembourser. ")
            if (deficit) append("Attention : tes dépenses essentielles dépassent ce que permet ta paie, voir les conseils.")
        }.trim()
        return EnvelopeProposal(amounts, essentialTotal, plaisir, avenir, pay, explanation)
    }

    private fun roundUp5(cents: Long): Long = (cents + 499) / 500 * 500
    private fun roundDown5(cents: Long): Long = if (cents <= 0) 0 else cents / 500 * 500
}
