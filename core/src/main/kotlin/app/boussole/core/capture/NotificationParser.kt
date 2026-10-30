package app.boussole.core.capture

import app.boussole.core.domain.Confidence
import java.text.Normalizer
import java.util.Locale

enum class CaptureKind { PAYMENT, WITHDRAWAL, DIRECT_DEBIT, TRANSFER_OUT, INCOME }

data class ParsedNotification(
    /** Montant signé en centimes (négatif = argent qui sort). */
    val amountCents: Long,
    val merchant: String?,
    val kind: CaptureKind,
    val confidence: Confidence,
)

/**
 * Lecture des notifications bancaires (Boursobank, Caisse d'Épargne, Samsung
 * Wallet…). Les formats exacts varient selon les banques et changent dans le
 * temps : l'analyse repose sur des indices robustes (montant en euros, verbes
 * d'opération, « chez … ») plutôt que sur un gabarit figé. Une notification sans
 * montant ou de sécurité (code, validation 3-D Secure, refus) est ignorée.
 */
object NotificationParser {
    private const val SPACES = "   "
    private val AMOUNT = Regex(
        "(?<![\\d,.])([+\\-−]\\s?)?(\\d{1,3}(?:[$SPACES.]\\d{3})+|\\d+)(?:[.,](\\d{1,2}))?\\s?(?:€|EUR\\b|euros?\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT_PREFIX = Regex(
        "(?:€|EUR)\\s?([+\\-−]\\s?)?(\\d{1,3}(?:[$SPACES.]\\d{3})+|\\d+)(?:[.,](\\d{1,2}))?",
        RegexOption.IGNORE_CASE,
    )

    /** Notifications à ignorer : sécurité, refus, simples informations de solde. */
    private val IGNORE = Regex(
        "\\b(code|authentifi\\w*|valide[rz]? (votre|le|ce) paiement|confirme[rz]?|3d ?secure|connexion|" +
            "mot de passe|otp|refuse|refus|echec|annule|bloque)\\b",
    )
    private val BALANCE_CONTEXT = Regex("(solde|plafond|disponible|encours)\\W{0,3}$")

    private val DEBIT = Regex(
        "\\b(paiement|paye|payee|achat|depense|debit|debite|preleve|prelevement|carte|transaction|vous avez paye)\\b",
    )
    private val WITHDRAWAL = Regex("\\b(retrait|dab|distributeur)\\b")
    private val DIRECT_DEBIT = Regex("\\b(prelevement|preleve|prlv)\\b")
    private val TRANSFER_OUT = Regex("\\bvirement (emis|envoye|vers|effectue|sortant)\\b")
    private val CREDIT = Regex(
        "\\b(recu|recue|credit|credite|versement|remboursement|rembourse|virement de|virement entrant|vous avez recu)\\b",
    )

    private val MERCHANT = Regex(
        "(?:\\bchez|\\bauprès d[eu]|\\bà|\\bau|\\bvers|\\bde la part de|\\bde)\\s+" +
            "([A-Za-zÀ-ÿ][\\w&'’.\\-*]*(?:\\s+[\\w&'’.\\-*]+){0,4}?)" +
            "(?=\\s*(?:[,.;:!()]|\\s(?:le|du|avec|pour|a été|est|sur|via|depuis|par|reçu|de|d'un|d’un|-)\\b|$))",
    )

    /** Marchand en majuscules juste après le montant : « -12,50 EUR CARREFOUR CITY ». */
    private val MERCHANT_AFTER_AMOUNT = Regex("(?:€|EUR\\b)\\s*[-–:]?\\s*([A-Z][A-Z0-9&'.*\\-]*(?: [A-Z0-9&'.*\\-]+){0,4})")

    /** Titres qui décrivent l'opération plutôt que le marchand. */
    private val GENERIC_TITLE = Regex(
        "\\b(paiement|transaction|operation|achat|alerte|notification|compte|boursobank|bourso|boursorama|" +
            "caisse d'epargne|wallet|samsung|carte|debit|credit|virement|prelevement|retrait|depense)\\b",
    )

    fun parse(title: String, text: String): ParsedNotification? {
        val raw = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")
        val plain = normalize(raw)
        if (IGNORE.containsMatchIn(plain)) return null

        val amount = findAmount(raw) ?: return null
        val kind = when {
            amount.explicitSign == '+' -> CaptureKind.INCOME
            WITHDRAWAL.containsMatchIn(plain) -> CaptureKind.WITHDRAWAL
            TRANSFER_OUT.containsMatchIn(plain) -> CaptureKind.TRANSFER_OUT
            DIRECT_DEBIT.containsMatchIn(plain) -> CaptureKind.DIRECT_DEBIT
            amount.explicitSign == '-' -> CaptureKind.PAYMENT
            CREDIT.containsMatchIn(plain) && !DEBIT.containsMatchIn(plain) -> CaptureKind.INCOME
            else -> CaptureKind.PAYMENT
        }
        val keyword = amount.explicitSign != null || DEBIT.containsMatchIn(plain) || CREDIT.containsMatchIn(plain) ||
            WITHDRAWAL.containsMatchIn(plain)
        val merchant = if (kind == CaptureKind.WITHDRAWAL) null else findMerchant(raw, title)
        val confidence = when {
            merchant != null && keyword -> Confidence.HIGH
            merchant != null || keyword -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val signed = if (kind == CaptureKind.INCOME) amount.cents else -amount.cents
        return ParsedNotification(signed, merchant, kind, confidence)
    }

    private class Amount(val cents: Long, val explicitSign: Char?)

    private fun findAmount(raw: String): Amount? {
        val candidates = AMOUNT.findAll(raw).map { it to it.groupValues } +
            AMOUNT_PREFIX.findAll(raw).map { it to it.groupValues }
        return candidates
            .sortedBy { it.first.range.first }
            .filterNot { (match, _) -> BALANCE_CONTEXT.containsMatchIn(normalize(raw.substring(0, match.range.first))) }
            .firstNotNullOfOrNull { (_, groups) ->
                val units = groups[2].filter(Char::isDigit).toLongOrNull() ?: return@firstNotNullOfOrNull null
                val cents = units * 100 + groups[3].padEnd(2, '0').ifEmpty { "00" }.toLong()
                if (cents <= 0) return@firstNotNullOfOrNull null
                val sign = groups[1].trim().firstOrNull()?.let { if (it == '+') '+' else '-' }
                Amount(cents, sign)
            }
    }

    private fun findMerchant(raw: String, title: String): String? {
        MERCHANT.findAll(raw)
            .map { it.groupValues[1].trim().trimEnd('.', '-', '*') }
            .firstOrNull { candidate -> candidate.length >= 2 && !AMOUNT.containsMatchIn(candidate) && candidate.any(Char::isLetter) }
            ?.let { return it }
        MERCHANT_AFTER_AMOUNT.find(raw)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length >= 2 && !GENERIC_TITLE.containsMatchIn(normalize(it)) }
            ?.let { return it }
        val cleanTitle = title.trim()
        if (cleanTitle.length in 2..40 && !GENERIC_TITLE.containsMatchIn(normalize(cleanTitle)) &&
            !AMOUNT.containsMatchIn(cleanTitle) && cleanTitle.any(Char::isLetter)
        ) return cleanTitle
        return null
    }

    /** Minuscules sans accents. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
}
