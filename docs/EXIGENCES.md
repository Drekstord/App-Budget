# Boussole — Cahier des exigences

> **Version 2.0 — 29 octobre 2026.** Refonte totale : remplace le document
> d'exigences d'« Écu » (PWA, dernier commit de cette version : `fdeaeb4`).
> « Boussole » est un nom de travail, modifiable à tout moment.

## 1. Contexte

Application de gestion de budget personnel, **native Android**, pour un
utilisateur unique sur **Samsung Galaxy S26 Ultra**. Une version PC est un
bonus, prévu par l'architecture mais pas développé dans un premier temps.

### Objectifs, par ordre de priorité

1. **Remonter les dépenses automatiquement**, les classer et les rattacher au budget.
2. **Conseiller** : sortir d'une situation difficile, épargner, financer un
   objectif (« une guitare ») sereinement, en tenant compte des vraies dépenses
   et des contraintes de vie.
3. **Sécurité maximale** : aucune donnée ne sort du téléphone sans action
   volontaire, et ce qui sort est chiffré. Détail dans [SECURITE.md](SECURITE.md).

### Profil de l'utilisateur (entretien du 29/10/2026)

| Sujet | Réponse |
|---|---|
| Banques | **Boursobank** : compte courant principal, reçoit la paie, sert à payer le quotidien. **Caisse d'Épargne** : compte courant secondaire pour les prélèvements d'abonnements, et comptes d'épargne. |
| Moyens de paiement | Carte physique et **Samsung Wallet**. Une notification s'affiche quand il paie avec Wallet. |
| Revenus | Paie fixe versée en fin de mois, à une date variable, plus des aides et primes variables. |
| Charges | Pas de loyer, mais des charges fixes. |
| Situation | **Dans le négatif sans interruption depuis août 2026**, après des réparations payées en urgence : découvert, fins de mois tendues. |
| Disponibilité | Risque d'oublier d'ouvrir l'app, mais d'accord pour y passer un peu de temps chaque jour et chaque semaine. |
| Validation | Veut valider chaque dépense ; l'app apprend de ses corrections. |
| Méthode | 50/30/20 adaptée (sans loyer), avec des enveloppes strictes pour se responsabiliser. |
| Ton des conseils | Suggestifs avec des tendances directives ; proactifs **et** consultables ; adaptés aux contraintes qu'il explique. |
| Ressenti sur Écu | « L'app ne me comprenait pas. » |
| Menaces redoutées | Entreprises (collecte de données), pirates, fuite de données. |
| Tiers | **Aucun** : pas d'agrégateur bancaire, pas de serveur (pour l'instant). |
| Déverrouillage | **Empreinte + mot de passe**, ce dernier géré par Proton Pass (un PIN d'au moins 6 chiffres reste possible). |
| Sauvegardes | Proton Drive. |
| Dossier sécurisé Samsung | Existe mais pas utilisé ; téléphone non rooté. |
| Installation | APK installé à la main, signé par l'utilisateur. |
| IA | D'abord des conseils calculés, sans IA. Une IA éventuellement plus tard. |
| Tickets de caisse | À garder. |

## 2. Choix structurants

| Sujet | Décision |
|---|---|
| Plateforme | Android natif : Kotlin + Jetpack Compose, minSdk 31 (Android 12), cible Android 16 |
| Réseau | **Aucun** : la permission `INTERNET` est absente de l'APK, et la CI vérifie à chaque build qu'elle n'y entre jamais |
| Architecture | Module `core` en Kotlin pur (règles métier, crypto, formats), sans dépendance Android, réutilisable pour un futur client PC ; module `android` pour l'interface et l'intégration système |
| Capture des dépenses | Lecture des notifications des apps bancaires et de Wallet (liste blanche) + import de relevés CSV/OFX + saisie manuelle + ticket de caisse |
| Conseils | Moteur déterministe : calculs explicables et testés, sans IA ni réseau |
| Sauvegarde | Fichier chiffré, transmis à Proton Drive via le menu « Partager » d'Android. Seule la phrase de récupération permet de le déchiffrer. |
| Distribution | APK signé. Les builds de test ne portent que des données fictives tant que la clé de signature définitive n'est pas en place. |
| Langue / devise | Français, euro (`1 234,56 €`), montants en centimes entiers |

## 3. Exigences fonctionnelles

### SEC — Accès et protection
- **SEC-1** Premier lancement : choix d'un **mot de passe** (au moins 12 caractères)
  ou d'un **code** (au moins 6 chiffres). Le champ accepte le remplissage
  automatique de Proton Pass.
- **SEC-2** Génération d'une **phrase de récupération de 24 mots** (liste BIP39
  française), à recopier sur papier, puis vérifiée par la saisie de 3 mots tirés au hasard.
