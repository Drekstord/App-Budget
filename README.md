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

## État d'avancement

| Phase | Contenu | État |
|---|---|---|
| 0 | Cadrage, modèle de menaces | ✅ |
| 1 | Socle sécurisé : empreinte + mot de passe, coffre chiffré, phrase de récupération, sauvegarde chiffrée | 🧪 en test |
| 2 | Capture : notifications bancaires, import CSV/OFX | ⏳ |
| 3 | Validation et apprentissage du classement | ⏳ |
| 4 | Profil de vie, enveloppes, mode redressement | ⏳ |
| 5 | Objectifs (tranquille / équilibré / rapide) | ⏳ |
| 6 | Tickets de caisse, bilan hebdo, audit final, clé de signature définitive | ⏳ |

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
- **Dépendances minimales** : AndroidX/Compose, Bouncy Castle (Argon2),
  kotlinx.serialization. Aucun SDK tiers.

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
   exact (module `android/e2e`, UI Automator) : création du coffre, empreinte,
   verrouillage, mauvais mot de passe, récupération par la phrase, export ;
4. seulement si tout est vert : publication sur le lien de test.
