package app.boussole.core.vault

import app.boussole.core.crypto.Aead
import app.boussole.core.crypto.Argon2Params
import app.boussole.core.crypto.Argon2id
import app.boussole.core.crypto.AuthenticationFailure
import app.boussole.core.crypto.RecoveryPhrase
import app.boussole.core.crypto.encodeSecret
import app.boussole.core.domain.Transaction
import app.boussole.core.domain.VaultData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Paramètres réduits : les tests vérifient la logique, pas le coût. */
internal val FAST_KDF = Argon2Params(memoryKiB = 64, iterations = 1, parallelism = 1)

internal fun sampleData() = VaultData(
    transactions = listOf(
        Transaction("t1", "2026-10-29T08:00:00Z", "2026-10-29", -1250, "Boulangerie café"),
    ),
)

class VaultCodecTest {
    private val dek = Aead.newKey()
    private val salt = Argon2id.newSalt()

    @Test
    fun `l'enveloppe mot de passe ne s'ouvre qu'avec le bon mot de passe`() {
        val p = VaultCodec.sealDekWithPassword(dek, encodeSecret("correct horse battery"), salt, FAST_KDF)
        assertContentEquals(dek, VaultCodec.openDekWithPassword(p, encodeSecret("correct horse battery"), salt, FAST_KDF))
        assertNull(VaultCodec.openDekWithPassword(p, encodeSecret("correct horse batterY"), salt, FAST_KDF))
    }

    @Test
    fun `l'enveloppe de récupération ne s'ouvre qu'avec la bonne phrase`() {
        val phrase = RecoveryPhrase.generate()
        val r = VaultCodec.sealDekWithRecovery(dek, phrase)
        assertContentEquals(dek, VaultCodec.openDekWithRecovery(r, RecoveryPhrase.parse(phrase.words)))
        assertNull(VaultCodec.openDekWithRecovery(r, RecoveryPhrase.generate()))
    }

    @Test
    fun `une enveloppe ne peut pas être substituée à une autre`() {
        // Même clé de 32 octets utilisée des deux côtés : seules les données associées diffèrent.
        val phrase = RecoveryPhrase.generate()
        val r = VaultCodec.sealDekWithRecovery(dek, phrase)
        assertFailsWith<AuthenticationFailure> { VaultCodec.openData(phrase.deriveKey(), r) }
    }

    @Test
    fun `les données chiffrées ne laissent rien paraître et se relisent`() {
        val sealed = VaultCodec.sealData(dek, sampleData())
        assertFalse(sealed.decodeToString().contains("Boulangerie"))
        assertEquals(sampleData(), VaultCodec.openData(dek, sealed))
    }

    @Test
    fun `l'en-tête fait l'aller-retour et refuse un format inconnu`() {
        val header = VaultHeader(
            kdf = Argon2Params.DEFAULT,
            salt = salt,
            hardwareSealedPasswordEnvelope = byteArrayOf(9, 8, 7),
            recoveryEnvelope = byteArrayOf(1, 2, 3),
        )
        assertEquals(header, VaultCodec.decodeHeader(VaultCodec.encodeHeader(header)))
        val future = VaultCodec.encodeHeader(header.copy(version = 99))
        assertFailsWith<InvalidVault> { VaultCodec.decodeHeader(future) }
        assertFailsWith<InvalidVault> { VaultCodec.decodeHeader("{}".encodeToByteArray()) }
    }
}

class SecretPolicyTest {
    @Test
    fun `codes et mots de passe`() {
        assertEquals(SecretPolicy.Verdict.PIN_TOO_SHORT, SecretPolicy.check("12345"))
        assertEquals(SecretPolicy.Verdict.PIN_TOO_SIMPLE, SecretPolicy.check("123456"))
        assertEquals(SecretPolicy.Verdict.PIN_TOO_SIMPLE, SecretPolicy.check("000000"))
        assertEquals(SecretPolicy.Verdict.PIN_TOO_SIMPLE, SecretPolicy.check("987654"))
        assertEquals(SecretPolicy.Verdict.OK, SecretPolicy.check("358291"))
        assertEquals(SecretPolicy.Verdict.PASSWORD_TOO_SHORT, SecretPolicy.check("guitare2026"))
        assertEquals(SecretPolicy.Verdict.OK, SecretPolicy.check("Guitare-Fender-2026!"))
        assertEquals(SecretPolicy.Verdict.PASSWORD_TOO_SHORT, SecretPolicy.check(""))
    }

    @Test
    fun `délai croissant après des échecs`() {
        assertEquals(0, AttemptThrottle.lockoutMillis(4))
        assertEquals(30_000, AttemptThrottle.lockoutMillis(5))
        assertEquals(60_000, AttemptThrottle.lockoutMillis(6))
        assertEquals(24 * 3_600_000L, AttemptThrottle.lockoutMillis(40))
    }
}
