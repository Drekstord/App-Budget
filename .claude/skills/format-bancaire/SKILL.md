---
name: format-bancaire
description: Apprend à Butin un nouveau format bancaire à partir d'un exemple réel fourni par l'utilisateur — notification non comprise (mode diagnostic), en-tête ou lignes d'un CSV/OFX qui ne s'importe pas, ou app bancaire inconnue vue par la capture. Anonymise l'exemple, l'ajoute comme test dans core, adapte l'analyse, relance les tests. À utiliser dès que l'utilisateur colle une notification, un message d'erreur d'import ou un nom technique d'app.
argument-hint: "[exemple collé par l'utilisateur]"
---

# Ajouter un format bancaire réel

Exemple fourni : $ARGUMENTS

## 1. Anonymiser d'abord

L'exemple vient des vraies finances de l'utilisateur. Avant de l'écrire où que ce soit
(test, commit, doc) :
- remplace les montants par d'autres de même forme (`-37,84 €` → `-12,50 €`, en gardant le signe,
  le séparateur décimal, les espaces insécables, la position du symbole) ;
- remplace marchands, noms, IBAN, numéros de carte ou de compte, lieux, par des valeurs neutres
  (`CARREFOUR CITY`, `ACME`, `0001`, `CB*1234`) ;
- garde **exactement** la structure : mots, ponctuation, majuscules, ordre, retours à la ligne,
  séparateurs, guillemets, en-têtes de colonnes. C'est elle que l'analyse doit reconnaître.

Ne recopie jamais l'exemple brut dans un fichier versionné.

## 2. Identifier le cas

| Ce que l'utilisateur a collé | Code à adapter | Tests à compléter |
|---|---|---|
| Notification non comprise | `core/src/main/kotlin/app/boussole/core/capture/NotificationParser.kt` | `NotificationParserTest` dans `core/src/test/kotlin/app/boussole/core/capture/CaptureTest.kt` |
| Import CSV/OFX en échec (message « Première ligne lue… ») | `core/src/main/kotlin/app/boussole/core/importer/StatementImport.kt` | `core/src/test/kotlin/app/boussole/core/importer/ImportTest.kt` |
| Ticket mal lu | `core/src/main/kotlin/app/boussole/core/capture/ReceiptParser.kt` | `ReceiptParserTest` dans `CaptureTest.kt` |
| App bancaire inconnue (« Apps vues récemment ») | `CaptureConfig.DEFAULT_SOURCES` dans `core/src/main/kotlin/app/boussole/core/capture/CaptureConfig.kt` | — |

Pour une app inconnue : `sources` est enregistré dans la configuration de capture du téléphone.
Une nouvelle valeur par défaut ne s'applique donc pas forcément à une installation existante.
Vérifie dans `android/app/src/main/java/app/boussole/capture/CaptureStore.kt` comment la
configuration est relue. Si besoin, prévois une fusion avec les valeurs par défaut ou dis à
l'utilisateur comment l'ajouter depuis l'écran Capture.

## 3. Test d'abord, puis le code

1. Écris le test avec l'exemple anonymisé et le résultat attendu : montant en centimes et signe,
   marchand, type (paiement, revenu, retrait, prélèvement), confiance ; ou date, libellé et montant
   pour un relevé.
2. Lance-le et vérifie qu'il échoue pour la bonne raison :
   `./gradlew -p core test --tests '*NotificationParserTest*'` (ou `*StatementImportTest*`).
3. Adapte l'analyse au plus juste. Préfère une règle générale à un cas particulier, sans casser
   les formats déjà reconnus.
4. Relance **tous** les tests du cœur : `./gradlew -p core test`. Un nouveau format ne doit pas
   en faire régresser un autre.

## 4. Après

- Ajoute à `docs/GUIDE_DE_TEST.md` (« Limites connues ») ce qui est désormais confirmé ou reste supposé.
- Propose à l'utilisateur de livrer avec `/livrer`. C'est lui qui lance cette commande.
- Dis-lui ce qu'il doit vérifier sur son téléphone (la même notification ou le même fichier doit
  maintenant passer), et que les nouveaux exemples non compris restent les bienvenus.
