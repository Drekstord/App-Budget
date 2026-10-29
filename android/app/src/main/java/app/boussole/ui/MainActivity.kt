package app.boussole.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import app.boussole.BoussoleApp
import app.boussole.vault.VaultService
import app.boussole.vault.VaultState

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ni capture d'écran, ni enregistrement, ni aperçu dans les apps récentes (SEC-8).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        // Ignore les touchers quand une autre app superpose une fenêtre (tapjacking).
        window.decorView.filterTouchesWhenObscured = true

        val vault = (application as BoussoleApp).vault
        setContent {
            BoussoleTheme {
                BoussoleRoot(vault, this)
            }
        }
    }
}

@Composable
private fun BoussoleRoot(vault: VaultService, activity: FragmentActivity) {
    val state by vault.state.collectAsState()
    when (val current = state) {
        VaultState.Empty -> WelcomeFlow(vault, activity)
        is VaultState.Locked -> LockedFlow(current, vault, activity)
        is VaultState.Unlocked -> HomeScreen(current, vault, activity)
    }
}
