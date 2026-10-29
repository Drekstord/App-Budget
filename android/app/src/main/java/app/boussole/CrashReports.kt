package app.boussole

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Versions de test uniquement : un plantage est noté dans un fichier local
 * (jamais envoyé), puis affiché au lancement suivant pour être copié et
 * transmis à la main. Seule la pile d'appels est enregistrée, aucune donnée.
 */
object CrashReports {
    private fun file(context: Context) = File(context.noBackupFilesDir, "dernier-plantage.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file(context).writeText(
                    "Boussole ${BuildInfo.versionName(context)} — Android ${android.os.Build.VERSION.RELEASE}\n\n$trace",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}

object BuildInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
