package app.boussole.core.capture

import app.boussole.core.crypto.AuthenticationFailure
import app.boussole.core.domain.Confidence
import app.boussole.core.domain.Fx
import app.boussole.core.domain.TransactionSource
import app.boussole.core.domain.TransactionStatus
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InboxTest {
    @Test
    fun `scellé avec la clé publique, ouvert seulement avec la clé privée`() {
        val keys = Inbox.newKeyPair()
        val sealed = Inbox.seal(keys.publicKey, "Paiement 12,50 € CARREFOUR".encodeToByteArray())
        assertContentEquals("Paiement 12,50 € CARREFOUR".encodeToByteArray(), Inbox.open(keys.privateKey, sealed))
        assertFailsWith<AuthenticationFailure> { Inbox.open(Inbox.newKeyPair().privateKey, sealed) }
        val tampered = sealed.copyOf().also { it[40] = (it[40] + 1).toByte() }
        assertFailsWith<AuthenticationFailure> { Inbox.open(keys.privateKey, tampered) }
        assertFailsWith<AuthenticationFailure> { Inbox.open(keys.privateKey, ByteArray(10)) }
    }

    @Test
    fun `la clé publique se déduit de la privée`() {
        val keys = Inbox.newKeyPair()
        assertContentEquals(keys.publicKey, Inbox.publicKeyOf(keys.privateKey))
    }

    @Test
    fun `une capture fait l'aller-retour`() {
        val item = CapturedItem("i1", "com.boursorama.android.clients", 1_761_800_000_000, "Paiement", "12,50 € chez LIDL")
        assertEquals(item, CapturedItem.decode(item.encode()))
    }
}

class NotificationParserTest {
    private fun parse(title: String, text: String) = NotificationParser.parse(title, text)

    @Test
    fun `paiement par carte avec « chez »`() {
        val p = assertNotNull(parse("Paiement par carte", "Vous avez payé 12,50 € chez CARREFOUR CITY le 29/10."))
        assertEquals(-1250, p.amountCents)
        assertEquals("CARREFOUR CITY", p.merchant)
        assertEquals(CaptureKind.PAYMENT, p.kind)
        assertEquals(Confidence.HIGH, p.confidence)
    }

    @Test
    fun `Samsung Wallet - le titre est le marchand`() {
        val p = assertNotNull(parse("Boulangerie Marie", "4,20 €"))
        assertEquals(-420, p.amountCents)
        assertEquals("Boulangerie Marie", p.merchant)
    }

    @Test
    fun `montant signé suivi du marchand en majuscules`() {
        val p = assertNotNull(parse("Caisse d'Epargne", "Opération carte : -1 234,56 EUR DARTY PARIS"))
        assertEquals(-123456, p.amountCents)
        assertEquals("DARTY PARIS", p.merchant)
    }

    @Test
    fun `revenu, virement reçu`() {
        val p = assertNotNull(parse("Virement reçu", "Vous avez reçu un virement de 1 800,00 € de ACME SAS"))
        assertEquals(180000, p.amountCents)
        assertEquals(CaptureKind.INCOME, p.kind)
    }

    @Test
    fun `retrait d'espèces et prélèvement`() {
        assertEquals(CaptureKind.WITHDRAWAL, parse("Retrait", "Retrait de 40,00 € au distributeur")!!.kind)
        val prlv = assertNotNull(parse("Prélèvement", "Prélèvement de 13,49 € NETFLIX"))
        assertEquals(CaptureKind.DIRECT_DEBIT, prlv.kind)
        assertEquals(-1349, prlv.amountCents)
    }

    @Test
    fun `le solde mentionné n'est pas pris pour le montant`() {
        val p = assertNotNull(parse("Paiement", "Paiement de 9,90 € chez SPOTIFY. Solde : 1 234,00 €"))
        assertEquals(-990, p.amountCents)
    }

    @Test
    fun `notifications ignorées - sécurité, refus, sans montant`() {
        assertNull(parse("Boursobank", "Votre code de validation est 123456"))
        assertNull(parse("Paiement refusé", "Paiement de 45,00 € chez AMAZON refusé"))
        assertNull(parse("Sécurité", "Validez votre paiement de 45,00 € chez AMAZON"))
        assertNull(parse("Relevé", "Votre relevé de compte est disponible"))
    }
}

class CapturesTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val t0 = Instant.parse("2026-10-29T17:30:00Z").toEpochMilli()

    @Test
    fun `capture classée, à valider, sur le bon compte, sans doublon Wallet + banque`() {
        val data = Fx.data()
        val wallet = CapturedItem("a", "com.samsung.android.spay", t0, "Carrefour City", "12,50 €")
        val bank = CapturedItem("b", "com.boursorama.android.clients", t0 + 60_000, "Paiement", "Paiement de 12,50 € chez CARREFOUR CITY")
        val noise = CapturedItem("c", "com.boursorama.android.clients", t0, "Sécurité", "Votre code : 123456")
        val result = Captures.merge(data, listOf(wallet, bank, noise), zone)
        assertEquals(1, result.added)
        assertEquals(1, result.duplicates)
        assertEquals(1, result.ignored)
        val t = result.data.transactions.single()
        assertEquals(-1250, t.amountCents)
        assertEquals("cat.courses", t.categoryId)
        assertEquals(TransactionStatus.TO_VALIDATE, t.status)
        assertEquals(TransactionSource.NOTIFICATION, t.source)
        assertEquals(Fx.bourso.id, t.accountId)
        assertEquals("2026-10-29", t.date)
    }

    @Test
    fun `compte deviné d'après l'app source`() {
        val data = Fx.data()
        assertEquals(Fx.ceCourant.id, Captures.accountFor(data, "fr.caisseepargne.android.mobilebanking"))
        assertEquals(Fx.bourso.id, Captures.accountFor(data, "com.samsung.android.spay"))
        val forced = data.copy(settings = data.settings.copy(captureAccounts = mapOf("com.samsung.android.spay" to Fx.ceCourant.id)))
        assertEquals(Fx.ceCourant.id, Captures.accountFor(forced, "com.samsung.android.spay"))
    }
}

class ReceiptParserTest {
    @Test
    fun `ticket de supermarché`() {
        val r = ReceiptParser.parse(
            """
            CARREFOUR MARKET
            12 rue de la Paix
            29/10/2026 18:42
            PAIN COMPLET        2,10
            LAIT 1L             1,05
            TOTAL A PAYER      23,45
            CB                 23,45
            RENDU               0,00
            """.trimIndent(),
        )
        assertEquals(2345, r.amountCents)
        assertEquals("Carrefour", r.merchant)
        assertEquals("cat.courses", r.categoryId)
        assertEquals("2026-10-29", r.date.toString())
    }

    @Test
    fun `enseigne inconnue - première ligne propre`() {
        val r = ReceiptParser.parse("LA GUITARE D'OR\nTEL 01 02 03 04 05\nMontant\n89,00\n3 novembre 2026")
        assertEquals("LA GUITARE D'OR", r.merchant)
        assertEquals(8900, r.amountCents)
        assertEquals("2026-11-03", r.date.toString())
    }
}
