package app.boussole.vault

import android.content.Context
import androidx.core.util.AtomicFile
import app.boussole.core.backup.BackupCodec
import app.boussole.core.backup.InvalidBackup
import app.boussole.core.backup.RestoredBackup
import app.boussole.core.crypto.Aead
import app.boussole.core.crypto.Argon2Params
import app.boussole.core.crypto.Argon2id
import app.boussole.core.crypto.RecoveryPhrase
import app.boussole.core.crypto.encodeSecret
import app.boussole.core.domain.Account
import app.boussole.core.domain.AccountKind
import app.boussole.core.domain.VaultData
import app.boussole.core.vault.AttemptThrottle
import app.boussole.core.vault.VaultCodec
import app.boussole.core.vault.VaultHeader
import app.boussole.security.HardwareKey
import app.boussole.security.HardwareKeyInvalidated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

sealed interface VaultState {
    /** Premier lancement : aucun coffre sur ce téléphone. */
    data object Empty : VaultState

    data class Locked(
        val failures: Int,
        /** Instant (ms) avant lequel aucun essai n'est accepté ; 0 si aucun délai. */
        val lockedUntil: Long,
        /** Empreintes modifiées : seule la phrase de récupération peut rouvrir le coffre. */
        val keyInvalidated: Boolean,
    ) : VaultState

    data class Unlocked(val data: VaultData, val keyLevel: HardwareKey.Level) : VaultState
}

sealed interface UnlockResult {
    data object Success : UnlockResult
    data class WrongPassword(val failures: Int, val lockedUntil: Long) : UnlockResult
    data class TooEarly(val lockedUntil: Long) : UnlockResult
    data object KeyInvalidated : UnlockResult
}

/**
 * Le coffre : seul endroit de l'app qui touche aux clés et aux fichiers.
 *
 * Fichiers (dans `noBackupFilesDir`, exclus de toute sauvegarde système) :
 * `header.json` (en-tête, cf. [VaultHeader]) et `data.bin` (données scellées).
 * La DEK n'existe en mémoire que pendant qu'il est ouvert.
 */
class VaultService(context: Context) {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val exportsDir = File(context.cacheDir, "exports")
    private val headerFile = AtomicFile(File(dir, "header.json"))
    private val dataFile = AtomicFile(File(dir, "data.bin"))
    private val attemptsFile = File(dir, "attempts")
    private val hardware = HardwareKey()
    private val mutex = Mutex()

    private var header: VaultHeader? = readHeaderOrNull()
    private var dek: ByteArray? = null

    private val _state = MutableStateFlow(lockedOrEmpty())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    init {
        // Une sauvegarde exportée n'a pas à traîner dans le cache.
        exportsDir.deleteRecursively()
    }

    // --- Création -------------------------------------------------------------

    /** Prépare une nouvelle clé matérielle ; le Cipher rendu doit être authentifié par l'empreinte. */
    fun prepareNewHardwareKey(): Cipher {
        hardware.recreate()
        return hardware.encryptCipher()
    }

    suspend fun create(password: String, phrase: RecoveryPhrase, authorizedCipher: Cipher) = mutex.withLock {
        check(header == null) { "Un coffre existe déjà" }
        val newDek = Aead.newKey()
        val data = VaultData(accounts = defaultAccounts())
        val newHeader = withContext(Dispatchers.Default) {
            sealHeader(newDek, password, authorizedCipher, VaultCodec.sealDekWithRecovery(newDek, phrase))
        }
        persist(newHeader, newDek, data)
    }

    // --- Ouverture ------------------------------------------------------------

    /**
     * Cipher à faire authentifier par l'empreinte avant [unlock], ou null si la
     * clé matérielle a été invalidée (l'état passe alors en conséquence).
     */
    fun prepareUnlock(): Cipher? {
        val current = header ?: return null
        return try {
            hardware.decryptCipher(current.hardwareSealedPasswordEnvelope)
        } catch (_: HardwareKeyInvalidated) {
            markInvalidated()
            null
        }
    }

    suspend fun unlock(password: String, authorizedCipher: Cipher): UnlockResult = mutex.withLock {
        val current = checkNotNull(header)
        val (failures, until) = readAttempts()
        if (System.currentTimeMillis() < until) return UnlockResult.TooEarly(until)

        val sealed = current.hardwareSealedPasswordEnvelope
        val passwordEnvelope = try {
            authorizedCipher.doFinal(sealed, HardwareKey.NONCE_BYTES, sealed.size - HardwareKey.NONCE_BYTES)
        } catch (_: AEADBadTagException) {
            markInvalidated()
            return UnlockResult.KeyInvalidated
        }
        val opened = withContext(Dispatchers.Default) {
            val secret = encodeSecret(password)
            try {
                VaultCodec.openDekWithPassword(passwordEnvelope, secret, current.salt, current.kdf)
            } finally {
                secret.fill(0)
                passwordEnvelope.fill(0)
            }
        }
        if (opened == null) {
            val count = failures + 1
            val lockedUntil = AttemptThrottle.lockoutMillis(count).let { if (it == 0L) 0L else System.currentTimeMillis() + it }
            writeAttempts(count, lockedUntil)
            _state.value = VaultState.Locked(count, lockedUntil, keyInvalidated = false)
            return UnlockResult.WrongPassword(count, lockedUntil)
        }
        writeAttempts(0, 0)
        val data = withContext(Dispatchers.IO) { VaultCodec.openData(opened, dataFile.readFully()) }
        open(opened, data)
        UnlockResult.Success
    }

    fun lock() {
        dek?.fill(0)
        dek = null
        if (_state.value is VaultState.Unlocked) _state.value = lockedOrEmpty()
    }

