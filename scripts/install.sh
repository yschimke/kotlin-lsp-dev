#!/usr/bin/env bash
# One command from a clean checkout to a runnable, enhanced Kotlin language server.
#
#   scripts/install.sh                     install to ~/.local/share/kotlin-lsp-enhanced
#   scripts/install.sh --to DIR            install to DIR
#   scripts/install.sh --vscode            install into the bundled server of the official
#                                          VS Code extension, in place
#   scripts/install.sh --version 262.9593.0  build against a release other than the pinned one
#   scripts/install.sh --print-config      just print the editor configuration and exit
#
# What it does: downloads the pinned official release, builds the overlay against it, applies
# the overlay, and installs bin/enhanced-server. The result is a self-contained server directory
# that any LSP client can start over stdio, or that can listen on a TCP port for VS Code.
#
# Licensing: the official server is downloaded from JetBrains' CDN to your machine and stays
# there. Only our own Apache-2.0 classes are built here; nothing JetBrains-owned is published.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The pinned release, overridable for a one-off build against a different one --
# scripts/install.sh --version <v> sets this. dist.properties stays the repository's pin.
VERSION="${KOTLIN_LSP_VERSION:-$(grep -E '^kotlinLspVersion=' "$ROOT/dist.properties" | cut -d= -f2)}"
TARGET="${XDG_DATA_HOME:-$HOME/.local/share}/kotlin-lsp-enhanced"
MODE=standalone

while [[ $# -gt 0 ]]; do
  case "$1" in
    --to) TARGET="${2:?--to needs a directory}"; shift 2 ;;
    # For running a release other than the repository's pin -- e.g. when the newest release has
    # a regression that breaks your project's import. Features whose API is absent from that
    # release are skipped by build-server.sh, exactly as for the pin.
    --version) VERSION="${2:?--version needs a build number}"; export KOTLIN_LSP_VERSION="$VERSION"; shift 2 ;;
    --vscode) MODE=vscode; shift ;;
    --print-config) MODE=print-config; shift ;;
    -h|--help) sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

# The upstream sources target JVM 25, so the overlay must be compiled and run on 25+.
find_jdk25() {
  local candidate
  for candidate in "${JAVA_HOME:-}" /usr/lib/jvm/java-25-openjdk /usr/lib/jvm/java-26-openjdk \
                   "$HOME/.local/share/mise/installs/java/25"* /usr/lib/jvm/temurin-25*; do
    [[ -n "$candidate" && -x "$candidate/bin/javac" ]] || continue
    local major
    major="$("$candidate/bin/javac" -version 2>&1 | sed -E 's/javac ([0-9]+).*/\1/')"
    [[ "$major" =~ ^[0-9]+$ && "$major" -ge 25 ]] && { echo "$candidate"; return 0; }
  done
  return 1
}

find_vscode_server() {
  local dir
  for dir in "$HOME/.vscode/extensions/jetbrains.kotlin-server-"*/server \
             "$HOME/.vscode-server/extensions/jetbrains.kotlin-server-"*/server \
             "$HOME/.vscode-insiders/extensions/jetbrains.kotlin-server-"*/server; do
    [[ -f "$dir/bin/intellij-server" ]] && { echo "$dir"; return 0; }
  done
  return 1
}

print_config() {
  local server="$1"
  cat <<EOF

  Installed: $server
  Start it:  $server/bin/enhanced-server --stdio
             $server/bin/enhanced-server --socket 9999

  VS Code (official "Kotlin by JetBrains" extension), settings.json:

      {
        "intellij.dev.serverPort": 9999
      }

  Then run the server yourself before reloading the window:

      $server/bin/enhanced-server --socket 9999

  With serverPort set the extension dials that port instead of starting its bundled server, so
  it talks to the enhanced server and gets the proxy repairs (Format Selection) as well as the
  added in-process features.

  Any other LSP client: run 'bin/enhanced-server --stdio' as the server command.
EOF
}

if [[ "$MODE" == "print-config" ]]; then
  print_config "$TARGET"
  exit 0
fi

JDK="$(find_jdk25)" || {
  echo "error: no JDK 25+ found. Set JAVA_HOME to one (upstream targets JVM 25)." >&2
  exit 1
}
export JAVA_HOME="$JDK"
echo "[install] JDK:     $JAVA_HOME"
echo "[install] release: $VERSION"
PINNED="$(grep -E '^kotlinLspVersion=' "$ROOT/dist.properties" | cut -d= -f2)"
[[ "$VERSION" == "$PINNED" ]] || echo "[install] note:    the repository pin is $PINNED; building against $VERSION"

"$ROOT/scripts/fetch-dist.sh"
"$ROOT/scripts/build-server.sh" --jar-only

OVERLAY_JAR="$ROOT/build/server/language-server.overlay-$VERSION.jar"
[[ -f "$OVERLAY_JAR" ]] || { echo "error: overlay jar was not produced: $OVERLAY_JAR" >&2; exit 1; }

if [[ "$MODE" == "vscode" ]]; then
  SERVER="$(find_vscode_server)" || {
    echo "error: no installed 'JetBrains.kotlin-server' extension found." >&2
    echo "       Install it from the VS Code Marketplace first, or use --to DIR instead." >&2
    exit 1
  }
  echo "[install] target:  $SERVER (VS Code extension, in place)"
else
  SERVER="$TARGET"
  echo "[install] target:  $SERVER"
  rm -rf "$SERVER"
  mkdir -p "$(dirname "$SERVER")"
  cp -r "$ROOT/build/dist/kotlin-server-$VERSION" "$SERVER"

  # fetch-dist.sh drops the bundled JBR because nothing in the build needs it. A standalone
  # install does: without it the server falls back to whatever `java` is on PATH, and the
  # release targets JVM 25. Restore it from the archive so the install owns its runtime.
  if [[ ! -x "$SERVER/jbr/bin/java" ]]; then
    ARCHIVE="$ROOT/build/dist/kotlin-server-$VERSION.tar.gz"
    if [[ -f "$ARCHIVE" ]]; then
      echo "[install] restoring the bundled JBR ..."
      tar xzf "$ARCHIVE" -C "$SERVER" --strip-components=1 "kotlin-server-$VERSION/jbr" 2>/dev/null \
        || tar xzf "$ARCHIVE" -C "$SERVER" --strip-components=1 --wildcards '*/jbr/*' 2>/dev/null \
        || echo "[install] warning: could not restore the JBR; the server will need a JDK 25 on PATH" >&2
    else
      echo "[install] warning: $ARCHIVE is gone, so no JBR to restore; a JDK 25 must be on PATH" >&2
    fi
  fi
fi

"$ROOT/scripts/install-overlay.sh" "$SERVER" "$OVERLAY_JAR"
print_config "$SERVER"
