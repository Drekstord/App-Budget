---
name: livrer
description: Livre les changements en cours à l'utilisateur — tests du cœur, commit, envoi sur la branche, suivi de la CI jusqu'au parcours sur émulateur, vérification de la Release « test », puis résumé en français avec le lien d'installation. À lancer quand une correction ou une fonctionnalité est prête à être essayée sur le téléphone.
disable-model-invocation: true
argument-hint: "[résumé du changement, facultatif]"
---

# Livrer une version de test

Règle de l'utilisateur : **il ne reçoit jamais un APK qui n'a pas passé toute la CI**
(tests du cœur, contrôle « aucune permission réseau », parcours complet sur émulateur).
Tant que la CI n'est pas verte sur le commit livré, il n'y a rien à annoncer.

Résumé fourni : $ARGUMENTS

## 1. Vérifier avant d'envoyer

1. `git status` et `git diff` : relis le changement de façon critique (qu'est-ce qui ferait échouer la CI ?).
2. Tests du cœur : `./gradlew -p core test --console=plain`. Tout doit passer.
3. Si le changement touche l'interface : chaque élément utilisé par `android/e2e` garde son `testTag`
   (cherche les tags dans `android/e2e/src/main/java/app/boussole/e2e/VaultJourneyTest.kt`).
4. Si la navigation ou des libellés changent : mets à jour `docs/GUIDE_DE_TEST.md`.
5. Si le changement touche la sécurité, la crypto, le stockage, les journaux ou les notifications :
   lance l'agent `security-reviewer` et corrige ce qu'il signale avant d'aller plus loin.

## 2. Envoyer

- Commit en français, message clair (titre + puces si utile).
- Pousse sur la branche de travail courante (`git push -u origin <branche>`), jamais sur `main`
  sans accord explicite. Seules `main` et `claude/**` déclenchent la CI.
- En cas d'erreur réseau au push : jusqu'à 4 nouvelles tentatives (2 s, 4 s, 8 s, 16 s).

## 3. Suivre la CI (environ 10 minutes)

- Repère le run : `mcp__github__actions_list` (`list_workflow_runs`, filtre sur la branche) et
  vérifie que son `head_sha` est bien ton commit.
- Attends sans boucler : une seule commande `sleep 600` lancée en arrière-plan, puis
  `list_workflow_jobs` sur le run. Les trois jobs : « Tests, APK et contrôles de sécurité »,
  « Parcours complet sur émulateur », « Publier l'APK de test (lien fixe) ».
- **Échec** : `mcp__github__get_job_logs` (`run_id`, `failed_only: true`, `return_content: true`).
  Pour le parcours sur émulateur, regarde les groupes « Écrans parcourus par le test »,
  « Journal technique de l'app » et « Plantages ». Trouve la vraie cause (« flaky » n'en est pas une),
  corrige, revérifie (étape 1) et repousse. Ne désactive ni n'affaiblis jamais un test pour passer.

## 4. Vérifier la publication

`mcp__github__get_release_by_tag` (tag `test`) : le titre contient les 8 premiers caractères du
commit livré et l'asset `butin-test.apk` est présent. Sinon, la publication n'a pas eu lieu :
ne donne pas le lien.

## 5. Prévenir l'utilisateur (en français, tutoiement)

- Ce qui a changé, du point de vue de l'usage (pas du code).
- Ce que la CI a vérifié, et **ce qu'elle ne couvre pas** (dis-le franchement).
- Le lien : https://github.com/Drekstord/App-Budget/releases/download/test/butin-test.apk
- La marche à suivre : ⚙️ → Sauvegarde → exporter, désinstaller, installer, restaurer
  (clé de signature de test : chaque version oblige à désinstaller la précédente).
- Ce qu'il doit tester en priorité, et quoi te renvoyer si ça coince.
