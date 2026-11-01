package app.boussole.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.boussole.BoussoleApp
import app.boussole.core.domain.Transaction
import app.boussole.core.domain.VaultData
import app.boussole.vault.VaultService
import app.boussole.vault.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Tab(val label: String, val icon: String, val tag: String, val tour: String) {
    HOME("Accueil", "🏰", "tab_home", Tours.HOME),
    EXPENSES("Dépenses", "🧾", "tab_expenses", Tours.EXPENSES),
    GOALS("Objectifs", "🎯", "tab_goals", Tours.GOALS),
}

/** Pages ouvertes par-dessus les onglets. */
sealed interface Route {
    data object Validation : Route
    data class Edit(val id: String?, val draft: Transaction? = null) : Route
    data object Budget : Route
    data object Envelopes : Route
    data class GoalEdit(val id: String?) : Route
    data object Settings : Route
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

/**
 * Navigation. Elle vit dans l'application, pas dans l'écran : après un
 * verrouillage (passage par une autre app), on revient exactement où on était.
 */
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

    fun home(tab: Tab) {
        stack.clear()
        this.tab = tab
    }
}

/** Ce dont toutes les pages ont besoin. */
class Ctx(val vault: VaultService, val activity: FragmentActivity, val nav: Nav, val scope: CoroutineScope, val tour: TourState) {
    val app: BoussoleApp get() = activity.application as BoussoleApp
    val today: LocalDate get() = LocalDate.now()
    val drafts = Drafts(this)

    /** Enregistrement dans le coffre, mené à terme même si l'écran se ferme entre-temps. */
    fun update(transform: (VaultData) -> VaultData) {
        app.appScope.launch { vault.update(transform) }
    }
}

@Composable
fun Shell(state: VaultState.Unlocked, vault: VaultService, activity: FragmentActivity) {
    val app = activity.application as BoussoleApp
    val nav = app.nav
    val scope = rememberCoroutineScope()
    val tour = remember { TourState() }
    val ctx = remember { Ctx(vault, activity, nav, scope, tour) }
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
        var help by remember { mutableStateOf<String?>(null) }

        // Visite guidée à la première visite de chaque onglet.
        LaunchedEffect(nav.tab, route == null) {
            if (route == null && nav.tab.tour !in data.settings.toursSeen && tour.active == null) {
                delay(600) // laisser les éléments se placer
                tour.start(nav.tab.tour)
            }
        }

        Scaffold(
            topBar = {
                TopBar(
                    title = title(route, nav.tab),
                    canGoBack = route != null,
                    onBack = nav::back,
                    onHelp = {
                        if (route == null) tour.start(nav.tab.tour) else help = helpText(route)
                    },
                    onSettings = { nav.open(Route.Settings) },
                    onLock = vault::lock,
                    tour = tour,
                )
            },
            bottomBar = {
                if (route == null) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = nav.tab == tab,
                                onClick = { nav.tab = tab },
                                icon = { Text(tab.icon, style = MaterialTheme.typography.titleLarge) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                                modifier = Modifier.testTag(tab.tag).tourTarget(tour, tab.tag),
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
                        Tab.EXPENSES -> ExpensesTab(ctx, data)
                        Tab.GOALS -> GoalsTab(ctx, data)
                    }
                    Route.Validation -> ValidationPage(ctx, data)
                    is Route.Edit -> EditTransactionPage(ctx, data, route)
                    Route.Budget -> BudgetTab(ctx, data)
                    Route.Envelopes -> EnvelopesPage(ctx, data)
                    is Route.GoalEdit -> GoalPage(ctx, data, route.id)
                    Route.Settings -> SettingsHub(ctx)
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

        TourOverlay(tour) { id ->
            ctx.update { d -> d.copy(settings = d.settings.copy(toursSeen = d.settings.toursSeen + id)) }
        }
        help?.let { text ->
            AlertDialog(
                onDismissRequest = { help = null },
                title = { Text("À quoi sert cet écran ?") },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { help = null }) { Text("Compris") } },
            )
        }
    }
}

private fun title(route: Route?, tab: Tab): String = when (route) {
    null -> if (tab == Tab.HOME) "Butin" else tab.label
    Route.Validation -> "À valider"
    is Route.Edit -> if (route.id == null) "Nouvelle dépense" else "Opération"
    Route.Budget -> "Le mois en détail"
    Route.Envelopes -> "Mes enveloppes"
    is Route.GoalEdit -> if (route.id == null) "Nouvel objectif" else "Objectif"
    Route.Settings -> "Réglages"
    Route.Profile -> "Profil de vie"
    Route.Accounts -> "Comptes et soldes"
    Route.Categories -> "Catégories"
    Route.Rules -> "Règles de classement"
    Route.FixedCharges -> "Charges fixes"
    Route.Capture -> "Capture des dépenses"
    Route.Reminders -> "Rappels"
    Route.Import -> "Importer un relevé"
    Route.Receipt -> "Scanner un ticket"
    Route.Backup -> "Sauvegarde"
    Route.Security -> "Sécurité"
    Route.Review -> "Bilan de la semaine"
}

private fun helpText(route: Route): String = when (route) {
    Route.Validation -> "Les dépenses captées automatiquement arrivent ici. Vérifie le montant et la catégorie proposée, puis valide. Si tu corriges une catégorie, l'app s'en souviendra pour ce marchand."
    is Route.Edit -> "Saisis ou corrige une opération. La catégorie se propose toute seule d'après le libellé."
    Route.Budget -> "Le mois budgétaire commence le jour où ta paie arrive. Chaque enveloppe a un montant strict : si l'une déborde, couvre-la en prenant dans une autre."
    Route.Envelopes -> "Fixe le montant mensuel de chaque enveloppe, ou laisse l'app te proposer une répartition 50/30/20 adaptée à ta situation."
    is Route.GoalEdit -> "Dis ce que tu veux t'offrir et pour quand : l'app calcule l'effort mensuel sans te remettre dans le rouge."
    Route.Settings -> "Tout ce qui se règle une fois : comptes, profil, capture, rappels, sauvegarde, sécurité."
    Route.Accounts -> "Ajoute tes comptes (courant, Livret A, PEL, assurance vie…) et saisis leur solde actuel. L'app le fait ensuite évoluer avec tes opérations."
    Route.Capture -> "Autorise la lecture des notifications de ta banque : chaque paiement devient une dépense à valider. Le mode diagnostic sert à me transmettre les notifications non comprises."
    else -> "Modifie les réglages de cette page ; tout est enregistré dans ton coffre chiffré."
}

@Composable
private fun TopBar(
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    tour: TourState,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("nav_back")) { Text("←", style = MaterialTheme.typography.titleLarge) }
        }
        Text(
            title,
            Modifier.weight(1f).padding(start = if (canGoBack) 0.dp else 16.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onHelp, modifier = Modifier.testTag("nav_help")) { Text("?", style = MaterialTheme.typography.titleLarge) }
        if (!canGoBack) {
            TextButton(onClick = onSettings, modifier = Modifier.testTag("nav_settings").tourTarget(tour, "nav_settings")) {
                Text("⚙️", style = MaterialTheme.typography.titleMedium)
            }
        }
        TextButton(onClick = onLock, modifier = Modifier.testTag("home_lock")) { Text("🔒", style = MaterialTheme.typography.titleMedium) }
    }
}
