package app.boussole.core.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurringTest {
    @Test
    fun `repère un abonnement mensuel, pas une habitude`() {
        val data = Fx.data(
            Fx.tx("2026-08-05", -13.49, "PRLV NETFLIX.COM", "cat.abonnements"),
            Fx.tx("2026-09-05", -13.49, "PRLV NETFLIX.COM", "cat.abonnements"),
            Fx.tx("2026-10-06", -13.49, "PRLV NETFLIX.COM", "cat.abonnements"),
            Fx.tx("2026-09-02", -8.0, "STARBUCKS", "cat.restos"),
            Fx.tx("2026-09-12", -8.0, "STARBUCKS", "cat.restos"),
            Fx.tx("2026-10-03", -8.0, "STARBUCKS", "cat.restos"),
        )
        val found = Recurring.detect(data, LocalDate.parse("2026-10-30"))
        assertEquals(listOf("Netflix"), found.map { it.name })
        assertEquals(1349, found.single().amountCents)
        assertEquals(5, found.single().dayOfMonth)
    }
}
