package app.boussole.core.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Le contenu chiffré ne s'authentifie pas : mauvaise clé, données associées différentes ou altération. */
class AuthenticationFailure : Exception("Le contenu chiffré ne s'authentifie pas")

internal object SecureRandoms {
    private val rng = SecureRandom()

    fun bytes(size: Int): ByteArray = ByteArray(size).also(rng::nextBytes)
}

/**
 * AES-256-GCM. Format scellé : nonce (12 octets) ‖ chiffré ‖ tag (16 octets).
 *
 * Les données associées (`aad`) ne sont pas chiffrées mais authentifiées : elles
 * lient un contenu chiffré à son rôle (enveloppe de mot de passe, données…) pour
 * qu'on ne puisse pas substituer l'un à l'autre.
 */
object Aead {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun newKey(): ByteArray = SecureRandoms.bytes(KEY_BYTES)

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Clé AES-256 attendue" }
        val nonce = SecureRandoms.bytes(NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** @throws AuthenticationFailure si le contenu ne s'authentifie pas. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Clé AES-256 attendue" }
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) throw AuthenticationFailure()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
        )
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (_: AEADBadTagException) {
            throw AuthenticationFailure()
        }
    }
}
