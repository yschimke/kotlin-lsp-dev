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
  smoke-test.py      drive a patched server (stdio or TCP) and assert the features answer
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
| **Named call arguments** — new ([#175](https://github.com/Kotlin/kotlin-lsp/issues/175)) | ✅ runnable | unit tests + CI smoke test (add names + fill placeholders) |
| **Unused-import diagnostics** — enhancement ([#201](https://github.com/Kotlin/kotlin-lsp/issues/201)) | ✅ runnable | unit tests + CI smoke test (warning + `Unnecessary` tag) |
| **Workspace commands** — doctor, stack traces, dependency search, FQN | ✅ runnable | unit tests + CI smoke test (all four commands) |
| **Implement/override members** — declaration-generation code actions | ✅ runnable | unit tests + CI smoke test (direct implementation edit) |
| **Code vision** code lenses (usages / implementations / run-test) — new | ✅ runnable | unit tests + CI smoke test (usage / implementation / run-test lenses, exact counts) |
| **Closing-brace inlay hints** — enhancement | ✅ runnable | unit tests + CI smoke test (function + class hints, merged with built-ins) |
| **Range formatting capability** (`textDocument/rangeFormatting`) — repair | ✅ runnable via composition server | CI smoke test (advertised capability + real formatting edits) |
| **Document highlight** (`textDocument/documentHighlight`) — new, proxy-only | ✅ runnable via composition server | CI smoke test (exact highlight positions, decoy excluded) |

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
```

The standalone install is self-contained: it restores the release's bundled JBR 25, so the
server needs no JDK on `PATH`. Verify it end-to-end with:

```sh
./scripts/smoke-test.py ~/.local/share/kotlin-lsp-enhanced
```

### Connecting an editor

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
specifically. Run it against a stock server whenever you add or change a check:

```sh
./scripts/smoke-test.py /path/to/stock-kotlin-server-<v>   # must fail every check
```

`smoke-test.py` is only the harness. Each feature supplies `smoke/check.py` defining a `FIXTURE`
(Kotlin source) and a `check(lsp, uri)`; the harness discovers them, writes every fixture into one
workspace, starts the server once, and runs each check against its own file. `--expect=<names>`
narrows the run.

CI runs the suite three ways. The default combined run proves all extensions and proxy repairs
coexist in one server. `--each` starts a fresh server and workspace for every feature, proving
each check passes independently and making registration or order-dependent failures easier to
isolate. `--socket` runs the same checks over the TCP transport the VS Code extension dials,
proving the composition server is a drop-in for the stock launcher rather than a stdio-only
convenience.

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
