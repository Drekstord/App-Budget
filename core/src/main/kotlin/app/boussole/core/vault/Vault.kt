package app.boussole.core.vault

import app.boussole.core.crypto.Aead
import app.boussole.core.crypto.Argon2Params
import app.boussole.core.crypto.Argon2id
import app.boussole.core.crypto.AuthenticationFailure
import app.boussole.core.crypto.Base64Serializer
import app.boussole.core.crypto.RecoveryPhrase
import app.boussole.core.domain.VaultData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * En-tête du coffre, stocké en clair à côté des données chiffrées.
 *
 * Hiérarchie (cf. docs/SECURITE.md §3) :
 * - la DEK (clé de données) chiffre les données ;
 * - l'enveloppe « mot de passe » P = AES-GCM(Argon2id(mot de passe), DEK), elle-même
 *   scellée par la clé matérielle biométrique du téléphone → [hardwareSealedPasswordEnvelope] ;
 * - l'enveloppe « récupération » R = AES-GCM(HKDF(phrase de 24 mots), DEK).
 *
 * Le cœur ignore comment l'enveloppe P est scellée matériellement : c'est le rôle
 * de la couche Android (Keystore + BiometricPrompt).
 */
@Serializable
data class VaultHeader(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val kdf: Argon2Params,
    @Serializable(with = Base64Serializer::class) val salt: ByteArray,
    @Serializable(with = Base64Serializer::class) val hardwareSealedPasswordEnvelope: ByteArray,
    @Serializable(with = Base64Serializer::class) val recoveryEnvelope: ByteArray,
) {
    companion object {
        const val FORMAT = "boussole-vault"
        const val VERSION = 1
    }

    // Égalité structurelle sur les tableaux (utile aux tests et aux comparaisons d'état).
    override fun equals(other: Any?): Boolean =
        other is VaultHeader && format == other.format && version == other.version &&
            kdf == other.kdf && salt.contentEquals(other.salt) &&
            hardwareSealedPasswordEnvelope.contentEquals(other.hardwareSealedPasswordEnvelope) &&
            recoveryEnvelope.contentEquals(other.recoveryEnvelope)

    override fun hashCode(): Int = salt.contentHashCode()
}

class InvalidVault(message: String) : Exception(message)

object VaultCodec {
    internal val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    private val AAD_PASSWORD = "boussole/envelope/password/v1".encodeToByteArray()
    private val AAD_RECOVERY = "boussole/envelope/recovery/v1".encodeToByteArray()
    private val AAD_DATA = "boussole/data/v1".encodeToByteArray()

    /** Enveloppe P : à sceller ensuite avec la clé matérielle. */
    fun sealDekWithPassword(dek: ByteArray, password: ByteArray, salt: ByteArray, kdf: Argon2Params): ByteArray {
        val kek = Argon2id.derive(password, salt, kdf)
        try {
            return Aead.seal(kek, dek, AAD_PASSWORD)
        } finally {
            kek.fill(0)
        }
    }

    /** Ouvre P avec le mot de passe ; null si le mot de passe est faux. */
    fun openDekWithPassword(passwordEnvelope: ByteArray, password: ByteArray, salt: ByteArray, kdf: Argon2Params): ByteArray? {
        val kek = Argon2id.derive(password, salt, kdf)
        return try {
            Aead.open(kek, passwordEnvelope, AAD_PASSWORD)
        } catch (_: AuthenticationFailure) {
            null
        } finally {
            kek.fill(0)
        }
    }

    fun sealDekWithRecovery(dek: ByteArray, phrase: RecoveryPhrase): ByteArray {
        val kek = phrase.deriveKey()
        try {
            return Aead.seal(kek, dek, AAD_RECOVERY)
        } finally {
            kek.fill(0)
        }
    }

    /** Ouvre R avec la phrase ; null si la phrase ne correspond pas à ce coffre. */
    fun openDekWithRecovery(recoveryEnvelope: ByteArray, phrase: RecoveryPhrase): ByteArray? {
        val kek = phrase.deriveKey()
        return try {
            Aead.open(kek, recoveryEnvelope, AAD_RECOVERY)
        } catch (_: AuthenticationFailure) {
            null
        } finally {
            kek.fill(0)
        }
    }

    fun sealData(dek: ByteArray, data: VaultData): ByteArray =
        Aead.seal(dek, json.encodeToString(VaultData.serializer(), data).encodeToByteArray(), AAD_DATA)

    /** @throws AuthenticationFailure si les données sont altérées ou la clé fausse. */
    fun openData(dek: ByteArray, sealed: ByteArray): VaultData {
        val plaintext = Aead.open(dek, sealed, AAD_DATA)
        try {
            return json.decodeFromString(VaultData.serializer(), plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    fun encodeHeader(header: VaultHeader): ByteArray =
        json.encodeToString(VaultHeader.serializer(), header).encodeToByteArray()

    fun decodeHeader(bytes: ByteArray): VaultHeader {
        val header = try {
            json.decodeFromString(VaultHeader.serializer(), bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw InvalidVault("En-tête du coffre illisible : ${e.message}")
        }
        if (header.format != VaultHeader.FORMAT || header.version > VaultHeader.VERSION) {
            throw InvalidVault("Coffre d'un format inconnu ou d'une version plus récente")
        }
        return header
    }
}
