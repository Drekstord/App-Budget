package app.boussole.core.advice

import app.boussole.core.domain.Defaults
import app.boussole.core.domain.FixedCharge
import app.boussole.core.domain.Fx
import app.boussole.core.domain.Goal
import app.boussole.core.domain.Pace
import app.boussole.core.domain.Transaction
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdvisorTest {
    private val today = LocalDate.parse("2026-10-30")

    /** Trois mois d'historique, découvert de 600 € chez Boursobank, 400 € sur le livret. */
    private fun situationDifficile(goals: List<Goal> = emptyList()) = run {
        val history = mutableListOf<Transaction>()
        for (month in listOf("07", "08", "09", "10")) {
            history += Fx.salary("2026-$month-28", 1800.0)
        }
        for (month in listOf("08", "09", "10")) {
            history += Fx.tx("2026-$month-02", -420.0, "LECLERC", "cat.courses")
            history += Fx.tx("2026-$month-05", -160.0, "TOTAL", "cat.transport")
            history += Fx.tx("2026-$month-06", -45.0, "MAIF", "cat.assurances")
            history += Fx.tx("2026-$month-07", -20.0, "FREE MOBILE", "cat.telephone")
            history += Fx.tx("2026-$month-10", -260.0, "RESTO", "cat.restos")
            history += Fx.tx("2026-$month-12", -180.0, "ZARA", "cat.shopping")
            history += Fx.tx("2026-$month-15", -13.49, "NETFLIX", "cat.abonnements")
            history += Fx.tx("2026-$month-15", -10.99, "SPOTIFY", "cat.abonnements")
        }
        Fx.data(*history.toTypedArray()) {
            copy(
                accounts = listOf(
                    Fx.bourso.copy(openingBalanceCents = -60000, openingDate = "2026-10-30", openingSetAt = "2026-10-30T23:00:00Z"),
                    Fx.ceCourant.copy(openingDate = "2026-10-30", openingSetAt = "2026-10-30T23:00:00Z"),
                    Fx.ceEpargne.copy(openingBalanceCents = 40000, openingDate = "2026-10-30", openingSetAt = "2026-10-30T23:00:00Z"),
                ),
                fixedCharges = listOf(
                    FixedCharge("netflix", "Netflix", 1349, 15, "cat.abonnements", essential = false),
                    FixedCharge("spotify", "Spotify", 1099, 15, "cat.abonnements", essential = false),
                    FixedCharge("maif", "MAIF", 4500, 6, "cat.assurances", essential = true),
                ),
                goals = goals,
            )
        }
    }

    @Test
    fun `situation tendue - découvert, épargne mobilisable, marge mesurée sur l'historique`() {
        val advice = Advisor.analyze(situationDifficile(), today)
        val s = advice.situation
        assertEquals(Level.TENDU, s.level)
        assertEquals(60000, s.overdraftCents)
        assertEquals(30000, s.usableSavingsCents) // 400 € moins le coussin de 100 €
        assertEquals(180000, s.payCents)
        assertTrue(s.capacityCents in 60000..80000, "marge = ${s.capacityCents}")
        // Première étape : directive, virer l'épargne mobilisable.
        val first = advice.steps.first()
        assertTrue(first.directive && !first.done)
        assertTrue("300,00" in first.detail, first.detail)
    }

    @Test
    fun `leviers - abonnements non essentiels et réduction des plaisirs, jamais le non négociable`() {
        var data = situationDifficile()
        val levers = Advisor.analyze(data, today).levers
        assertTrue(levers.any { it.chargeId == "netflix" })
        assertTrue(levers.any { it.categoryId == "cat.restos" })
        data = data.copy(categories = data.categories.map { if (it.id == "cat.restos") it.copy(nonNegotiable = true) else it })
        assertTrue(Advisor.analyze(data, today).levers.none { it.categoryId == "cat.restos" })
    }

    @Test
    fun `trois rythmes ordonnés et objectif guitare`() {
        val guitar = Goal("guitare", "Guitare", 90000, createdAt = "2026-10-30T00:00:00Z", deadline = "2027-06-30")
        val advice = Advisor.analyze(situationDifficile(listOf(guitar)), today)
        val (t, e, r) = Pace.entries.map { pace -> advice.outcomes.first { it.pace == pace } }
        assertTrue(t.monthlyCents < e.monthlyCents && e.monthlyCents < r.monthlyCents)
        val tDebt = assertNotNull(t.debtFreeOn)
        val rDebt = assertNotNull(r.debtFreeOn)
        assertTrue(!rDebt.isAfter(tDebt))
        val rGuitar = assertNotNull(r.goalsReachedOn["guitare"])
        val tGuitar = assertNotNull(t.goalsReachedOn["guitare"])
        assertTrue(!rGuitar.isAfter(tGuitar))
        val verdict = advice.goals.single()
        assertEquals(true, verdict.reachableByDeadline, verdict.text)
    }

    @Test
    fun `revenu variable - proposition de répartition priorisant le découvert`() {
        val data = situationDifficile().let {
            it.copy(transactions = it.transactions + Fx.tx("2026-10-25", 200.0, "PRIME", "cat.primes"))
        }
        val item = Advisor.analyze(data, today).items.first { it.id == "variable-income" }
        assertTrue("140,00" in item.text, item.text)
    }

    @Test
    fun `proposition d'enveloppes - 50-30-20 adaptée, avenir dédié au découvert`() {
        val proposal = assertNotNull(EnvelopePlanner.propose(situationDifficile(), today))
        assertEquals(180000, proposal.payCents)
        assertTrue(proposal.amounts.getValue("cat.courses") >= 42000)
        assertTrue(Defaults.DEBT_REPAYMENT in proposal.amounts)
        assertTrue(proposal.amounts.values.sum() <= 180000, "total ${proposal.amounts.values.sum()}")
        assertTrue(proposal.plaisirCents >= 18000)
    }
}

class IlliquidSavingsTest {
    @Test
    fun `un PEL ou une assurance vie ne sont jamais proposés pour combler le découvert`() {
        val data = Fx.data {
            copy(
                accounts = listOf(
                    Fx.bourso.copy(openingBalanceCents = -50000, openingDate = "2026-10-30", openingSetAt = "2026-10-30T23:00:00Z"),
                    Fx.ceEpargne.copy(name = "PEL", product = app.boussole.core.domain.AccountProduct.PEL, openingBalanceCents = 300000, openingDate = "2026-10-30", openingSetAt = "2026-10-30T23:00:00Z"),
                ),
            )
        }
        val s = Advisor.analyze(data, LocalDate.parse("2026-10-30")).situation
        assertEquals(0, s.usableSavingsCents)
        assertEquals(300000, s.savingsCents)
    }
}
