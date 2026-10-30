# Boussole — Modèle de menaces et architecture de sécurité

> Version 2.0 — 29 octobre 2026. Ce document fait autorité : toute
> fonctionnalité qui le contredit doit d'abord le faire évoluer.

## 1. Le principe

La sécurité absolue n'existe pas. L'objectif est de **réduire la surface
d'attaque au strict minimum**, puis de rendre chaque risque restant **connu,
mesuré et traité**.

Trois garanties structurent tout le reste :

1. **L'app ne peut rien envoyer** : la permission `INTERNET` est absente. C'est
   Android qui l'interdit, pas notre code. Même un bug ou une bibliothèque
   piégée ne peut pas exfiltrer de données.
2. **Rien de lisible au repos** : toutes les données sont chiffrées par une clé
   que seuls la puce sécurisée du téléphone, ton empreinte et ton mot de passe,
   ensemble, permettent d'ouvrir.
3. **Rien ne sort en clair** : les sauvegardes sont chiffrées avant de quitter l'app.

## 2. Ce qu'on protège, contre qui

**Actifs** : transactions, soldes, habitudes de vie, profil, règles de
classement, phrase de récupération, mot de passe.

| Adversaire (q14) | Capacités supposées | Couvert ? |
|---|---|---|
| **Entreprises / collecte de données** | SDK publicitaires et analytiques, sauvegarde cloud automatique, clavier tiers, OS | Oui : pas de réseau, pas de SDK tiers, sauvegarde Android désactivée, clavier en mode incognito |
| **Pirate à distance** | Fuite d'un cloud, app malveillante installée, compte GitHub compromis | Oui, dans les limites du §6 |
| **Voleur du téléphone** | Accès physique, téléphone verrouillé ou non | Oui : chaque ouverture exige empreinte + mot de passe |
| **Proche curieux** | Accès ponctuel au téléphone déverrouillé | Oui : verrouillage immédiat en arrière-plan |
| Attaquant étatique, exploit noyau ou TEE inconnu | Contrôle total du téléphone | **Non** (risque résiduel accepté, §6) |

## 3. Hiérarchie de clés

```
                    ┌────────────────────────────────────────┐
  Données ◄─AES-GCM─┤ DEK : clé de données, 256 bits aléatoires │
                    └───────▲───────────────────▲────────────┘
                            │ enveloppe P       │ enveloppe R
            AES-GCM(KEK_mdp)│                   │AES-GCM(KEK_récup)
   KEK_mdp = Argon2id(mot de passe, sel)    KEK_récup = HKDF(entropie des 24 mots)
                            │
            AES-GCM(clé matérielle Keystore)
            StrongBox, biométrie classe 3,
            invalidée si une empreinte est ajoutée
                            │
                     enveloppe H(P) sur le disque
```

- **Ouverture** : l'empreinte débloque la clé matérielle, qui donne P ; le mot
  de passe, passé dans Argon2id, donne KEK_mdp, qui ouvre la DEK. **Il faut
  les deux.**
- **Pourquoi c'est solide** : copier les fichiers ne sert à rien, car
  l'enveloppe externe ne s'ouvre que dans la puce sécurisée de *ce*
  téléphone, après une empreinte. Le mot de passe stocké dans Proton Pass
  ne suffit pas non plus sans le téléphone.
- **Phrase de récupération** : 24 mots = 256 bits d'entropie, donc impossible à
  deviner. Elle ouvre l'enveloppe R, présente sur le téléphone et dans chaque
  sauvegarde. Elle sert si l'empreinte est invalidée ou si le téléphone change.
- **Argon2id** : 64 Mio, 3 itérations, 1 fil. Les paramètres sont stockés dans
  l'en-tête, donc ajustables sans casser l'existant.
