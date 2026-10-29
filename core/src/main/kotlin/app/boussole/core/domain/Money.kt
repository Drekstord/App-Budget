package app.boussole.core.domain

import kotlin.math.absoluteValue

/**
 * Montants en centimes (Long) : jamais de virgule flottante pour de l'argent.
 * Formatage français déterministe (identique sur la JVM et sur Android) :
 * espace fine insécable pour les milliers, espace insécable avant « € ».
 */
object Money {
    private const val THOUSANDS = ' '
    private const val BEFORE_SYMBOL = ' '

    fun format(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = cents.absoluteValue
        val units = (abs / 100).toString().reversed().chunked(3).joinToString(THOUSANDS.toString()).reversed()
        val decimals = (abs % 100).toString().padStart(2, '0')
        return "$sign$units,$decimals$BEFORE_SYMBOL€"
    }

    /**
     * Lit une saisie française (« 12,50 », « 1 234,5 », « 12.50 € »). Retourne
     * null si ce n'est pas un montant valide à deux décimales au plus.
     */
    fun parse(input: String): Long? {
        val cleaned = input.filterNot { it.isWhitespace() || it == '€' || it == THOUSANDS || it == BEFORE_SYMBOL }
            .replace(',', '.')
        val match = Regex("""^(-?)(\d{1,13})(?:\.(\d{1,2}))?$""").matchEntire(cleaned) ?: return null
        val (sign, units, decimals) = match.destructured
        val cents = units.toLong() * 100 + decimals.padEnd(2, '0').toLong()
        return if (sign == "-") -cents else cents
    }
}
