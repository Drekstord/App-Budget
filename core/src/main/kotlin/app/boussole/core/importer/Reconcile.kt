package app.boussole.core.importer

import app.boussole.core.domain.Classifier
import app.boussole.core.domain.Transaction
import app.boussole.core.domain.TransactionSource
import app.boussole.core.domain.TransactionStatus
import app.boussole.core.domain.VaultData
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ReconcileResult(val data: VaultData, val added: Int, val matched: Int, val alreadyImported: Int)

/**
 * Rapprochement d'un relevé avec le coffre (CAP-4) : une ligne déjà captée
 * (notification, saisie, ticket) est reliée à l'opération existante au lieu
 * d'être recréée ; une ligne déjà importée est ignorée ; le reste devient une
 * opération « à valider ».
 */
object Reconcile {
    /** Écart de dates toléré entre la capture et l'inscription au relevé. */
    private const val MAX_DAYS = 4L

    fun apply(data: VaultData, accountId: String, lines: List<StatementLine>, now: Instant): ReconcileResult {
        var transactions = data.transactions
        var added = 0
        var matched = 0
        var already = 0
        for (line in lines.sortedBy { it.date }) {
            if (transactions.any { it.importRef == line.ref }) {
                already++
                continue
            }
            val candidate = transactions
                .filter { t ->
                    t.importRef == null && t.source != TransactionSource.IMPORT && t.amountCents == line.amountCents &&
                        (t.accountId == null || t.accountId == accountId) &&
                        kotlin.math.abs(ChronoUnit.DAYS.between(LocalDate.parse(t.date), line.date)) <= MAX_DAYS
                }
                .minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(LocalDate.parse(it.date), line.date)) }
            if (candidate != null) {
                transactions = transactions.map {
                    if (it.id != candidate.id) it else it.copy(
                        importRef = line.ref,
                        accountId = accountId,
                        sourceText = listOfNotNull(it.sourceText, "Relevé : ${line.label}").joinToString("\n"),
                    )
                }
                matched++
                continue
            }
            val transaction = Transaction(
                id = UUID.randomUUID().toString(),
                createdAt = now.toString(),
                date = line.date.toString(),
                amountCents = line.amountCents,
                label = line.label.ifBlank { "Opération" },
                accountId = accountId,
                source = TransactionSource.IMPORT,
                status = TransactionStatus.TO_VALIDATE,
                sourceText = "Relevé : ${line.label}",
                importRef = line.ref,
            )
            transactions = transactions + Classifier.classify(transaction, data.copy(transactions = transactions))
            added++
        }
        return ReconcileResult(data.copy(transactions = transactions), added, matched, already)
    }
}
