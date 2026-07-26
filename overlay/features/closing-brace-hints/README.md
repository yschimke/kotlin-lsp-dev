# Closing-brace inlay hints

**Status:** PR-ready + unit-tested, but **temporarily not runnable as an overlay**. Live testing
showed that the provider runs and computes the expected hints, but the client receives none.
Disassembly has since confirmed that inlay-hint dispatch is additive, so the old explanation was
wrong; registration, language mapping, and result `data` metadata are being investigated.
`build-server.sh` skips it (`PR_ONLY` marker) until the live path is fixed and verified.
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
