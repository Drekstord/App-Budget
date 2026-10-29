package app.boussole.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Parcours complet de la phase 1 sur l'APK final, comme le ferait l'utilisateur :
 * création du coffre, ajout d'une opération, verrouillage, mauvais mot de passe,
 * ouverture, verrouillage en arrière-plan, récupération par la phrase, export.
 *
 * Un seul test, car les étapes s'enchaînent sur le même coffre.
 */
@RunWith(AndroidJUnit4::class)
class VaultJourneyTest {
    private val ui = Driver()
    private val password = "Guitare-Fender-2026!"
    private val newPassword = "Nouveau-Cap-Boussole-42"

    @Test
    fun parcoursComplet() {
        ui.ensureFingerprintEnrolled()

        // 1. Création du coffre.
        ui.launchApp()
        ui.click("welcome_create")
        ui.type("secret_new", password)
        ui.type("secret_confirm", password)
        ui.click("secret_submit")

        // 2. Phrase de récupération : 24 mots lus à l'écran.
        val words = (0 until 24).map { i ->
            val text = ui.tag("phrase_word_$i").text
            text.substringAfter(". ").also { assert(it.isNotBlank()) { "Mot $i vide : « $text »" } }
        }
        assertEquals(24, words.size)
        ui.click("phrase_copied")
        ui.click("phrase_next")

        // 3. Vérification de 3 mots tirés au hasard.
        ui.tag("verify_submit")
        val fields = ui.device.findObjects(androidx.test.uiautomator.By.res(Pattern.compile("verify_word_\\d+")))
        assertEquals("3 mots à vérifier attendus\n${ui.screen()}", 3, fields.size)
        for (field in fields) {
            val position = field.resourceName.substringAfterLast('_').toInt()
            field.text = words[position]
        }
        ui.click("verify_submit")

        // 4. Empreinte (le doigt virtuel valide l'invite), puis coffre ouvert.
        ui.click("biometric_action")
        ui.tag("home_lock", timeout = 60_000)
        ui.expectText("Coffre ouvert")

        // 5. Une opération fictive.
        ui.type("expense_amount", "12,50")
        ui.type("expense_label", "Café test")
        ui.click("expense_add")
        ui.expectText("Café test")

        // 6. Verrouillage manuel puis mauvais mot de passe.
        ui.click("home_lock")
        ui.type("secret_unlock", "mauvais-mot-de-passe")
        ui.click("unlock_submit")
        ui.expectText("Mot de passe incorrect", timeout = 60_000)

        // 7. Bon mot de passe : les données sont toujours là.
        ui.type("secret_unlock", password)
        ui.click("unlock_submit")
        ui.tag("home_lock", timeout = 60_000)
        ui.expectText("Café test")

        // 8. Passage en arrière-plan : le coffre se verrouille.
        ui.device.pressHome()
        Thread.sleep(3_000)
        ui.launchApp()
        ui.tag("unlock_submit")

        // 9. Récupération par la phrase, nouveau mot de passe.
        ui.click("unlock_forgot")
        ui.type("phrase_input", words.joinToString(" "))
        ui.click("phrase_submit")
        ui.type("secret_new", newPassword)
        ui.type("secret_confirm", newPassword)
        ui.click("secret_submit")
        ui.click("biometric_action")
        ui.tag("home_lock", timeout = 60_000)
        ui.expectText("Café test")

        // 10. Export d'une sauvegarde chiffrée : la feuille de partage s'ouvre.
        ui.click("backup_export")
        Thread.sleep(3_000)
        ui.device.pressBack()
        ui.launchApp()
        // Le partage a fait passer l'app en arrière-plan : elle doit être verrouillée.
        ui.tag("unlock_submit")

        // 11. Le nouveau mot de passe ouvre le coffre, sans plantage.
        ui.type("secret_unlock", newPassword)
        ui.click("unlock_submit")
        ui.tag("home_lock", timeout = 60_000)
        ui.expectNoCrashReport()
    }
}
