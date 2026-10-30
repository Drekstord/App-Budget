# Boussole

Application Android de budget personnel. Elle **capte tes dépenses toute
seule**, les classe, les range dans tes enveloppes, et te **conseille** pour
sortir du rouge ou financer un objectif sereinement. Tout cela **sans jamais
rien envoyer** : l'app n'a pas accès à Internet.

> Refonte totale (octobre 2026) de l'ancienne PWA « Écu », toujours
> consultable dans l'historique git (commit `fdeaeb4`).

- 📋 Exigences : [docs/EXIGENCES.md](docs/EXIGENCES.md)
- 🛡️ Sécurité, menaces et risques : [docs/SECURITE.md](docs/SECURITE.md)
- 📱 Installer une version de test : [docs/INSTALLATION.md](docs/INSTALLATION.md)
- 🧪 Marche à suivre des tests et du peaufinage : [docs/GUIDE_DE_TEST.md](docs/GUIDE_DE_TEST.md)

## État d'avancement

| Domaine | Contenu | État |
|---|---|---|
| Sécurité | Empreinte + mot de passe, coffre chiffré, phrase de récupération, sauvegarde chiffrée, aucun accès réseau | ✅ |
| Capture | Notifications bancaires (liste blanche, captures chiffrées même coffre fermé), relevés CSV/OFX avec rapprochement, tickets (OCR sur le téléphone), saisie | ✅ à valider sur tes banques |
| Classement | Propositions par règles apprises de tes corrections et mots-clés, file de validation | ✅ |
| Budget | Mois calé sur la paie réelle, enveloppes strictes 50/30/20 adaptées, couverture des dépassements, disponible réel, charges fixes détectées | ✅ |
| Conseils | Situation, marche à suivre (redressement), leviers, rythmes tranquille/équilibré/rapide, objectifs, primes | ✅ |
| Rappels | Soir, dimanche, sauvegarde, absence de capture (sans montant visible) | ✅ |
| Peaufinage | Avec tes retours : [docs/GUIDE_DE_TEST.md](docs/GUIDE_DE_TEST.md) | 🧪 |
| Clé définitive | Signature par ta propre clé | ⏳ |
| Bonus | Version PC, IA locale | ⏳ |

## Architecture

```
core/      Kotlin pur : crypto, coffre, sauvegarde, règles métier (testé sur la JVM,
           réutilisable par un futur client PC)
android/   Application Android (Jetpack Compose), Keystore, biométrie
docs/      Exigences et sécurité
```

- **Aucune permission réseau** : retirée du manifeste et vérifiée sur l'APK
  final par la CI.
- **Chiffrement** : AES-256-GCM. Clé de données scellée par Argon2id (mot de
  passe), elle-même scellée par une clé matérielle StrongBox débloquée par
  l'empreinte. Phrase BIP39 de 24 mots pour la récupération.
- **Dépendances minimales** : AndroidX/Compose, Bouncy Castle (Argon2,
  X25519), kotlinx.serialization, ML Kit (OCR embarqué, sans réseau possible).

## Développer

```bash
./gradlew -p core test                      # tests du cœur (JVM, sans SDK Android)
./gradlew -p android :app:assembleRelease   # APK (nécessite le SDK Android)
```

La CI (`.github/workflows/android.yml`) enchaîne, à chaque envoi :

1. les tests du cœur ;
2. la construction de l'APK final (optimisé par R8) et le contrôle de
   l'absence de permission réseau ;
3. **le parcours utilisateur complet sur un émulateur Android**, sur cet APK
   exact (module `android/e2e`, UI Automator) : création du coffre, profil de
   vie, dépense captée par notification (coffre ouvert et fermé), validation et
   apprentissage, budget, conseils, objectif, verrouillages, récupération par
   la phrase, sauvegarde ;
4. seulement si tout est vert : publication sur le lien de test.
