package app.boussole.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** La clé matérielle n'est plus utilisable : empreintes modifiées, ou clé effacée. */
class HardwareKeyInvalidated(cause: Throwable? = null) : Exception(cause)

/**
 * Clé AES-256 non exportable de l'Android Keystore, dans la puce StrongBox si
 * elle est disponible. Chaque utilisation exige une authentification biométrique
 * forte (classe 3) ; l'ajout d'une empreinte l'invalide définitivement ; elle est
 * inutilisable tant que le téléphone est verrouillé.
 */
class HardwareKey(private val alias: String = "boussole.vault.v1") {

    enum class Level { STRONGBOX, TEE, SOFTWARE, UNKNOWN }

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Crée (en remplaçant une éventuelle clé existante) la clé matérielle. */
    fun recreate() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    /** Cipher de chiffrement, à authentifier par BiometricPrompt avant usage. */
    fun encryptCipher(): Cipher = cipher { init(Cipher.ENCRYPT_MODE, key()) }

    /** Cipher de déchiffrement pour un contenu scellé (nonce ‖ chiffré). */
    fun decryptCipher(sealed: ByteArray): Cipher =
        cipher { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES)) }

    fun level(): Level = try {
        val key = key()
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> Level.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Level.TEE
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> Level.SOFTWARE
            else -> Level.UNKNOWN
        }
    } catch (_: Exception) {
        Level.UNKNOWN
    }

    private fun key(): SecretKey =
        (keyStore.getKey(alias, null) as? SecretKey) ?: throw HardwareKeyInvalidated()

    private inline fun cipher(init: Cipher.() -> Unit): Cipher = try {
        Cipher.getInstance(TRANSFORMATION).apply(init)
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw HardwareKeyInvalidated(e)
    } catch (e: UnrecoverableKeyException) {
        throw HardwareKeyInvalidated(e)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(spec)
            generateKey()
        }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        const val NONCE_BYTES = 12
    }
}
