#!/usr/bin/env bash
# Compile le cœur après chaque modification d'un fichier Kotlin de core/ (hook
# PostToolUse). Le cœur compile avec allWarningsAsErrors : une variable inutilisée
# ferait échouer la CI. Code de sortie 2 = erreurs renvoyées à Claude sur stderr.
set -uo pipefail

path=$(jq -r '.tool_input.file_path // empty')
case "$path" in
  */core/*.kt) ;;
  *) exit 0 ;;
esac

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
output=$(./gradlew -p core compileKotlin compileTestKotlin -q --console=plain 2>&1)
status=$?
[ "$status" -eq 0 ] && exit 0
output=$(grep -v '^Picked up JAVA_TOOL_OPTIONS' <<<"$output")

# Panne de téléchargement des dépendances (réseau, quota) : ce n'est pas une
# erreur du code, on ne bloque pas.
if grep -qE "Could not resolve|Could not GET|Could not download|429|Unable to tunnel" <<<"$output"; then
  echo "Compilation du cœur impossible (dépendances inaccessibles) : vérification sautée." >&2
  exit 0
fi

{
  echo "La compilation du cœur échoue après cette modification (allWarningsAsErrors) :"
  grep -E "^(e|w): " <<<"$output" | head -n 20
  grep -qE "^(e|w): " <<<"$output" || tail -n 20 <<<"$output"
} >&2
exit 2
