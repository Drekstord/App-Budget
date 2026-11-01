package app.boussole.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** Une bulle de la visite guidée, pointant l'élément repéré par [target]. */
class TourStep(val target: String, val title: String, val text: String)

/** Visites guidées par écran : elles se lancent à la première visite et se rejouent avec « ? ». */
object Tours {
    const val HOME = "accueil"
    const val EXPENSES = "depenses"
    const val GOALS = "objectifs"

    val steps: Map<String, List<TourStep>> = mapOf(
        HOME to listOf(
            TourStep("home_free", "Ton chiffre clé", "Ce que tu peux encore dépenser jusqu'à ta prochaine paie, une fois les enveloppes et les prélèvements à venir mis de côté."),
            TourStep("home_todo", "Une seule chose à faire", "Ici, l'action la plus utile du moment : valider des dépenses, suivre ton plan… Rien d'autre à retenir."),
            TourStep("home_detail", "Le détail, si tu veux", "Enveloppes, prélèvements à venir, dépenses hors budget : tout le mois en détail."),
            TourStep("home_add", "Ajouter à la main", "Une dépense en espèces ou oubliée : touche +. Le reste arrive tout seul par les notifications de ta banque."),
            TourStep("tab_expenses", "Tes dépenses", "Les dépenses captées arrivent ici « à valider ». Chaque correction apprend à l'app à mieux classer."),
            TourStep("tab_goals", "Tes objectifs", "Ton plan pour sortir du rouge, tes objectifs (la guitare !) et les conseils pour y arriver."),
            TourStep("nav_settings", "Réglages", "Comptes, profil de vie, capture des dépenses, sauvegarde et sécurité sont rangés ici."),
        ),
        EXPENSES to listOf(
            TourStep("exp_tabs", "À valider ou tout voir", "« À valider » : les dépenses captées automatiquement, à confirmer d'un geste. « Toutes » : l'historique complet."),
            TourStep("exp_tools", "Rattraper le reste", "Importe le relevé de ta banque (CSV ou OFX) ou scanne un ticket : rien n'est créé en double."),
            TourStep("ops_add", "Saisie rapide", "Une dépense en espèces ? Touche + : montant, libellé, c'est fait."),
        ),
        GOALS to listOf(
            TourStep("advice_level", "Où tu en es", "Ta situation en un mot, calculée sur tes vraies dépenses."),
            TourStep("goals_plan", "Ton plan", "Les étapes dans le bon ordre : stopper les frais, sécuriser l'essentiel, rembourser, épargner, puis te faire plaisir."),
            TourStep("goals_pace", "Ton rythme", "Tranquille, équilibré ou rapide : choisis l'effort qui te convient, les dates s'ajustent."),
            TourStep("advice_new_goal", "Tes envies", "Ajoute un objectif : je calcule comment l'atteindre sans repasser dans le rouge."),
        ),
    )
}

/** Positions des éléments ciblés et visite en cours. */
class TourState {
    val bounds = mutableStateMapOf<String, Rect>()
    var active by mutableStateOf<String?>(null)
    var index by mutableStateOf(0)

    fun start(id: String) {
        index = 0
        active = id
    }
}

/** Repère un élément pour que la visite puisse le pointer. */
fun Modifier.tourTarget(state: TourState, key: String): Modifier =
    onGloballyPositioned { state.bounds[key] = it.boundsInRoot() }

/**
 * Surimpression : assombrit l'écran, découpe une fenêtre autour de l'élément
 * expliqué, et affiche la bulle au-dessus ou en dessous. Bloque les touchers.
 */
@Composable
fun TourOverlay(state: TourState, onFinish: (String) -> Unit) {
    val id = state.active ?: return
    val steps = Tours.steps[id].orEmpty()
    val step = steps.getOrNull(state.index) ?: run {
        state.active = null
        onFinish(id)
        return
    }
    val target = state.bounds[step.target]
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    val gold = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.78f))
            if (target != null) {
                val topLeft = Offset(target.left - pad, target.top - pad)
                val size = Size(target.width + 2 * pad, target.height + 2 * pad)
                drawRoundRect(Color.Transparent, topLeft, size, CornerRadius(pad * 2), blendMode = BlendMode.Clear)
                drawRoundRect(gold, topLeft, size, CornerRadius(pad * 2), style = Stroke(width = pad / 3))
            }
        }
        val screenHeight = with(density) { maxHeight.toPx() }
        // La bulle se place du côté où il y a le plus de place.
        val cardHeight = with(density) { 240.dp.toPx() }
        val y = when {
            target == null -> screenHeight / 3
            target.center.y < screenHeight / 2 -> target.bottom + pad * 3
            else -> maxOf(pad * 6, target.top - pad * 3 - cardHeight)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .offset { IntOffset(0, y.toInt()) },
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(step.title, style = MaterialTheme.typography.titleLarge, color = gold)
                Text(step.text, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        state.active = null
                        onFinish(id)
                    }, modifier = Modifier.testTag("tour_skip")) { Text("Passer") }
                    Box {
                        Text("${state.index + 1}/${steps.size}", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { state.index++ }, modifier = Modifier.testTag("tour_next")) {
                        Text(if (state.index == steps.lastIndex) "Terminer" else "Suivant")
                    }
                }
            }
        }
    }
}
