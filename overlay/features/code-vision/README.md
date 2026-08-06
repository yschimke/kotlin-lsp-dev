# Code Vision code lenses (`textDocument/codeLens`)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

This feature was release-gated for its whole life until now: releases through `262.9593.0`
neither advertised `codeLensProvider` nor shipped `LSCodeLensProvider`, so `build-server.sh`
skipped it automatically. `263.2689.0` ships both, and the feature activated with no code
change — which is the release-gating mechanism working as designed.
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
| `core/…/codeVision/KotlinCodeVisionComputation.kt` | pure-PSI cores (reference count, implementation/override count, `@Test` detection) | `test/CodeVisionTest.kt` |
| `ext/…` | `LSCodeLensProvider` adapters | `scripts/compile-check.sh` + boots via `scripts/build-server.sh` |
| `smoke/check.py` | live `textDocument/codeLens` request against a real patched server | `scripts/smoke-test.py` |

## Live verification

`smoke/check.py` asserts the server advertises `codeLensProvider`, then sends a real
`textDocument/codeLens` request and requires a usage lens, an implementation lens and a run-test
lens. It also pins the count: the fixture's `Greeter` has exactly two implementors, so a
`2 implementations` lens must come back — a lens with the wrong number fails rather than passes.

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
