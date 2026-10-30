package app.boussole.core.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Réglages et état du service de capture, lisibles SANS ouvrir le coffre (le
 * service tourne quand l'app est verrouillée). Rien de financier ici : des noms
 * d'apps, des dates et un compteur.
 */
@Serializable
data class CaptureConfig(
    /** Apps dont les notifications sont lues (liste blanche, CAP-2). */
    val sources: Set<String> = DEFAULT_SOURCES,
    /** Mode diagnostic : retient le nom des apps qui notifient et les notifications non comprises. */
    val diagnostic: Boolean = false,
    val seen: Map<String, SeenApp> = emptyMap(),
    val lastCaptureAt: Long = 0,
    val lastBackupAt: Long = 0,
    /** Nombre d'opérations à valider dans le coffre au dernier enregistrement. */
    val pendingSnapshot: Int = 0,
    val reminders: ReminderMirror = ReminderMirror(),
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val DEFAULT_SOURCES = setOf(
            "com.boursorama.android.clients",
            "com.samsung.android.spay",
            "fr.caisseepargne.android.mobilebanking",
        )
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): CaptureConfig = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class SeenApp(val label: String, val lastSeen: Long, val count: Int)

/** Copie en clair des réglages de rappels (le coffre est chiffré, les rappels partent coffre fermé). */
@Serializable
data class ReminderMirror(
    val evening: Boolean = true,
    val hour: Int = 20,
    val weekly: Boolean = true,
    val backup: Boolean = true,
    val gapDays: Int = 4,
)
