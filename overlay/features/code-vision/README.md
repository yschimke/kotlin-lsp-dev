# Code Vision code lenses (`textDocument/codeLens`)

**Status:** carried in overlay — not yet submitted upstream.
_Tracking: (add the upstream PR/issue URL here once opened)_

## What this adds

Three "code vision" lenses over Kotlin declarations, via the existing (already-routed)
`LSCodeLensProvider` interface. kotlin-lsp currently ships only the run-**main** lens (in the
DAP configuration); these add usage/inheritance/testing affordances:

- **Reference count** — "N usages" above a declaration.
- **Implementation/override count** — "N implementations" above interfaces, abstract classes,
  and overridable members.
- **Run test** — a run affordance above `@Test` functions (run-main exists; run-test does not).

## Layout

| Path | Role | Verified by |
|---|---|---|
| `core/…/codeVision/KotlinCodeVisionComputation.kt` | pure-PSI cores (reference count, implementation/override count, `@Test` detection) | `../../../src/test/kotlin/overlay/CodeVisionTest.kt` (runs in the overlay) |
| `ext/…` | `LSCodeLensProvider` adapters | `scripts/compile-check.sh` + boots via `scripts/build-server.sh` |

## Upstream target paths

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/codeVision/…`
- registration entries in `LSKotlinLanguageConfiguration`

When these land in a release, delete this directory; the overlay build drops it automatically.
These three are separable into individual upstream PRs if reviewers prefer; they share only the
computation object.

---

## Draft PR body

> **[kotlin] Add reference-count, implementation-count and run-test code lenses**
>
> kotlin-lsp ships a run-main code lens but no "code vision" lenses. This adds three, all via
> the existing `LSCodeLensProvider` interface:
>
> - reference count ("N usages") over declarations, using `ReferencesSearch`;
> - implementation/override count over classes/interfaces/overridable members, using
>   `ClassInheritorsSearch` / `OverridingMethodsSearch` on the light classes/methods;
> - a run-test lens over `@Test`-annotated functions (complements the existing run-main lens).
>
> **Structure** — a single `KotlinCodeVisionComputation` holds the PSI-level counting/detection
> (free of LSP types); thin `LSCodeLensProvider` adapters map results to `CodeLens`.
>
> **Testing** — unit tests assert reference counts across files, implementation counts for
> interfaces and their members, and `@Test` detection.
