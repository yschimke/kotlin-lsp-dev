#!/usr/bin/env bash
# Type-checks the kotlin-lsp checkout against the pinned release distribution.
#
# This is the *only* automated check that sees the real upstream sources against the real
# closed-source jars -- the Gradle test build compiles a curated slice against a shimmed
# interface instead. Neither is a substitute for the other:
#
#   compile-check.sh   real interfaces + real closed jars, no tests
#   ./gradlew test     real MoveFilesProcessor executing, shimmed interface
#
# Upstream master runs ahead of the newest public release, so unrelated files legitimately
# fail. Those are listed in compile-check-baseline.txt; this script only fails on errors in
# files that are NOT baselined.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LSP="$ROOT/kotlin-lsp"
VERSION="$(grep -E '^kotlinLspVersion=' "$ROOT/dist.properties" | cut -d= -f2)"
DIST="$ROOT/build/dist/kotlin-server-$VERSION"
KOTLINC_VERSION=2.4.10
KOTLINC="$ROOT/build/kotlinc/bin/kotlinc"
BASELINE="$ROOT/compile-check-baseline.txt"

[[ -d "$LSP/features-impl" ]] || { echo "error: $LSP is not a kotlin-lsp checkout (fix the symlink)" >&2; exit 1; }

"$ROOT/scripts/fetch-dist.sh"

if [[ ! -x "$KOTLINC" ]]; then
  echo "[compile-check] fetching kotlinc $KOTLINC_VERSION..."
  mkdir -p "$ROOT/build"
  curl -fL --progress-bar -o "$ROOT/build/kotlinc.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLINC_VERSION/kotlin-compiler-$KOTLINC_VERSION.zip"
  unzip -q -o "$ROOT/build/kotlinc.zip" -d "$ROOT/build"
fi

# Every jar in the distribution except the modules we compile from source -- otherwise the
# stale copies shadow the checkout.
CP="$(find "$DIST/lib" "$DIST/plugins" "$DIST/modules" -name '*.jar' \
      | grep -vE 'language-server\.api\.features|language-server-plugins-(kotlin|java-base|editorconfig)-lsp' \
      | tr '\n' ':')"

OUT="$ROOT/build/compile-check"
mkdir -p "$OUT"
ERRS="$ROOT/build/compile-check-errors.txt"

echo "[compile-check] compiling against $VERSION ..."
set +e
"$KOTLINC" -cp "$CP" \
  -jvm-target 25 -language-version 2.4 -api-version 2.4 \
  -Xcontext-parameters -Xjvm-default=all -Xwhen-guards \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaIdeApi \
  -opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi \
  -opt-in=kotlin.contracts.ExperimentalContracts \
  -nowarn -d "$OUT" \
  "$LSP/api.features/src" \
  "$LSP/features-impl/common/src" 2>&1 \
  | grep -E '^[^ ]+\.kt:[0-9]+:[0-9]+: error' \
  | sed -E 's|.*/(api\.features\|features-impl)/|\1/|' > "$ERRS"
set -e

total="$(wc -l < "$ERRS" | tr -d ' ')"
files_with_errors="$(cut -d: -f1 "$ERRS" | sort -u)"
baselined="$(grep -vE '^\s*#|^\s*$' "$BASELINE" 2>/dev/null | sort -u || true)"
unexpected="$(comm -23 <(echo "$files_with_errors") <(echo "$baselined") | grep -v '^$' || true)"

echo
echo "[compile-check] $total error(s) in $(echo "$files_with_errors" | grep -cv '^$') file(s); $(echo "$baselined" | grep -cv '^$') file(s) baselined as known skew."

if [[ -n "$unexpected" ]]; then
  echo
  echo "FAIL -- errors in files that are not baselined:"
  while read -r f; do
    [[ -z "$f" ]] && continue
    echo "  $f"
    grep "^$f:" "$ERRS" | sed 's/^/      /'
  done <<< "$unexpected"
  echo
  echo "If these are more upstream drift rather than your change, add them to compile-check-baseline.txt."
  exit 1
fi

echo "PASS -- no errors outside the known-skew baseline."
