package app.boussole.core.domain

/** Export CSV des opérations (en clair : réservé à un usage ponctuel, avec avertissement). */
object CsvExport {
    fun transactions(data: VaultData): String = buildString {
        appendLine("Date;Libellé;Montant;Catégorie;Compte;Statut;Note")
        for (t in data.transactions.sortedBy { it.date }) {
            val account = data.account(t.accountId)?.let { "${it.institution} — ${it.name}" }.orEmpty()
            val amount = Money.format(t.amountCents).replace(" €", "").replace(" ", "")
            appendLine(
                listOf(t.date, t.label, amount, data.category(t.categoryId)?.name.orEmpty(), account, t.status.name, t.note)
                    .joinToString(";") { cell -> "\"" + cell.replace("\"", "\"\"") + "\"" },
            )
        }
    }
}
