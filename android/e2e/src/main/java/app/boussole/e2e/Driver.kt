package app.boussole.e2e

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.provider.Settings
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import java.util.regex.Pattern

const val APP_PACKAGE = "app.boussole"
const val LOG_TAG = "BoussoleE2E"
private const val CHANNEL = "banque-factice"
private const val DEFAULT_TIMEOUT = 20_000L

/** Pilote de l'app par l'accessibilité, avec des messages d'échec qui montrent l'écran. */
class Driver {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val context: Context = InstrumentationRegistry.getInstrumentation().context

    fun launchApp() {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)) {
            "L'app $APP_PACKAGE n'est pas installée"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(APP_PACKAGE).depth(0)), DEFAULT_TIMEOUT)
    }

    /**
     * Ferme les dialogues système « … ne répond pas » (fréquents sur un émulateur
     * chargé) en choisissant d'attendre. Rend vrai si un dialogue a été fermé.
     */
    fun dismissSystemDialogs(): Boolean {
        val wait = device.findObject(By.res("android:id/aerr_wait")) ?: return false
        Log.i(LOG_TAG, "Dialogue système fermé : ${screen()}")
        wait.click()
        return true
    }

    /** Trouve un élément, en faisant défiler l'écran si besoin ; échoue avec l'état de l'écran sinon. */
    fun find(selector: BySelector, what: String, timeout: Long = DEFAULT_TIMEOUT, scroll: Boolean = true): UiObject2 {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            device.wait(Until.findObject(selector), 1_500)?.let { return it }
            if (dismissSystemDialogs()) continue
            if (scroll) scrollTo(selector)?.let { return it }
        }
        fail("Introuvable : $what\n${screen()}")
        error("inaccessible")
    }

    /** Fait défiler l'écran vers le bas puis vers le haut à la recherche de l'élément. */
    private fun scrollTo(selector: BySelector): UiObject2? {
        val scrollable = device.findObject(By.scrollable(true)) ?: return null
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            repeat(6) {
                device.findObject(selector)?.let { return it }
                if (!scrollable.scroll(direction, 0.7f)) return@repeat
            }
        }
        return device.findObject(selector)
    }

    fun tag(tag: String, timeout: Long = DEFAULT_TIMEOUT) = find(By.res(tag), "élément « $tag »", timeout)

    /**
     * Attend un élément après une demande d'empreinte, sans faire défiler : un
     * geste sur l'écran pourrait annuler l'invite biométrique.
     */
    fun awaitAfterFingerprint(tag: String) = find(By.res(tag), "élément « $tag »", 90_000, scroll = false)

    fun awaitTextAfterFingerprint(text: String) = find(By.textContains(text), "texte « $text »", 90_000, scroll = false)

    fun type(tag: String, value: String) {
        tag(tag).text = value
    }

    /**
     * Clique dès que l'élément est activé (les boutons le deviennent après la
     * saisie) ; recommence si l'écran s'est recomposé entre-temps.
     */
    fun click(tag: String, timeout: Long = DEFAULT_TIMEOUT) {
        tag(tag, timeout)
        repeat(5) { attempt ->
            try {
                find(By.res(tag).enabled(true), "bouton activé « $tag »", timeout).click()
                return
            } catch (e: StaleObjectException) {
                if (attempt == 4) throw e
                Thread.sleep(300)
            }
        }
    }

    /** Texte d'un élément, en attendant qu'il soit affiché (pendant une recomposition, il peut manquer). */
    fun textOf(tag: String, timeout: Long = DEFAULT_TIMEOUT): String {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val text = runCatching { tag(tag, timeout).text }.getOrNull()
            if (!text.isNullOrEmpty()) return text
            Thread.sleep(250)
        }
        fail("Texte vide pour « $tag »\n${screen()}")
        error("inaccessible")
    }

    fun expectText(text: String, timeout: Long = DEFAULT_TIMEOUT) =
        find(By.textContains(text), "texte « $text »", timeout)

    /** Le test joue le rôle d'une app bancaire (source autorisée dans les versions de test). */
    fun createNotificationChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Banque factice", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun postNotification(id: Int, title: String, text: String) {
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        Log.i(LOG_TAG, "Notification publiée : $title — $text")
    }

    fun expectNoCrashReport() {
        if (device.findObject(By.res("crash_copy")) != null) fail("L'app a planté :\n${screen()}")
    }

    /** Textes visibles à l'écran, pour comprendre un échec. */
    fun screen(): String {
        val texts = device.findObjects(By.text(Pattern.compile(".+"))).mapNotNull { runCatching { it.text }.getOrNull() }
        val tags = device.findObjects(By.res(Pattern.compile(".+"))).mapNotNull { runCatching { it.resourceName }.getOrNull() }
        return "Écran (${device.currentPackageName}) :\n  textes = $texts\n  repères = $tags"
    }

    /**
     * Enregistre une empreinte sur l'émulateur si besoin. Le script de CI pose un
     * doigt virtuel en continu (`adb emu finger touch 1`) et a défini le code 1111.
     */
    fun ensureFingerprintEnrolled() {
        val manager = context.getSystemService(BiometricManager::class.java)
        fun enrolled() = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (enrolled()) return

        @Suppress("DEPRECATION")
        context.startActivity(
            Intent(Settings.ACTION_FINGERPRINT_ENROLL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val buttons = Pattern.compile("(?i)^(i agree|agree|more|next|continue|start|ok|got it|done)$")
        val deadline = System.currentTimeMillis() + 120_000
        while (!enrolled() && System.currentTimeMillis() < deadline) {
            Log.i(LOG_TAG, "Enregistrement de l'empreinte — ${screen()}")
            if (dismissSystemDialogs()) {
                Thread.sleep(1_500)
                continue
            }
            val pin = device.findObject(By.clazz("android.widget.EditText"))
            if (pin != null) {
                pin.text = "1111"
                device.pressEnter()
            } else {
                device.findObject(By.text(buttons))?.click()
            }
            Thread.sleep(1_500)
        }
        if (!enrolled()) fail("Impossible d'enregistrer une empreinte sur l'émulateur.\n${screen()}")
        // Terminer proprement l'assistant (« Done ») puis revenir à l'accueil.
        device.findObject(By.text(Pattern.compile("(?i)^done$")))?.click()
        device.pressHome()
    }
}
