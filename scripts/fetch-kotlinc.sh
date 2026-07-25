#!/usr/bin/env bash
# Downloads the standalone Kotlin compiler used by compile-check.sh and build-server.sh.
#
# Both scripts compile against the release jars with a compiler that matches upstream's
# language level, not whatever kotlinc happens to be on PATH — so it is pinned here.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_VERSION=2.4.10
KOTLINC="$ROOT/build/kotlinc/bin/kotlinc"

if [[ -x "$KOTLINC" ]]; then
  exit 0
fi

echo "[fetch-kotlinc] fetching kotlinc $KOTLINC_VERSION..."
mkdir -p "$ROOT/build"
curl -fL --progress-bar -o "$ROOT/build/kotlinc.zip" \
  "https://github.com/JetBrains/kotlin/releases/download/v$KOTLINC_VERSION/kotlin-compiler-$KOTLINC_VERSION.zip"
unzip -q -o "$ROOT/build/kotlinc.zip" -d "$ROOT/build"
