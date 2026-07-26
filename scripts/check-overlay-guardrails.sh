#!/usr/bin/env bash
# Reject provider types that the pinned server cannot safely compose with its built-ins.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FEATURES="$ROOT/overlay/features"

# These interface names are deliberately checked in source, before fetching the distribution or
# invoking kotlinc. Registering any implementation is fatal at request time on the pinned release.
forbidden=(
  LSCompletionProvider
  LSFormattingProvider
  LSSignatureHelpProvider
)

failed=0
for provider in "${forbidden[@]}"; do
  mapfile -t matches < <(rg --files-with-matches --glob '*.kt' "\\b${provider}\\b" "$FEATURES" || true)
  if (( ${#matches[@]} > 0 )); then
    printf 'error: forbidden overlay provider %s found in:\n' "$provider" >&2
    printf '  %s\n' "${matches[@]#"$ROOT/"}" >&2
    failed=1
  fi
done

if (( failed != 0 )); then
  cat >&2 <<'EOF'
The pinned server already has providers for completion, signature help, and formatting.
Adding another breaks the entire request instead of augmenting it; see AGENTS.md.
EOF
  exit 1
fi

echo "[guardrails] no forbidden overlay providers"
