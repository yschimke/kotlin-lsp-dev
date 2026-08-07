# kotlin-lsp-dev

Builds **enhanced [Kotlin/kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) servers** — the
official release plus extra LSP features — from a pinned source checkout, and packages the
additions as a small overlay you apply to a server you download yourself.

kotlin-lsp cannot be built from source (it depends on JetBrains' closed-source code) and merges
no external PRs. This project adds features *around* it: each is written as upstream-ready
Kotlin, unit-tested here, and injected into the shipped server through the platform's own
`LanguageServerExtension` ServiceLoader — no forking, no patching of JetBrains jars, no bytecode
manipulation.

The installed overlay also provides `bin/enhanced-server`, a small Kotlin composition server. It
starts the official `bin/intellij-server` as a child and owns the outer LSP boundary, over stdio or
TCP, so it can repair or implement operations that cannot compose as in-process providers at all.
Two things live there today: advertising the shipped, working range-formatting handler that the
server omits from its initialize capabilities, and *implementing* `textDocument/documentHighlight`,
for which the server has neither a handler nor a provider interface — it is answered from the
child's own `textDocument/references`, so occurrences come from real Kotlin resolution. All
additive features still run inside the child through the normal extension API.

> [!IMPORTANT]
> **We publish only our own Apache-2.0 code** (`language-server.overlay-*.jar`), never JetBrains'
> proprietary server binaries. You download the official server yourself and apply the overlay
> locally with `scripts/install-overlay.sh`. The full "enhanced tarball" that `build-server.sh`
> produces is a **local** convenience for testing and is not redistributable. See
> [Licensing](#licensing) for the full picture.

## Layout

```
overlay/features/<name>/
  README.md   draft PR body + upstream target paths + status/tracking link
  core/       pure-PSI computation, free of LSP types
  ext/        LSP adapter + a per-feature LanguageServerExtension
  resources/  META-INF/services entry registering that extension
  test/       unit tests for the core (auto-discovered by build.gradle.kts)
  smoke/      check.py + fixture driven against a real server (runnable features only)
upstream/       git submodule: Kotlin/kotlin-lsp pinned at the release commit
dist.properties pinned release version (source + downloaded dist stay matched)
scripts/
  fetch-dist.sh      download + unpack the pinned release
  fetch-kotlinc.sh   download the pinned standalone Kotlin compiler
  build-server.sh    compile features vs the release → overlay jar (+ local enhanced tarball)
  install-overlay.sh apply the overlay jar and composition-server launcher
  compile-check.sh   type-check the pinned upstream sources vs the release (drift detection)
  check-superseded.sh   fail the build if a release ships a feature we carry
  smoke-test.py      drive a patched server (stdio or TCP) and assert the features answer
  check-client-contract.py  assert the server still provides what the VS Code client assumes
  probe-capabilities.py print the initialize result a server advertises
  install.sh         one command: fetch + build + apply + report editor config
```

## Feature lifecycle (permanent overlay, PR-ready shape)

Treat the overlay as the permanent home of every feature. Kotlin/kotlin-lsp is a read-only mirror
of an internal JetBrains monorepo, its README says that code contributions are not accepted, and
no external pull request has ever merged. Features still use upstream-ready paths, headers, and
PR bodies because that discipline keeps them isolated and reviewable—not because a merge is part
of their expected lifecycle. Prefer features that run on the pinned release now over features
whose only advantage is a clean hypothetical upstream integration.

**Everything a feature owns lives in its own directory** — sources, unit tests, and smoke check.
That makes a feature one self-contained unit to submit upstream, and it means deleting the
directory drops all of it: `build.gradle.kts` discovers `core/` and `test/` per feature, and
`smoke-test.py` discovers `smoke/check.py`, so nothing is left behind referencing code that is
gone. When a release ships the feature itself, delete the directory — that is the whole edit.

**`build-server.sh` release-gates automatically**: a feature whose LSP API isn't in the pinned
release is skipped (it stays unit-tested + PR-ready and activates once a release ships the API).

## Current features

| Feature | On `263.2689.0` | Verified |
|---|---|---|
| **Type hierarchy** (`textDocument/typeHierarchy`) — new | ✅ runnable | unit tests + CI smoke test (prepare + supertypes/subtypes) |
| **Region folding** (`//region`…`//endregion`) — enhancement | ✅ runnable | unit tests + CI smoke test (folds merge with built-in) |
| **Convert to expression body** — enhancement (code action) | ✅ runnable | unit tests + CI smoke test (action + edit, merged with built-ins) |
| **Extract variable** — new (code action) | ✅ runnable | unit tests + CI smoke test (direct declaration + replacement edit) |
| **Extract function** — new (code action) | ✅ runnable | unit tests + CI smoke test (signature, substituted call, statements moved) |
| **Inline variable** — new (code action) | ✅ runnable | unit tests + CI smoke test (applied edits compared to expected source) |
| **Fill named call arguments** — new ([#175](https://github.com/Kotlin/kotlin-lsp/issues/175)) | ✅ runnable | unit tests + CI smoke test (applied edit compared to expected source) |
| **Unused-import diagnostics** — enhancement ([#201](https://github.com/Kotlin/kotlin-lsp/issues/201)) | ✅ runnable | unit tests + CI smoke test (warning + `Unnecessary` tag) |
| **Workspace commands** — doctor, stack traces, dependency search, FQN | ✅ runnable | unit tests + CI smoke test (all four commands) |
| **Implement/override members** — declaration-generation code actions | ✅ runnable | unit tests + CI smoke test (direct implementation edit) |
| **Code vision** code lenses (usages / implementations / run-test) — new | ✅ runnable | unit tests + CI smoke test (usage / implementation / run-test lenses, exact counts) |
| **Closing-brace inlay hints** — enhancement | ✅ runnable | unit tests + CI smoke test (function + class hints, merged with built-ins) |
| **Range formatting capability** (`textDocument/rangeFormatting`) — repair | ✅ runnable via composition server | CI smoke test (advertised capability + real formatting edits) |
| **Document highlight** (`textDocument/documentHighlight`) — new, proxy-only | ✅ runnable via composition server | CI smoke test (exact highlight positions, decoy excluded) |
| **Move file** (`workspace/willRenameFiles`) — new | ✅ runnable | CI smoke test (package updated **and** referring file fixed) |
| **Inline function** — new (code action) | ✅ runnable | unit tests + CI smoke test (applied edit compared to expected source) |

**Every feature is runnable as of `263.2689.0`.** Code vision was release-gated for its whole
life — releases through `262.9593.0` neither advertised `codeLensProvider` nor shipped
`LSCodeLensProvider` — and activated on this release with no code change, which is the
release-gating mechanism working as intended.

Features can still become un-runnable in either direction. A feature's LSP API may **postdate**
the pinned release, in which case `build-server.sh` skips it and it stays unit-tested and
PR-ready until a release ships the API. Separately, some request types dispatch to a **single**
provider rather than merging, so they cannot be enhanced by adding a provider at all; the
dispatch table in `AGENTS.md` records the verified behavior per release. Those are the operations
the composition server exists for.

## Overlay platform guardrails

The dispatch behavior is release-specific. The table in `AGENTS.md` was derived for the version
in `dist.properties` by disassembling `lib/product.jar` from that distribution, then comparing the
handlers with `upstream/api.features/src`. When the pinned version changes, re-run that audit:

1. Inspect the new server's initialize capability construction and registered request handlers.
2. Disassemble each handler/combiner in `product.jar` (for example with `javap -c -p`) and record
   whether it visits all matching providers, chooses one, or rejects multiple providers.
3. Compare the bytecode with the pinned `upstream/api.features/src` sources and update the table
   in `AGENTS.md`; do not assume behavior from another release.
4. Build, install, and exercise every runnable feature end-to-end. Compilation alone does not
   prove registration, capability advertisement, or result routing.
5. `scripts/probe-capabilities.py` prints the `initialize` result directly and is the fastest
   way to see what a new release un-gates — the closed classes are re-obfuscated on every
   build, so diffing their bytecode mostly reports renamed private methods.

Some combinations fail loudly rather than degrading. Never add completion, signature-help, or
formatting/range-formatting providers: built-ins already occupy those paths, and another provider
causes request-time errors. `scripts/check-overlay-guardrails.sh` enforces this before the build
downloads toolchains. Server configuration also validates globally unique command names and a
unique `uniqueId` for every `LSUniqueConfigurationEntry`. An extension must not contribute an
`LSLanguage` whose `lspName` is `kotlin`; use a Kotlin language instance only in an entry's
`supportedLanguages`, because adding it to `LSConfigurationPiece.languages` duplicates the
built-in language and aborts startup.

## Install

One command takes a clean checkout to a runnable server. It downloads the pinned official
release, builds the overlay against it, applies it, and installs `bin/enhanced-server`:

```sh
git clone --recurse-submodules https://github.com/yschimke/kotlin-lsp-dev
cd kotlin-lsp-dev

./scripts/install.sh                     # → ~/.local/share/kotlin-lsp-enhanced
./scripts/install.sh --to /opt/kotlin-lsp
./scripts/install.sh --vscode            # overlay the bundled server of the installed extension
./scripts/install.sh --version 262.9593.0   # a release other than the repository pin
```

The standalone install is self-contained: it restores the release's bundled JBR 25, so the
server needs no JDK on `PATH`. Verify it end-to-end with:

```sh
./scripts/smoke-test.py ~/.local/share/kotlin-lsp-enhanced
```

### Which release to run

`--version` exists because the newest release is not always the one that works on *your* project.
`263.2689.0` fails to import **Kotlin Multiplatform** projects, measured on two — one with an
Android target and one without:

| Project shape | `262.9593.0` | `263.2689.0` |
|---|---|---|
| plain Kotlin/JVM Gradle (this repo) | ok | **ok** |
| KMP + Compose, **no** Android target | workspace model 94 K | **0 K**, `IdeaKotlinResolvedBinaryDependency` CCE |
| KMP + Android | workspace model 177 K | **0 K**, same CCE |

The upstream report is [Kotlin/kotlin-lsp#243](https://github.com/Kotlin/kotlin-lsp/issues/243),
whose title says *Android* — the failing frame is `populateDependenciesForAndroidModule`. But a KMP
project with no Android target hits it too, so the real scope is **KMP**, and "wait for Android
support" is the wrong mental model. Reproduced on the stock server, so the overlay does not cause it.

A `0 K` workspace model is the tell: the import does not merely warn, it produces nothing, and then
every feature looks individually broken for one shared reason. Measured with our own doctor command
on the same KMP project:

| | modules | source roots | classpath entries |
|---|---|---|---|
| `262.9593.0`, Gradle import | **16** | 4 | **1831** |
| `263.2689.0`, Gradle import | 0 | 0 | 0 |

**A workaround was tried and does not work.** 263 restructured Gradle import into a plugin-side
model unpacked reflectively in the IDE (`ProxyUtil`, `Reflected`, `KotlinCompilationReflection`,
plus a new `language-server.workspace-import.gradle-plugin.jar`), and the cast fails between the
proxy's class and the IDE's own copy. It is not a duplicate-jar problem — the class lives in one
jar, `intellij.kotlin.base.projectModel.jar`, in both releases — so there is nothing to
de-duplicate and no small patch to apply.

The obvious escape is to skip the Gradle importer: export `workspace.json` from 262 (the server's
own `exportWorkspace` writes one), then run 263 with `buildTools` set to `""` so the JSON importer
takes over. That *does* avoid the crash — no Gradle run, no exception, `ready-for-test` arrives —
but the doctor still reports **0 modules and 0 classpath entries**. It is quieter, not better, and
a quiet empty workspace is worse than a loud one. Stay on `262.9593.0` for multiplatform.

So: run `263.2689.0` for plain Kotlin/JVM projects, where it works and adds code vision; run
`262.9593.0` for anything multiplatform. Install both and choose per workspace —
`kotlinLspDev.serverPath` is an ordinary setting, so `.vscode/settings.json` can select one:

```sh
./scripts/install.sh --version 262.9593.0                                    # multiplatform
./scripts/install.sh --version 263.2689.0 --to ~/.local/share/kotlin-lsp-263  # plain JVM
```

```jsonc
// .vscode/settings.json in a plain Kotlin/JVM project
{ "kotlinLspDev.serverPath": "/home/you/.local/share/kotlin-lsp-263" }
```

### Seeing where a request goes

The composition server logs its routing to stderr (never stdout — in stdio mode that is the
protocol):

```
[kotlin-lsp-dev]    0.004s child   initialize                    id=1 (response will be patched)
[kotlin-lsp-dev]    1.741s patched initialize                    advertised documentRangeFormattingProvider, ...
[kotlin-lsp-dev]    1.743s LOCAL   textDocument/documentHighlight id=2 -- answered here, not forwarded
[kotlin-lsp-dev]    2.119s   └     textDocument/references        3 reference(s) -> 3 in-file highlight(s) in 372ms
[kotlin-lsp-dev]    5.021s child   textDocument/foldingRange      id=4
```

`KOTLIN_LSP_DEV_LOG` selects the level: `routing` (default, one line per request), `verbose` (also
notifications and the requests this layer makes on your behalf), `trace` (also full JSON bodies,
capped by `KOTLIN_LSP_DEV_LOG_BODY`), or `off`.

**This only sees our own boundary.** It cannot show what the editor chose to send, or what it did
with a response — so it answers "what did the server return" but not "why did nothing happen in
the UI". For the other side set `"intellij.trace.server": "verbose"` in VS Code and read its output
channel. The two logs together cover the whole path.

### VS Code

`editors/vscode/` is a client for this server, at parity with the official *Kotlin by JetBrains*
extension — decompiled-source navigation, debugging, workspace export, reload, organize imports,
file templates — plus the operations the overlay adds, which the official extension cannot reach
because nothing there invokes them: the doctor report, stack-trace analysis, dependency-jar search
and copy-FQN.

It also shows indexing state in the status bar, driven by the server's own `intellij/ready-for-test`.
That matters more than it sounds: before that signal, index-backed operations answer from an
incomplete index *without failing*, which is why a rename can come back with the declaration
renamed and every usage missed.

Press F5 from the repository root for an Extension Development Host. See
[editors/vscode/README.md](editors/vscode/README.md) for what is and is not carried over, and why.

`scripts/check-client-contract.py` asserts the server still provides what that client assumes —
the commands it calls and the shapes it reads. Those assumptions are about another program, and
when one is wrong the client does not fail loudly, it quietly does nothing.

### Connecting another editor

The server speaks both transports the official VS Code extension uses:

```sh
bin/enhanced-server --stdio          # any LSP client
bin/enhanced-server --socket 9999    # listen on a TCP port
bin/enhanced-server --socket 0       # ephemeral port, announced on stdout like the stock launcher
```

**VS Code**, with the official *Kotlin by JetBrains* extension installed — start the server, then
point the extension at it instead of its own bundled copy:

```jsonc
// settings.json
{ "intellij.dev.serverPort": 9999 }
```

```sh
~/.local/share/kotlin-lsp-enhanced/bin/enhanced-server --socket 9999
```

This is the path that gets *everything*: the added in-process features and the proxy repairs.
`--vscode` is the lower-friction alternative — it overlays the extension's own bundled server in
place, so no port or settings change is needed, but the extension still launches
`bin/intellij-server` directly and therefore misses the repairs that need the outer boundary
(today: Format Selection).

**Any other client** — run `bin/enhanced-server --stdio` as the server command. The stock
`bin/intellij-server` also remains untouched and usable, minus the repairs.

### Building the pieces separately

```sh
./gradlew test              # unit-test the feature cores (downloads an IDE the first time)
./scripts/build-server.sh   # compile features vs the pinned release → build/server/language-server.overlay-<v>.jar
./scripts/install-overlay.sh /path/to/kotlin-server-<v>   # apply to a server you unpacked yourself
```

`install-overlay.sh` refuses a server whose `build.txt` differs from the pinned version, because
the overlay compiles against one release's closed API and a mismatch surfaces as a feature
silently answering nothing rather than as an install error. `ALLOW_VERSION_MISMATCH=1` overrides.

### Staying on the newest server

```sh
./scripts/fetch-dist.sh --check
```

Build numbers are not enumerable and the CDN has no index, so probing invented numbers finds
nothing — that is how this repo once sat on `262.8190.0` while `263.2689.0` was already
published. `--check` reads the build out of any installed Marketplace extension (the earliest
reliable signal), then reports the newest extension and GitHub release for comparison.

## Design notes

- [The composition server](docs/lsp-proxy.md) — how the LSP boundary reaches operations no
  in-process provider can, and the rule that keeps that honest.
- [LSP feature survey and roadmap](docs/feature-roadmap.md) — peer comparison and what is left.

## Testing

Three layers, all run by CI (`.github/workflows/ci.yml` on every push and PR). They check
different things and none substitutes for another:

| Layer | What it proves | What it can't see |
|---|---|---|
| `./gradlew test` | each feature's computation core is correct, against a real IntelliJ platform | nothing about the LSP adapters or the server |
| `./scripts/compile-check.sh` | upstream master still type-checks against the pinned release — i.e. how far the closed APIs have drifted | nothing executes |
| `./scripts/smoke-test.py` | the built overlay, applied with `install-overlay.sh`, is loaded by the real server and **answers real LSP requests** | only the features runnable on the pinned release |

The smoke test is the end-to-end one: it builds the overlay jar, applies it to a server copy the
way a user would, drives it over stdio, and asserts the responses. Its assertions are written to
fail against an **un-patched** server — e.g. the stock server already returns a `comment`-kind
fold over the same lines as our `//region` block, so the check requires the `region` kind
specifically. `--stock` enforces that property for every check (see below).

Checks assert on **applied edits**, not on an edit merely existing. `lsp.apply_edits(text, edits)`
applies a response the way a conforming client would, so a check can compare the resulting source.
An action returning a corrupt or misordered `TextEdit` passes a presence assertion and fails a
content one.

`smoke-test.py` is only the harness. Each feature supplies `smoke/check.py` defining a `FIXTURE`
(Kotlin source) and a `check(lsp, uri)`; the harness discovers them, writes every fixture into one
workspace, starts the server once, and runs each check against its own file. `--expect=<names>`
narrows the run.

A feature needing more than one file ships `smoke/project/`, whose contents are copied into the
workspace with relative paths preserved. `FIXTURE` alone is a single Kotlin file, which cannot
express what some features actually need: `type-hierarchy` uses this to put a **Java** subclass of
its Kotlin type in the workspace, which is the case its core goes through light classes to support
and which a Kotlin-only fixture would pass even if that path were broken. The combined run shares
one workspace, so prefix these files with the feature name to avoid collisions.

CI runs the suite four ways. The default combined run proves all extensions and proxy repairs
coexist in one server. `--each` starts a fresh server and workspace for every feature, proving
each check passes independently and making registration or order-dependent failures easier to
isolate. `--socket` runs the same checks over the TCP transport the VS Code extension dials,
proving the composition server is a drop-in for the stock launcher rather than a stdio-only
convenience.

`--stock` is the **negative control**, and it inverts the verdict: it drives the shipped
`bin/intellij-server` on a server with no overlay applied and requires every check to *fail*.

```sh
./scripts/smoke-test.py build/dist/kotlin-server-<v> --stock   # every check must fail
```

This matters more than it sounds. A check that passes against an unmodified server is asserting
something the shipped server already does — it would keep passing if the feature were deleted, so
it is not evidence the feature works. Two of the original three checks were in that state and were
caught by hand. It is now a harness property, and it immediately earned its place: it revealed that
a code action being added here duplicated a built-in the server already shipped, and that half of
the feature was removed as a result.

The workspace carries a `workspace.json` for the server's JSON workspace importer. That module
definition is load-bearing: without it the files are opened outside any module and index-backed
queries (the inheritor search behind `typeHierarchy/subtypes`) correctly return nothing. Gradle or
Maven fixtures would work too, at the cost of a build-tool download.

Features that are release-gated or non-additive (see the table above) ship no `smoke/` directory —
the server cannot serve them, so there is nothing to assert. Presence of `smoke/` is therefore the
signal that a feature is actually served; those features rely on their unit tests until they land.
As of `263.2689.0` every feature has one.

## Requirements

JDK 21+ for Gradle; JDK 25 for `build-server.sh`/`compile-check.sh` (upstream targets JVM 25,
and the scripts fetch their own kotlinc). The release download is ~376 MB.

## Licensing

This is the part to read before publishing or redistributing anything.

**This project's own code** — everything under `overlay/`, `scripts/`, `src/`, and the build
files — is licensed **Apache 2.0** (see [LICENSE](LICENSE)). The distributable artifact
`build/server/language-server.overlay-*.jar` contains **only these classes** — no JetBrains
code — so it is safe to publish. Our GitHub releases attach only this jar.

> **Note on the source headers.** The `.kt` files carry the upstream header
> `// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.`
> **on purpose**: each feature is written to be submitted to kotlin-lsp as a PR *verbatim*, and
> that repo requires this header on every file (contributions are Apache 2.0). It is not a claim
> that JetBrains currently holds copyright in this repo. If you decide to keep a file as
> overlay-only and never upstream it, change its header to your own attribution.

**The upstream server is NOT ours to redistribute.** [Kotlin/kotlin-lsp](https://github.com/Kotlin/kotlin-lsp)
is Apache 2.0 for its open sources (the `upstream/` submodule), but the *shipped server* is, in
its own words, "based on … proprietary parts of JetBrains Air and Fleet products, making it
partially closed-source." So:

- **We never redistribute JetBrains binaries.** You download the official server yourself from
  the [Kotlin/kotlin-lsp releases](https://github.com/Kotlin/kotlin-lsp/releases) (subject to
  JetBrains' own terms) and apply our overlay to *your* copy with `scripts/install-overlay.sh`.
- **`build-server.sh` fetches the official release and compiles our code against its jars
  locally.** The `…-enhanced.tar.gz` it produces bundles JetBrains' proprietary jars, so it is a
  **local test artifact only — do not publish or redistribute it.** Only the overlay jar is
  distributable.
- **`compile-check.sh`** likewise only compiles against the downloaded release locally; it ships
  nothing.

**In short:** publish the overlay jar (ours, Apache 2.0); never publish the enhanced server,
the release download, or anything containing `com.jetbrains.*` binaries.
