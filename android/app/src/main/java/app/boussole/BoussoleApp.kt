package app.boussole

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.boussole.vault.VaultService

class BoussoleApp : Application() {
    lateinit var vault: VaultService
        private set

    override fun onCreate() {
        super.onCreate()
        vault = VaultService(this)
        // Verrouillage dès que l'app quitte l'écran (SEC-5) : écran éteint,
        // retour à l'accueil, changement d'app, partage d'une sauvegarde…
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) vault.lock()
            },
        )
    }
}
