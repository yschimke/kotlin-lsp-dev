#!/usr/bin/env bash
# Fails the build when the shipped server has grown its own implementation of a feature we carry.
#
# The overlay's lifecycle says: when a release ships the feature itself, delete the directory.
# Nothing enforced that, so a superseded feature would quietly keep registering a second provider
# next to the built-in one -- which at best duplicates it and at worst breaks the request, since
# several dispatch paths reject more than one provider.
#
# This checks the *distribution*, not our source: for each feature that declares one, a marker
# names the class whose presence in the release means upstream now implements it.
#
#   overlay/features/<name>/SUPERSEDED_BY   one class name per line, # comments allowed
#
# Related but different: `smoke-test.py --stock` catches the same situation behaviourally, by
# requiring every check to fail against an unmodified server. That fires only when the feature is
# both shipped *and* working; this fires as soon as the class appears, which is the point at which
# we should be looking.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FEATURES="$ROOT/overlay/features"
VERSION="${KOTLIN_LSP_VERSION:-$(grep -E '^kotlinLspVersion=' "$ROOT/dist.properties" | cut -d= -f2)}"
DIST="$ROOT/build/dist/kotlin-server-$VERSION"

if [[ ! -d "$DIST" ]]; then
  echo "[superseded] $DIST not unpacked; skipping (run scripts/fetch-dist.sh)"
  exit 0
fi

# One pass over the jars: listing every entry once is far cheaper than grepping per marker.
INDEX="$ROOT/build/superseded-classes.txt"
mkdir -p "$(dirname "$INDEX")"
find "$DIST/lib" "$DIST/plugins" "$DIST/modules" -name '*.jar' 2>/dev/null \
  | while read -r jar; do unzip -l "$jar" 2>/dev/null | awk '{print $4}'; done \
  | grep -E '\.class$' | sort -u > "$INDEX"

failed=0
checked=0
for marker in "$FEATURES"/*/SUPERSEDED_BY; do
  [[ -f "$marker" ]] || continue
  feature="$(basename "$(dirname "$marker")")"
  while read -r line; do
    line="${line%%#*}"; line="$(echo "$line" | xargs)"
    [[ -n "$line" ]] || continue
    checked=$((checked + 1))
    # Match the class file anywhere in the jars, by its simple or fully-qualified name.
    pattern="$(echo "$line" | tr '.' '/')"
    if grep -qE "(^|/)${pattern}\.class$" "$INDEX"; then
      echo "error: $feature is superseded -- $line now ships in $VERSION" >&2
      failed=1
    fi
  done < "$marker"
done

if (( failed != 0 )); then
  cat >&2 <<'EOF'

The release now contains a class that a feature here declared as its replacement. Registering a
second provider alongside the built-in one duplicates it at best and breaks the request at worst.

Verify with `scripts/smoke-test.py <stock-server> --stock`: if the feature's check now passes
against an unmodified server, the server does the job and the feature directory should be deleted
-- sources, tests and smoke check together, which is the whole edit.
EOF
  exit 1
fi

echo "[superseded] $checked replacement class(es) checked, none shipped in $VERSION"
