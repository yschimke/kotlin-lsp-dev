#!/usr/bin/env bash
# Builds an ENHANCED kotlin-lsp server: the pinned release distribution plus our overlay
# providers, registered via a ServiceLoader LanguageServerExtension. Output is a patched
# standalone tarball (and, with --vsix, a VS Code extension).
#
# The overlay never modifies upstream jars: it adds one extension jar to the distribution's
# module path and appends one line to the LanguageServerExtension services file. Our providers
# compile against the shipped jars (their siblings resolve as binary), so no upstream source
# build is needed — see README.md.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(grep -E '^kotlinLspVersion=' "$ROOT/dist.properties" | cut -d= -f2)"
DIST_SRC="$ROOT/build/dist/kotlin-server-$VERSION"
KOTLINC="$ROOT/build/kotlinc/bin/kotlinc"
# `jar` tool (the release JBR is a JRE without it) — prefer JAVA_HOME, fall back to PATH
JAR="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}/bin/jar"
[[ -x "$JAR" ]] || JAR="$(command -v jar)"
OUT="$ROOT/build/server"
STAGE="$OUT/kotlin-server-$VERSION-enhanced"
EXT_JAR_NAME="language-server.overlay.jar"
SERVICES="META-INF/services/com.jetbrains.ls.api.features.LanguageServerExtension"

WANT_VSIX=0
[[ "${1:-}" == "--vsix" ]] && WANT_VSIX=1

"$ROOT/scripts/fetch-dist.sh"
[[ -x "$KOTLINC" ]] || { echo "error: kotlinc missing — run scripts/compile-check.sh once to fetch it" >&2; exit 1; }

# --- 1. compile the overlay (cores + adapters + extension) against the shipped jars ----------
CP="$(find "$DIST_SRC/lib" "$DIST_SRC/plugins" "$DIST_SRC/modules" -name '*.jar' | tr '\n' ':')"
CLASSES="$OUT/classes"
rm -rf "$CLASSES"; mkdir -p "$CLASSES"

echo "[build-server] compiling overlay against $VERSION ..."
mapfile -t SRCS < <(find "$ROOT/overlay" -name '*.kt')
echo "[build-server] $(printf '%s\n' "${SRCS[@]}" | wc -l) overlay source files"
SER_PLUGIN="$(dirname "$KOTLINC")/../lib/kotlinx-serialization-compiler-plugin.jar"
"$KOTLINC" -cp "$CP" \
  -jvm-target 25 -language-version 2.4 -api-version 2.4 \
  -Xplugin="$SER_PLUGIN" \
  -Xcontext-parameters -Xjvm-default=all \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaIdeApi \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi \
  -nowarn -d "$CLASSES" "${SRCS[@]}"

# --- 2. stage a copy of the distribution --------------------------------------------------
echo "[build-server] staging enhanced distribution ..."
rm -rf "$STAGE"
cp -r "$DIST_SRC" "$STAGE"
MODULES_DIR="$STAGE/plugins/kotlin.lsp/lib/modules"
KOTLIN_JAR="$MODULES_DIR/language-server.api.features.impl.kotlin.jar"

# --- 3. inject overlay classes + the services entry INTO the kotlin.lsp module jar ----------
# The class must live in a jar the server's ServiceLoader actually scans; the shipped kotlin
# module jar (which already declares the service) is the reliable, verified location.
( cd "$CLASSES" && "$JAR" uf "$KOTLIN_JAR" . )
TMP_SVC="$OUT/services.txt"
{ unzip -p "$KOTLIN_JAR" "$SERVICES"; echo; echo "overlay.OverlayLanguageServerExtension"; } \
  | awk 'NF' > "$TMP_SVC"
( cd "$OUT" && mkdir -p "$(dirname "$SERVICES")" && cp "$TMP_SVC" "$SERVICES" && "$JAR" uf "$KOTLIN_JAR" "$SERVICES" )
echo "[build-server] injected $(find "$CLASSES" -name '*.class' | wc -l) overlay classes + services entry into $(basename "$KOTLIN_JAR")"

# --- 4. repackage as a tarball ---------------------------------------------------------------
TARBALL="$OUT/kotlin-server-$VERSION-enhanced.tar.gz"
echo "[build-server] packaging $TARBALL ..."
( cd "$OUT" && tar czf "$(basename "$TARBALL")" "$(basename "$STAGE")" )
echo "[build-server] DONE: $TARBALL"

if [[ "$WANT_VSIX" == 1 ]]; then
  echo "[build-server] --vsix: see scripts/build-vsix.sh (packages this staged server into a VSIX)"
  "$ROOT/scripts/build-vsix.sh" "$STAGE" || echo "[build-server] VSIX packaging not yet wired; tarball is ready"
fi
