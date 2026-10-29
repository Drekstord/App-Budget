package app.boussole.core.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.text.Normalizer

/** Paramètres Argon2id, stockés avec ce qu'ils protègent pour pouvoir évoluer. */
@Serializable
data class Argon2Params(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(iterations >= 1 && parallelism >= 1 && memoryKiB >= 8 * parallelism) {
            "Paramètres Argon2id invalides"
        }
    }

    /** Vrai si ces paramètres atteignent au moins le minimum recommandé (OWASP 2025). */
    val isStrongEnough: Boolean
        get() = memoryKiB >= 19 * 1024 && iterations >= 2

    companion object {
        /** 64 Mio, 3 passes : environ une seconde sur un téléphone haut de gamme. */
        val DEFAULT = Argon2Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)
    }
}

object Argon2id {
    const val SALT_BYTES = 16

    fun newSalt(): ByteArray = SecureRandoms.bytes(SALT_BYTES)

    fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params, length: Int = 32): ByteArray =
        derive(password, salt, params, length, secret = null, associatedData = null)

    /** Variante complète, exposée aux tests pour vérifier les vecteurs de la RFC 9106. */
    internal fun derive(
        password: ByteArray,
        salt: ByteArray,
        params: Argon2Params,
        length: Int,
        secret: ByteArray?,
        associatedData: ByteArray?,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withSalt(salt)
        if (secret != null) builder.withSecret(secret)
        if (associatedData != null) builder.withAdditional(associatedData)
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(length)
        generator.generateBytes(password, out)
        return out
    }
}

/**
 * Encode un mot de passe saisi en octets, après normalisation Unicode (NFKC) :
 * un « é » composé ou précomposé donne la même clé, quel que soit le clavier.
 */
fun encodeSecret(secret: CharSequence): ByteArray =
    Normalizer.normalize(secret, Normalizer.Form.NFKC).encodeToByteArray()
