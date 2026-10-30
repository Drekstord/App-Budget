package app.boussole.capture

import android.content.Context
import app.boussole.core.capture.CaptureConfig
import app.boussole.core.capture.SeenApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Base64

/**
 * Stockage du service de capture, utilisable coffre fermé :
 * - `config.json` : réglages et état, sans donnée financière (cf. CaptureConfig) ;
 * - `inbox.pub` : clé publique de la boîte de réception ;
 * - `inbox.log` / `diagnostic.log` : captures SCELLÉES, une par ligne (Base64),
 *   illisibles sans la clé privée rangée dans le coffre.
 */
class CaptureStore(context: Context, private val testBuild: Boolean) {
    private val dir = File(context.noBackupFilesDir, "capture").apply { mkdirs() }
    private val configFile = File(dir, "config.json")
    private val publicKeyFile = File(dir, "inbox.pub")
    private val inboxFile = File(dir, "inbox.log")
    private val processingFile = File(dir, "inbox.processing")
    private val diagnosticFile = File(dir, "diagnostic.log")
    private val lock = Any()

    private val _config = MutableStateFlow(load())
    val config: StateFlow<CaptureConfig> = _config.asStateFlow()

    private fun load(): CaptureConfig {
        val stored = runCatching { CaptureConfig.decode(configFile.readText()) }.getOrNull() ?: CaptureConfig()
        // Versions de test : l'app de test de bout en bout peut jouer le rôle d'une banque.
        return if (testBuild) stored.copy(sources = stored.sources + E2E_PACKAGE) else stored
    }

    fun update(transform: (CaptureConfig) -> CaptureConfig) = synchronized(lock) {
        val next = transform(_config.value)
        if (next == _config.value) return@synchronized
        configFile.writeText(next.encode())
        _config.value = next
    }

    fun recordSeen(packageName: String, label: String, now: Long) = update { config ->
        val previous = config.seen[packageName]
        config.copy(seen = config.seen + (packageName to SeenApp(label, now, (previous?.count ?: 0) + 1)))
    }

    var publicKey: ByteArray?
        get() = publicKeyFile.takeIf { it.exists() }?.readBytes()
        set(value) {
            if (value == null) publicKeyFile.delete() else publicKeyFile.writeBytes(value)
        }

    fun append(sealed: ByteArray) = appendLine(inboxFile, sealed)

    fun appendDiagnostic(sealed: ByteArray) = appendLine(diagnosticFile, sealed)

    private fun appendLine(file: File, sealed: ByteArray) = synchronized(lock) {
        file.appendText(Base64.getEncoder().encodeToString(sealed) + "\n")
    }

    /**
     * Récupère toutes les captures en attente et vide la boîte. Une capture
     * arrivée pendant la lecture va dans un nouveau fichier : rien ne se perd.
     */
    fun drain(): List<ByteArray> = synchronized(lock) {
        if (inboxFile.exists()) {
            if (processingFile.exists()) processingFile.appendText(inboxFile.readText()) else inboxFile.renameTo(processingFile)
            inboxFile.delete()
        }
        if (!processingFile.exists()) return@synchronized emptyList()
        val items = decodeLines(processingFile)
        items
    }

    /** À appeler une fois les captures enregistrées dans le coffre. */
    fun acknowledgeDrain() = synchronized(lock) { processingFile.delete() }

    fun inboxCount(): Int = synchronized(lock) {
        listOf(inboxFile, processingFile).filter { it.exists() }.sumOf { file -> file.readLines().count { it.isNotBlank() } }
    }

    fun diagnostics(): List<ByteArray> = synchronized(lock) {
        if (diagnosticFile.exists()) decodeLines(diagnosticFile) else emptyList()
    }

    fun clearDiagnostics() = synchronized(lock) { diagnosticFile.delete() }

    private fun decodeLines(file: File): List<ByteArray> =
        file.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    companion object {
        const val E2E_PACKAGE = "app.boussole.e2e"
    }
}
