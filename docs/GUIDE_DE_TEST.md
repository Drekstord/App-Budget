# Marche à suivre : tests et peaufinage

Ce guide t'accompagne de la première installation jusqu'à l'usage au quotidien.
Chaque version qui arrive sur ton lien a déjà passé, sur un émulateur Android,
le parcours automatique complet (création, profil, capture d'une dépense par
notification, validation, apprentissage, budget, conseils, objectif,
verrouillages, récupération, sauvegarde). Ton rôle est de tester ce qu'un
émulateur ne peut pas vérifier : **tes vraies banques, ton vrai téléphone, ta
vraie vie.**

> Installation et mise à jour : [INSTALLATION.md](INSTALLATION.md). Lien fixe :
> https://github.com/Drekstord/App-Budget/releases/download/test/boussole-test.apk

## Avant de commencer : données réelles ou fictives ?

Les versions de test sont signées avec une clé temporaire : **chaque nouvelle
version oblige à désinstaller la précédente**. Tes données ne sont pas perdues
pour autant si tu fais ceci avant chaque mise à jour :

1. Plus → Sauvegarde et export → **Exporter une sauvegarde chiffrée** → Proton Drive.
2. Désinstalle, installe la nouvelle version.
3. Au premier écran : **Restaurer une sauvegarde** → le fichier → ta phrase.

Tu peux donc tester avec tes **vraies** dépenses : elles restent chiffrées sur
le téléphone et l'app n'a pas accès à Internet. Seule la mise à jour est
contraignante, jusqu'à la clé définitive (étape 9).

---

## Étape 1 : création et profil de vie (10 min)

1. Crée le coffre : mot de passe via Proton Pass, phrase de 24 mots, empreinte.
2. Le **profil de vie** s'ouvre ensuite :
   - ta paie nette habituelle et son jour (31 si « fin de mois ») ;
   - le **solde actuel** de chaque compte, lu dans tes apps bancaires (avec
     « - » s'il est négatif), et ton découvert autorisé ;
   - tes **charges fixes** : assurance, téléphone, abonnements… (indispensable
     ou non) ;
   - tes contraintes et ce qui n'est **pas négociable** ;
   - laisse « Créer mes enveloppes avec la répartition proposée » coché.

✅ **À vérifier** : sur l'Accueil, les soldes des comptes correspondent à ceux de
tes banques.

## Étape 2 : capture automatique (le plus important, sur 1 semaine)

1. Plus → **Capture des dépenses** :
   - « 1. Infos de l'app » → ⋮ → **Autoriser les paramètres restreints** ;
   - « 2. Autoriser l'accès aux notifications » → active Boussole.
2. Active le **mode diagnostic** (même écran).
3. Dans tes apps Boursobank et Caisse d'Épargne, active les **notifications
   de paiement** si ce n'est pas déjà fait.
4. Vis normalement pendant quelques jours : paie avec Samsung Wallet, avec la
   carte, retire des espèces.

✅ **À vérifier chaque soir** (un rappel arrive à 20 h) :

- chaque paiement apparaît dans « À valider » avec le **bon montant** et le
  **bon marchand** ;
- un paiement Wallet **et** sa notification bancaire ne créent **qu'une seule**
  dépense ;
- la catégorie proposée est juste ; sinon corrige-la : la fois suivante, ce
  marchand doit être classé correctement tout seul.

📤 **Ce que tu m'envoies** :

- dans Capture des dépenses, la liste « **Notifications non comprises** » :
  touche **Copier** sur chacune et colle-la moi. Masque ce que tu veux (montant,
  lieu), mais garde la structure de la phrase ;
- les apps listées dans « **Apps vues récemment** » qui sont des banques que je
  n'ai pas prévues : leur nom technique s'affiche, je l'ajouterai par défaut.

C'est avec ces exemples réels que j'affinerai la lecture des notifications :
je ne connais pas encore les formats exacts de tes banques.

## Étape 3 : relevés bancaires (10 min)

1. Sur le **site** Boursobank : exporte les opérations du mois en **CSV**. Fais
   de même sur le site de la Caisse d'Épargne (CSV ou OFX).
2. Plus → **Importer un relevé** → choisis le compte → le fichier → Importer.

✅ **À vérifier** :

- les paiements déjà captés par notification sont **rapprochés**, pas dupliqués ;
- les prélèvements et virements absents des notifications apparaissent « à valider » ;
- un **second import** du même fichier n'ajoute rien.

📤 Si l'import échoue : envoie-moi **la première ligne** du fichier (les noms des
colonnes, sans tes opérations).

