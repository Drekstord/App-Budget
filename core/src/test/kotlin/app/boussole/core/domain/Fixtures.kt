package app.boussole.core.domain

import java.util.concurrent.atomic.AtomicInteger

private val counter = AtomicInteger()

/** Petite fabrique de données de test lisibles. */
object Fx {
    val bourso = Account("acc.bourso", "Compte courant", AccountKind.CHECKING, "Boursobank")
    val ceCourant = Account("acc.ce", "Compte courant", AccountKind.CHECKING, "Caisse d'Épargne")
    val ceEpargne = Account("acc.livret", "Épargne", AccountKind.SAVINGS, "Caisse d'Épargne")

    fun data(vararg transactions: Transaction, block: VaultData.() -> VaultData = { this }): VaultData =
        VaultData(accounts = listOf(bourso, ceCourant, ceEpargne), transactions = transactions.toList())
            .normalized()
            .block()

    fun tx(
        date: String,
        euros: Double,
        label: String,
        category: String? = null,
        account: String = bourso.id,
        status: TransactionStatus = TransactionStatus.VALIDATED,
        source: TransactionSource = TransactionSource.MANUAL,
    ) = Transaction(
        id = "t${counter.incrementAndGet()}",
        createdAt = "${date}T12:00:00Z",
        date = date,
        amountCents = Math.round(euros * 100),
        label = label,
        accountId = account,
        categoryId = category,
        source = source,
        status = status,
    )

    fun salary(date: String, euros: Double = 1800.0) = tx(date, euros, "VIR SALAIRE ACME", Defaults.SALARY)
}
