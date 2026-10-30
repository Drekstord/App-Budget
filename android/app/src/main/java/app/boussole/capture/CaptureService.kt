package app.boussole.capture

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.boussole.BoussoleApp
import app.boussole.core.capture.CapturedItem
import app.boussole.core.capture.Inbox
import app.boussole.core.capture.NotificationParser
import java.util.UUID

/**
 * Lecture des notifications (CAP-2). Seules les apps de la liste blanche sont
 * lues ; le texte n'est jamais écrit en clair : il est scellé dans la boîte de
 * réception avec la clé publique, puis analysé à l'ouverture du coffre.
 */
class CaptureService : NotificationListenerService() {

    override fun onListenerConnected() {
        (application as? BoussoleApp)?.traceTest("capture : service connecté")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as? BoussoleApp ?: return
        val store = app.captureStore
        val config = store.config.value
        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (config.diagnostic) store.recordSeen(pkg, appLabel(sbn), System.currentTimeMillis())
        app.traceTest("capture : notification de $pkg, source autorisée = ${pkg in config.sources}")
        if (pkg !in config.sources) return

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val publicKey = store.publicKey
        app.traceTest("capture : clé publique présente = ${publicKey != null}")
        publicKey ?: return // coffre jamais ouvert : rien à faire
        val item = CapturedItem(UUID.randomUUID().toString(), pkg, sbn.postTime, title, text)
        val sealed = Inbox.seal(publicKey, item.encode())
        val understood = NotificationParser.parse(title, text) != null
        app.traceTest("capture : comprise = $understood")
        if (understood) {
            store.append(sealed)
            store.update { it.copy(lastCaptureAt = System.currentTimeMillis()) }
            app.vault.onCaptured()
        } else if (config.diagnostic) {
            store.appendDiagnostic(sealed)
        }
    }

    private fun appLabel(sbn: StatusBarNotification): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sbn.notification.extras.getParcelable(EXTRA_APP_INFO, ApplicationInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            sbn.notification.extras.getParcelable(EXTRA_APP_INFO)
        }
        return info?.loadLabel(packageManager)?.toString() ?: sbn.packageName
    }

    companion object {
        /** Extra posé par le système sur chaque notification (Notification.EXTRA_BUILDER_APPLICATION_INFO). */
        private const val EXTRA_APP_INFO = "android.appInfo"
    }
}