## Étape 4 : budget et enveloppes (15 min)

1. Onglet **Budget** : vérifie que le mois commence **le jour où ta paie est
   arrivée**. Pour cela, la paie doit être classée « Salaire ».
2. Plus → Charges fixes : ajoute ce que l'app a **détecté** dans tes opérations.
3. Budget → **Modifier mes enveloppes** → « Me proposer une répartition » :
   ajuste les montants à ta réalité.
4. Fais déborder une enveloppe exprès, puis **Couvrir** depuis une autre.

✅ **À vérifier** : le « Disponible jusqu'à la paie » de l'Accueil te paraît-il
juste ? Est-ce ce que tu peux vraiment dépenser ?

## Étape 5 : conseils et objectif (15 min)

1. Onglet **Conseils** : lis la situation, la marche à suivre et les leviers.
2. Compare les rythmes **tranquille / équilibré / rapide** : effort par mois,
   date de sortie du découvert.
3. **+ Nouvel objectif** : la guitare (montant, échéance si tu en as une).

✅ **À vérifier** :

- les conseils sont-ils **réalistes pour toi** ?
- rien de ce que tu as marqué non négociable n'est proposé à la coupe ?
- le ton te convient-il (suggestif, avec une recommandation claire) ?
- reçois une prime ou une aide (classe-la « Primes » ou « Aides ») : l'app
  propose-t-elle une répartition sensée ?

## Étape 6 : ticket de caisse (5 min)

Plus → Scanner un ticket → photo d'un ticket de supermarché.

✅ **À vérifier** : le montant total, l'enseigne et la date sont-ils trouvés ?

## Étape 7 : rappels et vie quotidienne (1 semaine)

- 20 h : « N dépenses à valider » (si besoin). L'heure se règle dans Plus → Rappels.
- Dimanche : bilan de la semaine et rappel de sauvegarde.
- Aucune capture pendant 4 jours → alerte.

✅ **À vérifier** : les rappels arrivent, sans montant visible sur l'écran
verrouillé. Ne sont-ils pas trop envahissants ?

## Étape 8 : sécurité (5 min)

- Plus → Sécurité : « Puce sécurisée StrongBox », état du téléphone.
- Quitte l'app (bouton Accueil) puis reviens : elle doit être verrouillée.
- Capture d'écran dans l'app : elle doit être bloquée.
- Changer le mot de passe : Plus → Sécurité.

---

## Le peaufinage : comment me faire tes retours

Envoie-les au fil de l'eau, même en vrac, idéalement sous cette forme :

```
Écran : Budget
Ce que j'ai fait : j'ai couvert « Restos » depuis « Courses »
Ce que j'attendais : …
Ce qui s'est passé : …
Gravité : bloquant / gênant / détail / idée
```

- **Plantage** : au lancement suivant, l'écran « L'app a planté » propose
  **Copier le rapport** : colle-le moi (il ne contient aucune donnée).
- **Idée ou envie** : dis-la, même vague (« l'accueil devrait me montrer… »).

Ensuite, chaque cycle se passe ainsi :

1. Je corrige ou j'améliore, et j'envoie le code sur GitHub.
2. La CI teste tout : tests du cœur, APK sans accès réseau, parcours complet
   sur émulateur.
3. **Seulement si tout passe**, ton lien est mis à jour et je te préviens.
4. Tu sauvegardes, réinstalles, restaures et retestes ce qui a changé.

## Étape 9 : passer à l'usage réel (clé définitive)

Quand l'app te convient :

1. Tu génères **ta** clé de signature, que toi seul détiens (sur un PC, ou
   directement sur le téléphone avec Termux) : je te guiderai pas à pas.
2. Les versions définitives sont signées avec cette clé : les mises à jour
   s'installent **par-dessus**, sans désinstaller.
3. Dernière sauvegarde de la version de test, installation de la version
   définitive, restauration : c'est parti pour de bon.

---

## Limites connues (je les traiterai pendant le peaufinage)

- **Formats des notifications** : écrits d'après des formats courants, à
  confirmer avec tes exemples réels (étape 2).
- **Nom technique de l'app Caisse d'Épargne** : supposé. Le mode diagnostic
  dira le vrai et je l'ajouterai.
- **Premier mois** : sans historique, les conseils s'appuient sur ton profil et
  sur des parts usuelles. Ils s'affinent dès que tu as validé environ un mois
  d'opérations.
- **Restauration depuis un fichier** : testée sur ton téléphone (ça marche),
  mais pas encore dans le parcours automatique.
- **Version PC** : prévue par l'architecture, pas encore développée.
