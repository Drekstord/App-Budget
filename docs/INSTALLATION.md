# Installer une version de test sur le S26 Ultra

> ⚠️ Les versions de test sont signées avec une clé temporaire de la CI.
> **N'y mets que des données fictives.** Chaque nouvelle version demande de
> désinstaller la précédente, ce qui efface son contenu.

## 1. Récupérer l'APK (depuis le téléphone)

1. Dans Chrome, connecte-toi à GitHub et ouvre le dépôt **App-Budget** →
   onglet **Actions** → workflow **Android**.
2. Ouvre l'exécution la plus récente marquée ✅ sur la branche de travail.
3. En bas, section **Artifacts** : touche **boussole-test-apk** pour
   télécharger le fichier `.zip`.
4. Ouvre-le avec **Mes fichiers** (Samsung) → **Extraire**. Tu obtiens
   `boussole-test-xxxxxxxx.apk`.

*Facultatif, pour vérifier l'APK* : la page de l'exécution (résumé) affiche
l'empreinte SHA-256 de l'APK et celle du certificat.

## 2. Installer

1. Si une version précédente est installée : appui long sur son icône →
   **Désinstaller**.
2. Touche l'APK dans Mes fichiers. Android demande d'autoriser **Mes fichiers**
   à installer des applications inconnues : autorise-le **pour cette fois**,
   puis retire l'autorisation après l'installation (Paramètres → Applications
   → Mes fichiers → Installer applis inconnues).
3. Play Protect peut avertir d'une app inconnue : c'est normal pour une app
   que tu installes toi-même. Choisis « Installer quand même ».

## 3. Ce qu'il faut tester (phase 1)

1. **Création** : mot de passe (Proton Pass doit proposer d'en générer un),
   phrase de 24 mots, vérification de 3 mots, empreinte.
2. **Verrouillage** : touche « Accueil » puis reviens → le coffre doit être
   verrouillé. Même chose en éteignant l'écran.
3. **Ouverture** : mot de passe puis empreinte. Essaie un mauvais mot de passe.
4. **Captures d'écran** : elles doivent être bloquées (écran noir), y compris
   dans la vue des apps récentes.
5. **Opérations d'essai** : ajoute-en, verrouille, rouvre → elles sont toujours là.
6. **Sauvegarde** : « Exporter une sauvegarde chiffrée » → Proton Drive.
7. **Restauration** : désinstalle, réinstalle, « Restaurer une sauvegarde »
   avec le fichier de Proton Drive et la phrase.
8. **Récupération** : sur l'écran verrouillé, « Mot de passe oublié » → phrase
   → nouveau mot de passe.

Note tout ce qui te surprend, même un détail : c'est exactement ce qu'il me faut.
