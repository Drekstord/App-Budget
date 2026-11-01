package app.boussole.core.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Une ligne de relevé bancaire. */
data class StatementLine(
    val date: LocalDate,
    val label: String,
    val amountCents: Long,
    /** Identifiant stable de la ligne (FITID OFX, ou empreinte), pour ne jamais l'importer deux fois. */
    val ref: String,
)

class ImportError(message: String) : Exception(message)

/**
 * Lecture des relevés exportés depuis le site de la banque (Boursobank et
 * Caisse d'Épargne proposent CSV et OFX). Les colonnes CSV sont reconnues par
 * leur nom, quel que soit leur ordre ; séparateur et encodage sont détectés.
 */
object StatementImport {
    fun parse(bytes: ByteArray): List<StatementLine> {
        rejectBinary(bytes)
        val text = decode(bytes)
        val lines = if (text.contains("<OFX>", ignoreCase = true) || text.contains("OFXHEADER")) {
            OfxStatement.parse(text)
        } else {
            CsvStatement.parse(text)
        }
        if (lines.isEmpty()) throw ImportError("Aucune opération trouvée dans ce fichier.")
        return lines
    }

    /** Les erreurs de fichier les plus courantes : un Excel ou un PDF à la place du CSV. */
    private fun rejectBinary(bytes: ByteArray) {
        fun startsWith(vararg magic: Int) = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it].toByte() }
        when {
            startsWith(0x50, 0x4B, 0x03, 0x04) ->
                throw ImportError("Ce fichier est un classeur Excel (.xlsx) ou une archive. Sur le site de ta banque, choisis l'export au format CSV (ou OFX).")
            startsWith(0xD0, 0xCF, 0x11, 0xE0) ->
                throw ImportError("Ce fichier est un classeur Excel (.xls). Sur le site de ta banque, choisis l'export au format CSV (ou OFX).")
            startsWith(0x25, 0x50, 0x44, 0x46) ->
                throw ImportError("Ce fichier est un relevé PDF : il ne contient pas les opérations sous forme lisible. Choisis l'export au format CSV (ou OFX).")
        }
    }

    /** UTF-8 si le fichier en est, UTF-16 s'il en porte la marque, sinon Windows-1252 (exports bancaires anciens). */
    fun decode(bytes: ByteArray): String {
        utf16(bytes)?.let { return it }
        val withoutBom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(withoutBom))
                .toString()
        } catch (_: CharacterCodingException) {
            String(withoutBom, Charset.forName("windows-1252"))
        }
    }

    /** Fichiers « Unicode » d'Excel : UTF-16 avec marque, ou sans marque mais un octet nul sur deux. */
    private fun utf16(bytes: ByteArray): String? {
        if (bytes.size < 2) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val sample = bytes.take(400)
        val evenZeros = sample.filterIndexed { i, b -> i % 2 == 0 && b == 0.toByte() }.size
        val oddZeros = sample.filterIndexed { i, b -> i % 2 == 1 && b == 0.toByte() }.size
        val half = sample.size / 2
        return when {
            oddZeros > half * 0.6 && evenZeros == 0 -> String(bytes, Charsets.UTF_16LE)
            evenZeros > half * 0.6 && oddZeros == 0 -> String(bytes, Charsets.UTF_16BE)
            else -> null
        }
    }

    /** Lit un montant bancaire : « -1 234,56 », « 1.234,56 », « 12.50 », « 12,5 € ». */
    fun parseAmount(input: String): Long? {
        var s = input.filterNot { it.isWhitespace() || it == ' ' || it == ' ' || it == '€' || it == '"' }
            .replace("EUR", "", ignoreCase = true)
            .replace('−', '-')
        if (s.isEmpty()) return null
        val negative = s.startsWith('-')
        s = s.trimStart('-', '+')
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        val decimalSep = when {
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) ',' else '.'
            lastComma >= 0 -> ','
            lastDot >= 0 && s.length - lastDot - 1 in 1..2 -> '.'
            else -> null
        }
        val (units, decimals) = if (decimalSep == null) {
            s.filter(Char::isDigit) to ""
        } else {
            val i = s.lastIndexOf(decimalSep)
            s.substring(0, i).filter(Char::isDigit) to s.substring(i + 1)
        }
        if (units.isEmpty() && decimals.isEmpty()) return null
        if (decimals.length > 2 || decimals.any { !it.isDigit() }) return null
        val cents = (units.ifEmpty { "0" }.toLong()) * 100 + decimals.padEnd(2, '0').toLong()
        return if (negative) -cents else cents
    }

    private val DATE_FORMATS = listOf("dd/MM/yyyy", "d/M/yyyy", "yyyy-MM-dd", "dd-MM-yyyy", "dd.MM.yyyy", "dd/MM/yy", "yyyyMMdd")
        .map { DateTimeFormatter.ofPattern(it, Locale.FRANCE) }

    fun parseDate(input: String): LocalDate? {
        val s = input.trim().trim('"').take(10).let { if (it.length == 8 && it.all(Char::isDigit)) it else input.trim().trim('"') }
        for (format in DATE_FORMATS) {
            runCatching { return LocalDate.parse(s, format) }
        }
        return null
    }

    /** Empreinte stable d'une ligne sans identifiant bancaire. */
    fun fingerprint(date: LocalDate, amountCents: Long, label: String, occurrence: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$date|$amountCents|${label.trim().uppercase(Locale.ROOT)}|$occurrence".encodeToByteArray())
        return "csv:" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun normalizeHeader(s: String): String =
        Normalizer.normalize(s.trim().trim('"').lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}

object CsvStatement {
    private class Columns(val date: Int, val label: Int, val amount: Int?, val debit: Int?, val credit: Int?)

    fun parse(text: String): List<StatementLine> {
        val records = records(text).filter { cells -> cells.any { it.isNotBlank() } }
        if (records.isEmpty()) throw ImportError("Le fichier est vide.")
        val headerIndex = records.take(20).indexOfFirst { columnsOf(it) != null }
        val (columns, body) = if (headerIndex >= 0) {
            columnsOf(records[headerIndex])!! to records.drop(headerIndex + 1)
        } else {
            val inferred = inferColumns(records) ?: throw ImportError(unrecognized(records))
            inferred to records
        }

        val occurrences = mutableMapOf<String, Int>()
        return body.mapNotNull { cells ->
            val date = cells.getOrNull(columns.date)?.let(StatementImport::parseDate) ?: return@mapNotNull null
            val label = cells.getOrNull(columns.label)?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            val amount = when {
                columns.amount != null -> cells.getOrNull(columns.amount)?.let(StatementImport::parseAmount)
                else -> {
                    val debit = columns.debit?.let { cells.getOrNull(it) }?.let(StatementImport::parseAmount)
                    val credit = columns.credit?.let { cells.getOrNull(it) }?.let(StatementImport::parseAmount)
                    when {
                        debit != null && debit != 0L -> -kotlin.math.abs(debit)
                        credit != null -> kotlin.math.abs(credit)
                        else -> null
                    }
                }
            } ?: return@mapNotNull null
            if (amount == 0L) return@mapNotNull null
            val tuple = "$date|$amount|$label"
            val occurrence = occurrences.merge(tuple, 1, Int::plus)!!
            StatementLine(date, label, amount, StatementImport.fingerprint(date, amount, label, occurrence))
        }
    }

    /** Message d'échec qui montre les noms de colonnes lus, pour que je puisse ajouter le format. */
    private fun unrecognized(records: List<List<String>>): String {
        val first = records.first().joinToString(" | ") { it.take(30) }.take(300)
        return "Colonnes non reconnues : il faut au moins une date, un libellé et un montant.\n" +
            "Première ligne lue (${records.first().size} colonnes) : « $first »"
    }

    private val DATE_HEADERS = setOf(
        "dateop", "date op", "date operation", "date de l operation", "date d operation", "date comptable",
        "date comptabilisation", "date de comptabilisation", "booking date", "transaction date",
    )

    private fun columnsOf(cells: List<String>): Columns? {
        val headers = cells.map(StatementImport::normalizeHeader)
        fun find(predicate: (String) -> Boolean) = headers.indexOfFirst(predicate).takeIf { it >= 0 }
        val date = find { it in DATE_HEADERS }
            ?: find { it.startsWith("date") && "val" !in it }
            ?: find { it.startsWith("date") }
            ?: return null
        val label = find { it == "label" || it == "libelle" || it == "libelle operation" || it == "libelle de l operation" }
            ?: find {
                it.startsWith("libelle") || it.startsWith("label") || it == "description" || it.startsWith("intitule") ||
                    it.startsWith("nature") || it.startsWith("detail") || it == "operation" || it == "operations" ||
                    it == "beneficiaire" || it == "wording"
            }
            ?: return null
        val amount = find { it == "amount" || it == "montant" || it.startsWith("montant") || it.startsWith("amount") || it == "somme" }
        val debit = find { it.startsWith("debit") }
        val credit = find { it.startsWith("credit") }
        if (amount == null && debit == null && credit == null) return null
        if (label == date || label == amount) return null
        return Columns(date, label, amount, debit, credit)
    }

    /**
     * Sans en-tête reconnu, devine les colonnes d'après leur contenu : une colonne
     * de dates, une de montants (avec décimales) et la plus « textuelle » pour le libellé.
     */
    private fun inferColumns(records: List<List<String>>): Columns? {
        val sample = records.drop(1).take(30).ifEmpty { return null }
        val width = sample.maxOf { it.size }
        if (width < 3) return null
        fun share(column: Int, test: (String) -> Boolean): Double {
            val values = sample.mapNotNull { it.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }
            if (values.size < sample.size / 2.0) return 0.0
            return values.count(test).toDouble() / values.size
        }
        val amountLike = Regex("^[\"+\\-−]?[\\d\\s\u00A0\u202F.]*\\d[.,]\\d{1,2}\\s*(€|EUR)?\"?$")
        val columns = 0 until width
        val date = columns.firstOrNull { share(it) { v -> StatementImport.parseDate(v) != null } >= 0.8 } ?: return null
        val amount = columns.firstOrNull { it != date && share(it) { v -> amountLike.matches(v.trim()) } >= 0.8 } ?: return null
        val label = columns.filter { it != date && it != amount }
            .maxByOrNull { c -> sample.sumOf { row -> row.getOrNull(c)?.count(Char::isLetter) ?: 0 } }
            ?.takeIf { c -> sample.sumOf { row -> row.getOrNull(c)?.count(Char::isLetter) ?: 0 } > 0 }
            ?: return null
        return Columns(date, label, amount, null, null)
    }

    private fun delimiterOf(text: String): Char {
        val firstLines = text.lineSequence().filter { it.isNotBlank() }.take(5).toList()
        return listOf(';', '\t', ',', '|').maxBy { d -> firstLines.sumOf { line -> line.count { it == d } } }
    }

    /** Découpe tout le fichier en enregistrements, y compris un libellé entre guillemets sur plusieurs lignes. */
    fun records(text: String): List<List<String>> {
        val delimiter = delimiterOf(text.removePrefix("sep=;").removePrefix("sep=,"))
        val records = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { cells += current.toString().trim(); current.clear() }
        fun endRecord() { endCell(); records += cells; cells = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted && (c == '\n' || c == '\r') -> {
                    current.append(' ')
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                c == delimiter -> endCell()
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRecord()
                }
                c == '\u0000' -> {}
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty() || cells.isNotEmpty()) endRecord()
        return records.filterNot { it.size == 1 && it[0].startsWith("sep=") }
    }
}

object OfxStatement {
    private val TRANSACTION = Regex("<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|</BANKTRANLIST>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun parse(text: String): List<StatementLine> {
        val occurrences = mutableMapOf<String, Int>()
        return TRANSACTION.findAll(text).mapNotNull { match ->
            val block = match.groupValues[1]
            fun field(name: String) = Regex("<$name>([^<\\r\\n]*)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
            val date = field("DTPOSTED")?.take(8)?.let(StatementImport::parseDate) ?: return@mapNotNull null
            val amount = field("TRNAMT")?.let(StatementImport::parseAmount) ?: return@mapNotNull null
            val label = listOfNotNull(field("NAME"), field("MEMO")).filter { it.isNotEmpty() }.distinct().joinToString(" ")
            val ref = field("FITID")?.takeIf { it.isNotEmpty() }?.let { "ofx:$it" } ?: run {
                val occurrence = occurrences.merge("$date|$amount|$label", 1, Int::plus)!!
                StatementImport.fingerprint(date, amount, label, occurrence)
            }
            StatementLine(date, label, amount, ref)
        }.toList()
    }
}
