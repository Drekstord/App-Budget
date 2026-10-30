package app.boussole.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.boussole.BoussoleApp
import app.boussole.core.domain.Transaction
import app.boussole.core.domain.VaultData
import app.boussole.vault.VaultService
import app.boussole.vault.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Tab(val label: String, val icon: String, val tag: String) {
    HOME("Accueil", "🧭", "tab_home"),
    OPERATIONS("Opérations", "🧾", "tab_operations"),
    BUDGET("Budget", "✉️", "tab_budget"),
    ADVICE("Conseils", "💡", "tab_advice"),
    MORE("Plus", "⚙️", "tab_more"),
}

/** Pages ouvertes par-dessus les onglets. */
sealed interface Route {
    data object Validation : Route
    data class Edit(val id: String?, val draft: Transaction? = null) : Route
    data object Envelopes : Route
    data class GoalEdit(val id: String?) : Route
    data object Profile : Route
    data object Accounts : Route
    data object Categories : Route
    data object Rules : Route
    data object FixedCharges : Route
    data object Capture : Route
    data object Reminders : Route
    data object Import : Route
    data object Receipt : Route
    data object Backup : Route
    data object Security : Route
    data object Review : Route
}

class Nav {
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Route>()

    fun open(route: Route) {
        stack += route
    }

    fun back() {
        stack.removeLastOrNull()
    }

    /** Remplace la page courante (ex. après une saisie de ticket, ouvrir l'édition). */
    fun replace(route: Route) {
        stack.removeLastOrNull()
        stack += route
    }
}

/** Ce dont toutes les pages ont besoin. */
class Ctx(val vault: VaultService, val activity: FragmentActivity, val nav: Nav, val scope: CoroutineScope) {
    val app: BoussoleApp get() = activity.application as BoussoleApp
    val today: LocalDate get() = LocalDate.now()

    fun update(transform: (VaultData) -> VaultData) {
        scope.launch { vault.update(transform) }
    }
}

@Composable
fun Shell(state: VaultState.Unlocked, vault: VaultService, activity: FragmentActivity) {
    val nav = remember { Nav() }
    val scope = rememberCoroutineScope()
    val ctx = remember { Ctx(vault, activity, nav, scope) }
    val data = state.data

    Surface(Modifier.fillMaxSize().exposeTags(), color = MaterialTheme.colorScheme.background) {
        if (!data.profile.onboardingDone) {
            OnboardingFlow(ctx, data)
            return@Surface
        }
        BackHandler(enabled = nav.stack.isNotEmpty()) { nav.back() }
        val snackbar = remember { SnackbarHostState() }
        val notice by vault.notice.collectAsState()
        LaunchedEffect(notice) {
            notice?.let {
                vault.consumeNotice()
                snackbar.showSnackbar(it)
            }
        }
        val route = nav.stack.lastOrNull()
        Scaffold(
            topBar = { TopBar(title(route, nav.tab), canGoBack = route != null, onBack = nav::back, onLock = vault::lock) },
            bottomBar = {
                if (route == null) {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = nav.tab == tab,
                                onClick = { nav.tab = tab },
                                icon = { Text(tab.icon) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag),
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (route) {
                    null -> when (nav.tab) {
                        Tab.HOME -> HomeTab(ctx, state)
                        Tab.OPERATIONS -> OperationsTab(ctx, data)
                        Tab.BUDGET -> BudgetTab(ctx, data)
                        Tab.ADVICE -> AdviceTab(ctx, data)
                        Tab.MORE -> MoreTab(ctx)
                    }
                    Route.Validation -> ValidationPage(ctx, data)
                    is Route.Edit -> EditTransactionPage(ctx, data, route)
                    Route.Envelopes -> EnvelopesPage(ctx, data)
                    is Route.GoalEdit -> GoalPage(ctx, data, route.id)
                    Route.Profile -> ProfilePage(ctx, data)
                    Route.Accounts -> AccountsPage(ctx, data)
                    Route.Categories -> CategoriesPage(ctx, data)
                    Route.Rules -> RulesPage(ctx, data)
                    Route.FixedCharges -> FixedChargesPage(ctx, data)
                    Route.Capture -> CapturePage(ctx, data)
                    Route.Reminders -> RemindersPage(ctx, data)
                    Route.Import -> ImportPage(ctx, data)
                    Route.Receipt -> ReceiptPage(ctx)
                    Route.Backup -> BackupPage(ctx, data)
                    Route.Security -> SecurityPage(ctx, state)
                    Route.Review -> ReviewPage(ctx, data)
                }
            }
        }
    }
}

private fun title(route: Route?, tab: Tab): String = when (route) {
    null -> if (tab == Tab.HOME) "Boussole" else tab.label
    Route.Validation -> "À valider"
    is Route.Edit -> if (route.id == null) "Nouvelle opération" else "Opération"
    Route.Envelopes -> "Mes enveloppes"
    is Route.GoalEdit -> if (route.id == null) "Nouvel objectif" else "Objectif"
    Route.Profile -> "Profil de vie"
    Route.Accounts -> "Comptes et soldes"
    Route.Categories -> "Catégories"
    Route.Rules -> "Règles de classement"
    Route.FixedCharges -> "Charges fixes"
    Route.Capture -> "Capture des dépenses"
    Route.Reminders -> "Rappels"
    Route.Import -> "Importer un relevé"
    Route.Receipt -> "Scanner un ticket"
    Route.Backup -> "Sauvegarde et export"
    Route.Security -> "Sécurité"
    Route.Review -> "Bilan de la semaine"
}

@Composable
private fun TopBar(title: String, canGoBack: Boolean, onBack: () -> Unit, onLock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("nav_back")) { Text("← Retour") }
        } else {
            Text(title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
        if (canGoBack) Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onLock, modifier = Modifier.testTag("home_lock")) { Text("🔒 Verrouiller") }
    }
}
