# kotlin-lsp-dev

Builds **enhanced [Kotlin/kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) servers** — the
official release plus extra LSP features — from a pinned source checkout, and packages the
additions as a small overlay you apply to a server you download yourself.

kotlin-lsp cannot be built from source (it depends on JetBrains' closed-source code) and merges
no external PRs. This project adds features *around* it: each is written as upstream-ready
Kotlin, unit-tested here, and injected into the shipped server through the platform's own
`LanguageServerExtension` ServiceLoader — no forking, no patching of JetBrains jars, no bytecode
manipulation.

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
  core/       pure-PSI computation, free of LSP types — unit-tested in src/test
  ext/        LSP adapter + a per-feature LanguageServerExtension
  resources/  META-INF/services entry registering that extension
upstream/       git submodule: Kotlin/kotlin-lsp pinned at the release commit
dist.properties pinned release version (source + downloaded dist stay matched)
scripts/
  fetch-dist.sh      download + unpack the pinned release
  build-server.sh    compile features vs the release → overlay jar (+ local enhanced tarball)
  install-overlay.sh apply the overlay jar to a server you downloaded
  compile-check.sh   type-check the pinned upstream sources vs the release (drift detection)
```

## Feature lifecycle (PR-then-drop)

Each feature is meant to become an upstream PR (its README carries a ready-to-submit body).
Until it lands, the overlay carries it. **`build-server.sh` release-gates automatically**: a
feature whose LSP API isn't in the pinned release is skipped (it stays unit-tested + PR-ready and
activates once a release ships the API). When a release ships the feature itself, delete its
directory — the build drops it with no other change.

## Current features

| Feature | On `262.8190.0` | Verified |
|---|---|---|
| **Type hierarchy** (`textDocument/typeHierarchy`) — new | ✅ runnable | unit tests + live stdio (prepare + supertypes/subtypes) |
| **Region folding** (`//region`…`//endregion`) — enhancement | ✅ runnable | unit tests + live stdio (folds merge with built-in) |
| **Convert to expression body** — enhancement (code action) | ✅ runnable | unit tests + live stdio (action + edit, merged with built-ins) |
| **Code vision** code lenses (usages / implementations / run-test) — new | ⊘ release-gated — `codeLens` API postdates the release | unit tests + PR-ready adapter |
| **Closing-brace inlay hints** — enhancement | ⊘ inlay dispatch isn't additive (needs upstream merge) | unit tests + PR-ready adapter |

**Why some features aren't runnable here** — two hard platform limits, mapped and documented
per feature: (1) a feature's LSP API may **postdate** the newest public release (there is no
newer server to pin), and (2) some request types dispatch to a **single** provider rather than
merging, so they can't be enhanced by *adding* a provider (folding, code lens, and code actions
merge; inlay hints do not). Both kinds are carried unit-tested + PR-ready and activate once the
constraint lifts.

## Build & apply

```sh
git clone --recurse-submodules https://github.com/yschimke/kotlin-lsp-dev
cd kotlin-lsp-dev

./gradlew test              # unit-test the feature cores (downloads an IDE the first time)
./scripts/build-server.sh   # compile features vs the pinned release → build/server/language-server.overlay-<v>.jar

# download the official server (see github.com/Kotlin/kotlin-lsp releases), unpack it, then:
./scripts/install-overlay.sh /path/to/kotlin-server-<v>
```

Point your editor at the patched server (`bin/intellij-server --stdio`, or VS Code's
`intellij.dev.serverPort` to attach to a running one).

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
