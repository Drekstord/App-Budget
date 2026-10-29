package app.boussole.core.backup

import app.boussole.core.crypto.Aead
import app.boussole.core.crypto.RecoveryPhrase
import app.boussole.core.vault.VaultCodec
import app.boussole.core.vault.sampleData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BackupTest {
    private val phrase = RecoveryPhrase.generate()
    private val dek = Aead.newKey()
    private val r = VaultCodec.sealDekWithRecovery(dek, phrase)
    private val file = BackupCodec.encode(sampleData(), dek, r, "2026-10-29T12:00:00Z")

    @Test
    fun `restaure avec la phrase seule`() {
        val restored = BackupCodec.decode(file, RecoveryPhrase.parse(phrase.words))
        assertEquals(sampleData(), restored.data)
        assertContentEquals(dek, restored.dek)
        assertContentEquals(r, restored.recoveryEnvelope)
    }

    @Test
    fun `le fichier ne contient rien de lisible`() {
        assertFalse(file.decodeToString().contains("Boulangerie"))
        assertEquals("2026-10-29T12:00:00Z", BackupCodec.readHeader(file).createdAt)
    }

    @Test
    fun `refuse une autre phrase`() {
        assertFailsWith<InvalidBackup> { BackupCodec.decode(file, RecoveryPhrase.generate()) }
    }

    @Test
    fun `refuse un fichier altéré, y compris dans l'en-tête`() {
        val payloadTampered = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<InvalidBackup> { BackupCodec.decode(payloadTampered, phrase) }

        val text = file.decodeToString(0, 200)
        val datePos = text.indexOf("2026-10-29")
        val headerTampered = file.copyOf().also { it[datePos] = '1'.code.toByte() }
        assertFailsWith<InvalidBackup> { BackupCodec.decode(headerTampered, phrase) }
    }

    @Test
    fun `refuse ce qui n'est pas une sauvegarde`() {
        assertFailsWith<InvalidBackup> { BackupCodec.readHeader("bonjour".encodeToByteArray()) }
        assertFailsWith<InvalidBackup> { BackupCodec.readHeader(byteArrayOf()) }
        val truncated = file.copyOfRange(0, 12)
        assertFailsWith<InvalidBackup> { BackupCodec.readHeader(truncated) }
    }
}
