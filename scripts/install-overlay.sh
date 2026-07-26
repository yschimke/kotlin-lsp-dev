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
# The injection steps run from a temp dir, so both paths must survive the cd.
[[ -d "$SERVER" ]] && SERVER="$(cd "$SERVER" && pwd)"
[[ -f "$OVERLAY_JAR" ]] && OVERLAY_JAR="$(cd "$(dirname "$OVERLAY_JAR")" && pwd)/$(basename "$OVERLAY_JAR")"
SERVICES="META-INF/services/com.jetbrains.ls.api.features.LanguageServerExtension"
JAR="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}/bin/jar"; [[ -x "$JAR" ]] || JAR="$(command -v jar)"

[[ -f "$SERVER/bin/intellij-server" ]] || { echo "error: $SERVER is not an unpacked kotlin-lsp server" >&2; exit 1; }
[[ -f "$OVERLAY_JAR" ]] || { echo "error: overlay jar not found: $OVERLAY_JAR (run scripts/build-server.sh)" >&2; exit 1; }

KOTLIN_JAR="$SERVER/plugins/kotlin.lsp/lib/modules/language-server.api.features.impl.kotlin.jar"
[[ -f "$KOTLIN_JAR" ]] || { echo "error: kotlin module jar not found in $SERVER" >&2; exit 1; }
PRODUCT_INFO="$SERVER/product-info.json"
[[ -f "$PRODUCT_INFO" ]] || { echo "error: product-info.json not found in $SERVER" >&2; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
( cd "$WORK" && "$JAR" xf "$OVERLAY_JAR" )

# merge the overlay's services entries with the ones already in the server jar
MERGED="$WORK/merged-services"
{ unzip -p "$KOTLIN_JAR" "$SERVICES" 2>/dev/null || true; echo; cat "$WORK/$SERVICES"; } | awk 'NF' | sort -u > "$MERGED"

# inject class trees (every top-level dir except META-INF), then the merged services file
( cd "$WORK" && for d in */; do [[ "$d" == "META-INF/" ]] && continue; "$JAR" uf "$KOTLIN_JAR" "$d"; done )
mkdir -p "$WORK/$(dirname "$SERVICES")"; cp "$MERGED" "$WORK/$SERVICES"
( cd "$WORK" && "$JAR" uf "$KOTLIN_JAR" "$SERVICES" )

# The native product launcher reads this jar list and main class from product-info.json. Keep the
# official native/JVM bootstrap, but enter through our small Kotlin main before delegating to the
# shipped server entry point.
LAUNCHER_JAR_NAME="language-server.overlay.jar"
cp "$OVERLAY_JAR" "$SERVER/lib/$LAUNCHER_JAR_NAME"
"$ROOT/scripts/configure-main.py" "$PRODUCT_INFO" "$LAUNCHER_JAR_NAME"

echo "Overlay applied to $SERVER"
echo "Server main: overlay.server.KotlinLspServer -> com.intellij.ls.server.MainImpl"
echo "Registered extensions:"; sed 's/^/  /' "$MERGED"
