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
# --jar-only stops after the distributable overlay jar, skipping the ~1.3 GB staged copy of the
# release and its tarball. That is all CI needs: it applies the jar to its own server copy with
# install-overlay.sh, which is the path users actually take.
JAR_ONLY=0
case "${1:-}" in
  --vsix) WANT_VSIX=1 ;;
  --jar-only) JAR_ONLY=1 ;;
  "") ;;
  *) echo "usage: build-server.sh [--vsix | --jar-only]" >&2; exit 2 ;;
esac

"$ROOT/scripts/fetch-dist.sh"
"$ROOT/scripts/fetch-kotlinc.sh"

# --- 1. compile each feature independently against the shipped jars --------------------------
# A feature whose LSP API isn't in the pinned release (e.g. codeLens postdates 262.8190) simply
# fails to compile and is SKIPPED — it stays PR-ready + unit-tested and auto-activates once a
# release ships its API. This is the release-gating that makes the overlay safe across versions.
CP="$(find "$DIST_SRC/lib" "$DIST_SRC/plugins" "$DIST_SRC/modules" -name '*.jar' | tr '\n' ':')"
SER_PLUGIN="$(dirname "$KOTLINC")/../lib/kotlinx-serialization-compiler-plugin.jar"
CLASSES="$OUT/classes"
rm -rf "$CLASSES" "$OUT/services-all.txt"; mkdir -p "$CLASSES"; : > "$OUT/services-all.txt"

# Compile our distribution entry point separately from the release-gated features. The native
# launcher will call this class, which delegates to the official MainImpl after providing a stable
# seam for overlay-owned startup behavior.
mapfile -t launcher_srcs < <(find "$ROOT/launcher" -name '*.kt')
[[ ${#launcher_srcs[@]} -gt 0 ]] || { echo "error: no overlay launcher sources found" >&2; exit 1; }
"$KOTLINC" -cp "$CP" -jvm-target 25 -language-version 2.4 -api-version 2.4 \
    -nowarn -d "$CLASSES" "${launcher_srcs[@]}"
echo "[build-server]   ✓ overlay server main"

compile_feature() {
  local feat="$1" name; name="$(basename "$feat")"
  if [[ -f "$feat/PR_ONLY" ]]; then
    echo "[build-server]   ⊘ $name PR-ONLY — $(head -1 "$feat/PR_ONLY")"
    return 0
  fi
  mapfile -t srcs < <(find "$feat/core" "$feat/ext" -name '*.kt' 2>/dev/null)
  [[ ${#srcs[@]} -eq 0 ]] && return 0
  local fout="$OUT/feat-$name"; rm -rf "$fout"; mkdir -p "$fout"
  if "$KOTLINC" -cp "$CP" -jvm-target 25 -language-version 2.4 -api-version 2.4 \
      -Xplugin="$SER_PLUGIN" -Xcontext-parameters -Xjvm-default=all \
      -opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi \
      -opt-in=org.jetbrains.kotlin.analysis.api.KaIdeApi \
      -opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi \
      -nowarn -d "$fout" "${srcs[@]}" 2>"$OUT/feat-$name.log"; then
    cp -r "$fout/." "$CLASSES/"
    [[ -f "$feat/resources/$SERVICES" ]] && cat "$feat/resources/$SERVICES" >> "$OUT/services-all.txt"
    echo "[build-server]   ✓ $name (runnable on $VERSION)"
  else
    echo "[build-server]   ⊘ $name SKIPPED — LSP API not in $VERSION (PR-ready, not runnable here; see build/feat-$name.log)"
  fi
}

echo "[build-server] compiling features against $VERSION ..."
for feat in "$ROOT"/overlay/features/*/; do compile_feature "$feat"; done
FEATURE_CLASSES="$(find "$CLASSES" -name '*.class' | wc -l)"
[[ "$FEATURE_CLASSES" -gt 0 ]] || { echo "error: no feature compiled against $VERSION" >&2; exit 1; }

# --- 1b. package the DISTRIBUTABLE overlay jar (our Apache-2.0 classes only) ------------------
# This is the only artifact safe to publish: it contains no JetBrains binaries. Users apply it
# to a server they download themselves via scripts/install-overlay.sh.
OVERLAY_JAR="$OUT/language-server.overlay-$VERSION.jar"
OVJ="$OUT/overlay-jar"; rm -rf "$OVJ"; mkdir -p "$OVJ/$(dirname "$SERVICES")"
cp -r "$CLASSES/." "$OVJ/"
awk 'NF' "$OUT/services-all.txt" > "$OVJ/$SERVICES"
( cd "$OVJ" && "$JAR" cf "$OVERLAY_JAR" . )
echo "[build-server] distributable overlay jar: $OVERLAY_JAR ($(du -h "$OVERLAY_JAR" | cut -f1))"

if [[ "$JAR_ONLY" == 1 ]]; then
  echo "[build-server] --jar-only: skipping the staged enhanced distribution"
  exit 0
fi

# --- 2. stage a copy of the distribution (LOCAL test artifact — do NOT publish) --------------
echo "[build-server] staging enhanced distribution ..."
rm -rf "$STAGE"
cp -r "$DIST_SRC" "$STAGE"
MODULES_DIR="$STAGE/plugins/kotlin.lsp/lib/modules"
KOTLIN_JAR="$MODULES_DIR/language-server.api.features.impl.kotlin.jar"

# Put the overlay on the launcher's boot class path and select our delegating main. The feature
# classes are still injected into the Kotlin module below so ServiceLoader sees them in its normal
# module/class-loader context.
cp "$OVERLAY_JAR" "$STAGE/lib/$EXT_JAR_NAME"
"$ROOT/scripts/configure-main.py" "$STAGE/product-info.json" "$EXT_JAR_NAME"

# --- 3. inject overlay classes + the services entries INTO the kotlin.lsp module jar --------
# The classes must live in a jar the server's ServiceLoader actually scans; the shipped kotlin
# module jar (which already declares the service) is the reliable, verified location.
( cd "$CLASSES" && "$JAR" uf "$KOTLIN_JAR" . )
TMP_SVC="$OUT/services.txt"
{ unzip -p "$KOTLIN_JAR" "$SERVICES"; echo; cat "$OUT/services-all.txt"; } | awk 'NF' > "$TMP_SVC"
( cd "$OUT" && mkdir -p "$(dirname "$SERVICES")" && cp "$TMP_SVC" "$SERVICES" && "$JAR" uf "$KOTLIN_JAR" "$SERVICES" )
echo "[build-server] injected $FEATURE_CLASSES overlay classes + $(grep -c . "$OUT/services-all.txt") extension(s) into $(basename "$KOTLIN_JAR")"

# --- 4. repackage as a tarball ---------------------------------------------------------------
TARBALL="$OUT/kotlin-server-$VERSION-enhanced.tar.gz"
echo "[build-server] packaging $TARBALL ..."
( cd "$OUT" && tar czf "$(basename "$TARBALL")" "$(basename "$STAGE")" )
echo "[build-server] DONE: $TARBALL"

if [[ "$WANT_VSIX" == 1 ]]; then
  echo "[build-server] --vsix: see scripts/build-vsix.sh (packages this staged server into a VSIX)"
  "$ROOT/scripts/build-vsix.sh" "$STAGE" || echo "[build-server] VSIX packaging not yet wired; tarball is ready"
fi
