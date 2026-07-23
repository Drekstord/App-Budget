# App-Budget — Document d'exigences

> **Version 1.0 — 3 juillet 2026** — Document soumis à validation avant tout développement.

## 1. Contexte et objectifs

Application de gestion de budget personnel pour un utilisateur unique, utilisable sur
mobile (Android — Samsung S26 Ultra) et sur PC. L'application permet de suivre ses
dépenses et revenus, de définir des budgets par catégorie, et d'obtenir des analyses
et conseils sur ses habitudes de dépense.

**Objectifs clés :**
- Saisie d'une dépense ultra-rapide (2-3 interactions).
- Fonctionnement 100 % hors ligne, données stockées localement et chiffrées.
- Une seule base de code pour mobile et PC (PWA installable).
- Gratuit, sans serveur obligatoire — mais architecture prête pour une future
  synchronisation via serveur privé.

## 2. Choix structurants (validés avec l'utilisateur)

| Sujet | Décision |
|---|---|
| Utilisateurs | Mono-utilisateur |
| Accès | Écran de connexion local : code PIN + chiffrement des données |
| Devise | Euro uniquement (affichage format français : `1 234,56 €`) |
| Langue | Français uniquement |
| Connexion bancaire | Non — saisie manuelle + import de fichiers |
| Synchronisation | Import/export manuel pour v1 ; architecture prête pour sync serveur privé |
| Plateforme | PWA installable (Android + navigateur PC) |
| Hébergement | Local et gratuit (déploiement statique, ex. GitHub Pages) |
| Style | Moderne, épuré, touches de couleur mesurées, mode sombre, conforme RGAA |

## 3. Exigences fonctionnelles

