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
- 🏷️ Catégories prédéfinies + personnalisables (sous-catégories, icônes, couleurs)
- 🎯 Budgets mensuels par catégorie : jauges, seuil d'alerte, projection de dépassement
- 📊 Tableau de bord : solde, taux d'épargne, répartition par catégorie,
  évolution sur 6 mois, budget vs réel — avec alternatives tabulaires (RGAA)
- 💡 Conseils automatiques calculés localement (dérives de dépenses, budgets à risque…)
- 💾 Export / import de sauvegarde JSON
- 🌗 Thème clair / sombre, interface conforme RGAA, mois budgétaire personnalisable

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
- Chaque entité porte UUID + horodatages + suppression logique : prêt pour une
  future synchronisation via serveur privé (V3)
