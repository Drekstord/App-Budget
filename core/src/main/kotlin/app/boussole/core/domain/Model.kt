package app.boussole.core.domain

import kotlinx.serialization.Serializable

// Modèle minimal de la phase 1 (socle) : il s'étoffera avec la capture
// (phase 2), les enveloppes et le profil de vie (phase 4). Chaque entité porte
// un identifiant stable et des horodatages pour une future synchronisation.

@Serializable
data class VaultData(
    val schemaVersion: Int = SCHEMA_VERSION,
    val accounts: List<Account> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
enum class AccountKind { CHECKING, SAVINGS, CASH }

@Serializable
data class Account(
    val id: String,
    val name: String,
    val kind: AccountKind,
    val institution: String,
)

/** D'où vient une opération : la provenance compte pour le rapprochement. */
@Serializable
enum class TransactionSource { MANUAL, NOTIFICATION, IMPORT, RECEIPT }

@Serializable
enum class TransactionStatus { TO_VALIDATE, VALIDATED }

@Serializable
data class Transaction(
    val id: String,
    /** Instant ISO-8601 de création de l'enregistrement. */
    val createdAt: String,
    /** Date comptable, AAAA-MM-JJ. */
    val date: String,
    /** Montant signé en centimes : négatif pour une dépense, positif pour un revenu. */
    val amountCents: Long,
    val label: String,
    val accountId: String? = null,
    val categoryId: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val status: TransactionStatus = TransactionStatus.TO_VALIDATE,
)
