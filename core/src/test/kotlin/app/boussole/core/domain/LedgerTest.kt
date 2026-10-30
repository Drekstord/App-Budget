package app.boussole.core.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeriodsTest {
    @Test
    fun `le mois commence à l'arrivée réelle de la paie`() {
        val data = Fx.data(Fx.salary("2026-09-29"), Fx.salary("2026-10-30"))
        val p = Periods.containing(data, LocalDate.parse("2026-10-15"))
        assertEquals(LocalDate.parse("2026-09-29"), p.start)
        assertEquals(LocalDate.parse("2026-10-30"), p.endExclusive)
        assertEquals(false, p.estimatedEnd)
    }

    @Test
    fun `sans paie suivante, la fin est estimée et prolongée si la paie est en retard`() {
        val data = Fx.data(Fx.salary("2026-09-28")) { copy(profile = profile.copy(usualPayday = 28)) }
        val p = Periods.containing(data, LocalDate.parse("2026-10-30"))
        assertEquals(LocalDate.parse("2026-09-28"), p.start)
        assertEquals(LocalDate.parse("2026-10-31"), p.endExclusive)
        assertTrue(p.estimatedEnd)
    }

    @Test
    fun `sans aucune paie, le jour habituel du profil sert de repère, fin de mois comprise`() {
        val data = Fx.data { copy(profile = profile.copy(usualPayday = 31)) }
        val p = Periods.containing(data, LocalDate.parse("2026-11-10"))
        assertEquals(LocalDate.parse("2026-10-31"), p.start)
        assertEquals(LocalDate.parse("2026-11-30"), p.endExclusive)
    }

    @Test
    fun `deux versements rapprochés comptent pour une seule paie`() {
        val data = Fx.data(Fx.salary("2026-10-29", 1500.0), Fx.salary("2026-10-31", 300.0))
        assertEquals(listOf(LocalDate.parse("2026-10-29")), Periods.paydays(data))
    }
}

class LedgerTest {
    @Test
    fun `solde = solde saisi + opérations postérieures`() {
        val account = Fx.bourso.copy(openingBalanceCents = -40000, openingDate = "2026-10-20", openingSetAt = "2026-10-20T10:00:00Z")
        val before = Fx.tx("2026-10-19", -50.0, "AVANT")
        val sameDayBefore = Fx.tx("2026-10-20", -5.0, "MEME JOUR").copy(createdAt = "2026-10-20T09:00:00Z")
        val sameDayAfter = Fx.tx("2026-10-20", -7.0, "MEME JOUR APRES").copy(createdAt = "2026-10-20T11:00:00Z")
        val after = Fx.tx("2026-10-25", 100.0, "APRES")
        val ignored = Fx.tx("2026-10-26", -999.0, "IGNOREE", status = TransactionStatus.IGNORED)
        assertEquals(-40000 - 700 + 10000L, Ledger.balance(account, listOf(before, sameDayBefore, sameDayAfter, after, ignored)))
    }

    @Test
    fun `dépenses par catégorie - remboursements déduits, revenus et virements internes exclus`() {
        val data = Fx.data(
            Fx.salary("2026-10-01"),
            Fx.tx("2026-10-02", -30.0, "CARREFOUR", "cat.courses"),
            Fx.tx("2026-10-03", 10.0, "REMBOURSEMENT CARREFOUR", "cat.courses"),
            Fx.tx("2026-10-04", -200.0, "VIR VERS LIVRET", Defaults.INTERNAL_TRANSFER),
            Fx.tx("2026-10-05", -12.0, "INCONNU"),
        )
        val p = Periods.containing(data, LocalDate.parse("2026-10-10"))
        val spent = Ledger.spentByCategory(data, p)
        assertEquals(2000L, spent["cat.courses"])
        assertEquals(1200L, spent[null])
        assertEquals(null, spent[Defaults.INTERNAL_TRANSFER])
        assertEquals(180000L, Ledger.income(data, p))
    }
}

class ClassifierTest {
    @Test
    fun `clé marchand stable entre notification et relevé`() {
        assertEquals("CARREFOUR CITY", Merchant.key("CARTE 29/10 CARREFOUR CITY PARIS 15"))
        assertEquals("CARREFOUR CITY", Merchant.key("Carrefour City"))
        assertEquals("NETFLIX", Merchant.key("PRLV SEPA NETFLIX.COM"))
    }

