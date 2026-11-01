package app.boussole.core.importer

import app.boussole.core.domain.Fx
import app.boussole.core.domain.TransactionSource
import app.boussole.core.domain.TransactionStatus
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StatementImportTest {
    @Test
    fun `CSV façon Boursobank (montant signé, guillemets, point-virgule)`() {
        val csv = """
            dateOp;dateVal;label;category;categoryParent;supplierFound;amount;comment;accountNum;accountLabel;accountbalance
            2026-10-29;2026-10-29;"CARTE 28/10/26 CARREFOUR CITY CB*1234";"Alimentation";"Vie quotidienne";"carrefour city";-12,50;;"0001";"BoursoBank";-412,30
            2026-10-30;2026-10-30;"VIR SALAIRE ACME";"Salaire";"Revenus";"";1 800,00;;"0001";"BoursoBank";1387,70
        """.trimIndent()
        val lines = StatementImport.parse(csv.encodeToByteArray())
        assertEquals(2, lines.size)
        assertEquals(LocalDate.parse("2026-10-29"), lines[0].date)
        assertEquals(-1250, lines[0].amountCents)
        assertEquals("CARTE 28/10/26 CARREFOUR CITY CB*1234", lines[0].label)
        assertEquals(180000, lines[1].amountCents)
    }

    @Test
    fun `CSV façon Caisse d'Épargne (débit - crédit séparés, Windows-1252)`() {
        val csv = "Date;Numéro d'opération;Libellé;Débit;Crédit;Détail\r\n" +
            "29/10/2026;123;PRLV SEPA NETFLIX.COM;-13,49;;\r\n" +
            "31/10/2026;124;VIR REMBOURSEMENT MUTUELLE;;25,00;\r\n"
        val lines = StatementImport.parse(csv.toByteArray(charset("windows-1252")))
        assertEquals(listOf(-1349L, 2500L), lines.map { it.amountCents })
        assertEquals("PRLV SEPA NETFLIX.COM", lines[0].label)
    }

    @Test
    fun `OFX avec FITID`() {
        val ofx = """
            OFXHEADER:100
            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261029120000<TRNAMT>-12.50<FITID>ABC123<NAME>CARREFOUR CITY<MEMO>CB 28/10</STMTTRN>
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261030<TRNAMT>1800.00<FITID>ABC124<NAME>SALAIRE ACME</STMTTRN>
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
        """.trimIndent()
        val lines = StatementImport.parse(ofx.encodeToByteArray())
        assertEquals(listOf("ofx:ABC123", "ofx:ABC124"), lines.map { it.ref })
        assertEquals("CARREFOUR CITY CB 28/10", lines[0].label)
        assertEquals(-1250, lines[0].amountCents)
    }

    @Test
    fun `montants dans tous les formats`() {
        assertEquals(-123456L, StatementImport.parseAmount("-1 234,56"))
        assertEquals(123456L, StatementImport.parseAmount("1.234,56"))
        assertEquals(123456L, StatementImport.parseAmount("1,234.56"))
        assertEquals(1250L, StatementImport.parseAmount("12.5"))
        assertEquals(123400L, StatementImport.parseAmount("1.234"))
        assertEquals(-990L, StatementImport.parseAmount("\"-9,90 €\""))
    }

    @Test
    fun `fichier non reconnu - le message montre la première ligne`() {
        val error = assertFailsWith<ImportError> { StatementImport.parse("bonjour;monde\n1;2".encodeToByteArray()) }
        assertTrue("bonjour | monde" in error.message!!)
    }

    @Test
    fun `CSV Unicode d'Excel (UTF-16 avec marque) et en-têtes entre guillemets`() {
        val csv = "\"dateOp\";\"dateVal\";\"label\";\"amount\"\r\n\"2026-10-29\";\"2026-10-29\";\"CARTE CAFÉ\";\"-3,20\"\r\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + csv.toByteArray(Charsets.UTF_16LE)
        val lines = StatementImport.parse(bytes)
        assertEquals(listOf(-320L), lines.map { it.amountCents })
        assertEquals("CARTE CAFÉ", lines[0].label)
    }

    @Test
    fun `libellé entre guillemets sur plusieurs lignes`() {
        val csv = "dateOp;label;amount\n2026-10-29;\"VIR SEPA\nLOYER\";-500,00\n2026-10-30;\"CB BOULANGERIE\";-2,10\n"
        val lines = StatementImport.parse(csv.encodeToByteArray())
        assertEquals(listOf("VIR SEPA LOYER", "CB BOULANGERIE"), lines.map { it.label })
    }

    @Test
    fun `en-têtes inconnus - colonnes devinées d'après le contenu`() {
        val csv = "Jour;Compte;Opération faite;Valeur;Solde\n" +
            "29/10/2026;00012345;CARTE CARREFOUR;-12,50;-412,30\n" +
            "30/10/2026;00012345;VIR SALAIRE ACME;1 800,00;1387,70\n"
        val lines = StatementImport.parse(csv.encodeToByteArray())
        assertEquals(listOf(-1250L, 180000L), lines.map { it.amountCents })
        assertEquals("CARTE CARREFOUR", lines[0].label)
    }

    @Test
    fun `sans en-tête du tout`() {
        val csv = "29/10/2026;CARTE CARREFOUR;-12,50\n30/10/2026;VIR SALAIRE ACME;1800,00\n"
        assertEquals(listOf(-1250L, 180000L), StatementImport.parse(csv.encodeToByteArray()).map { it.amountCents })
    }

    @Test
    fun `classeur Excel et PDF refusés avec une explication`() {
        val xlsx = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertTrue("Excel" in assertFailsWith<ImportError> { StatementImport.parse(xlsx) }.message!!)
        val pdf = "%PDF-1.7\n".encodeToByteArray()
        assertTrue("PDF" in assertFailsWith<ImportError> { StatementImport.parse(pdf) }.message!!)
    }
}

class ReconcileTest {
    private val now = Instant.parse("2026-11-01T10:00:00Z")

    @Test
    fun `rapproche la capture, ajoute le reste, ignore un second import`() {
        val captured = Fx.tx("2026-10-28", -12.50, "Carrefour City", "cat.courses", status = TransactionStatus.VALIDATED, source = TransactionSource.NOTIFICATION)
        val data = Fx.data(captured)
        val lines = listOf(
            StatementLine(LocalDate.parse("2026-10-30"), "CARTE 28/10 CARREFOUR CITY", -1250, "csv:1"),
            StatementLine(LocalDate.parse("2026-10-30"), "PRLV SEPA NETFLIX.COM", -1349, "csv:2"),
        )
        val first = Reconcile.apply(data, Fx.bourso.id, lines, now)
        assertEquals(1, first.matched)
        assertEquals(1, first.added)
        val netflix = first.data.transactions.single { it.importRef == "csv:2" }
        assertEquals("cat.abonnements", netflix.categoryId)
        assertEquals(TransactionStatus.TO_VALIDATE, netflix.status)
        assertEquals("csv:1", first.data.transactions.single { it.id == captured.id }.importRef)

        val second = Reconcile.apply(first.data, Fx.bourso.id, lines, now)
        assertEquals(2, second.alreadyImported)
        assertEquals(first.data.transactions.size, second.data.transactions.size)
    }
}
