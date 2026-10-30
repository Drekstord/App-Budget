package app.boussole.core.domain

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Normalisation des libellés bancaires en une « clé marchand » stable. */
object Merchant {
    /** Mots qui décrivent l'opération, pas le marchand. */
    private val NOISE = setOf(
        "CB", "CARTE", "PAIEMENT", "PAYE", "ACHAT", "PRLV", "PRELEVEMENT", "PRELEVT", "SEPA", "VIR", "VIREMENT",
        "INST", "INSTANTANE", "RECU", "EMIS", "FACT", "FACTURE", "RETRAIT", "DAB", "EUR", "EUROS", "SAS", "SA",
        "SARL", "LE", "LA", "LES", "DE", "DU", "DES", "ET", "CHEZ", "AUPRES", "A", "AU", "AUX", "EN", "PAR", "POUR",
        "WWW", "COM", "FR", "HTTPS", "HTTP", "MOBILE", "SANS", "CONTACT", "APPLE", "PAY", "GOOGLE", "SAMSUNG",
        "WALLET", "VOTRE", "VOUS", "AVEZ", "DEPENSE", "TRANSACTION", "OPERATION", "DEBIT", "CREDIT",
    )

    /** Tous les mots significatifs, en majuscules sans accents. */
    fun words(label: String): List<String> =
        Normalizer.normalize(label.uppercase(Locale.ROOT), Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .replace(Regex("[^A-Z0-9]+"), " ")
            .split(' ')
            .filter { it.isNotEmpty() && it.none(Char::isDigit) && it !in NOISE }

    /** Clé de règle : les deux premiers mots significatifs (« CARREFOUR CITY »). */
    fun key(label: String): String = words(label).take(2).joinToString(" ")
}

enum class Confidence { HIGH, MEDIUM, LOW, NONE }

data class Suggestion(val categoryId: String?, val confidence: Confidence, val reason: String?)

/**
 * Classement automatique : règles apprises des corrections (CLA-2), puis
 * mots-clés par défaut, puis indices (montant proche de la paie).
 */
object Classifier {
    fun suggest(label: String, amountCents: Long, data: VaultData): Suggestion {
        val isIncome = amountCents > 0
        fun allowed(categoryId: String): Boolean {
            val category = data.category(categoryId) ?: return false
            if (category.archived) return false
            // Une catégorie de dépense reste possible pour un montant positif :
            // c'est un remboursement, qui diminue la dépense.
            return category.family != Family.REVENU || isIncome
        }

        val key = Merchant.key(label)
        if (key.isNotEmpty()) {
            data.rules.firstOrNull { it.merchantKey == key && allowed(it.categoryId) }?.let { rule ->
                val strong = rule.origin == RuleOrigin.USER || rule.hits >= 2
                return Suggestion(rule.categoryId, if (strong) Confidence.HIGH else Confidence.MEDIUM, "Règle apprise : $key")
            }
            val first = key.substringBefore(' ')
            data.rules.firstOrNull { it.merchantKey == first && allowed(it.categoryId) }?.let { rule ->
                return Suggestion(rule.categoryId, Confidence.MEDIUM, "Règle apprise : ${rule.merchantKey}")
            }
        }

        val words = Merchant.words(label)
        val text = " " + words.joinToString(" ") + " "
        Defaults.keywords.entries
            .filter { (keyword, categoryId) -> " $keyword " in text && allowed(categoryId) }
            .filter { (_, categoryId) -> (data.category(categoryId)?.family == Family.REVENU) == isIncome }
            .maxByOrNull { it.key.length }
            ?.let { (keyword, categoryId) -> return Suggestion(categoryId, Confidence.MEDIUM, "Mot-clé : $keyword") }

        if (isIncome) {
            val pay = Ledger.referencePay(data)
            if (pay > 0 && amountCents >= pay * 6 / 10) {
                return Suggestion(Defaults.SALARY, Confidence.LOW, "Montant proche de ta paie")
            }
        }
        return Suggestion(null, Confidence.NONE, null)
    }

    /** Retient la catégorie choisie pour ce marchand ; une correction remplace l'ancienne règle. */
    fun learn(data: VaultData, label: String, categoryId: String): VaultData {
        val key = Merchant.key(label)
        if (key.isEmpty()) return data
        val existing = data.rules.firstOrNull { it.merchantKey == key }
        val rules = when {
            existing == null -> data.rules + CategoryRule(UUID.randomUUID().toString(), key, categoryId)
            existing.categoryId == categoryId -> data.rules.map { if (it === existing) it.copy(hits = it.hits + 1) else it }
            else -> data.rules.map { if (it === existing) it.copy(categoryId = categoryId, hits = 1) else it }
        }
        return data.copy(rules = rules)
    }

    /** Applique une suggestion à une opération à valider (sans écraser un choix existant). */
    fun classify(transaction: Transaction, data: VaultData): Transaction {
        if (transaction.categoryId != null) return transaction
        val suggestion = suggest(transaction.label, transaction.amountCents, data)
        return transaction.copy(categoryId = suggestion.categoryId, suggestionReason = suggestion.reason)
    }
}