### RF-1 — Sécurité d'accès
- **RF-1.1** À la première ouverture, l'utilisateur définit un code PIN (4 à 8 chiffres).
- **RF-1.2** L'ouverture de l'application exige la saisie du PIN, sauf si une
  session de déverrouillage est encore valide (délai d'inactivité non écoulé) —
  un simple rechargement de page ne redemande pas le PIN.
- **RF-1.3** Les données sont chiffrées au repos (AES-256-GCM), la clé étant dérivée
  du PIN (PBKDF2/Argon2). Sans PIN, les données locales sont illisibles.
- **RF-1.4** Verrouillage automatique après une période d'inactivité (paramétrable).
- **RF-1.5** Possibilité de changer le PIN (re-chiffrement des données).

### RF-2 — Comptes
- **RF-2.1** Gestion de plusieurs comptes : compte courant, livret, espèces, carte, etc.
- **RF-2.2** Chaque compte a un nom, un type, un solde initial et une icône/couleur.
- **RF-2.3** Virements entre comptes (une opération, deux mouvements liés).
- **RF-2.4** Solde calculé automatiquement à partir des transactions.

### RF-3 — Transactions (dépenses / revenus)
- **RF-3.1** Saisie rapide d'une dépense : montant → catégorie → validation
  (compte et date pré-remplis par défaut). Objectif : moins de 5 secondes.
- **RF-3.2** Champs : montant, type (dépense/revenu/virement), catégorie, compte,
  date, note libre, bénéficiaire/marchand (optionnel), étiquettes (optionnel).
- **RF-3.3** Modification et suppression d'une transaction.
- **RF-3.4** Liste des transactions avec recherche, filtres (période, catégorie,
  compte, type) et tri.
- **RF-3.5** Transactions récurrentes : loyer, salaire, abonnements — définition
  d'une fréquence (hebdo/mensuelle/annuelle…), génération automatique à échéance,
  avec possibilité de confirmer ou d'ajuster.

### RF-4 — Catégories
- **RF-4.1** Jeu de catégories prédéfinies à la première utilisation (alimentation,
  logement, transport, loisirs, santé, abonnements, revenus…).
- **RF-4.2** Création, modification, suppression et réorganisation de catégories
  personnalisées, avec sous-catégories, icône et couleur.
- **RF-4.3** Import de catégories depuis un fichier (JSON/CSV).
- **RF-4.4** La suppression d'une catégorie utilisée propose la réaffectation des
  transactions existantes.

### RF-5 — Budgets
- **RF-5.1** Budget mensuel par catégorie (ex. 300 €/mois pour les courses).
- **RF-5.2** Suivi visuel de la consommation du budget (jauge, % consommé).
- **RF-5.3** Alertes de dépassement : seuil d'avertissement (ex. 80 %) puis
  dépassement, affichées dans l'app (et notification PWA si autorisée).
- **RF-5.4** Report du budget d'un mois sur l'autre (option de reconduction
  automatique) et historique budget vs réalisé.

### RF-6 — Objectifs d'épargne
- **RF-6.1** Définition d'objectifs (nom, montant cible, échéance optionnelle).
- **RF-6.2** Suivi de la progression avec projection d'atteinte de l'objectif.

### RF-7 — Analyses, indicateurs et conseils
- **RF-7.1** Tableau de bord : solde global, dépenses du mois, reste à dépenser,
  top catégories, tendance.
- **RF-7.2** Graphiques : répartition par catégorie (donut), évolution mois par
  mois (barres/ligne), comparaison budget vs réel, évolution du solde.
- **RF-7.3** Indicateurs de performance : taux d'épargne, variation vs mois
  précédent et vs moyenne des 3-6 derniers mois, catégories en dérive.
- **RF-7.4** Conseils automatiques basés sur des règles locales (aucun envoi de
  données à l'extérieur) : « Tes dépenses "Loisirs" sont 40 % au-dessus de ta
  moyenne », « À ce rythme, ton budget Courses sera dépassé le 22 », suggestions
  d'économie sur les catégories récurrentes en hausse, etc.

### RF-7bis — Plans de financement (amortissement d'une grosse dépense)
- **RF-7bis.1** Créer un plan pour une dépense future : motif, montant, échéance.
- **RF-7bis.2** Déclarer les comptes mobilisables, leur ordre de priorité de
  ponction, un montant à préserver par compte et/ou une exclusion totale
  (« ne pas toucher à cette épargne »).
- **RF-7bis.3** Déclarer les revenus futurs (fixes garantis / variables bonus)
  et les événements de dépense à venir (loyer, vacances…), ponctuels ou mensuels.
- **RF-7bis.4** Calculer localement un plan : mobilisable immédiat et allocation
  par compte selon les priorités, épargne mensuelle à constituer, faisabilité à
  l'échéance (avec/sans revenus variables), trajectoire de trésorerie mensuelle
  et alertes de tension. Les revenus variables ne sont jamais comptés comme acquis.

### RF-8 — Import / Export
- **RF-8.1** Export complet des données en JSON (sauvegarde) et export des
  transactions en CSV.
- **RF-8.2** Import d'une sauvegarde JSON (restauration complète) avec contrôle
  de version du format.
- **RF-8.3** Import de transactions depuis CSV avec assistant de correspondance
  des colonnes et détection des doublons.
- **RF-8.4** Option d'export chiffré (protégé par mot de passe).

### RF-9 — Paramètres
- **RF-9.1** Thème clair / sombre / automatique (suivant le système).
- **RF-9.2** Gestion du PIN et du délai de verrouillage.
- **RF-9.3** Jour de début de mois budgétaire (ex. le 28, jour de paie).
- **RF-9.4** Gestion des catégories, comptes, récurrences depuis les paramètres.

## 4. Exigences non fonctionnelles

- **RNF-1 — PWA** : installable sur Android (Add to Home Screen) et utilisable
  dans tout navigateur moderne sur PC ; plein écran sur mobile ; responsive
  (mobile-first, mise en page adaptée aux grands écrans).
- **RNF-2 — Hors ligne** : toutes les fonctionnalités disponibles sans réseau
  (service worker, cache des ressources, données locales IndexedDB).
- **RNF-3 — Sécurité** : chiffrement au repos AES-256-GCM ; clé dérivée du PIN ;
  aucune donnée transmise à un tiers ; verrouillage automatique.
- **RNF-4 — Accessibilité (RGAA)** : contrastes conformes (AA minimum),
  navigation clavier complète, attributs ARIA, tailles de cible tactile ≥ 44 px,
  textes alternatifs sur les graphiques (tableaux de données équivalents),
  respect de `prefers-reduced-motion`.
- **RNF-5 — Performance** : démarrage < 2 s sur mobile, interactions fluides,
  bundle initial léger.
- **RNF-6 — Évolutivité (sync future)** : chaque entité porte un UUID, des
  horodatages `createdAt`/`updatedAt` et un marqueur de suppression logique
  (tombstone), afin de permettre plus tard une synchronisation par fusion avec
  un serveur privé sans migration douloureuse. La couche d'accès aux données est
  isolée derrière une interface unique (repository) pour brancher un backend.
- **RNF-7 — Qualité** : TypeScript strict, tests unitaires sur la logique métier
  (calculs de budget, indicateurs, moteur de conseils, import/export), lint.

## 5. Architecture technique proposée

| Brique | Choix | Raison |
|---|---|---|
| Framework | **React 19 + TypeScript + Vite** | Écosystème mature, PWA simple avec `vite-plugin-pwa` |
| Stockage | **IndexedDB via Dexie.js** | Base locale robuste, requêtes indexées, hors ligne natif |
| Chiffrement | **WebCrypto (AES-GCM + PBKDF2)** | Standard navigateur, aucune dépendance externe |
| État | **Zustand** | Léger, simple, adapté à une app locale |
| Graphiques | **Recharts** | Accessible, personnalisable, léger |
| Styles | **CSS modules + variables CSS** | Thème clair/sombre natif, contrôle fin pour le RGAA |
| Routage | **React Router** | Standard |
| Tests | **Vitest + Testing Library** | Rapide, intégré à Vite |
| Déploiement | **Statique (GitHub Pages)** + utilisable en local | Gratuit, HTTPS requis pour PWA |

**Modèle de données (principales entités) :** `Account`, `Transaction`,
`Category`, `Budget`, `RecurringRule`, `SavingsGoal`, `Settings` — toutes avec
UUID + horodatages + tombstones (cf. RNF-6).

## 6. Périmètre des versions

### V1 — MVP (les 3 indispensables + socle)
1. **Dépenses** : saisie rapide, liste, filtres, comptes, catégories personnalisables.
2. **Budgets** : budgets mensuels par catégorie, jauges, alertes de dépassement.
3. **Indicateurs de performance** : tableau de bord, graphiques principaux,
   comparaison budget vs réel.

Plus le socle indispensable : PIN + chiffrement, PWA hors ligne, thème
clair/sombre, export/import JSON, RGAA.

### V2
- Transactions récurrentes, objectifs d'épargne, import CSV avec assistant,
  moteur de conseils avancé, export CSV/chiffré, notifications.

### V3 (préparée dès la V1 par l'architecture)
- Synchronisation multi-appareils via serveur privé auto-hébergé.

## 7. Hors périmètre

- Connexion bancaire automatique (agrégateurs type Bridge/Powens).
- Multi-devises, multilingue, multi-utilisateurs.
- Publication sur les stores (Google Play / App Store).