- **Toutes les clés d'enveloppe** utilisent AES-256-GCM avec un nonce aléatoire
  de 96 bits et des **données associées** (type d'enveloppe, version), ce qui
  empêche d'échanger une enveloppe contre une autre.

### Où ranger la phrase : décision de l'utilisateur (30/10/2026)

La phrase de récupération ouvre toutes les sauvegardes. La recommandation
était de la garder **uniquement sur papier**, car les sauvegardes sont sur
Proton Drive : si la phrase y est aussi, **une compromission du compte Proton
donne tout**.

**Décision** : l'utilisateur range la phrase sur **Proton Drive**, avec ses
autres codes. L'app propose donc de la **copier** (presse-papiers marqué
« sensible », vidé au bout de 60 s) ou de l'**enregistrer en fichier** via la
feuille de partage (copie temporaire effacée en quittant l'écran), toujours
après un avertissement. Les captures d'écran restent bloquées : elles
partiraient dans la Galerie et ses synchronisations.

**Conséquence** : le compte Proton devient le point unique de défaillance
(risque R17). Contre-mesures demandées à l'utilisateur :

- **double authentification** sur le compte Proton (clé de sécurité ou
  application TOTP), mot de passe Proton unique et long ;
- idéalement, une copie papier en plus, au cas où le compte Proton serait perdu.

> À noter : la sauvegarde Google One du téléphone **ne contient pas**
> Boussole (`allowBackup="false"` et règles d'extraction qui excluent tout).
> La seule sauvegarde est le fichier chiffré exporté volontairement.

### Captures faites coffre fermé : la boîte de réception scellée

Le service qui lit les notifications tourne quand l'app est verrouillée : il n'a
pas la DEK. Il chiffre donc chaque capture avec une **clé publique X25519**
(chiffrement hybride : clé éphémère, HKDF-SHA256, AES-256-GCM). La **clé
privée** correspondante est rangée **dans le coffre chiffré** : seules
l'empreinte et le mot de passe permettent de relire les captures, qui sont
intégrées à l'ouverture.

- En clair sur le disque : la clé publique (vérifiée contre la clé privée à
  chaque ouverture), les noms des apps sources, la date de la dernière capture
  et de la dernière sauvegarde, le nombre d'opérations à valider et les
  réglages de rappels. **Aucun montant, aucun marchand, aucun texte de
  notification.**
- Les rappels (soir, dimanche, sauvegarde, absence de capture) ne s'appuient
  que sur ces données en clair et n'affichent jamais de montant.

## 4. Mesures

| Domaine | Mesure | Phase |
|---|---|---|
| Réseau | `INTERNET` et `ACCESS_NETWORK_STATE` retirées du manifeste fusionné (`tools:node="remove"`), **vérifiées par la CI sur l'APK final** | 1 |
| Stockage | DEK aléatoire, données chiffrées en AES-256-GCM, fichiers dans `noBackupFilesDir` | 1 |
| Clé matérielle | Android Keystore, StrongBox si disponible, `setUserAuthenticationRequired` + biométrie classe 3, `setInvalidatedByBiometricEnrollment(true)`, `setUnlockedDeviceRequired(true)` | 1 |
| Authentification | Empreinte via `BiometricPrompt` + `CryptoObject` (l'empreinte débloque réellement la clé, ce n'est pas un simple oui/non), puis mot de passe → Argon2id | 1 |
| Échecs | Délai croissant après des échecs de mot de passe ; le nombre d'essais d'empreinte est limité par le système | 1 |
| Session | Verrouillage au passage en arrière-plan ; DEK effacée de la mémoire (au mieux, sur la JVM) | 1 |
| Écran | `FLAG_SECURE` (pas de capture ni d'aperçu dans les apps récentes), champs sensibles en `IME_FLAG_NO_PERSONALIZED_LEARNING` (clavier sans mémorisation) | 1 |
| Sauvegarde Android | `allowBackup="false"` + `dataExtractionRules` qui excluent tout : rien ne part dans Google ou Samsung Cloud | 1 |
| Composants | Seule l'activité de lancement est exportée ; le futur service de notifications est protégé par `BIND_NOTIFICATION_LISTENER_SERVICE` | 1-2 |
| Sauvegarde | Fichier chiffré (DEK, avec R dans l'en-tête), partagé via le menu « Partager » ; aucun export complet en clair | 1 |
| Notifications captées | Liste blanche d'apps ; le texte n'est jamais écrit en clair : scellé dans la boîte de réception (X25519 + AES-GCM), analysé à l'ouverture du coffre ; seules les notifications contenant un montant sont retenues (le mode diagnostic, désactivé par défaut, garde aussi les autres, scellées) | 2 |
| Notifications émises | Visibilité `PRIVATE` + version publique neutre : aucun montant, ni sur l'écran verrouillé ni dans le texte | 3 |
| Actions externes | Appareil photo, sélecteur de fichiers, partage, réglages : lancés depuis l'app, ils suspendent le verrouillage **3 minutes au plus** ; sinon verrouillage au passage en arrière-plan (délai de grâce réglable 0, 30 ou 60 s) | 2 |
| Tickets de caisse | OCR ML Kit embarqué (modèle dans l'APK) ; photo écrite dans le cache privé et effacée après analyse ; sans permission INTERNET, la télémétrie de la bibliothèque ne peut rien envoyer | 6 |
| Tests de bout en bout | Repères de test exposés à l'accessibilité (`testTagsAsResourceId`) : des identifiants d'écran, aucune donnée | 1 |
| Dépendances | Minimum vital (AndroidX, Compose, Bouncy Castle) ; versions figées ; vérification des sommes de contrôle Gradle (`verification-metadata.xml`) | 1, durci en 6 |
| Build | R8 (minification et obfuscation) ; build reproductible ; CI qui lance les tests et contrôle les permissions | 1 |
| Signature | Signature v2 + v3 ; clé définitive générée et conservée **par l'utilisateur** ; rotation possible grâce à la lignée de clés v3 | 6 |
| Intégrité de l'appareil | Attestation de clé (état de démarrage vérifié) et détection de root, comme défense en profondeur | 6 |
| Audit | Liste de contrôle MASVS L2 + R, analyse MobSF, revue de code de chaque changement | 6 |

## 5. Registre des risques

Prob. = probabilité. Résid. = risque résiduel une fois les mesures en place.
F = faible, M = moyen, É = élevé.

| # | Risque | Prob. | Impact | Mesures | Résid. |
|---|---|---|---|---|---|
| R1 | Vol du téléphone déverrouillé | M | É | Verrouillage en arrière-plan, empreinte + mot de passe à chaque ouverture | F |
| R2 | Vol du téléphone verrouillé, extraction des fichiers | F | É | Enveloppe matérielle StrongBox : fichiers inexploitables hors de la puce | F |
| R3 | App malveillante sur le téléphone (accessibilité, superposition) | F | Critique | `FLAG_SECURE`, filtrage des touches quand une fenêtre est masquée, conseil de prudence sur les apps à accessibilité | M |
| R4 | Exfiltration par le code de l'app ou une dépendance | F | Critique | **Pas d'INTERNET**, dépendances minimales et vérifiées | F |
| R5 | Fuite par une sauvegarde | M | Critique | Sauvegarde toujours chiffrée ; seule la phrase de récupération l'ouvre (voir R17) | F |
| R6 | Perte de données (téléphone perdu ou cassé, empreinte invalidée) | M | É | Phrase de récupération en lieu sûr, sauvegarde chiffrée hebdomadaire rappelée, restauration testée | F |
| R7 | Oubli du mot de passe | M | M | Restauration par la phrase de récupération ; mot de passe dans Proton Pass | F |
| R8 | Détournement de la lecture des notifications | F | M | Liste blanche stricte, extraction minimale, rien de brut conservé | F |
| R9 | La banque change le format de ses notifications | É | M | Détection des trous (CAP-5), import CSV/OFX en rattrapage, analyseurs testés | F |
| R10 | Mauvais classement | É | F | Validation de chaque dépense ; apprentissage par les corrections | F |
| R11 | Mauvais conseil aux conséquences financières | M | É | Moteur déterministe testé, hypothèses prudentes, chaque conseil explique son calcul | F |
| R12 | APK falsifié à l'installation | F | Critique | APK signé, empreinte de certificat publiée et vérifiable ; Android refuse une mise à jour signée par une autre clé | F |
| R13 | Une mise à jour Android ou One UI casse une fonction | M | M | Import CSV en secours, tests à chaque version majeure | F |
| R14 | Compte GitHub compromis (code piégé poussé) | F | É | 2FA obligatoire, branches protégées, revue des diffs avant chaque installation | M |
| R15 | Erreur de conception ou de développement | M | É | Ce document, des tests (crypto incluse), une revue de sécurité à chaque phase, un audit final | M |
| R16 | Clavier tiers qui enregistre la saisie | F | M | Clavier en mode incognito sur les champs sensibles, Proton Pass pour le mot de passe | F |
| R18 | Bibliothèque ML Kit (Google) embarquée pour l'OCR | F | M | Pas de permission INTERNET (vérifiée par la CI) : aucune donnée ne peut sortir ; photo effacée après analyse | F |
| R19 | Une autre app malveillante notifie en se faisant passer pour une banque | F | F | Liste blanche par nom de paquet (unique sur Android) ; chaque capture reste « à valider » | F |
| R17 | Compte Proton compromis alors qu'il contient la phrase **et** les sauvegardes (décision du 30/10) | F | Critique | Double authentification Proton, mot de passe unique ; copie de la phrase marquée sensible et effacée après 60 s | M (accepté) |

## 6. Risques résiduels acceptés

- **Téléphone entièrement compromis** (exploit du noyau ou du TEE, malware avec
  root) : l'attaquant voit ce que tu vois quand l'app est ouverte. Aucune app
  ne peut s'en protéger seule. On garde Android et One UI à jour, et on
  n'installe pas d'apps douteuses.
- **Builds de test (phases 1 à 5)** : ils sont signés par une clé de debug
  éphémère de la CI, il faut désinstaller et réinstaller à chaque version.
  **Uniquement des données fictives** tant que la clé définitive n'est pas
  générée par toi (phase 6, sur un PC ou via Termux sur le téléphone).
- **Dossier sécurisé Samsung** : non utilisé au départ. Une app placée à
  l'intérieur ne voit pas les notifications des apps restées dehors. On le
  réévaluera en déplaçant aussi les apps bancaires dans le Dossier.

## 7. Notes d'installation liées à la sécurité

- Android 13+ bloque par défaut l'accès aux notifications pour une app
  installée à la main (« paramètres restreints »). Il faudra l'autoriser
  explicitement : Infos de l'app → ⋮ → « Autoriser les paramètres restreints ».
  C'est voulu, et cela prouve que la protection d'Android fonctionne.
- Vérifie l'empreinte du certificat de l'APK avant chaque installation de la
  version définitive (procédure fournie en phase 6).
