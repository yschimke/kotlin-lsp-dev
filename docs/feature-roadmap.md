# LSP feature survey and overlay roadmap

_Surveyed 2026-07-26 against the open Kotlin/kotlin-lsp issue tracker and the pinned `262.8190.0`
dispatch surface. **Two of its conclusions no longer hold — see the 2026-08-06 update at the
bottom before using this as a plan.**_

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
| Generate overrides / implement members | [#171](https://github.com/Kotlin/kotlin-lsp/issues/171) | **Done:** the declaration-generation code actions have landed on `main`. |
| Generate named call arguments | [#175](https://github.com/Kotlin/kotlin-lsp/issues/175) | **High:** additive code action, independent of forbidden completion dispatch. |
| Java-to-Kotlin conversion | [#157](https://github.com/Kotlin/kotlin-lsp/issues/157) | **Medium:** code action/command is reachable, but converter packaging must be verified. |
| Go to implementation | [#107](https://github.com/Kotlin/kotlin-lsp/issues/107) | **Verify first:** handler is additive and a built-in now exists; focus on failing cases. |
| External Java/Kotlin documentation | [#230](https://github.com/Kotlin/kotlin-lsp/issues/230) | **Low:** hover is first-non-null, so an overlay cannot reliably augment it. |
| Workspace-wide diagnostics | [#238](https://github.com/Kotlin/kotlin-lsp/issues/238) | **Medium:** diagnostic dispatch is reachable, but push/index lifecycle is platform-level. |
| Agent readiness and huge symbol result sets | [#182](https://github.com/Kotlin/kotlin-lsp/issues/182) | **Low:** initialization and response limiting need server/client lifecycle changes. |
| Kotlin script support | [#229](https://github.com/Kotlin/kotlin-lsp/issues/229) | **Low:** primarily workspace/import and analysis configuration, not a provider gap. |

## Next overlay features, in order

1. **Unused-import diagnostics — implemented in this PR.** It uses Kotlin's import optimizer and
   the existing organize-imports action as the repair path.
2. **Implement/override members action — implemented as a separate PR.** It offers distinct actions
   for required and optional members, without touching forbidden completion dispatch.
3. **Fill named arguments action — implemented as a separate PR.** It generates `name = TODO()`
   placeholders for unambiguous empty calls and conservatively declines overloads.
4. **Missing `when` branches quick fix — already built in.** A live stock-server audit returned
   `NO_ELSE_IN_WHEN` plus `Add else branch`, `Add remaining branches`, and
   `Add remaining branches with * import`; another provider would only duplicate working actions.
5. **Test navigation and run commands — already represented by the code-vision feature.** Its core
   and adapter are PR-ready, but the pinned release does not expose `codeLens`; release-gating it is
   the only safe outcome until a newer public server exists.
6. **Workspace symbol quality — deliberately not implemented.** Providers are additive, so a
   filtered provider cannot remove or limit the built-in's large result stream and would add
   duplicates. The fix belongs in the built-in provider or client request/result policy.

This closes the actionable roadmap: every safe additive feature is implemented, an already-working
feature was verified instead of duplicated, and the remaining two items are blocked by demonstrated
dispatch/capability constraints rather than missing overlay code.

Not candidates for this overlay: extra completion, formatting, signature help, hover replacement,
document highlights, selection ranges, and code lenses on the current release. Their dispatch or
capability constraints make an otherwise-good implementation unreachable or actively harmful.

---

## Update, 2026-08-06 — pinned release `263.2689.0`

Two conclusions above were correct for `262.8190.0` and are now wrong. Both were wrong for the
same reason: they treated "unreachable on the pinned release, in-process" as permanent.

**Code lenses are shipped.** Item 5 release-gated code vision because `262.8190.0` did not expose
`codeLens`. `263.2689.0` advertises `codeLensProvider` and ships `LSCodeLensProvider`, and the
feature activated with no code change. It is live-verified. Note how the newer build was found:
`fetch-dist.sh --check` had been probing invented build numbers, getting 404s and concluding the
pin was newest, while `263.2689.0` was already on the CDN and inside VS Code extension 0.0.8. The
survey's "there is no newer public server" premise was an artefact of a broken probe, not a fact.

**Document highlights are shipped.** They were listed as not a candidate, and as an in-process
overlay feature they still are not — `263.2689.0` has no highlight provider interface at all. But
`bin/enhanced-server` owns the LSP boundary, and `documentHighlight` reduces to a filtered
`textDocument/references`, which the child does answer. It is implemented there and live-verified.

**What this changes about how to read the rest of this document.** "Not a candidate" needs
splitting into two claims that were previously conflated:

- *Not a candidate in-process* — dispatch or capability constraints inside the child. Still true
  of extra completion, formatting, signature help, and hover replacement, and those remain
  actively harmful to attempt.
- *Not a candidate anywhere* — the operation cannot be reduced to requests the child already
  answers, so nothing can compute it honestly. This is a much smaller set than the list above.

`selectionRange` is the interesting remaining case. It has no provider interface and is not
advertised, so it is out in-process. Whether it belongs at the boundary depends on whether it can
be built from requests the child answers; a brace- and indentation-based approximation would be
easy and would be the wrong thing to ship, because a client that trusts the advertised capability
would show wrong selections rather than none. Unanswered, and worth answering deliberately.

See "When a feature cannot be a provider" in AGENTS.md for the three tiers this now implies.
