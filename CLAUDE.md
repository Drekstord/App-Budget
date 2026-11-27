# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Butin** is a personal-budget Android app for a single French user (Samsung phone, Boursobank + Caisse d'Épargne). It captures expenses from bank notifications, classifies them, fills strict budget envelopes and gives savings advice, **with no network access at all**. Code identifiers keep the former name `boussole` (package `app.boussole`, `BoussoleApp`, `BoussoleTheme`…); only user-facing strings say "Butin".

Everything user-facing (UI strings, docs, commit messages) is written in **French**. Code comments are also in French.

Key docs: `docs/EXIGENCES.md` (requirements), `docs/SECURITE.md` (threat model, key hierarchy, risk register R1…), `docs/GUIDE_DE_TEST.md` (manual test plan the user follows, so update it when navigation or labels change), `docs/INSTALLATION.md`.

## Commands

There are two independent Gradle builds sharing one wrapper (`./gradlew` at the root) and one version catalog (`gradle/libs.versions.toml`):

```bash
./gradlew -p core test                                    # core unit tests (pure JVM, no Android SDK)
./gradlew -p core test --tests 'app.boussole.core.importer.StatementImportTest'   # one test class
./gradlew -p core test --tests '*StatementImportTest.OFX*'  # one test: match the start of its backtick name (a pattern with spaces or accents fails)
./gradlew -p android :app:assembleRelease                 # R8-minified APK (needs Android SDK)
./gradlew -p android :e2e:assembleDebug                   # UI Automator end-to-end test APK
./scripts/e2e.sh                                          # run e2e on a running emulator (expects APK in dist/)
```

- `core` compiles with `allWarningsAsErrors`: an unused variable or a deprecation breaks the build.
- Kotlin backtick test names cannot contain `:` (and some other characters); the tests use French sentences.
- The Android build needs `dl.google.com`/`maven.google.com`. In sandboxes where those are blocked, only `core` can be built locally; the Android code is then compiled and tested only by CI.

## CI is the release gate

`.github/workflows/android.yml` runs on every push to `main` and `claude/**`:
1. `build`: core tests, `assembleRelease`, then **fails if the APK declares any network permission** (aapt2 dump), and prints the APK and certificate hashes.
2. `e2e`: installs that exact APK on an API 34 emulator and runs `VaultJourneyTest` (full user journey) via `scripts/e2e.sh`.
3. `publish`: only if both pass, replaces the GitHub Release `test` with `butin-test.apk`. The user installs from that fixed link.

The user explicitly refuses untested builds: a change is delivered only once this pipeline is green. All Actions are pinned by commit SHA; keep it that way.

## Architecture

### `core/`: pure Kotlin, no Android dependency
All business logic and crypto live here so they are JVM-testable and reusable by a future PC client.
- `domain/`: `Model.kt` holds `VaultData`, the **single serialized document** that is the whole database (accounts, transactions, categories, envelopes, rules, goals, profile, settings, UI drafts, inbox private key). It has a custom `equals` (byte array) and `schemaVersion` with `normalized()` for upgrades: new fields must have defaults so old vaults and backups still deserialize. `Ledger`/`Periods` (budget month starts on the actual salary day), `Budget` (envelopes, "disponible réel"), `Classifier` (rules learned from user corrections, then keywords), `Recurring`, `Defaults`.
- `crypto/`, `vault/`: AES-256-GCM data key (DEK), Argon2id password envelope, BIP39 French 24-word recovery envelope (HKDF). The BIP39 wordlist is loaded by **absolute** resource path; relative resource lookups break under R8.
- `capture/`: `NotificationParser` (bank notification heuristics), `CaptureConfig` (package whitelist), `Inbox` (X25519 sealed box so notifications captured while the vault is locked are stored encrypted to a key that lives inside the vault), `ReceiptParser` (OCR text, receipt to amount/merchant/date).
- `importer/`: CSV/OFX statement parsing (header detection, then content-based column inference) and `Reconcile` (matches statement lines against captured transactions, dedupes by ref).
- `advice/`: `Advisor` (situation level, recovery steps, savings levers, pace simulations, goal verdicts; illiquid products such as PEL/assurance vie/PEA are never counted as usable savings), `EnvelopePlanner` (adapted 50/30/20).
- `backup/`: encrypted `.butin` backup format.

### `android/app`: thin shell around core
- `BoussoleApp`: owns `VaultService`, `CaptureStore`, the persistent `Nav`, and `appScope`. It locks on `ON_STOP`, with a grace delay and a guard while an external action (file picker, share sheet) is open.
- `vault/VaultService`: the only writer. `update { transform }` runs under a mutex and re-encrypts/persists the whole `VaultData`. Post-open work (draining the inbox) runs in the service's own scope, not a UI scope, so screen changes cannot cancel it. The password envelope is additionally sealed by an Android Keystore key (StrongBox when available, biometric per-use) unlocked through `BiometricPrompt`.
- `capture/CaptureService`: `NotificationListenerService` restricted to the whitelist. It writes to the sealed inbox when locked, and the inbox is drained into the vault on unlock.
- `ui/`: Compose, "Taverne" dark theme (`Theme.kt`). `Shell.kt` defines 3 tabs (Accueil / Dépenses / Objectifs), routes, the `Nav` back stack and `Ctx`. Write through `ctx.update { }`, which runs on `appScope` so a save survives the screen closing. Typed text that must survive a lock or app switch goes through `Drafts.kt` (`rememberDraft`, stored encrypted in `VaultData.drafts`); the onboarding step is persisted in the profile. `Tour.kt` is the guided tour (`Modifier.tourTarget`), shown once per tab via `settings.toursSeen`.

### `android/e2e`: end-to-end test against the release APK
UI Automator drives the installed app by **test tags** (`testTagsAsResourceId`). `Driver.kt` handles tours, system dialogs, scrolling and stale objects, and posts fake bank notifications from the test package (whitelisted only in test builds). When renaming or moving a UI element, keep or update its `testTag`, or the journey breaks. Text inside dialogs needs `exposeTags()`/`dialogTag()`, and text on buttons must be tagged on the inner `Text`.

## Non-negotiable constraints

- **No network**: never add `INTERNET`, `ACCESS_NETWORK_STATE` or any network-capable dependency. The manifest strips them with `tools:node="remove"` and CI checks the final APK.
- **Zero third parties**: no analytics, crash SDKs, cloud services or remote models. ML Kit OCR is the bundled, offline variant. The Google Maven repository is restricted to `com.android`/`com.google`/`androidx` groups in `settings.gradle.kts`.
- Keep `FLAG_SECURE`, `allowBackup=false` and the data-extraction exclusions. Notifications and reminders never show amounts on the lock screen.
- Test-only diagnostics (`BoussoleTrace` logging, crash reports, the e2e package in the capture whitelist) are gated on the `is_test_build` resource and must never log financial data.
- Release signing uses the user's own key via the `BOUSSOLE_KEYSTORE*` env vars. Without them, the APK is a debug-signed "Butin (test)" build, so every new test version must be uninstalled and the data restored from a backup.
