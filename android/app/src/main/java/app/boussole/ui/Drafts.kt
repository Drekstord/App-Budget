package app.boussole.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.reflect.KProperty

/**
 * Saisies en cours enregistrées dans le coffre (chiffré) au fil de la frappe :
 * quitter l'app pour vérifier un solde verrouille le coffre, mais au retour les
 * champs sont restaurés.
 */
class Drafts(private val ctx: Ctx) {
    fun read(key: String): String? = ctx.vault.data()?.drafts?.get(key)

    /** Enregistré tout de suite : le verrouillage attend la fin des écritures en cours. */
    fun save(key: String, value: String) {
        ctx.update { d -> if (d.drafts[key] == value) d else d.copy(drafts = d.drafts + (key to value)) }
    }

    /** Oublie les brouillons d'un formulaire validé ou abandonné. */
    fun clear(prefix: String) {
        ctx.update { d -> d.copy(drafts = d.drafts.filterKeys { !it.startsWith(prefix) }) }
    }
}

class DraftState(private val drafts: Drafts, private val key: String, initial: String) {
    private var value by mutableStateOf(drafts.read(key) ?: initial)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: String) {
        value = newValue
        drafts.save(key, newValue)
    }
}

/** Champ texte dont la saisie survit au verrouillage (`var x by rememberDraft(...)`). */
@Composable
fun rememberDraft(ctx: Ctx, key: String, initial: String): DraftState = remember(key) { DraftState(ctx.drafts, key, initial) }