    @Test
    fun `mots-clés par défaut, puis règle apprise qui l'emporte`() {
        var data = Fx.data()
        val first = Classifier.suggest("CB CARREFOUR CITY", -1250, data)
        assertEquals("cat.courses", first.categoryId)
        assertEquals(Confidence.MEDIUM, first.confidence)

        data = Classifier.learn(data, "CB CARREFOUR CITY", "cat.restos")
        assertEquals("cat.restos", Classifier.suggest("Carrefour City", -800, data).categoryId)
        data = Classifier.learn(data, "CARREFOUR CITY", "cat.restos")
        assertEquals(Confidence.HIGH, Classifier.suggest("Carrefour City", -800, data).confidence)
        // Une correction remplace la règle.
        data = Classifier.learn(data, "CARREFOUR CITY", "cat.courses")
        assertEquals("cat.courses", Classifier.suggest("CARREFOUR CITY", -800, data).categoryId)
        assertEquals(1, data.rules.size)
    }

    @Test
    fun `un revenu proche de la paie est proposé comme salaire`() {
        val data = Fx.data { copy(profile = profile.copy(netPayCents = 180000)) }
        assertEquals(Defaults.SALARY, Classifier.suggest("VIR ACME SAS", 175000, data).categoryId)
        assertEquals(null, Classifier.suggest("VIR ACME SAS", 2000, data).categoryId)
    }

    @Test
    fun `une catégorie de revenu n'est jamais proposée pour une dépense`() {
        val data = Fx.data()
        assertEquals(null, Classifier.suggest("SALAIRE", -5000, data).categoryId)
    }
}

class BudgetTest {
    private val today = LocalDate.parse("2026-10-15")

    @Test
    fun `enveloppes, couverture d'un dépassement et disponible réel`() {
        var data = Fx.data(
            Fx.salary("2026-09-30", 1800.0),
            Fx.tx("2026-10-02", -350.0, "LECLERC", "cat.courses"),
            Fx.tx("2026-10-05", -60.0, "BURGER KING", "cat.restos"),
            Fx.tx("2026-10-06", -40.0, "INCONNU"),
        )
        data = Budget.setEnvelope(data, "cat.courses", 30000)
        data = Budget.setEnvelope(data, "cat.restos", 15000)
        var view = Budget.view(data, today)
        assertEquals(listOf("cat.courses"), view.toCover.map { it.category.id })
        // Dépassement non couvert : il mange l'argent libre. 1800 − 450 dépensés − 90 réservés en restos.
        assertEquals(126000L, view.freeCents)

        data = Budget.cover(data, view.period, "cat.restos", "cat.courses", 5000)
        view = Budget.view(data, today)
        assertTrue(view.toCover.isEmpty())
        assertEquals(0L, view.envelopes.first { it.category.id == "cat.courses" }.availableCents)
        assertEquals(4000L, view.envelopes.first { it.category.id == "cat.restos" }.availableCents)
        // Couvert par l'enveloppe restos : l'argent libre est préservé.
        assertEquals(131000L, view.freeCents)
        assertEquals(131000L / view.daysLeft, view.dailyCents)
    }

    @Test
    fun `échéance fixe à venir déduite, sauf si elle est déjà passée`() {
        val charge = FixedCharge("f1", "Assurance auto MAIF", 4500, dayOfMonth = 20, categoryId = "cat.assurances")
        var data = Fx.data(Fx.salary("2026-09-30", 1800.0)) { copy(fixedCharges = listOf(charge)) }
        var view = Budget.view(data, today)
        assertEquals(listOf("f1"), view.upcoming.map { it.charge.id })
        assertEquals(180000L - 4500, view.freeCents)

        data = data.copy(transactions = data.transactions + Fx.tx("2026-10-19", -45.0, "PRLV MAIF ASSURANCE", "cat.assurances"))
        view = Budget.view(data, today)
        assertTrue(view.upcoming.isEmpty())
        assertEquals(180000L - 4500, view.freeCents)
    }
}
