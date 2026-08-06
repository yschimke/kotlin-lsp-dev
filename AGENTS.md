# AGENTS.md

Instructions for AI agents and new contributors working in this repository.

## What this repo is

`kotlin-lsp-dev` is **not a language server**. It is a build harness that compiles extra LSP
features against a pinned, closed-source [Kotlin/kotlin-lsp](https://github.com/Kotlin/kotlin-lsp)
release and injects them into the shipped server through the platform's own
`LanguageServerExtension` ServiceLoader. No forking, no patching JetBrains jars, no bytecode
manipulation.

The pinned release lives in [dist.properties](dist.properties) (currently `263.2689.0`). Read
[README.md](README.md) before starting anything.

## The one rule that governs every feature

**An overlay feature can only work if the pinned release both (a) advertises the capability in its
`initialize` result and (b) dispatches the request to *all* registered providers.**

Getting this wrong wastes an entire feature's work, so check the table below *first* — before
writing any code, and before believing a feature is "just a provider away".

### Dispatch table for `263.2689.0`

Derived by disassembling `lib/product.jar` (262) / `lib/language-server.main.jar` (263) and
cross-checking against `upstream/api.features/src/**`. Re-derive whenever the pinned release
changes.

**Start with `scripts/probe-capabilities.py <server-dir>`.** It prints the `initialize` result a
server actually returns, which is the real gate on what any conformant client will ever request.
That is both faster and more trustworthy than reading the launcher: the closed classes are
re-obfuscated on every build, so a bytecode diff between two releases is mostly renamed private
methods. The capability probe is how `codeLens` was found to be newly available in 263 after two
releases of identical LSP surface.

| Dispatch | Requests | What it means for an overlay |
|---|---|---|
| **Additive** — merges every provider | `codeAction`, `foldingRange`, `inlayHint`, `diagnostic`, `definition`, `typeDefinition`, `implementation`, `references`, `documentSymbol`, `workspace/symbol`, `semanticTokens`, `workspace/executeCommand` (keyed by unique command name) | ✅ Safe. Adding a provider is pure gain. |
| **Empty slot** | `typeHierarchy` | ✅ No built-in Kotlin provider, so an added one wins outright. |
| **First non-null wins** | `hover`, `rename`, `callHierarchy/prepare` | ⚠️ A built-in already occupies the slot and ServiceLoader order is not controllable — there is no priority field. At best your provider fires only when the built-in returns null. Do not rely on it. |
| **Served by the composition server** | `rangeFormatting` (capability repair), `documentHighlight` (answered locally from the child's `references`) | ✅ Outside the child's dispatcher entirely. See "When a feature cannot be a provider" below. |
| **Adding a provider BREAKS the request** | `signatureHelp`, `formatting`, `rangeFormatting`, `completion` | ❌ **Never add these.** See below. |
| **Empty slot** | `codeLens` | ✅ New in `263.2689.0` — advertised and routed, with only the DAP run-main lens built in. This is what un-gated `overlay/features/code-vision`. |
| **No capability advertised** | `selectionRange`, `documentLink`, `declaration`, `onTypeFormatting`, `linkedEditingRange`, `documentColor`, `inlineValue`, `moniker`, `prepareRename` | ⚠️ Unreachable **in-process** — absent from the `initialize` result on 263.2689.0 (verified with `probe-capabilities.py`), and no provider interface exists to register against. Not necessarily unreachable at the boundary: see below. |

### When a feature cannot be a provider

The rows above are about the **in-process** overlay. The composition server (`bin/enhanced-server`,
source in `launcher/overlay/server/KotlinLspServer.kt`) owns the outer LSP boundary and is not
bound by any of them. It starts the shipped server as a child and can advertise a capability the
child does not, answer a request without forwarding it, or rewrite what the child returned.

That gives three tiers, and you should pick the **highest** one that works:

1. **In-process provider** — the default. Cheapest, upstream-shaped, and runs inside the server's
   own analysis context. Requires an advertised capability and additive dispatch.
2. **Capability repair at the boundary** — the server implements the operation but never
   advertises it, so no client asks. Set the flag on the initialize response and change nothing
   else. This is `rangeFormatting`.
3. **Answered at the boundary** — the server has no handler *and* no provider interface. The
   composition server implements the operation itself. This is `documentHighlight`.

Tier 3 has a hard constraint worth stating plainly: **the boundary has no Kotlin analysis.** It
sees JSON, not PSI. So a tier-3 feature is only honest if it can be expressed in terms of requests
the child already answers. `documentHighlight` qualifies because it is a filtered
`textDocument/references` — the child does the resolution and we keep the results in one file.
A feature needing real semantic analysis the child will not perform cannot be faked here; do not
advertise a capability you can only approximate textually, because a client that trusts the
advertisement will show wrong results rather than none.

Mechanics, if you add one:

- Requests originated by the boundary use string ids prefixed `kotlin-lsp-dev/` so they cannot
  collide with the client's id space; responses to those are intercepted, never relayed.
- Both pumps write to the client, so all writes go through the synchronized emit helpers.
- Only the initialize response is re-encoded. Everything else is relayed byte for byte — do not
  add a parse/re-serialise step to the hot path.
- Errors from the child should usually become empty results for cursor-driven requests. Editors
  send them on every keystroke, and an error per keystroke is worse than no result.
- Give the feature a directory under `overlay/features/<name>/` with a `README.md` and
  `smoke/check.py` even though it has no `core/`/`ext/`. The smoke check is the only thing that
  can prove a boundary feature works, and it keeps the delete-the-directory property.

### Never add a provider for these

Three request types fail hard with more than one provider. Two throw at dispatch and one throws on
every keystroke:

- **`signatureHelp`** — `LSSignatureHelp.getSignatureHelp` does
  `else -> error("Multiple signature help providers found...")`. Kotlin already registers one.
- **`formatting` / `rangeFormatting`** — `LSDocumentFormatting.provider()` does
  `else -> error("Multiple ... LSFormattingProvider found ...")`. `LSCommonFormattingProvider`
  already covers Kotlin.
- **`completion`** — additive on paper, fatal in practice: the combiner asserts
  `require(none { it.isIncomplete })`, and the built-in provider always returns
  `isIncomplete = true`. A second provider throws on every completion request.

### `LSConfiguration` validation — these throw at server startup, not softly

- Command **names** must be globally unique (`requireNoDuplicatesBy { it.name }`).
- Any `LSUniqueConfigurationEntry` (completion, inlay hints, call/type hierarchy, code actions)
  needs a fresh `uniqueId`. A collision with a built-in fails the whole server config.
- Do **not** contribute an `LSLanguage` with `lspName == "kotlin"` in `LSConfigurationPiece.languages`
  — that duplicates the built-in and throws. `LSLanguage` compares by `lspName` only, so you can
  safely construct your own instance for `supportedLanguages` without an identity problem.

## Adding a feature

Create `overlay/features/<name>/` with:

| Path | Role |
|---|---|
| `core/com/jetbrains/ls/api/features/impl/kotlin/<area>/<X>Computation.kt` | Pure-PSI computation. **No LSP types, no closed-source types** — this is what the unit tests exercise. |
| `ext/com/jetbrains/ls/api/features/impl/kotlin/<area>/LSKotlin<X>Provider.kt` | LSP adapter. Written in the *upstream* package so the file is submittable verbatim. |
| `ext/overlay/<name>/<X>ServerExtension.kt` | `LanguageServerExtension` returning `LSConfigurationPiece(entries = listOf(<provider>))`. |
| `resources/META-INF/services/com.jetbrains.ls.api.features.LanguageServerExtension` | The extension FQN. |
| `README.md` | Status, upstream target paths, tracking link, draft PR body. |
| `PR_ONLY` (optional) | Marker file — `build-server.sh` skips the feature. |

Then add a row to the "Current features" table in [README.md](README.md). Add a test at
`src/test/kotlin/overlay/<X>Test.kt`.

**No build-file edit is needed** — `build.gradle.kts` auto-discovers `overlay/features/*/core`, so
adding or deleting a feature directory is the whole change.

**Before writing anything, check whether the server already does it.** Open the fixture on a stock
server and ask for the response you plan to provide — and *poll*, because intention-backed code
actions only appear once analysis is ready, so asking once will tell you it is missing when it is
not. A duplicate action is worse than no action: it clutters every user's list and cannot be
distinguished from the built-in. `scripts/smoke-test.py <stock-server> --stock` is the mechanical
form of this question and runs in CI.

## Build and verify

```sh
./gradlew test              # unit-test the feature cores (downloads an IDE the first time)
./scripts/fetch-dist.sh     # download + unpack the pinned release (~376 MB)
./scripts/build-server.sh   # compile features vs the release → overlay jar + local enhanced tarball
./scripts/install-overlay.sh /path/to/kotlin-server-<v>
./scripts/compile-check.sh  # type-check pinned upstream sources vs the release (drift detection)
```

JDK 21+ for Gradle; **JDK 25** for `build-server.sh` and `compile-check.sh` (upstream targets JVM 25).

`build-server.sh` **release-gates automatically**: each feature compiles independently, and a
feature whose LSP API is absent from the pinned release is silently skipped with a log at
`build/server/feat-<name>.log`. A skipped feature is not a failure — check the log before debugging.

### A feature is not done until it is verified live

Unit tests alone are insufficient — they exercise `core/`, which by design cannot catch a
registration, dispatch, or capability problem. Add a `smoke/check.py` and verify against a real
patched server (`scripts/smoke-test.py <server-dir>`), then record the result in the feature's
README, as the existing features do. Run `--each` to prove the feature stands alone, `--socket`
to prove it survives the TCP transport VS Code uses, and `--stock` to prove the check fails
without the overlay at all.

**Assert on the applied edit, not on an edit existing.** `lsp.apply_edits(text, edits)` applies a
response the way a client would; compare the resulting source. A provider returning a corrupt or
misordered `TextEdit` sails through a presence assertion. Most of the ways an overlay feature fails are invisible to unit tests:

- The provider never registers (missing/malformed services file).
- The `uniqueId` collides and the server fails to start.
- The provider is absent from `entriesByLanguage` (wrong or empty `supportedLanguages`).
- Results are returned but dropped for lack of a `data` entry-id.

## Conventions

- **Source headers.** `.kt` files carry the upstream JetBrains Apache-2.0 header *on purpose* — each
  feature is written to be submittable to kotlin-lsp verbatim, and that repo requires it. It is not
  a copyright claim. See "Licensing" in [README.md](README.md). If a file is deliberately
  overlay-only and will never be upstreamed, change its header to your own attribution.
- **`core/` stays free of LSP and closed-source types.** This is what makes it unit-testable
  against a plain IntelliJ platform, and it is the boundary that keeps features PR-shaped.
- **Never commit JetBrains binaries or the enhanced tarball.** Only
  `build/server/language-server.overlay-*.jar` (our Apache-2.0 classes) is redistributable.
- **Git.** Branch as `agent/<name>`, never `claude/<name>`. No agent co-authors, `Signed-off-by`,
  or generated-by trailers in commits; no agent attribution in issue or PR text. Commit as the
  human using local git `user.name`/`user.email`.

## Upstream reality — plan for a permanent overlay

Kotlin/kotlin-lsp is a read-only mirror of a JetBrains internal monorepo and has **merged zero
pull requests, ever** — 26 opened, none merged, including one-line documentation fixes. The README
states code contributions are not accepted.

Keep writing features in PR-ready shape (it is good discipline and costs nothing), but do not plan
around them landing upstream. When choosing what to build, weight **"runnable on the pinned
release now"** far above "clean upstream shape". The "PR-then-drop" lifecycle in README.md is
aspirational, not a schedule.

## Resolved investigations

- **Inlay hints are additive.** Disassembly and a live stdio smoke test on `262.8190.0` confirm
  that `LSInlayHints.inlayHints` collects every matching provider. The earlier closing-brace test
  failed because its client returned `null` to the built-in provider's `workspace/configuration`
  request, causing that provider to throw before dispatch could return the combined results.
  Return one configuration object per requested item when testing inlay hints.
