# App Budget

Application de gestion de budget personnel : **PWA** installable sur Android et
utilisable dans n'importe quel navigateur sur PC. 100 % hors ligne, données
locales **chiffrées** (AES-256-GCM, clé dérivée du code PIN), aucune donnée
transmise à l'extérieur.

Les exigences complètes sont dans [EXIGENCES.md](EXIGENCES.md).

## Fonctionnalités (V1)

- 🔐 Code PIN + chiffrement de toutes les données au repos, verrouillage automatique
- 🧾 Saisie ultra-rapide des dépenses/revenus, virements entre comptes,
  recherche et filtres
- 📷 **Scan de ticket de caisse** : OCR 100 % local (Tesseract WASM, la photo ne
  quitte jamais l'appareil) — montant, enseigne, date et catégorie détectés,
  toujours soumis à confirmation avant ajout. Seul le premier scan nécessite
  une connexion (téléchargement du moteur, ensuite mis en cache hors ligne)
- 🔔 Alertes à la saisie : plafond de budget atteint, seuil d'avertissement
  franchi, grosse dépense au-delà d'un montant configurable — en surimpression
  dans l'app et, en option, en notifications système
- 🏷️ Catégories prédéfinies + personnalisables (sous-catégories, icônes, couleurs)
- 🎯 Budgets mensuels par catégorie : jauges, seuil d'alerte, projection de dépassement
- 📊 Tableau de bord : solde, taux d'épargne, répartition par catégorie,
  évolution sur 6 mois, budget vs réel — avec alternatives tabulaires (RGAA)
- 💡 Conseils automatiques calculés localement (dérives de dépenses, budgets à risque…)
- 🧮 **Plans de financement** (amortir une grosse dépense) : déclare la dépense
  à venir, tes comptes avec leur ordre de priorité, leur découvert autorisé
  (sans frais) et les épargnes à protéger,
  tes revenus futurs (fixes/variables) et tes dépenses prévues (loyer, vacances) ;
  l'app calcule un plan expliqué — mobilisable immédiat par compte, épargne
  mensuelle à constituer, faisabilité à l'échéance, trajectoire de trésorerie
  et points de tension. Les projets **partagent la même trésorerie** : traités
  par ordre d'échéance, les plus urgents se servent en premier et les suivants
  tiennent compte de ce qui reste (pas de double engagement du même argent)
- 💳 **Prélèvements** (abonnements & prêts) : montant, échéance, catégorie,
  indispensable ou non, compte de prélèvement ; total mensuel, montant à
  provisionner par compte, et reste à rembourser d'un prêt jusqu'à sa date de fin.
  À chaque échéance, l'**opération est créée automatiquement** (une seule fois) :
  c'est elle qui consomme le budget, comme une dépense saisie à la main
- 🧮 **Disponible réel du mois** : revenu − tout ce qui est déjà dépensé (y compris
  hors budget) − ce qui reste réservé dans les enveloppes, plus la liste des
  dépenses non budgétées — pour ne pas se croire plus riche qu'on ne l'est
- 📅 **Mois budgétaire calé sur la paie** : le mois commence le jour où le
  salaire tombe (n'importe quel jour, y compris « dernier jour du mois », qui
  s'adapte à la longueur de chaque mois). L'app **détecte le jour de paie** à
  partir des revenus déjà saisis et propose de s'y caler ; l'accueil indique
  combien de jours restent à tenir et le budget quotidien correspondant
- 💾 Export / import de sauvegarde JSON
- 🌗 Thème clair / sombre, interface conforme RGAA

## Interface

Direction visuelle **sobre et bancaire** : neutres légèrement bleutés, un seul
accent, couleurs de statut réservées au statut, montants en chiffres tabulaires.

- **4 onglets** (Accueil, Opérations, Budgets, Plus) + un **bouton d'ajout
  central** disponible depuis n'importe quel écran ; sur PC, la barre devient
  une navigation latérale
- **Accueil** : un seul chiffre en gros (le disponible réel), une barre de
  répartition dépensé / réservé / libre, quatre tuiles, puis les conseils
- **Budgets** : un récapitulatif unique, toutes les enveloppes dans une seule
  carte (une ligne + une jauge fine chacune, pourcentage affiché seulement s'il
  mérite l'attention) et une section « hors enveloppe »
- **Saisie** : feuille remontant du bas, montant en grand, **pavé numérique**
  sur mobile (champ de saisie réel conservé pour le clavier et les lecteurs
  d'écran), type segmenté, catégories en pastilles défilantes, scan de ticket
  accessible depuis l'en-tête
- Icônes d'interface en **SVG** (rendu identique partout) ; les emoji restent
  réservés aux catégories et comptes, choisis par l'utilisateur

## Démarrer

```bash
npm install
npm run dev        # développement
npm test           # tests unitaires (Vitest)
npm run build      # build de production dans dist/
npm run preview    # sert le build localement
```

Ouvre l'app dans le navigateur, choisis un code PIN, et c'est parti.
Sur Android : menu du navigateur → « Ajouter à l'écran d'accueil » pour
l'installer en plein écran.

> ⚠️ Le PIN chiffre les données : s'il est perdu, elles sont irrécupérables.
> Pense à exporter des sauvegardes régulières (Réglages → Sauvegarde).

## Déployer (gratuit)

Le build est 100 % statique : héberge le contenu de `dist/` n'importe où
(GitHub Pages, Netlify, un simple dossier servi en HTTPS — requis pour la PWA).

## Architecture

- **React 19 + TypeScript + Vite** — PWA via `vite-plugin-pwa`
- **IndexedDB (Dexie)** — coffre chiffré : seuls les identifiants sont en clair
- **WebCrypto** — PBKDF2 (600 000 itérations) → AES-256-GCM
- **Zustand** — état applicatif ; **Recharts** — graphiques
- **Tesseract.js** — OCR embarqué (moteur WASM et langue française auto-hébergés
  dans `public/ocr`, aucun CDN)
- Chaque entité porte UUID + horodatages + suppression logique : prêt pour une
  future synchronisation via serveur privé (V3)
