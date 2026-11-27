#!/usr/bin/env python3
"""Garde « zéro réseau, zéro tiers » (hook PreToolUse sur Edit/Write/MultiEdit).

Bloque, avant qu'elle soit écrite, toute modification qui donnerait à Butin un
accès réseau ou une dépendance tierce : c'est l'exigence n° 1 du projet
(docs/SECURITE.md). La CI le vérifie aussi sur l'APK final, mais trop tard.
Code de sortie 2 = modification refusée, avec l'explication sur stderr.
"""
import json
import re
import sys

NETWORK_PERMISSION = re.compile(
    r"<uses-permission[^>]*android\.permission\."
    r"(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE|CHANGE_WIFI_STATE)"
    r"[^>]*>",
    re.S,
)

# Bibliothèques réseau, télémétrie, publicité, crash reporting, et l'OCR ML Kit
# « non embarqué » (qui télécharge son modèle via les services Google).
THIRD_PARTY_DEPENDENCY = re.compile(
    r"okhttp|retrofit|ktor|volley|apollo|grpc|cronet|"
    r"firebase|crashlytics|sentry|bugsnag|appcenter|instabug|acra|"
    r"analytics|mixpanel|amplitude|segment\.io|adjust|appsflyer|branch\.io|"
    r"admob|play-services-ads|play-services-measurement|facebook|onesignal|"
    r"play-services-mlkit",
    re.I,
)

NETWORK_CODE = re.compile(
    r"\bjava\.net\.(URL|Socket|ServerSocket|DatagramSocket|HttpURLConnection|URLConnection)\b|"
    r"\bjavax\.net\.ssl\.|\bHttpURLConnection\b|\bandroid\.net\.(ConnectivityManager|wifi)\b|"
    r"\bjava\.net\.http\b|\bWebView\b"
)


def new_text(tool_input: dict) -> str:
    parts = [tool_input.get("content", ""), tool_input.get("new_string", "")]
    parts += [edit.get("new_string", "") for edit in tool_input.get("edits", []) or []]
    return "\n".join(p for p in parts if p)


def refuse(reason: str) -> None:
    sys.stderr.write(
        "Modification refusée par la garde « zéro réseau, zéro tiers » "
        "(.claude/hooks/garde_reseau.py) : " + reason + "\n"
        "Butin ne doit jamais accéder au réseau ni embarquer de tiers (docs/SECURITE.md). "
        "Trouve une solution locale ; si l'utilisateur exige vraiment ce changement, "
        "c'est à lui de modifier ou de désactiver la garde.\n"
    )
    sys.exit(2)


def main() -> None:
    try:
        event = json.load(sys.stdin)
    except json.JSONDecodeError:
        return
    tool_input = event.get("tool_input") or {}
    path = (tool_input.get("file_path") or "").replace("\\", "/")
    text = new_text(tool_input)
    if not path or not text:
        return

    # Les fichiers de la garde, du test et de la doc citent légitimement ces motifs.
    if "/.claude/" in path or path.endswith(".md") or "/android/e2e/" in path:
        return

    if path.endswith("AndroidManifest.xml"):
        for match in NETWORK_PERMISSION.finditer(text):
            if 'tools:node="remove"' not in match.group(0):
                refuse(f"permission réseau ajoutée au manifeste ({match.group(1)}).")

    if path.endswith((".gradle.kts", ".gradle", "libs.versions.toml")):
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith(("//", "#")):
                continue
            match = THIRD_PARTY_DEPENDENCY.search(stripped)
            if match:
                refuse(f"dépendance réseau ou tierce « {match.group(0)} » dans {path.rsplit('/', 1)[-1]}.")

    if path.endswith((".kt", ".java")) and ("/src/main/" in path):
        match = NETWORK_CODE.search(text)
        if match:
            refuse(f"code réseau « {match.group(0)} » dans {path.rsplit('/', 1)[-1]}.")


if __name__ == "__main__":
    main()
