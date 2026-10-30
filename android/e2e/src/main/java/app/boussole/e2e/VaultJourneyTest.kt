package app.boussole.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

private val SHARE_SHEET = Pattern.compile("com\\.android\\.intentresolver|android")

/**
 * Parcours complet sur l'APK final, comme le ferait l'utilisateur : création du
 * coffre, profil de vie, capture d'une dépense par notification, validation et
 * apprentissage, budget, conseils et objectif, capture coffre verrouillé,
 * verrouillages, récupération par la phrase, sauvegarde.
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
        ui.createNotificationChannel()

        // 1. Création du coffre.
        ui.launchApp()
        ui.click("welcome_create")
        ui.type("secret_new", password)
        ui.type("secret_confirm", password)
        ui.click("secret_submit")

        // 2. Phrase de récupération : 24 mots lus à l'écran, copie et fichier.
        val words = (0 until 24).map { i ->
            val text = ui.textOf("phrase_word_$i")
            text.substringAfter(". ").also { assert(it.isNotBlank()) { "Mot $i vide : « $text »" } }
        }
        assertEquals(24, words.size)
        ui.click("phrase_copy")
        ui.click("phrase_export_confirm")
        ui.expectText("Phrase copiée")
        ui.click("phrase_share")
        ui.click("phrase_export_confirm")
        assertTrue(
            "La feuille de partage de la phrase ne s'est pas ouverte\n${ui.screen()}",
            ui.device.wait(Until.hasObject(By.pkg(SHARE_SHEET).depth(0)), 15_000),
        )
        ui.device.pressBack()
        ui.click("phrase_copied")
        ui.click("phrase_next")

        // 3. Vérification de 3 mots tirés au hasard.
        ui.tag("verify_submit")
        val fields = ui.device.findObjects(By.res(Pattern.compile("verify_word_\\d+")))
        assertEquals("3 mots à vérifier attendus\n${ui.screen()}", 3, fields.size)
        for (field in fields) {
            val position = field.resourceName.substringAfterLast('_').toInt()
            field.text = words[position]
        }
        ui.click("verify_submit")

        // 4. Empreinte (le doigt virtuel valide l'invite).
        ui.click("biometric_action")

        // 5. Profil de vie : paie, soldes (600 € de découvert, 400 € d'épargne), une charge fixe.
        ui.awaitAfterFingerprint("onboarding_pay")
        ui.type("onboarding_pay", "1800")
        ui.type("onboarding_payday", "28")
        ui.click("onboarding_next")
        ui.type("onboarding_balance_0", "-600")
        ui.type("onboarding_balance_2", "400")
        ui.click("onboarding_next")
        ui.type("onboarding_charge_name", "Netflix")
        ui.type("onboarding_charge_amount", "13,49")
        ui.click("onboarding_charge_add")
        ui.expectText("Netflix · le")
        ui.click("onboarding_next")
        ui.click("onboarding_next")
        ui.click("onboarding_finish")
        ui.tag("home_free")
        ui.expectText("Comptes courants")

        // 6. Une dépense captée par notification, classée par mot-clé, validée.
        ui.postNotification(1, "Paiement par carte", "Vous avez payé 12,50 € chez CARREFOUR CITY le 29/10.")
        ui.click("home_validate", timeout = 30_000)
        assertEquals("CARREFOUR CITY", ui.textOf("pending_0_label"))
        assertTrue(ui.textOf("pending_0_category").contains("Courses"))
        ui.click("pending_0_validate")
        ui.expectText("Tout est à jour")
        ui.click("nav_back")

        // 7. Correction apprise : une autre dépense chez le même marchand, reclassée.
        ui.postNotification(2, "Paiement par carte", "Vous avez payé 8,00 € chez CARREFOUR CITY.")
        ui.click("home_validate", timeout = 30_000)
        ui.click("pending_0_category")
        ui.click("pick_cat.restos")
        ui.expectText("Tout est à jour")
        ui.click("nav_back")
        ui.postNotification(3, "Paiement par carte", "Vous avez payé 6,40 € chez CARREFOUR CITY.")
        ui.click("home_validate", timeout = 30_000)
        assertTrue("La correction n'a pas été apprise\n${ui.screen()}", ui.textOf("pending_0_category").contains("Restaurants"))
        ui.click("pending_0_validate")
        ui.click("nav_back")

        // 8. Budget (enveloppes proposées) et conseils.
        ui.click("tab_budget")
        ui.expectText("Disponible réel")
        ui.expectText("Courses")
        ui.click("tab_advice")
        ui.tag("advice_level")
        ui.expectText("Stopper les frais")
        ui.click("advice_new_goal")
        ui.type("goal_name", "Guitare")
        ui.type("goal_target", "900")
        ui.click("goal_save")
        ui.tag("goal_Guitare")

        // 9. Verrouillage manuel puis mauvais mot de passe.
        ui.click("tab_home")
        ui.click("home_lock")
        ui.type("secret_unlock", "mauvais-mot-de-passe")
        ui.click("unlock_submit")
        ui.awaitTextAfterFingerprint("Mot de passe incorrect")

        // 10. Capture coffre fermé : chiffrée, intégrée à l'ouverture.
        ui.postNotification(4, "Paiement par carte", "Vous avez payé 4,20 € chez BOULANGERIE MARIE.")
        Thread.sleep(3_000)
        ui.type("secret_unlock", password)
        ui.click("unlock_submit")
        ui.awaitAfterFingerprint("home_validate")
        ui.click("home_validate")
        assertEquals("BOULANGERIE MARIE", ui.textOf("pending_0_label"))
        ui.click("pending_0_validate")
        ui.click("nav_back")

        // 11. Passage en arrière-plan : le coffre se verrouille.
        ui.device.pressHome()
        Thread.sleep(3_000)
        ui.launchApp()
        ui.tag("unlock_submit")

        // 12. Récupération par la phrase, nouveau mot de passe ; les données sont là.
        ui.click("unlock_forgot")
        ui.type("phrase_input", words.joinToString(" "))
        ui.click("phrase_submit")
        ui.type("secret_new", newPassword)
        ui.type("secret_confirm", newPassword)
        ui.click("secret_submit")
        ui.click("biometric_action")
        ui.awaitAfterFingerprint("home_free")
        ui.click("tab_operations")
        ui.expectText("BOULANGERIE MARIE")

        // 13. Export d'une sauvegarde chiffrée : la feuille de partage s'ouvre.
        ui.click("tab_more")
        ui.click("more_backup")
        ui.click("backup_export")
        assertTrue(
            "La feuille de partage ne s'est pas ouverte\n${ui.screen()}",
            ui.device.wait(Until.hasObject(By.pkg(SHARE_SHEET).depth(0)), 15_000),
        )
        ui.device.pressBack()
        ui.launchApp()

        // 14. Le nouveau mot de passe ouvre le coffre, sans plantage.
        ui.device.findObject(By.res("home_lock"))?.click()
        ui.type("secret_unlock", newPassword)
        ui.click("unlock_submit")
        ui.awaitAfterFingerprint("home_free")
        ui.expectNoCrashReport()
    }
}