    // --- Récupération (empreintes modifiées, mot de passe oublié) -------------

    /** Vérifie la phrase AVANT de toucher à la clé matérielle ; null si elle n'ouvre pas ce coffre. */
    fun checkRecoveryPhrase(phrase: RecoveryPhrase): ByteArray? =
        header?.let { VaultCodec.openDekWithRecovery(it.recoveryEnvelope, phrase) }

    /** Nouveau mot de passe et nouvelle clé matérielle, après [checkRecoveryPhrase]. */
    suspend fun rekey(recoveredDek: ByteArray, newPassword: String, authorizedCipher: Cipher) = mutex.withLock {
        val current = checkNotNull(header)
        val newHeader = withContext(Dispatchers.Default) {
            sealHeader(recoveredDek, newPassword, authorizedCipher, current.recoveryEnvelope)
        }
        val data = withContext(Dispatchers.IO) { VaultCodec.openData(recoveredDek, dataFile.readFully()) }
        writeAttempts(0, 0)
        persist(newHeader, recoveredDek, data)
    }

    // --- Sauvegarde -----------------------------------------------------------

    /** Écrit une sauvegarde chiffrée dans le cache, prête à être partagée. */
    suspend fun exportBackup(): File = mutex.withLock {
        val key = checkNotNull(dek) { "Coffre verrouillé" }
        val data = (state.value as VaultState.Unlocked).data
        val bytes = withContext(Dispatchers.Default) {
            BackupCodec.encode(data, key, checkNotNull(header).recoveryEnvelope, Instant.now().toString())
        }
        withContext(Dispatchers.IO) {
            exportsDir.deleteRecursively()
            exportsDir.mkdirs()
            File(exportsDir, "boussole-${LocalDate.now()}.${BackupCodec.EXTENSION}").apply { writeBytes(bytes) }
        }
    }

    /** @throws InvalidBackup */
    fun readBackup(file: ByteArray, phrase: RecoveryPhrase): RestoredBackup = BackupCodec.decode(file, phrase)

    suspend fun restore(backup: RestoredBackup, newPassword: String, authorizedCipher: Cipher) = mutex.withLock {
        check(header == null) { "Un coffre existe déjà" }
        val newHeader = withContext(Dispatchers.Default) {
            sealHeader(backup.dek, newPassword, authorizedCipher, backup.recoveryEnvelope)
        }
        persist(newHeader, backup.dek, backup.data)
    }

    // --- Données --------------------------------------------------------------

    suspend fun update(transform: (VaultData) -> VaultData) = mutex.withLock {
        val key = dek ?: return@withLock
        val current = (state.value as? VaultState.Unlocked)?.data ?: return@withLock
        val next = transform(current)
        withContext(Dispatchers.IO) { writeAtomically(dataFile, VaultCodec.sealData(key, next)) }
        _state.value = VaultState.Unlocked(next, hardware.level())
    }

    // --- Interne --------------------------------------------------------------

    private fun sealHeader(dek: ByteArray, password: String, authorizedCipher: Cipher, recoveryEnvelope: ByteArray): VaultHeader {
        val salt = Argon2id.newSalt()
        val kdf = Argon2Params.DEFAULT
        val secret = encodeSecret(password)
        val passwordEnvelope = try {
            VaultCodec.sealDekWithPassword(dek, secret, salt, kdf)
        } finally {
            secret.fill(0)
        }
        val hardwareSealed = authorizedCipher.iv + authorizedCipher.doFinal(passwordEnvelope)
        passwordEnvelope.fill(0)
        return VaultHeader(
            kdf = kdf,
            salt = salt,
            hardwareSealedPasswordEnvelope = hardwareSealed,
            recoveryEnvelope = recoveryEnvelope,
        )
    }

    /** Données d'abord, en-tête ensuite : l'en-tête marque un coffre complet. */
    private suspend fun persist(newHeader: VaultHeader, key: ByteArray, data: VaultData) {
        withContext(Dispatchers.IO) {
            writeAtomically(dataFile, VaultCodec.sealData(key, data))
            writeAtomically(headerFile, VaultCodec.encodeHeader(newHeader))
        }
        header = newHeader
        open(key.copyOf(), data)
    }

    private fun open(key: ByteArray, data: VaultData) {
        dek?.fill(0)
        dek = key
        _state.value = VaultState.Unlocked(data, hardware.level())
    }

    private fun markInvalidated() {
        val (failures, until) = readAttempts()
        _state.value = VaultState.Locked(failures, until, keyInvalidated = true)
    }

    private fun lockedOrEmpty(): VaultState {
        if (header == null) return VaultState.Empty
        val (failures, until) = readAttempts()
        return VaultState.Locked(failures, until, keyInvalidated = false)
    }

    private fun readHeaderOrNull(): VaultHeader? =
        if (headerFile.baseFile.exists()) VaultCodec.decodeHeader(headerFile.readFully()) else null

    private fun readAttempts(): Pair<Int, Long> = runCatching {
        val (count, until) = attemptsFile.readText().split(';')
        count.toInt() to until.toLong()
    }.getOrDefault(0 to 0L)

    private fun writeAttempts(count: Int, until: Long) = attemptsFile.writeText("$count;$until")

    private fun writeAtomically(file: AtomicFile, bytes: ByteArray) {
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun defaultAccounts() = listOf(
        Account(UUID.randomUUID().toString(), "Compte courant", AccountKind.CHECKING, "Boursobank"),
        Account(UUID.randomUUID().toString(), "Compte courant", AccountKind.CHECKING, "Caisse d'Épargne"),
        Account(UUID.randomUUID().toString(), "Épargne", AccountKind.SAVINGS, "Caisse d'Épargne"),
    )
}
