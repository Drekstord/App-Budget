package app.boussole.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class AeadTest {
    private val key = Aead.newKey()
    private val aad = "role".encodeToByteArray()

    @Test
    fun `chiffre puis déchiffre`() {
        val sealed = Aead.seal(key, "café 12,50 €".encodeToByteArray(), aad)
        assertEquals("café 12,50 €", Aead.open(key, sealed, aad).decodeToString())
    }

    @Test
    fun `deux scellements du même contenu diffèrent (nonce aléatoire)`() {
        val a = Aead.seal(key, byteArrayOf(1, 2, 3), aad)
        val b = Aead.seal(key, byteArrayOf(1, 2, 3), aad)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `refuse une mauvaise clé, d'autres données associées ou une altération`() {
        val sealed = Aead.seal(key, byteArrayOf(1, 2, 3), aad)
        assertFailsWith<AuthenticationFailure> { Aead.open(Aead.newKey(), sealed, aad) }
        assertFailsWith<AuthenticationFailure> { Aead.open(key, sealed, "autre".encodeToByteArray()) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<AuthenticationFailure> { Aead.open(key, tampered, aad) }
        assertFailsWith<AuthenticationFailure> { Aead.open(key, ByteArray(5), aad) }
    }
}

class KdfTest {
    @Test
    fun `Argon2id respecte le vecteur de test de la RFC 9106`() {
        val tag = Argon2id.derive(
            password = ByteArray(32) { 1 },
            salt = ByteArray(16) { 2 },
            params = Argon2Params(memoryKiB = 32, iterations = 3, parallelism = 4),
            length = 32,
            secret = ByteArray(8) { 3 },
            associatedData = ByteArray(12) { 4 },
        )
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", tag.toHex())
    }

    @Test
    fun `les paramètres par défaut sont assez robustes`() {
        assert(Argon2Params.DEFAULT.isStrongEnough)
        assertFalse(Argon2Params(memoryKiB = 1024, iterations = 1, parallelism = 1).isStrongEnough)
    }

    @Test
    fun `la normalisation Unicode rend la saisie indifférente au clavier`() {
        assertContentEquals(encodeSecret("été"), encodeSecret("été"))
    }

    @Test
    fun `HKDF respecte le cas de test 1 de la RFC 5869`() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }
}

class RecoveryPhraseTest {
    @Test
    fun `entropie nulle donne 23 fois le premier mot puis le mot de contrôle`() {
        val words = RecoveryPhrase.fromEntropy(ByteArray(32)).words
        assertEquals(List(23) { "abaisser" } + "anaphore", words)
    }

    @Test
    fun `aller-retour mots - entropie`() {
        val phrase = RecoveryPhrase.generate()
        val parsed = RecoveryPhrase.parse(phrase.words.joinToString(" "))
        assertEquals(phrase.words, parsed.words)
        assertContentEquals(phrase.deriveKey(), parsed.deriveKey())
    }

    @Test
    fun `tolère majuscules, accents absents et abréviations à 4 lettres`() {
        val phrase = RecoveryPhrase.generate()
        val sloppy = phrase.words.mapIndexed { i, w ->
            val plain = Wordlist.normalize(w)
            when (i % 3) {
                0 -> plain.uppercase()
                1 -> if (plain.length >= 4) plain.take(4) else plain
                else -> plain
            }
        }
        assertEquals(phrase.words, RecoveryPhrase.parse(sloppy).words)
    }

    @Test
    fun `refuse une phrase incomplète, un mot inconnu ou un contrôle faux`() {
        val words = RecoveryPhrase.generate().words
        assertEquals(23, assertFailsWith<InvalidPhrase.WrongWordCount> { RecoveryPhrase.parse(words.drop(1)) }.count)
        val unknown = assertFailsWith<InvalidPhrase.UnknownWord> {
            RecoveryPhrase.parse(words.toMutableList().also { it[4] = "boussolle" })
        }
        assertEquals(5, unknown.position)
        // Échanger deux mots distincts casse la somme de contrôle (sauf collision 1/256).
        val swapped = words.toMutableList()
        val j = (1 until 24).first { swapped[it] != swapped[0] }
        swapped[0] = swapped[j].also { swapped[j] = swapped[0] }
        val result = runCatching { RecoveryPhrase.parse(swapped) }
        if (result.isSuccess) assertFalse(result.getOrThrow().words == words)
        else assert(result.exceptionOrNull() is InvalidPhrase.BadChecksum)
    }

    @Test
    fun `deux phrases différentes donnent deux clés différentes`() {
        assertFalse(RecoveryPhrase.generate().deriveKey().contentEquals(RecoveryPhrase.generate().deriveKey()))
    }
}

class SameWordTest {
    @Test
    fun `compare un mot saisi au mot attendu`() {
        assert(RecoveryPhrase.sameWord("ACADEMIE", "académie"))
        assert(RecoveryPhrase.sameWord("anap", "anaphore"))
        assertFalse(RecoveryPhrase.sameWord("abaisser", "anaphore"))
        assertFalse(RecoveryPhrase.sameWord("inconnu", "inconnu"))
    }
}
