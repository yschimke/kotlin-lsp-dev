# AGENTS.md

Instructions for AI agents and new contributors working in this repository.

## What this repo is

`kotlin-lsp-dev` is **not a language server**. It is a build harness that compiles extra LSP
features against a pinned, closed-source [Kotlin/kotlin-lsp](https://github.com/Kotlin/kotlin-lsp)
release and injects them into the shipped server through the platform's own
`LanguageServerExtension` ServiceLoader. No forking, no patching JetBrains jars, no bytecode
manipulation.

The pinned release lives in [dist.properties](dist.properties) (currently `262.8190.0`). Read
[README.md](README.md) before starting anything.

## The one rule that governs every feature

**An overlay feature can only work if the pinned release both (a) advertises the capability in its
`initialize` result and (b) dispatches the request to *all* registered providers.**

Getting this wrong wastes an entire feature's work, so check the table below *first* — before
writing any code, and before believing a feature is "just a provider away".

### Dispatch table for `262.8190.0`

Derived by disassembling `build/dist/kotlin-server-262.8190.0/lib/product.jar`. The shipped
bytecode matches `upstream/api.features/src/**` exactly, so the submodule sources are a
trustworthy reference. Re-derive this table whenever the pinned release changes.

| Dispatch | Requests | What it means for an overlay |
|---|---|---|
| **Additive** — merges every provider | `codeAction`, `foldingRange`, `inlayHint`, `diagnostic`, `definition`, `typeDefinition`, `implementation`, `references`, `documentSymbol`, `workspace/symbol`, `semanticTokens`, `workspace/executeCommand` (keyed by unique command name) | ✅ Safe. Adding a provider is pure gain. |
| **Empty slot** | `typeHierarchy` | ✅ No built-in Kotlin provider, so an added one wins outright. |
| **First non-null wins** | `hover`, `rename`, `callHierarchy/prepare` | ⚠️ A built-in already occupies the slot and ServiceLoader order is not controllable — there is no priority field. At best your provider fires only when the built-in returns null. Do not rely on it. |
| **Adding a provider BREAKS the request** | `signatureHelp`, `formatting`, `rangeFormatting`, `completion` | ❌ **Never add these.** See below. |
| **No handler registered at all** | `documentHighlight`, `selectionRange`, `documentLink`, `declaration`, `onTypeFormatting`, `linkedEditingRange`, `documentColor`, `inlineValue`, `moniker`, `prepareRename`, `codeLens` | ❌ Unreachable at any layer. The server registers only 29 request handlers and these are not among them. |

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
registration, dispatch, or capability problem. Verify over stdio against a real patched server
(`bin/intellij-server --stdio`) and record the result in the feature's README, as the existing
features do. Most of the ways an overlay feature fails are invisible to unit tests:

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
