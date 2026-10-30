# Installer une version de test sur le S26 Ultra

> ⚠️ Les versions de test sont signées avec une clé temporaire de la CI.
> **N'y mets que des données fictives.** Chaque nouvelle version demande de
> désinstaller la précédente, ce qui efface son contenu.

## 1. Récupérer l'APK (depuis le téléphone)

Ouvre ce lien dans Chrome, connecté à ton compte GitHub (le dépôt est privé) :

> **https://github.com/Drekstord/App-Budget/releases/download/test/boussole-test.apk**

L'APK se télécharge directement. **Garde ce lien en favori** : il pointe
toujours vers la dernière version de test. La page
https://github.com/Drekstord/App-Budget/releases/tag/test indique à quel
commit elle correspond et donne son empreinte SHA-256.

## 2. Installer

1. Si une version précédente est installée : appui long sur son icône →
   **Désinstaller**.
2. Ouvre le fichier téléchargé (notification de Chrome, ou Mes fichiers → Téléchargements). Android demande d'autoriser l'app qui
   ouvre le fichier (**Chrome** ou **Mes fichiers**) à installer des
   applications inconnues : autorise-la, puis retire l'autorisation après
   l'installation (Paramètres → Applications → Chrome → Installer applis
   inconnues).
3. Play Protect peut avertir d'une app inconnue : c'est normal pour une app
   que tu installes toi-même. Choisis « Installer quand même ».

## 3. Ce qu'il faut tester

La marche à suivre complète est dans [GUIDE_DE_TEST.md](GUIDE_DE_TEST.md). Vérifications de base :

1. **Création** : mot de passe (Proton Pass doit proposer d'en générer un),
   phrase de 24 mots (boutons « Copier » et « Enregistrer en fichier » vers
   Proton Drive), vérification de 3 mots, empreinte.
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

## Si l'app plante

Les versions de test notent le plantage **sur le téléphone** (rien n'est
envoyé). Au lancement suivant, un écran « L'app a planté » s'affiche :
touche **Copier le rapport** et colle-le moi. Il ne contient que la pile
d'appels technique, aucune donnée.

Chaque version publiée sur le lien a d'abord passé le parcours complet sur un
émulateur Android : création du coffre, empreinte, verrouillage, récupération…
