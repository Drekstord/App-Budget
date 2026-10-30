package app.boussole.core.capture

import app.boussole.core.crypto.Aead
import app.boussole.core.crypto.AuthenticationFailure
import app.boussole.core.crypto.Hkdf
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * Boîte de réception scellée : le service qui lit les notifications doit
 * pouvoir enregistrer une dépense alors que le coffre est verrouillé (la DEK
 * n'est pas en mémoire). Il chiffre donc avec une clé PUBLIQUE X25519 ; seule
 * la clé privée, rangée DANS le coffre chiffré, permet de relire les captures.
 *
 * Format : clé éphémère (32 octets) ‖ AES-256-GCM(HKDF(secret partagé), contenu).
 */
object Inbox {
    private val random = SecureRandom()
    private val INFO = "boussole/inbox/v1".encodeToByteArray()

    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun newKeyPair(): KeyPair {
        val private = ByteArray(X25519.SCALAR_SIZE)
        X25519.generatePrivateKey(random, private)
        return KeyPair(private, publicKeyOf(private))
    }

    fun publicKeyOf(privateKey: ByteArray): ByteArray =
        ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(privateKey, 0, it, 0) }

    fun seal(recipientPublicKey: ByteArray, plaintext: ByteArray): ByteArray {
        require(recipientPublicKey.size == X25519.POINT_SIZE) { "Clé publique invalide" }
        val ephemeral = newKeyPair()
        val key = derive(ephemeral.privateKey, recipientPublicKey, ephemeral.publicKey, recipientPublicKey)
        ephemeral.privateKey.fill(0)
        try {
            return ephemeral.publicKey + Aead.seal(key, plaintext, INFO)
        } finally {
            key.fill(0)
        }
    }

    /** @throws AuthenticationFailure si le contenu n'a pas été scellé pour cette clé ou a été altéré. */
    fun open(privateKey: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size <= X25519.POINT_SIZE) throw AuthenticationFailure()
        val ephemeralPublic = sealed.copyOfRange(0, X25519.POINT_SIZE)
        val key = derive(privateKey, ephemeralPublic, ephemeralPublic, publicKeyOf(privateKey))
        try {
            return Aead.open(key, sealed.copyOfRange(X25519.POINT_SIZE, sealed.size), INFO)
        } finally {
            key.fill(0)
        }
    }

    private fun derive(privateKey: ByteArray, peerPublic: ByteArray, ephemeralPublic: ByteArray, recipientPublic: ByteArray): ByteArray {
        val shared = ByteArray(X25519.POINT_SIZE)
        // Refuse les points de petit ordre (secret partagé nul).
        if (!X25519.calculateAgreement(privateKey, 0, peerPublic, 0, shared, 0)) throw AuthenticationFailure()
        try {
            return Hkdf.derive(shared, ephemeralPublic + recipientPublic, INFO, Aead.KEY_BYTES)
        } finally {
            shared.fill(0)
        }
    }
}
