# 📱 Tuto : installer et utiliser App Budget sur ton téléphone

Ce guide part de zéro : aucune connaissance nécessaire. Compte environ
10 minutes pour la mise en place (à faire une seule fois), puis
l'application vit sur ton téléphone comme n'importe quelle app.

**Le principe en une phrase :** App Budget est une *PWA* (application web
installable). On la met en ligne une fois sur une adresse HTTPS gratuite,
tu l'ouvres sur ton téléphone, tu l'« installes » depuis le navigateur, et
ensuite elle fonctionne entièrement hors ligne — tes données restent sur le
téléphone, chiffrées, rien ne circule sur internet.

---

## Étape 1 — Mettre l'application en ligne (une seule fois, depuis un PC)

L'application doit être servie en HTTPS pour être installable. Le plus
simple et gratuit : **GitHub Pages**, directement depuis ce dépôt.
Le déploiement automatique est déjà configuré (`.github/workflows/deploy.yml`),
il reste deux réglages à activer :

### 1.1 Fusionner la branche de développement dans `main`

1. Va sur la page GitHub du dépôt : `https://github.com/Drekstord/App-Budget`
2. GitHub affiche un bandeau jaune proposant de créer une *pull request*
   pour la branche `claude/budget-app-requirements-682v8f`. Clique sur
   **« Compare & pull request »**, puis **« Create pull request »**, puis
   **« Merge pull request »** → **« Confirm merge »**.
   *(Pas de bandeau ? Onglet « Pull requests » → « New pull request » →
   choisis la branche → même chose.)*

### 1.2 Activer GitHub Pages

1. Sur la page du dépôt, clique sur l'onglet **Settings** (⚙️ Réglages).
2. Dans le menu de gauche, clique sur **Pages**.
3. Sous **« Build and deployment » → « Source »**, choisis
   **« GitHub Actions »** dans la liste déroulante. C'est tout.
4. Retourne dans l'onglet **Actions** du dépôt : un déploiement
   « Déployer sur GitHub Pages » se lance (ou lance-le à la main avec le
   bouton « Run workflow »). Attends le ✅ vert (2-3 minutes).

> ⚠️ **Si ton dépôt est privé** : GitHub Pages sur dépôt privé demande un
> abonnement payant. Deux solutions gratuites :
> - **Rendre le dépôt public** (Settings → General → tout en bas →
>   « Change visibility »). Sans danger : seul le *code* est public, tes
>   données de budget ne quittent jamais ton téléphone.
> - **Ou utiliser Netlify Drop** : va sur https://app.netlify.com/drop,
>   glisse-dépose le dossier `dist/` (obtenu avec `npm run build`) — tu
>   obtiens une adresse HTTPS immédiatement.

### 1.3 Ton adresse

Une fois le déploiement terminé, ton application est disponible à :

> **https://drekstord.github.io/App-Budget/**

