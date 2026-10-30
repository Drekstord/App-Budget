package app.boussole.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.boussole.BoussoleApp
import app.boussole.R
import app.boussole.ui.MainActivity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Rappels générés sur le téléphone, sans réseau (NOT) : un passage quotidien le
 * soir décide quoi signaler. Le coffre étant chiffré, ils ne s'appuient que sur
 * l'état en clair du service de capture (un compteur, des dates) et n'affichent
 * jamais de montant (NOT-4).
 */
object Reminders {
    private const val CHANNEL = "rappels"
    private const val ACTION = "app.boussole.REMINDER"
    private const val ID_PENDING = 1
    private const val ID_WEEKLY = 2
    private const val ID_BACKUP = 3
    private const val ID_GAP = 4

    fun schedule(context: Context) {
        val app = context.applicationContext as BoussoleApp
        val hour = app.captureStore.config.value.reminders.hour
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val trigger = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger, AlarmManager.INTERVAL_DAY, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Passage quotidien : décide des rappels à montrer. */
    fun run(context: Context) {
        val app = context.applicationContext as BoussoleApp
        val store = app.captureStore
        val config = store.config.value
        val reminders = config.reminders
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        val pending = config.pendingSnapshot + store.inboxCount()
        if (reminders.evening && pending > 0) {
            post(context, ID_PENDING, "Dépenses à valider", "$pending dépense(s) attendent ta validation. 30 secondes suffisent.")
        }
        if (reminders.weekly && today.dayOfWeek == DayOfWeek.SUNDAY) {
            post(context, ID_WEEKLY, "Ton bilan de la semaine", "Deux minutes pour voir où en sont tes enveloppes.")
        }
        val backupAge = now - config.lastBackupAt
        if (reminders.backup && today.dayOfWeek == DayOfWeek.SUNDAY && backupAge > 7 * DAY_MS) {
            post(context, ID_BACKUP, "Sauvegarde", "Pense à exporter une sauvegarde chiffrée vers Proton Drive.")
        }
        val listening = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val gap = reminders.gapDays
        if (gap > 0 && listening && config.lastCaptureAt > 0 && now - config.lastCaptureAt > gap * DAY_MS) {
            post(
                context, ID_GAP, "Aucune dépense captée",
                "Rien depuis $gap jours : vérifie que la lecture des notifications est toujours active, ou importe un relevé.",
            )
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Boussole")
            .setContentText("Un rappel t'attend.")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        @Suppress("MissingPermission") // vérifiée par canNotify
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private const val DAY_MS = 24 * 3_600_000L
}

/** Réveil quotidien des rappels. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.run(context)
}

/** Les alarmes sont effacées au redémarrage du téléphone : on les reprogramme. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.schedule(context)
    }
}
