package app.boussole.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {
    @Test
    fun `formatage français`() {
        assertEquals("0,05 €", Money.format(5))
        assertEquals("12,50 €", Money.format(1250))
        assertEquals("1 234,56 €", Money.format(123456))
        assertEquals("-1 000 000,00 €", Money.format(-100_000_000))
    }

    @Test
    fun `lecture d'une saisie`() {
        assertEquals(1250, Money.parse("12,50"))
        assertEquals(1250, Money.parse("12,5"))
        assertEquals(1250, Money.parse("12.50 €"))
        assertEquals(123456, Money.parse("1 234,56"))
        assertEquals(1200, Money.parse("12"))
        assertEquals(-900, Money.parse("-9"))
        assertNull(Money.parse("12,505"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("1,2,3"))
    }
}