(L'adresse exacte est affichée dans Settings → Pages, « Your site is live at… ».)

---

## Étape 2 — Installer l'application sur ton téléphone

Sur ton Samsung (S26 Ultra), avec **Chrome** (recommandé) :

1. Ouvre **Chrome** et va sur l'adresse de l'étape 1.3.
2. Touche le menu **⋮** (les trois points en haut à droite).
3. Touche **« Ajouter à l'écran d'accueil »** (ou « Installer
   l'application » si Chrome le propose directement).
4. Confirme avec **« Installer »**.
5. Une icône **App Budget** (bleue, avec un €) apparaît sur ton écran
   d'accueil, à côté de tes autres applications.

Avec **Samsung Internet** ça marche aussi : menu **≡** → **« Ajouter la
page à »** → **« Écran d'accueil »**.

Désormais, l'app s'ouvre **en plein écran, sans barre de navigateur**,
et **fonctionne sans connexion internet** (avion, métro, zone blanche…).

---

## Étape 3 — Premier lancement

1. Ouvre l'app depuis son icône.
2. Elle te demande de **choisir un code PIN** (4 à 8 chiffres), puis de le
   confirmer.

> 🔑 **Très important** : ce PIN chiffre toutes tes données. Il n'existe
> aucun moyen de le récupérer si tu l'oublies — c'est justement ce qui rend
> l'app sûre. Choisis un code que tu retiendras, et exporte régulièrement
> des sauvegardes (voir étape 5).

L'app démarre avec un compte « Compte courant » et des catégories
prédéfinies (Alimentation, Logement, Transport…) — tout est modifiable.

**Premiers réglages conseillés** (onglet **Réglages** ⚙️) :
- **Jour de début du mois budgétaire** : mets ton jour de paie (ex. le 28)
  pour que les budgets suivent ton vrai rythme.
- **Thème** : clair, sombre ou automatique.
- **Verrouillage automatique** : 1, 5 ou 15 minutes d'inactivité.

Va aussi dans **Comptes** pour donner son vrai solde de départ à ton compte
(modifie le « solde initial »), et ajoute tes autres comptes (Livret A,
espèces…).

---

## Étape 4 — Au quotidien

### Ajouter une dépense (3 gestes, < 5 secondes)
1. Touche le gros bouton **+** (en bas à droite, présent partout).
2. Tape le **montant** (ex. `12,50`) et touche une **catégorie**.
3. Touche **Ajouter**. C'est tout — la date du jour et ton compte par
   défaut sont déjà remplis.

Pour un salaire : même chose en touchant **Revenu**. Pour déplacer de
l'argent entre deux comptes : **Virement**.

### Fixer tes budgets
Onglet **Budgets** → « Fixer un budget » sur une catégorie → montant
mensuel (ex. 300 € pour Alimentation). La jauge se remplit au fil du mois,
passe en orange à 80 %, en rouge si dépassé — avec une estimation du jour
où tu vas dépasser si tu continues à ce rythme.

### Suivre et analyser
L'onglet **Accueil** montre ton solde total, tes dépenses du mois, ton
taux d'épargne, la répartition par catégorie, l'évolution sur 6 mois, le
comparatif budget/réel — et des **conseils automatiques** (« tes dépenses
Loisirs sont 40 % au-dessus de ta moyenne », etc.). Tout est calculé sur
ton téléphone, rien n'est envoyé nulle part.

---

## Étape 5 — Sauvegardes (à faire régulièrement !)

Tes données n'existent **que** sur ton téléphone. Une sauvegarde te protège
contre la perte/casse du téléphone et l'oubli du PIN.

- **Exporter** : Réglages → Sauvegarde → **« Exporter (JSON) »**. Un
  fichier `app-budget-sauvegarde-AAAA-MM-JJ.json` se télécharge. Range-le
  en lieu sûr (Drive, mail à toi-même, PC…).
- **Restaurer** (nouveau téléphone, ou sur PC) : Réglages → Sauvegarde →
  **« Importer une sauvegarde… »** → choisis le fichier. ⚠️ L'import
  remplace toutes les données de l'appareil.

C'est aussi comme ça que tu passes tes données du téléphone au PC (et
inversement) en attendant la future synchronisation automatique (V3).

---

## Étape 6 — Sur PC

Même adresse, dans n'importe quel navigateur :
**https://drekstord.github.io/App-Budget/** — l'interface s'adapte
(navigation à gauche, graphiques côte à côte). Chrome/Edge proposent aussi
d'« installer » l'app (icône ⊕ dans la barre d'adresse) pour l'avoir dans
le menu Démarrer.

> Le téléphone et le PC ont chacun **leurs propres données** (et leur
> propre PIN). Pour transférer : export/import JSON (étape 5).

---

## Questions fréquentes

**Ça marche sans internet ?** Oui, complètement. Internet ne sert qu'à
l'installation et aux mises à jour.

**Où sont mes données ? Qui peut les voir ?** Uniquement dans la mémoire de
ton navigateur, sur ton appareil, chiffrées avec ton PIN (AES-256).
Personne d'autre — ni GitHub, ni moi, ni personne.

**J'ai oublié mon PIN.** Il n'y a pas de récupération possible. Il faut
réinitialiser : réglages Android → Applications → Chrome → Stockage →
« Effacer les données du site » pour l'adresse de l'app (ou dans Chrome :
cadenas dans la barre d'adresse → Autorisations → Effacer les données).
L'app repartira de zéro — d'où l'importance des sauvegardes.

**Comment l'app se met à jour ?** À chaque modification poussée sur la
branche `main` du dépôt, GitHub Pages republie automatiquement. L'app se
met à jour toute seule à l'ouverture suivante (connexion nécessaire à ce
moment-là).

**Je peux la désinstaller ?** Comme n'importe quelle app : appui long sur
l'icône → Désinstaller. ⚠️ Pense à exporter une sauvegarde avant si tu veux
garder tes données.
