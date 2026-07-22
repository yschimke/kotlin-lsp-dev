#!/usr/bin/env bash
# Downloads and extracts the pinned kotlin-lsp release distribution.
#
# The release archive is the only public artifact that carries the closed-source
# `//language-server/...` modules (analyzer, api.impl.analyzer, dap/platform) as plain jars,
# so it is what `compile-check.sh` type-checks against.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(grep -E '^kotlinLspVersion=' "$ROOT/dist.properties" | cut -d= -f2)"
BASE_URL="https://download-cdn.jetbrains.com/language-server/kotlin-server"
DIST_ROOT="$ROOT/build/dist"
DIST_DIR="$DIST_ROOT/kotlin-server-$VERSION"

if [[ "${1:-}" == "--check" ]]; then
  echo "pinned: $VERSION"
  echo "probing for newer builds on the CDN..."
  for probe in 262.9000.0 262.9500.0 262.10000.0 263.1000.0; do
    code="$(curl -s -o /dev/null -w '%{http_code}' -I "$BASE_URL/$probe/kotlin-server-$probe.tar.gz")"
    echo "  $probe -> HTTP $code"
  done
  echo "A 404 everywhere means the pin is already the newest public build."
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
