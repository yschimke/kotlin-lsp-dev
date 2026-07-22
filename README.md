# kotlin-lsp-dev

A small Gradle workspace for developing and testing changes to
[Kotlin/kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) — a repository that **cannot be
built from source and has no CI**. "dev" as in a companion development environment; it is
unaffiliated with JetBrains.

Point the `kotlin-lsp` symlink at a local checkout, list the files you want compiled, and the
overlay builds just those against a real IntelliJ Platform, with real tests you can run.

> [!CAUTION]
> **Not a valid setup for upstream kotlin-lsp PRs.** This is a local development and
> validation harness only. It deliberately skips the upstream build: it **shims the
> `RefactoringProcessor` interface, compiles a hand-picked slice of files rather than whole
> modules, uses IntelliJ's `BasePlatformTestCase` instead of the internal `test-api` harness,
> and pins a platform version that does not match the released server**. Tests written here
> cannot be upstreamed as-is.
>
> On top of that, kotlin-lsp is a **one-way mirror**: every commit carries a
> `GitOrigin-RevId` trailer, and **0 of 22 closed pull requests have ever been merged**.
> Development happens in JetBrains' internal monorepo. Use this overlay to validate a change
> locally; landing it is a separate problem.

## Why this exists

kotlin-lsp mirrors `//language-server/community/...` out of JetBrains' internal monorepo. Its
Bazel files depend on `//language-server/analyzer`, `api.impl.analyzer`, `dap/platform` and
`test-api` — none of which are public. `language-server/` does not exist in open-source
intellij-community, and a maintainer confirmed the situation directly in
[issue #122](https://github.com/Kotlin/kotlin-lsp/issues/122):

> it's not possible to build it yourself, as the LSP depends on our closed-source code

The repo also has **no GitHub Actions at all** — no `.github` directory, zero workflows, zero
runs. So a change to the Kotlin sources gets no automated feedback from any public source.

This overlay recovers as much of that feedback as is publicly possible, in two independent
layers.

## The two layers

| | `./gradlew test` | `scripts/compile-check.sh` |
|---|---|---|
| Platform | IntelliJ 2026.2 from Maven | the pinned release distribution |
| Interfaces | **shimmed** `RefactoringProcessor` | the **real** upstream interfaces |
| Closed jars | none | the real `product.jar` etc. |
| Runs code? | **yes** — real refactorings execute | no, type-check only |

Neither subsumes the other. The Gradle build proves the logic *works*; the compile-check
proves it still *fits* the real upstream and closed-source APIs. Run both.

### `./gradlew test`

Compiles a curated list of upstream files (`upstreamSources` in `build.gradle.kts`) against a
self-consistent IntelliJ 2026.2 with the bundled Kotlin plugin, and drives them from
`BasePlatformTestCase`.

The trick that makes this work with **zero closed-source jars**: upstream's
`RefactoringProcessor.kt` mixes a plain interface together with an `execute(...)` driver that
needs `LSAnalysisContext`, `FileUrl` and `server.fileChanges()`. Only the interface is needed
to compile `MoveFilesProcessor`, so `shims/` provides it and the real file is left out. No
closed jars means no version mixing.

### `scripts/compile-check.sh`

Type-checks the **real** `api.features` + `features-impl/common` sources against every jar in
the pinned release — including the closed ones, which the release ships as ordinary jars.

## Version skew

`dist.properties` pins `262.8190.0`. That is the newest build that exists publicly: the CDN
404s on every later build number, and the VS Code Marketplace agrees (extension 0.0.5 bundles
exactly `LS-262.8190.0`). Upstream master runs ahead of it, so files unrelated to your change
legitimately fail to compile — 98 errors across 17 files as of 2026-07-21.

Those files are listed in `compile-check-baseline.txt`. The script fails only on errors in
files that are *not* baselined, so **the signal is "did my change break something", not "is
the tree clean"**. Shrinking that baseline after a release bump is the maintenance chore.

The Gradle side has the same problem in a different shape: no `test-framework` is published at
`262.8190` (the published line jumps `261.26222.x` → `262.8665.x`), so an exact match with the
release is impossible. 2026.2 is the closest self-consistent platform.

## Layout

- `kotlin-lsp` — symlink to your checkout, defaults to `../kotlin-lsp`. Sources are read from
  there directly, so edit in place and re-run.
- `shims/` — minimal stand-ins for upstream declarations that drag in closed-source types.
- `src/test/kotlin/` — tests, which live here rather than upstream.
- `dist.properties` — the pinned release. `scripts/fetch-dist.sh --check` probes for newer.
- `compile-check-baseline.txt` — files failing purely from version skew.

## Usage

```sh
git clone https://github.com/Kotlin/kotlin-lsp ~/workspace/kotlin-lsp
git clone https://github.com/yschimke/kotlin-lsp-dev ~/workspace/kotlin-lsp-dev
cd ~/workspace/kotlin-lsp-dev
ln -sfn ~/workspace/kotlin-lsp kotlin-lsp   # only if not a sibling

./gradlew test              # ~2 min first run: downloads a full IDE distribution
./scripts/compile-check.sh  # ~376 MB release download on first run
```

Requires a JDK 21+ for Gradle. `compile-check.sh` needs a JDK 25 (upstream targets JVM 25) and
fetches its own kotlinc 2.4.10.

## Notes found while building this

**`MoveFilesProcessor.performRefactoring` needs a global `ProgressIndicator`.** The Kotlin
handler it delegates to does this, with no null check:

```kotlin
// K2MoveRenameUsageInfo.kt:468
val progress = ProgressManager.getInstance().progressIndicator.apply { pushState() }
```

`ProgressManager.getProgressIndicator()` returns `null` when no indicator is installed, so the
test NPE'd until it was wrapped in `ProgressManager.runProcess(..., EmptyProgressIndicator())`.
Worth checking whether the production path establishes one: upstream reaches
`performRefactoring` via `execute(...)` → `CommandProcessor.executeCommand { WriteAction.run { … } }`,
and no `runWithModalProgressBlocking` wraps *that* call — only the `findUsages` phase gets one,
through `runReadActionInBgt`. Unverified against a running server.
