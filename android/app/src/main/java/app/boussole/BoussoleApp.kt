package app.boussole

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.boussole.capture.CaptureStore
import app.boussole.reminders.Reminders
import app.boussole.vault.VaultService

class BoussoleApp : Application() {
    lateinit var vault: VaultService
        private set
    lateinit var captureStore: CaptureStore
        private set

    /** Navigation conservée tant que l'app vit, verrouillage compris (aucune donnée dedans). */
    val nav = app.boussole.ui.Nav()

    /** Enregistrements lancés depuis l'interface : ils survivent à la fermeture d'un écran. */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    private val handler = Handler(Looper.getMainLooper())
    private val lockNow = Runnable { vault.lockAfterPendingWrites() }

    /** Jusqu'à quand une activité ouverte par l'app elle-même (appareil photo, fichiers) suspend le verrouillage. */
    private var externalUntil = 0L

    override fun onCreate() {
        super.onCreate()
        testBuild = resources.getBoolean(R.bool.is_test_build)
        if (testBuild) CrashReports.install(this)
        captureStore = CaptureStore(this, testBuild)
        vault = VaultService(this, captureStore, ::traceTest)
        Reminders.schedule(this)
        // Verrouillage dès que l'app quitte l'écran (SEC-5), après le délai de grâce
        // choisi (0 à 60 s), ou à la fin d'une action externe lancée par l'app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        val grace = (vault.data()?.settings?.lockGraceSeconds ?: 0) * 1_000L
                        val external = (externalUntil - System.currentTimeMillis()).coerceAtLeast(0)
                        val delay = maxOf(grace, external)
                        if (delay == 0L) vault.lockAfterPendingWrites() else handler.postDelayed(lockNow, delay)
                    }
                    Lifecycle.Event.ON_START -> {
                        handler.removeCallbacks(lockNow)
                        externalUntil = 0
                    }
                    else -> Unit
                }
            },
        )
    }

    private var testBuild = false

    /**
     * Journal technique des versions de test (lu par les tests sur émulateur) :
     * jamais de montant ni de texte de notification, et rien en version définitive.
     */
    fun traceTest(message: String) {
        if (testBuild) android.util.Log.i("BoussoleTrace", message)
    }

    /**
     * À appeler juste avant d'ouvrir l'appareil photo ou le sélecteur de fichiers
     * depuis l'app : le coffre reste ouvert au plus 3 minutes pendant ce temps.
     */
    fun beginExternalAction() {
        externalUntil = System.currentTimeMillis() + EXTERNAL_ACTION_MS
    }

    companion object {
        private const val EXTERNAL_ACTION_MS = 3 * 60_000L
    }
}
