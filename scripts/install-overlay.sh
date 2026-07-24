#!/usr/bin/env bash
# Applies the overlay (our added LSP providers) to an OFFICIAL kotlin-lsp server you downloaded.
#
# We distribute only our own Apache-2.0 classes (language-server.overlay-*.jar) — never JetBrains'
# proprietary server binaries. This script injects those classes + their ServiceLoader entries
# into your local copy of the server, in place.
#
# Usage: install-overlay.sh <path-to-unpacked-kotlin-server> [path-to-overlay.jar]
#   <server> is the dir containing bin/intellij-server (e.g. kotlin-server-262.8190.0/).

set -euo pipefail

SERVER="${1:?usage: install-overlay.sh <server-dir> [overlay.jar]}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(grep -E '^kotlinLspVersion=' "$ROOT/dist.properties" | cut -d= -f2)"
OVERLAY_JAR="${2:-$ROOT/build/server/language-server.overlay-$VERSION.jar}"
SERVICES="META-INF/services/com.jetbrains.ls.api.features.LanguageServerExtension"
JAR="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}/bin/jar"; [[ -x "$JAR" ]] || JAR="$(command -v jar)"

[[ -f "$SERVER/bin/intellij-server" ]] || { echo "error: $SERVER is not an unpacked kotlin-lsp server" >&2; exit 1; }
[[ -f "$OVERLAY_JAR" ]] || { echo "error: overlay jar not found: $OVERLAY_JAR (run scripts/build-server.sh)" >&2; exit 1; }

KOTLIN_JAR="$SERVER/plugins/kotlin.lsp/lib/modules/language-server.api.features.impl.kotlin.jar"
[[ -f "$KOTLIN_JAR" ]] || { echo "error: kotlin module jar not found in $SERVER" >&2; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
( cd "$WORK" && "$JAR" xf "$OVERLAY_JAR" )

# merge the overlay's services entries with the ones already in the server jar
MERGED="$WORK/merged-services"
{ unzip -p "$KOTLIN_JAR" "$SERVICES" 2>/dev/null || true; echo; cat "$WORK/$SERVICES"; } | awk 'NF' | sort -u > "$MERGED"

# inject class trees (every top-level dir except META-INF), then the merged services file
( cd "$WORK" && for d in */; do [[ "$d" == "META-INF/" ]] && continue; "$JAR" uf "$KOTLIN_JAR" "$d"; done )
mkdir -p "$WORK/$(dirname "$SERVICES")"; cp "$MERGED" "$WORK/$SERVICES"
( cd "$WORK" && "$JAR" uf "$KOTLIN_JAR" "$SERVICES" )

echo "Overlay applied to $SERVER"
echo "Registered extensions:"; sed 's/^/  /' "$MERGED"
