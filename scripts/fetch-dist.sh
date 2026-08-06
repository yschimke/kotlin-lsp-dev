#!/usr/bin/env bash
# Downloads and extracts the pinned kotlin-lsp release distribution.
#
# The release archive is the only public artifact that carries the closed-source
# `//language-server/...` modules (analyzer, api.impl.analyzer, dap/platform) as plain jars,
# so it is what `compile-check.sh` type-checks against.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The pinned release, overridable for a one-off build against a different one --
# scripts/install.sh --version <v> sets this. dist.properties stays the repository's pin.
VERSION="${KOTLIN_LSP_VERSION:-$(grep -E '^kotlinLspVersion=' "$ROOT/dist.properties" | cut -d= -f2)}"
BASE_URL="https://download-cdn.jetbrains.com/language-server/kotlin-server"
DIST_ROOT="$ROOT/build/dist"
DIST_DIR="$DIST_ROOT/kotlin-server-$VERSION"

if [[ "${1:-}" == "--check" ]]; then
  # Build numbers are not enumerable and the CDN has no index, so guessing them finds nothing --
  # that is how this repo sat on 262.8190.0 while 263.2689.0 was published. The reliable sources,
  # in order of authority:
  #
  #   1. An installed "JetBrains.kotlin-server" extension: server/build.txt is the exact build,
  #      and JetBrains ships builds there well before cutting a GitHub release.
  #   2. The VS Code Marketplace: gives the newest extension version, though not the build inside
  #      it -- install/update the extension and re-run to learn the build.
  #   3. The GitHub releases, which lag both.
  echo "pinned: $VERSION"
  echo

  echo "installed VS Code extensions:"
  found_local=0
  for dir in "$HOME/.vscode/extensions/jetbrains.kotlin-server-"*/server \
             "$HOME/.vscode-server/extensions/jetbrains.kotlin-server-"*/server \
             "$HOME/.vscode-insiders/extensions/jetbrains.kotlin-server-"*/server; do
    [[ -f "$dir/build.txt" ]] || continue
    found_local=1
    build="$(sed -E 's/^[A-Za-z]+-//' "$dir/build.txt" | tr -d '[:space:]')"
    code="$(curl -s -o /dev/null -w '%{http_code}' -I "$BASE_URL/$build/kotlin-server-$build.tar.gz")"
    marker=""; [[ "$build" == "$VERSION" ]] && marker="  (== pinned)"
    echo "  $build  standalone tarball: HTTP $code$marker  <- $(dirname "$dir")"
  done
  [[ "$found_local" == 1 ]] || echo "  none found"
  echo

  echo "newest on the VS Code Marketplace:"
  curl -s -X POST "https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json;api-version=7.2-preview.1" \
    -d '{"filters":[{"criteria":[{"filterType":7,"value":"JetBrains.kotlin-server"}]}],"flags":914}' \
    2>/dev/null \
    | python3 -c 'import json,sys
try:
    d = json.load(sys.stdin)
    versions = {v["version"] for v in d["results"][0]["extensions"][0]["versions"]}
    print("  extension " + ", ".join(sorted(versions, reverse=True)[:3]))
    print("  (the bundled build is inside the .vsix -- install or update it, then re-run --check)")
except Exception:
    print("  unavailable")' || echo "  unavailable"
  echo

  echo "newest GitHub release (lags the CDN):"
  curl -s "https://api.github.com/repos/Kotlin/kotlin-lsp/releases/latest" 2>/dev/null \
    | python3 -c 'import json,sys
try:
    print("  " + json.load(sys.stdin)["tag_name"])
except Exception:
    print("  unavailable")' || echo "  unavailable"
  exit 0
fi

if [[ -d "$DIST_DIR/lib" ]]; then
  echo "[fetch-dist] already extracted: $DIST_DIR"
  exit 0
fi

mkdir -p "$DIST_ROOT"
ARCHIVE="$DIST_ROOT/kotlin-server-$VERSION.tar.gz"

if [[ ! -f "$ARCHIVE" ]]; then
  echo "[fetch-dist] downloading $VERSION (~376 MB)..."
  curl -fL --progress-bar -o "$ARCHIVE" "$BASE_URL/$VERSION/kotlin-server-$VERSION.tar.gz"
fi

echo "[fetch-dist] extracting (skipping the bundled JBR)..."
tar xzf "$ARCHIVE" -C "$DIST_ROOT" --exclude='*/jbr/*'
echo "[fetch-dist] ready: $DIST_DIR"
