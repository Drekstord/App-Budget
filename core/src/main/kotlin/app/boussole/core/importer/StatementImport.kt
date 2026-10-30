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
        val text = decode(bytes)
        val lines = if (text.contains("<OFX>", ignoreCase = true) || text.contains("OFXHEADER")) {
            OfxStatement.parse(text)
        } else {
            CsvStatement.parse(text)
        }
        if (lines.isEmpty()) throw ImportError("Aucune opération trouvée dans ce fichier.")
        return lines
    }

    /** UTF-8 si le fichier en est, sinon Windows-1252 (exports bancaires anciens). */
    fun decode(bytes: ByteArray): String {
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
        val rawLines = text.lines().filter { it.isNotBlank() }
        val headerIndex = rawLines.take(20).indexOfFirst { columnsOf(split(it, delimiterOf(it))) != null }
        if (headerIndex < 0) throw ImportError("Colonnes non reconnues : il faut au moins une date, un libellé et un montant.")
        val delimiter = delimiterOf(rawLines[headerIndex])
        val columns = columnsOf(split(rawLines[headerIndex], delimiter))!!

        val occurrences = mutableMapOf<String, Int>()
        return rawLines.drop(headerIndex + 1).mapNotNull { line ->
            val cells = split(line, delimiter)
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

    private fun columnsOf(cells: List<String>): Columns? {
        val headers = cells.map(StatementImport::normalizeHeader)
        fun find(predicate: (String) -> Boolean) = headers.indexOfFirst(predicate).takeIf { it >= 0 }
        val date = find { it == "dateop" || it == "date operation" || it == "date de l operation" || it == "date comptable" }
            ?: find { it.startsWith("date") && "val" !in it }
            ?: return null
        val label = find { it == "label" || it.startsWith("libelle") || it == "description" || it == "intitule" || it.startsWith("nature") }
            ?: return null
        val amount = find { it == "amount" || it.startsWith("montant") }
        val debit = find { it.startsWith("debit") }
        val credit = find { it.startsWith("credit") }
        if (amount == null && debit == null && credit == null) return null
        return Columns(date, label, amount, debit, credit)
    }

    private fun delimiterOf(line: String): Char =
        listOf(';', '\t', ',').maxBy { d -> line.count { it == d } }

    /** Découpe une ligne CSV en respectant les guillemets (« "" » = guillemet littéral). */
    fun split(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { cells += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells.map { it.trim() }
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
