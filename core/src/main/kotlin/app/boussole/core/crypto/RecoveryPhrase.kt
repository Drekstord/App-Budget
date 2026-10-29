package app.boussole.core.crypto

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Raison pour laquelle une phrase de récupération saisie est refusée. */
sealed class InvalidPhrase(message: String) : Exception(message) {
    class WrongWordCount(val count: Int) :
        InvalidPhrase("La phrase compte $count mots au lieu de ${RecoveryPhrase.WORD_COUNT}.")

    /** [position] commence à 1, comme sur la feuille papier. */
    class UnknownWord(val position: Int, val word: String) :
        InvalidPhrase("Le mot n° $position (« $word ») n'existe pas dans la liste.")

    class BadChecksum :
        InvalidPhrase("Les mots existent mais la phrase est incohérente : vérifie l'ordre et l'orthographe.")
}

/**
 * Phrase de récupération de 24 mots (BIP39, liste française) : 256 bits
 * d'entropie + 8 bits de contrôle. C'est la seule clé capable de déchiffrer une
 * sauvegarde : elle se garde sur papier, jamais dans un service en ligne.
 */
class RecoveryPhrase private constructor(private val entropy: ByteArray) {

    val words: List<String> by lazy { encode(entropy) }

    /** Clé de chiffrement de l'enveloppe de récupération. */
    fun deriveKey(): ByteArray = Hkdf.derive(entropy, KDF_SALT, KDF_INFO, Aead.KEY_BYTES)

    /** Efface l'entropie de la mémoire (au mieux, sur la JVM). */
    fun wipe() = entropy.fill(0)

    companion object {
        const val WORD_COUNT = 24
        private const val ENTROPY_BYTES = 32
        private val KDF_SALT = "boussole/recovery".encodeToByteArray()
        private val KDF_INFO = "kek/v1".encodeToByteArray()

        fun generate(): RecoveryPhrase = RecoveryPhrase(SecureRandoms.bytes(ENTROPY_BYTES))

        /** Vrai si [input] désigne le même mot que [expected], avec la même tolérance que [parse]. */
        fun sameWord(input: String, expected: String): Boolean =
            Wordlist.indexOf(input)?.let { it == Wordlist.indexOf(expected) } ?: false

        fun fromEntropy(entropy: ByteArray): RecoveryPhrase {
            require(entropy.size == ENTROPY_BYTES) { "256 bits d'entropie attendus" }
            return RecoveryPhrase(entropy.copyOf())
        }

        /**
         * Lit une phrase saisie. Tolère les majuscules, l'absence d'accents et
         * les mots abrégés à leurs 4 premières lettres (uniques dans la liste).
         *
         * @throws InvalidPhrase
         */
        fun parse(input: String): RecoveryPhrase =
            parse(input.split(Regex("[\\s,;.]+")).filter { it.isNotBlank() })

        fun parse(input: List<String>): RecoveryPhrase {
            if (input.size != WORD_COUNT) throw InvalidPhrase.WrongWordCount(input.size)
            val indices = input.mapIndexed { i, word ->
                Wordlist.indexOf(word) ?: throw InvalidPhrase.UnknownWord(i + 1, word.trim())
            }
            val bits = BooleanArray(WORD_COUNT * 11)
            indices.forEachIndexed { w, index ->
                for (b in 0 until 11) bits[w * 11 + b] = (index shr (10 - b)) and 1 == 1
            }
            val entropy = ByteArray(ENTROPY_BYTES)
            for (i in 0 until ENTROPY_BYTES * 8) {
                if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
            var checksum = 0
            for (b in 0 until 8) if (bits[ENTROPY_BYTES * 8 + b]) checksum = checksum or (0x80 ushr b)
            if (checksum != checksumOf(entropy)) {
                entropy.fill(0)
                throw InvalidPhrase.BadChecksum()
            }
            return RecoveryPhrase(entropy)
        }

        private fun checksumOf(entropy: ByteArray): Int =
            MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xFF

        private fun encode(entropy: ByteArray): List<String> {
            val bits = BooleanArray(WORD_COUNT * 11)
            for (i in 0 until ENTROPY_BYTES * 8) {
                bits[i] = (entropy[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
            }
            val checksum = checksumOf(entropy)
            for (b in 0 until 8) bits[ENTROPY_BYTES * 8 + b] = (checksum shr (7 - b)) and 1 == 1
            return List(WORD_COUNT) { w ->
                var index = 0
                for (b in 0 until 11) index = (index shl 1) or (if (bits[w * 11 + b]) 1 else 0)
                Wordlist.words[index]
            }
        }
    }
}

/** Liste BIP39 française, dont l'intégrité est vérifiée au chargement. */
internal object Wordlist {
    private const val RESOURCE = "bip39-fr.txt"
    private const val SHA256 = "ebc3959ab7801a1df6bac4fa7d970652f1df76b683cd2f4003c941c63d517e59"

    val words: List<String>
    private val byNormalized: Map<String, Int>
    private val byPrefix: Map<String, Int>

    init {
        val bytes = requireNotNull(Wordlist::class.java.getResourceAsStream(RESOURCE)) {
            "Liste de mots introuvable"
        }.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(digest == SHA256) { "Liste de mots altérée" }
        words = bytes.decodeToString().lines().filter { it.isNotBlank() }
        check(words.size == 2048) { "Liste de mots incomplète" }
        byNormalized = words.withIndex().associate { normalize(it.value) to it.index }
        byPrefix = words.withIndex().associate { normalize(it.value).take(4) to it.index }
        check(byNormalized.size == 2048 && byPrefix.size == 2048)
    }

    fun indexOf(word: String): Int? {
        val n = normalize(word)
        if (n.isEmpty()) return null
        byNormalized[n]?.let { return it }
        // Abréviation : exactement les 4 premières lettres, ou un mot de moins de 4 lettres.
        return if (n.length == 4) byPrefix[n] else null
    }

    /** Minuscules sans accents : « Élève » → « eleve ». */
    fun normalize(word: String): String =
        Normalizer.normalize(word.trim().lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
}
