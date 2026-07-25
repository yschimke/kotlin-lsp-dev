# Region folding (`//region` … `//endregion`)

**Status:** ✅ runnable in the enhanced build (folding API ships in `262.8190.0`). An
*enhancement* — it augments the existing folding rather than adding a new request type.
_Tracking: (add the upstream PR/issue URL here once opened)_

## What this adds

kotlin-lsp folds declarations/comments via the platform folding builder, but not custom
`//region` / `//endregion` markers. This adds a second `LSFoldingRangeProvider`; the server
merges its folds with the built-in ones.

## Layout

| Path | Role | Verified by |
|---|---|---|
| `core/…/folding/RegionFoldingComputation.kt` | pairs region/endregion line comments (stack, nesting) | `test/RegionFoldingTest.kt` |
| `ext/…/LSKotlinRegionFoldingProvider.kt` | `LSFoldingRangeProvider` adapter | `build-server.sh` + live stdio round-trip |

## Upstream target paths

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/folding/…` (or fold the
  region logic into the common folding provider's kind mapping)

---

## Draft PR body

> **[kotlin] Fold `//region` / `//endregion` custom regions**
>
> Adds folding for `//region [label]` … `//endregion` comment markers (with nesting), which the
> platform folding builder does not handle. Implemented as an `LSFoldingRangeProvider` whose
> ranges merge with the existing folds; the collapsed placeholder is the region label.
>
> **Testing** — unit tests over fixtures with single, labelled, and nested regions.