- **SEC-3** Enrôlement de l'empreinte : la clé matérielle n'est utilisable
  qu'après une authentification biométrique forte (classe 3).
- **SEC-4** Ouverture : **empreinte puis mot de passe**, les deux sont indispensables.
- **SEC-5** Verrouillage dès que l'app passe en arrière-plan, avec un délai de
  grâce réglable de 0 à 60 s au maximum. Pas d'option « jamais ».
- **SEC-6** Délai croissant après des échecs de mot de passe.
- **SEC-7** Restauration via la phrase de récupération si l'empreinte est
  invalidée (nouvelle empreinte enregistrée) ou si le téléphone change.
- **SEC-8** Captures d'écran et aperçu dans les applications récentes bloqués.

### CAP — Capture automatique des dépenses
- **CAP-1** **Mode diagnostic** (premier livrable fonctionnel) : affiche, sans
  rien enregistrer d'autre, ce que l'app comprend des notifications des apps
  autorisées, pour calibrer les formats réels.
- **CAP-2** Lecture des notifications **uniquement** pour une liste blanche
  d'apps (Boursobank, Caisse d'Épargne, Samsung Wallet). L'app n'extrait que le
  montant, le marchand, la date et le compte, et jette le reste du texte.
- **CAP-3** Chaque capture crée une **dépense « à valider »**.
- **CAP-4** Import de relevés **CSV / OFX** (Boursobank, Caisse d'Épargne), avec
  **rapprochement** : une dépense déjà captée par notification n'est pas créée deux fois.
- **CAP-5** Détection des **trous** : avertir si aucune capture n'est arrivée
  depuis plusieurs jours (format de notification changé, permission retirée).
- **CAP-6** Saisie manuelle en moins de 5 secondes, et **ticket de caisse** lu
  sur le téléphone (OCR local).

### CLA — Classement et apprentissage
- **CLA-1** Chaque dépense à valider arrive avec une **catégorie proposée** et un
  indice de confiance.
- **CLA-2** Valider ou corriger en un geste. Une correction crée ou ajuste une
  règle (« CARREFOUR CITY → Courses »).
- **CLA-3** Les règles sont lisibles, modifiables et supprimables.
- **CLA-4** Rattachement automatique à l'enveloppe correspondant à la catégorie.

### BUD — Budget
- **BUD-1** **Profil de vie** : charges fixes, absence de loyer, contraintes,
  habitudes, ce qui est non négociable. Rempli au départ et modifiable.
- **BUD-2** Trois familles inspirées du 50/30/20 et **adaptées** :
  **Essentiel / Plaisir / Avenir** (épargne et remboursement). Les proportions
  sont proposées à partir du profil et de l'historique, pas imposées.
- **BUD-3** **Enveloppes strictes** par catégorie dans chaque famille. Un
  dépassement doit être compensé explicitement, en prenant dans une autre
  enveloppe.
- **BUD-4** **Mois budgétaire déclenché par l'arrivée réelle de la paie**
  (détectée), et non par une date fixe.
- **BUD-5** Les revenus variables (aides, primes) ne sont **jamais anticipés**.
  À leur arrivée, l'app propose une répartition.
- **BUD-6** Comptes multiples (Boursobank, CE courant, CE épargne), virements
  internes, prélèvements et abonnements prévus.
- **BUD-7** « **Disponible réel** » : ce qui reste vraiment, après les
  enveloppes réservées et les prélèvements à venir d'ici la prochaine paie.

### CON — Conseils
- **CON-1** **Mode redressement** (situation négative), dans un ordre de priorité
  fixe et expliqué :
  1. stopper les frais (agios, commissions) ;
  2. sécuriser l'essentiel ;
  3. résorber le découvert, et les dettes coûteuses en premier ;
  4. constituer une épargne de précaution ;
  5. puis financer les objectifs.
- **CON-2** **Mode objectif** (« acheter une guitare à 900 € ») : trois scénarios,
  **tranquille / équilibré / rapide**. Chacun donne le montant mensuel, la
  date d'atteinte, et les leviers concrets tirés des vraies dépenses (enveloppe
  à réduire et de combien, abonnement à suspendre), sans jamais repasser dans
  le négatif.
