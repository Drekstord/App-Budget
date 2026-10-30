package app.boussole.core.capture

import app.boussole.core.domain.AccountKind
import app.boussole.core.domain.Classifier
import app.boussole.core.domain.Transaction
import app.boussole.core.domain.TransactionSource
import app.boussole.core.domain.TransactionStatus
import app.boussole.core.domain.VaultData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Une notification retenue par le service de capture, telle quelle : l'analyse
 * est faite à l'ouverture du coffre, pour profiter des améliorations de
 * l'analyseur sans perdre les captures déjà faites.
 */
@Serializable
data class CapturedItem(
    val id: String,
    val packageName: String,
    /** Instant de publication de la notification (ms depuis l'époque). */
    val postedAtMillis: Long,
    val title: String,
    val text: String,
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(bytes: ByteArray): CapturedItem = json.decodeFromString(serializer(), bytes.decodeToString())
    }
}

data class MergeResult(val data: VaultData, val added: Int, val duplicates: Int, val ignored: Int)

/** Transforme les captures en dépenses « à valider », sans doublon. */
object Captures {
    /** Même montant à moins de 15 minutes d'intervalle : même paiement (ex. Wallet + banque). */
    private val SAME_PAYMENT = Duration.ofMinutes(15)

    fun merge(data: VaultData, items: List<CapturedItem>, zone: ZoneId): MergeResult {
        var current = data
        var added = 0
        var duplicates = 0
        var ignored = 0
        for (item in items.sortedBy { it.postedAtMillis }) {
            val parsed = NotificationParser.parse(item.title, item.text)
            if (parsed == null) {
                ignored++
                continue
            }
            val posted = Instant.ofEpochMilli(item.postedAtMillis)
            val duplicate = current.transactions.firstOrNull { t ->
                t.source == TransactionSource.NOTIFICATION && t.amountCents == parsed.amountCents &&
                    Duration.between(Instant.parse(t.createdAt), posted).abs() <= SAME_PAYMENT
            }
            if (duplicate != null) {
                duplicates++
                // Garder le libellé le plus parlant (le marchand plutôt que « Paiement »).
                if (parsed.merchant != null && duplicate.label == kindLabel(parsed.kind)) {
                    current = current.copy(transactions = current.transactions.map {
                        if (it.id == duplicate.id) Classifier.classify(it.copy(label = parsed.merchant, categoryId = null), current) else it
                    })
                }
                continue
            }
            val transaction = Transaction(
                id = UUID.randomUUID().toString(),
                createdAt = posted.toString(),
                date = posted.atZone(zone).toLocalDate().toString(),
                amountCents = parsed.amountCents,
                label = parsed.merchant ?: kindLabel(parsed.kind),
                accountId = accountFor(current, item.packageName),
                source = TransactionSource.NOTIFICATION,
                status = TransactionStatus.TO_VALIDATE,
                sourceText = listOf(item.title, item.text).filter { it.isNotBlank() }.joinToString(" — "),
            )
            current = current.copy(transactions = current.transactions + Classifier.classify(transaction, current))
            added++
        }
        return MergeResult(current, added, duplicates, ignored)
    }

    fun kindLabel(kind: CaptureKind): String = when (kind) {
        CaptureKind.PAYMENT -> "Paiement"
        CaptureKind.WITHDRAWAL -> "Retrait d'espèces"
        CaptureKind.DIRECT_DEBIT -> "Prélèvement"
        CaptureKind.TRANSFER_OUT -> "Virement émis"
        CaptureKind.INCOME -> "Argent reçu"
    }

    /**
     * Compte d'une capture : réglage explicite, sinon la banque devinée d'après
     * l'app (Samsung Wallet → carte Boursobank, qui sert au quotidien).
     */
    fun accountFor(data: VaultData, packageName: String): String? {
        data.settings.captureAccounts[packageName]?.let { id -> if (data.account(id) != null) return id }
        val checking = data.accounts.filter { it.kind == AccountKind.CHECKING }
        fun byInstitution(fragment: String) =
            checking.firstOrNull { NotificationParser.normalize(it.institution).contains(fragment) }?.id
        val pkg = packageName.lowercase()
        return when {
            "caisse" in pkg || "epargne" in pkg || pkg.startsWith("fr.ce") -> byInstitution("caisse")
            else -> byInstitution("bourso")
        } ?: checking.firstOrNull()?.id
    }
}
