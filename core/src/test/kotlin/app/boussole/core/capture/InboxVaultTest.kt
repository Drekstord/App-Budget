package app.boussole.core.capture

import app.boussole.core.crypto.Aead
import app.boussole.core.domain.VaultData
import app.boussole.core.vault.VaultCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class InboxVaultTest {
    @Test
    fun `la clé privée de la boîte de réception survit au coffre et ouvre les captures`() {
        val keys = Inbox.newKeyPair()
        val dek = Aead.newKey()
        val data = VaultData(inboxPrivateKey = keys.privateKey).normalized()
        val reopened = VaultCodec.openData(dek, VaultCodec.sealData(dek, data))
        assertEquals(32, reopened.inboxPrivateKey.size)
        assertContentEquals(keys.privateKey, reopened.inboxPrivateKey)
        assertEquals(data, reopened)
        val copy = reopened.copy(profile = reopened.profile.copy(netPayCents = 1))
        assertEquals(32, copy.inboxPrivateKey.size)
        val item = CapturedItem("i", "p", 1, "t", "12,50 €")
        assertEquals(item, CapturedItem.decode(Inbox.open(copy.inboxPrivateKey, Inbox.seal(Inbox.publicKeyOf(copy.inboxPrivateKey), item.encode()))))
    }
}
