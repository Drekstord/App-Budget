package app.boussole.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.fragment.app.FragmentActivity
import app.boussole.BoussoleApp
import app.boussole.CrashReports
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
    var crashReport by remember { mutableStateOf(CrashReports.read(activity)) }
    crashReport?.let { report ->
        CrashScreen(report) {
            CrashReports.clear(activity)
            crashReport = null
        }
        return
    }
    val state by vault.state.collectAsState()
    when (val current = state) {
        VaultState.Empty -> WelcomeFlow(vault, activity)
        is VaultState.Locked -> LockedFlow(current, vault, activity)
        is VaultState.Unlocked -> Shell(current, vault, activity)
    }
}

@Composable
private fun CrashScreen(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Screen {
        Title("L'app a planté", "Désolé. Copie ce rapport et envoie-le moi : il ne contient aucune donnée personnelle.")
        PrimaryButton(if (copied) "Rapport copié ✓" else "Copier le rapport", {
            clipboard.setText(AnnotatedString(report))
            copied = true
        }, tag = "crash_copy")
        SecondaryButton("Continuer", onDismiss, tag = "crash_dismiss")
        Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
