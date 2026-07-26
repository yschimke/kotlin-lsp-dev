# LSP feature survey and overlay roadmap

_Surveyed 2026-07-26 against the open Kotlin/kotlin-lsp issue tracker and the pinned `262.8190.0`
dispatch surface._

## What mature language servers set as the baseline

The useful comparison is not protocol checkbox count, but semantic workflows:

- [rust-analyzer](https://rust-analyzer.github.io/book/features.html) combines assists,
  run/debug code lenses, structural search/replace, semantic syntax highlighting, inlay hints,
  related tests, and rich workspace symbol navigation.
- [Eclipse JDT Language Server](https://github.com/eclipse-jdtls/eclipse.jdt.ls#features) couples
  Java navigation and hierarchy with organize imports, source actions, refactorings, generation
  actions, code lenses, call hierarchy, and signature help.
- [Metals](https://scalameta.org/metals/docs/editors/overview/) shows the value of build-aware
  features around the protocol: worksheets, test discovery/debugging, dependency navigation,
  inferred-type hints, and actionable build diagnostics.

The recurring modern pattern is **diagnostic -> quick fix/refactor -> navigation -> test/run**.
For this overlay, however, only additive requests (plus the empty type-hierarchy slot) are viable.

## Gaps visible in the Kotlin tracker

| Gap | Tracker evidence | Overlay feasibility on `262.8190.0` |
|---|---|---|
| Unused imports are not diagnosed | [#201](https://github.com/Kotlin/kotlin-lsp/issues/201) | **High:** diagnostics are additive; implementation started here. |
| Type hierarchy returns `null` | [#197](https://github.com/Kotlin/kotlin-lsp/issues/197) | **Done:** this repository's type-hierarchy overlay occupies the empty slot. |
| Generate overrides / implement members | [#171](https://github.com/Kotlin/kotlin-lsp/issues/171) | **High:** additive code action; PSI generation needs analysis-backed tests. |
| Generate named call arguments | [#175](https://github.com/Kotlin/kotlin-lsp/issues/175) | **High:** additive code action, independent of forbidden completion dispatch. |
| Java-to-Kotlin conversion | [#157](https://github.com/Kotlin/kotlin-lsp/issues/157) | **Medium:** code action/command is reachable, but converter packaging must be verified. |
| Go to implementation | [#107](https://github.com/Kotlin/kotlin-lsp/issues/107) | **Verify first:** handler is additive and a built-in now exists; focus on failing cases. |
| External Java/Kotlin documentation | [#230](https://github.com/Kotlin/kotlin-lsp/issues/230) | **Low:** hover is first-non-null, so an overlay cannot reliably augment it. |
| Workspace-wide diagnostics | [#238](https://github.com/Kotlin/kotlin-lsp/issues/238) | **Medium:** diagnostic dispatch is reachable, but push/index lifecycle is platform-level. |
| Agent readiness and huge symbol result sets | [#182](https://github.com/Kotlin/kotlin-lsp/issues/182) | **Low:** initialization and response limiting need server/client lifecycle changes. |
| Kotlin script support | [#229](https://github.com/Kotlin/kotlin-lsp/issues/229) | **Low:** primarily workspace/import and analysis configuration, not a provider gap. |

## Next overlay features, in order

1. **Unused-import diagnostics** — implement now, using Kotlin's import optimizer and the existing
   organize-imports action as the repair path.
2. **Implement/override members action** — offer separate preferred quick fixes for required
   members and optional overrides; never implement it as a completion provider.
3. **Fill named arguments action** — generate `name = TODO()` placeholders for omitted parameters
   at a call expression, with vararg/default-parameter tests.
4. **Missing `when` branches quick fix** — an additive, high-frequency code action comparable to
   rust-analyzer/JDT semantic assists; first confirm it is not already exposed as an intention.
5. **Test navigation and run commands** — extend additive code vision/execute-command support once
   the pinned release exposes code lenses; keep command names globally unique.
6. **Workspace symbol quality** — add a narrowly filtered provider only if it can improve queries
   without duplicating the built-in's large unfiltered result stream.

Not candidates for this overlay: extra completion, formatting, signature help, hover replacement,
document highlights, selection ranges, and code lenses on the current release. Their dispatch or
capability constraints make an otherwise-good implementation unreachable or actively harmful.
