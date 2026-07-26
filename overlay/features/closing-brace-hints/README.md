# Closing-brace inlay hints

**Status:** Runnable on pinned release `262.8190.0`, unit-tested, and verified over stdio against
a patched server. Inlay-hint dispatch is additive: the server collects every matching provider's
flow. The earlier apparent failure came from the test client answering the built-in provider's
`workspace/configuration` request with `null`; it requires one configuration object per requested
item and failed the whole request before the combined result could be returned.
_Tracking: https://github.com/yschimke/kotlin-lsp-dev/issues/5_

## What this adds

A label at the closing `}` of long function/class bodies (e.g. `} fun foo`), which kotlin-lsp's
built-in type/parameter hints do not provide.

## Layout

| Path | Role | Verified by |
|---|---|---|
| `core/…/inlayHints/ClosingBraceHintsComputation.kt` | finds long bodies, labels their closing brace | `test/ClosingBraceHintsTest.kt` |
| `ext/…/LSKotlinClosingBraceInlayHintsProvider.kt` | additive `LSInlayHintsProvider` adapter | live stdio smoke test |
| `smoke/check.py` | requests hints for a long function and class | `scripts/smoke-test.py` |

## Live verification

`scripts/smoke-test.py` applies a real `textDocument/inlayHint` request to the pinned server and
requires both overlay labels. The same request also runs the built-in Kotlin provider, proving
that the two providers coexist and dispatch additively. Hints do not need provider-routing data:
this provider has nothing to resolve and `inlayHint/resolve` returns the original hint when its
`data` has no configuration-entry id.

## Scope versus built-in hints

The bundled Kotlin provider already covers type, parameter, lambda, value-range, Kotlin-time, and
call-chain hint families exposed through the twelve `jetbrains.kotlin.hints.*` settings. Closing
brace ownership labels are a genuine gap. Further overlay hint work should target similarly
distinct structural hints rather than duplicate those configurable families.

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
