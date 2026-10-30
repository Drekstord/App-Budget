#!/usr/bin/env bash
# Tests de bout en bout sur émulateur : installe l'APK final (celui qui sera
# publié) et le module de test, prépare un code de verrouillage et un doigt
# virtuel, puis lance le parcours. Utilisé par la CI (.github/workflows/android.yml).
set -euo pipefail

APK=$(ls dist/*.apk)
TEST_APK=android/e2e/build/outputs/apk/debug/e2e-debug.apk
mkdir -p e2e-results

# Écran toujours allumé, code de verrouillage requis pour la biométrie.
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb shell locksettings set-pin 1111
adb shell input keyevent 82 || true
# Laisser le système finir de démarrer (évite les « … ne répond pas »).
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 20

adb install -r "$APK"
adb install -r "$TEST_APK"
# Le test publie de fausses notifications bancaires, que l'app doit capter.
adb shell pm grant app.boussole.e2e android.permission.POST_NOTIFICATIONS
adb shell cmd notification allow_listener app.boussole/app.boussole.capture.CaptureService
echo "Écouteurs de notifications autorisés : $(adb shell settings get secure enabled_notification_listeners)"

# Doigt virtuel posé en continu : sert à l'enregistrement de l'empreinte, puis
# valide chaque demande d'empreinte de l'app.
( while true; do adb -e emu finger touch 1 >/dev/null 2>&1 || true; sleep 1; done ) &
TOUCH_PID=$!
trap 'kill $TOUCH_PID 2>/dev/null || true' EXIT

adb logcat -c
set +e
adb shell am instrument -w -r app.boussole.e2e/androidx.test.runner.AndroidJUnitRunner | tee e2e-results/instrument.txt
set -e
adb logcat -d > e2e-results/logcat.txt

echo "::group::Écrans parcourus par le test (logcat)"
grep "BoussoleE2E" e2e-results/logcat.txt | tail -n 60 || true
echo "::endgroup::"

echo "::group::Journal technique de l'app (versions de test)"
grep "BoussoleTrace" e2e-results/logcat.txt | tail -n 60 || true
echo "::endgroup::"

echo "::group::Plantages de l'app (logcat)"
grep -n -A 40 "FATAL EXCEPTION" e2e-results/logcat.txt || echo "Aucun plantage."
echo "::endgroup::"

if grep -q "^OK (" e2e-results/instrument.txt; then
  echo "Parcours de bout en bout réussi."
else
  echo "::error::Le parcours de bout en bout a échoué (voir ci-dessus)."
  exit 1
fi
