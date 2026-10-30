package app.boussole.core.capture

import java.time.LocalDate

data class ParsedReceipt(
    val amountCents: Long?,
    val date: LocalDate?,
    val merchant: String?,
    /** Catégorie suggérée (identifiant de catégorie par défaut). */
    val categoryId: String?,
)

/**
 * Analyse du texte reconnu sur un ticket de caisse (OCR fait sur le téléphone) :
 * total, date, enseigne. Heuristiques prudentes : le résultat est toujours
 * soumis à confirmation.
 */
object ReceiptParser {
    private class Brand(val match: String, val display: String, val categoryId: String)

    private val BRANDS = listOf(
        Brand("carrefour", "Carrefour", "cat.courses"), Brand("leclerc", "E.Leclerc", "cat.courses"),
        Brand("lidl", "Lidl", "cat.courses"), Brand("auchan", "Auchan", "cat.courses"),
        Brand("intermarche", "Intermarché", "cat.courses"), Brand("monoprix", "Monoprix", "cat.courses"),
        Brand("franprix", "Franprix", "cat.courses"), Brand("casino", "Casino", "cat.courses"),
        Brand("aldi", "Aldi", "cat.courses"), Brand("super u", "Super U", "cat.courses"),
        Brand("hyper u", "Hyper U", "cat.courses"), Brand("picard", "Picard", "cat.courses"),
        Brand("biocoop", "Biocoop", "cat.courses"), Brand("grand frais", "Grand Frais", "cat.courses"),
        Brand("boulangerie", "Boulangerie", "cat.courses"),
        Brand("mcdonald", "McDonald's", "cat.restos"), Brand("burger king", "Burger King", "cat.restos"),
        Brand("kfc", "KFC", "cat.restos"), Brand("subway", "Subway", "cat.restos"),
        Brand("domino", "Domino's", "cat.restos"), Brand("restaurant", "Restaurant", "cat.restos"),
        Brand("brasserie", "Brasserie", "cat.restos"), Brand("pizza", "Pizzeria", "cat.restos"),
        Brand("totalenergies", "TotalEnergies", "cat.transport"), Brand("esso", "Esso", "cat.transport"),
        Brand("shell", "Shell", "cat.transport"), Brand("station service", "Station-service", "cat.transport"),
        Brand("sncf", "SNCF", "cat.transport"), Brand("parking", "Parking", "cat.transport"),
        Brand("parapharmacie", "Parapharmacie", "cat.sante"), Brand("pharmacie", "Pharmacie", "cat.sante"),
        Brand("cinema", "Cinéma", "cat.sorties"), Brand("pathe", "Pathé", "cat.sorties"),
        Brand("decathlon", "Decathlon", "cat.shopping"), Brand("fnac", "Fnac", "cat.shopping"),
        Brand("kiabi", "Kiabi", "cat.shopping"), Brand("zara", "Zara", "cat.shopping"),
        Brand("primark", "Primark", "cat.shopping"), Brand("action", "Action", "cat.shopping"),
        Brand("woodbrass", "Woodbrass", "cat.musique"), Brand("thomann", "Thomann", "cat.musique"),
    )

    private val PRIMARY = Regex("\\b(total|montant|a\\s*payer|net\\s*a\\s*payer|ttc|somme\\s*due)\\b")
    private val PAYMENT = Regex("\\b(cb|carte\\s*(bancaire|bleue)|especes|cheque|paiement)\\b")
    private val EXCLUDED = Regex("\\b(rendu|monnaie|rendre|remise|reduction|economie|cagnotte)\\b")
    private val HEADER_NOISE = Regex(
        "\\b(ticket|caisse|recu|facture|bienvenue|merci|tel|tva|siret|siren|rcs|www|http|date|heure|client|vendeur|magasin\\s*n)\\b|^\\W*$|^\\d[\\d\\s/.:-]*$",
    )
    private val AMOUNT = Regex("(\\d{1,3}(?:[\\s.]\\d{3})*|\\d{1,6})\\s*[,.]\\s*(\\d{2})(?!\\d)")
    private val NUMERIC_DATE = Regex("\\b(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2}(?:\\d{2})?)\\b")
    private val TEXT_DATE = Regex("\\b(\\d{1,2})\\s+([a-z]{3,9})\\.?\\s+(\\d{2}(?:\\d{2})?)\\b")
    private val MONTHS = mapOf(
        "janvier" to 1, "fevrier" to 2, "mars" to 3, "avril" to 4, "mai" to 5, "juin" to 6, "juillet" to 7,
        "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12,
        "janv" to 1, "fevr" to 2, "avr" to 4, "juil" to 7, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    fun parse(text: String): ParsedReceipt {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val normalized = lines.map(NotificationParser::normalize)

        data class Found(val cents: Long, val line: Int)
        val amounts = lines.flatMapIndexed { i, line ->
            AMOUNT.findAll(line).mapNotNull { m ->
                val whole = m.groupValues[1].filter(Char::isDigit).toLongOrNull() ?: return@mapNotNull null
                val cents = whole * 100 + m.groupValues[2].toLong()
                if (cents in 1 until 10_000_000) Found(cents, i) else null
            }.toList()
        }.filterNot { EXCLUDED.containsMatchIn(normalized[it.line]) }

        fun near(keywords: Regex): List<Found> {
            val indices = mutableSetOf<Int>()
            normalized.forEachIndexed { i, line ->
                if (!keywords.containsMatchIn(line)) return@forEachIndexed
                indices += i
                // Total parfois renvoyé à la ligne suivante.
                if (amounts.none { it.line == i }) indices += i + 1
            }
            return amounts.filter { it.line in indices }
        }
        val pool = near(PRIMARY).ifEmpty { near(PAYMENT) }.ifEmpty { amounts }
        val amount = pool.maxOfOrNull { it.cents }

        val whole = NotificationParser.normalize(text)
        val brand = BRANDS.firstOrNull { whole.contains(it.match) }
        val merchant = brand?.display ?: lines.take(6).firstOrNull { line ->
            val norm = NotificationParser.normalize(line)
            norm.count { it in 'a'..'z' } >= 3 && !HEADER_NOISE.containsMatchIn(norm)
        }?.replace(Regex("\\s{2,}"), " ")?.take(40)

        return ParsedReceipt(amount, findDate(whole), merchant, brand?.categoryId)
    }

    private fun findDate(text: String): LocalDate? {
        NUMERIC_DATE.findAll(text).forEach { m ->
            toDate(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { return it }
        }
        TEXT_DATE.findAll(text).forEach { m ->
            val month = MONTHS[m.groupValues[2]] ?: return@forEach
            toDate(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())?.let { return it }
        }
        return null
    }

    private fun toDate(year: Int, month: Int, day: Int): LocalDate? {
        val y = if (year < 100) year + 2000 else year
        if (y !in 2000..2100) return null
        return runCatching { LocalDate.of(y, month, day) }.getOrNull()
    }
}
