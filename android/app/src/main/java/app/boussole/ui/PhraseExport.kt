package app.boussole.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sortie volontaire de la phrase de récupération hors de l'app (choix de
 * l'utilisateur, cf. docs/SECURITE.md §3) : copie dans le presse-papiers ou
 * fichier texte partagé (vers Proton Drive par exemple).
 */
object PhraseExport {
    private const val CLIP_LABEL = "Phrase de récupération Boussole"
    private const val CLEAR_DELAY_MS = 60_000L

    // Constante ClipDescription.EXTRA_IS_SENSITIVE (API 33), écrite en clair pour
    // s'appliquer aussi aux versions antérieures qui la respectent.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun text(words: List<String>): String = buildString {
        appendLine("Boussole — phrase de récupération (24 mots, dans l'ordre)")
        appendLine("Elle ouvre toutes tes sauvegardes, sur n'importe quel téléphone. Ne la partage avec personne.")
        appendLine()
        words.forEachIndexed { i, word -> appendLine("${i + 1}. $word") }
    }

    /**
     * Copie marquée « sensible » (Android masque l'aperçu), puis presse-papiers
     * vidé au bout de 60 s, même si l'écran a changé entre-temps.
     */
    fun copy(context: Context, words: List<String>) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(CLIP_LABEL, words.joinToString(" "))
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { clipboard.clearPrimaryClip() }
        }, CLEAR_DELAY_MS)
    }

    /** Écrit un fichier texte temporaire et ouvre la feuille de partage. */
    fun share(context: Context, words: List<String>) {
        val file = file(context).apply {
            parentFile?.mkdirs()
            writeText(text(words))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.backups", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Enregistrer la phrase de récupération"))
    }

    /** Le fichier temporaire ne doit pas survivre à l'étape (ni au redémarrage de l'app). */
    fun deleteFile(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(File(context.cacheDir, "exports"), "boussole-phrase-de-recuperation.txt")
}
