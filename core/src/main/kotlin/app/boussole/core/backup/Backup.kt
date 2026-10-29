package app.boussole.core.backup

import app.boussole.core.crypto.Aead
import app.boussole.core.crypto.AuthenticationFailure
import app.boussole.core.crypto.Base64Serializer
import app.boussole.core.crypto.RecoveryPhrase
import app.boussole.core.domain.VaultData
import app.boussole.core.vault.VaultCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

/** En-tête en clair d'une sauvegarde : rien de personnel, seulement de quoi l'ouvrir. */
@Serializable
data class BackupHeader(
    /** Instant ISO-8601 de création. */
    val createdAt: String,
    /** Enveloppe R : la DEK chiffrée par la phrase de récupération. */
    @Serializable(with = Base64Serializer::class) val recoveryEnvelope: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is BackupHeader && createdAt == other.createdAt &&
            recoveryEnvelope.contentEquals(other.recoveryEnvelope)

    override fun hashCode(): Int = createdAt.hashCode()
}

class InvalidBackup(message: String) : Exception(message)

/** Résultat d'une restauration : de quoi reconstruire le coffre sur un nouveau téléphone. */
class RestoredBackup(val dek: ByteArray, val recoveryEnvelope: ByteArray, val data: VaultData)

/**
 * Fichier de sauvegarde `.boussole` :
 *
 * ```
 * "BSLB" ‖ version (1 octet) ‖ taille de l'en-tête (4 octets) ‖ en-tête JSON ‖ données scellées
 * ```
 *
 * Les données sont scellées par la DEK, avec tout ce qui précède comme données
 * associées : modifier l'en-tête invalide le fichier. Seule la phrase de
 * récupération permet de l'ouvrir.
 */
object BackupCodec {
    const val EXTENSION = "boussole"
    private val MAGIC = "BSLB".encodeToByteArray()
    private const val VERSION: Byte = 1
    private const val MAX_HEADER_BYTES = 64 * 1024
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(data: VaultData, dek: ByteArray, recoveryEnvelope: ByteArray, createdAt: String): ByteArray {
        val header = json.encodeToString(BackupHeader.serializer(), BackupHeader(createdAt, recoveryEnvelope))
            .encodeToByteArray()
        val prefix = ByteBuffer.allocate(MAGIC.size + 1 + 4 + header.size)
            .put(MAGIC).put(VERSION).putInt(header.size).put(header).array()
        val payload = VaultCodec.json.encodeToString(VaultData.serializer(), data).encodeToByteArray()
        try {
            return prefix + Aead.seal(dek, payload, prefix)
        } finally {
            payload.fill(0)
        }
    }

    /** Lit l'en-tête sans rien déchiffrer (pour afficher la date avant de demander la phrase). */
    fun readHeader(file: ByteArray): BackupHeader = split(file).header

    /**
     * @throws InvalidBackup si le fichier n'est pas une sauvegarde, s'il est
     *   altéré, ou si la phrase ne correspond pas.
     */
    fun decode(file: ByteArray, phrase: RecoveryPhrase): RestoredBackup {
        val parts = split(file)
        val dek = VaultCodec.openDekWithRecovery(parts.header.recoveryEnvelope, phrase)
            ?: throw InvalidBackup("Cette phrase de récupération n'ouvre pas cette sauvegarde.")
        val payload = try {
            Aead.open(dek, parts.sealed, parts.prefix)
        } catch (_: AuthenticationFailure) {
            dek.fill(0)
            throw InvalidBackup("La sauvegarde est altérée ou corrompue.")
        }
        try {
            val data = VaultCodec.json.decodeFromString(VaultData.serializer(), payload.decodeToString())
            return RestoredBackup(dek, parts.header.recoveryEnvelope, data)
        } finally {
            payload.fill(0)
        }
    }

    private class Parts(val prefix: ByteArray, val header: BackupHeader, val sealed: ByteArray)

    private fun split(file: ByteArray): Parts {
        val fixed = MAGIC.size + 1 + 4
        if (file.size < fixed || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw InvalidBackup("Ce fichier n'est pas une sauvegarde Boussole.")
        }
        val buffer = ByteBuffer.wrap(file, MAGIC.size, 5)
        val version = buffer.get()
        if (version != VERSION) throw InvalidBackup("Sauvegarde d'une version inconnue ($version).")
        val headerSize = buffer.getInt()
        if (headerSize !in 1..MAX_HEADER_BYTES || fixed + headerSize > file.size) {
            throw InvalidBackup("En-tête de sauvegarde invalide.")
        }
        val headerBytes = file.copyOfRange(fixed, fixed + headerSize)
        val header = try {
            json.decodeFromString(BackupHeader.serializer(), headerBytes.decodeToString())
        } catch (_: IllegalArgumentException) {
            throw InvalidBackup("En-tête de sauvegarde illisible.")
        }
        return Parts(
            prefix = file.copyOfRange(0, fixed + headerSize),
            header = header,
            sealed = file.copyOfRange(fixed + headerSize, file.size),
        )
    }
}
