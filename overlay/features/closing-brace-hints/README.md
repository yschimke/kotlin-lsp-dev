# Closing-brace inlay hints

**Status:** PR-ready + unit-tested, but **not runnable as an overlay**. Inlay-hint dispatch is
**not additive** — the server does not merge a second `LSInlayHintsProvider`, so an added
provider's hints are dropped (verified: the provider runs and computes the right hints, but the
client receives none). It must land upstream by folding this into the built-in Kotlin inlay
provider. `build-server.sh` skips it (`PR_ONLY` marker).
_Tracking: (add the upstream PR/issue URL here once opened)_

## What this adds

A label at the closing `}` of long function/class bodies (e.g. `} fun foo`), which kotlin-lsp's
built-in type/parameter hints do not provide.

## Layout

| Path | Role | Verified by |
|---|---|---|
| `core/…/inlayHints/ClosingBraceHintsComputation.kt` | finds long bodies, labels their closing brace | `test/ClosingBraceHintsTest.kt` |
| `ext/…/LSKotlinClosingBraceInlayHintsProvider.kt` | `LSInlayHintsProvider` adapter (compiles; ineffective as an overlay) | compile-check |

## Upstream target path

- fold the closing-brace logic into
  `features-impl/common/src/com/jetbrains/ls/api/features/impl/common/inlayHints/…` /
  `features-impl/kotlin/src/.../inlayHints/LSKotlinInlayHintsProvider.kt`

---

## Draft PR body

> **[kotlin] Closing-brace inlay hints for long bodies**
>
> Adds a hint at the `}` of a function/class body spanning ≥ N lines (`} fun foo`), to orient in
> long files. Not covered by the existing type/parameter hints.
>
> **Testing** — unit tests over long vs short bodies.