- **CON-3** Ton **suggestif avec recommandation** : une option recommandée,
  plus des alternatives. Chaque conseil donne le chiffre, le pourquoi et
  l'effet sur le quotidien.
- **CON-4** Les conseils respectent le profil de vie : rien de ce qui est
  marqué « non négociable » n'est proposé à la coupe.
- **CON-5** Conseils **proactifs** (notifications) et **consultables** (écran dédié).
- **CON-6** Hypothèses prudentes : revenus variables non comptés, marge de
  sécurité. Aucun conseil en investissement réglementé.

### NOT — Rappels (générés sur le téléphone, sans réseau)
- **NOT-1** Le soir, si des dépenses attendent : « N dépenses à valider ».
- **NOT-2** Le dimanche : bilan de la semaine en 2 minutes.
- **NOT-3** Alerte immédiate quand une enveloppe est dépassée ou sur le point de l'être.
- **NOT-4** Le contenu des notifications reste discret sur l'écran verrouillé
  (pas de montant visible).

### SAV — Sauvegarde et restauration
- **SAV-1** Export d'une sauvegarde **chiffrée** vers Proton Drive (menu
  « Partager »), rappelé chaque semaine.
- **SAV-2** Restauration sur un téléphone neuf avec la phrase de récupération seule.
- **SAV-3** Aucun export en clair des données complètes. Un export CSV
  ponctuel reste possible, avec un avertissement explicite.

## 4. Exigences non fonctionnelles

- **RNF-1 Sécurité** : référentiel **OWASP MASVS** niveaux L2 + R. Détail
  dans [SECURITE.md](SECURITE.md).
- **RNF-2 Hors ligne** : 100 % des fonctions marchent sans réseau, et l'app
  n'a de toute façon pas accès au réseau.
- **RNF-3 Accessibilité** : contrastes AA, TalkBack, cibles tactiles ≥ 48 dp.
- **RNF-4 Qualité** : tests unitaires sur toute la logique de `core` (calculs,
  crypto, formats) ; la CI bloque si un test échoue ou si la permission
  `INTERNET` apparaît.
- **RNF-5 Évolutivité** : identifiants UUID, horodatages et suppressions
  logiques, pour une future synchronisation (PC, serveur personnel) chiffrée
  de bout en bout.

## 5. Phases

| Phase | Contenu | Livrable |
|---|---|---|
| 0 | Cadrage : ce document, [SECURITE.md](SECURITE.md) | Validation par l'utilisateur |
| 1 | **Socle sécurisé** : empreinte + mot de passe, phrase de récupération, coffre chiffré, sauvegarde chiffrée | APK de test (données fictives) |
| 2 | **Capture** : mode diagnostic des notifications, puis captation et import CSV/OFX | APK : les dépenses remontent seules |
| 3 | **Validation et apprentissage** : file « à valider », règles, rappel du soir | APK utilisable au quotidien |
| 4 | **Profil de vie, enveloppes, mode redressement** | Sortie du négatif guidée |
| 5 | **Objectifs** : scénarios tranquille / équilibré / rapide | « La guitare » |
| 6 | Ticket de caisse, bilan hebdomadaire, audit de sécurité final, clé de signature définitive | Version 1.0 |
| Bonus | Client PC (réutilise `core`), synchronisation chiffrée, IA locale | — |

## 6. Hors périmètre (V1)

- Agrégateurs bancaires (DSP2) et tout serveur tiers.
- IA conversationnelle.
- Multi-utilisateurs, multi-devises.
- Publication sur le Play Store.
