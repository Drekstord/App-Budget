package app.boussole.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Signes simples d'un téléphone modifié (défense en profondeur : un attaquant
 * qui contrôle le téléphone peut les masquer). Sert à prévenir, pas à bloquer.
 */
object DeviceIntegrity {
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su", "/data/local/xbin/su",
        "/data/local/bin/su", "/system/app/Superuser.apk", "/data/adb/magisk",
    )

    fun check(context: Context): List<String> {
        val issues = mutableListOf<String>()
        if (Build.TAGS?.contains("test-keys") == true) issues += "Système signé avec des clés de test (ROM modifiée)."
        if (SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }) issues += "Outil « su » détecté : le téléphone semble rooté."
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) {
            issues += "Débogage USB activé : désactive-le dans les options de développement si tu ne t'en sers pas."
        }
        return issues
    }
}
