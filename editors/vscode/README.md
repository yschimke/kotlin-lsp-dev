# Kotlin (kotlin-lsp-dev) — VS Code client

A VS Code client for the enhanced Kotlin server this repository builds. It covers what the
official *Kotlin by JetBrains* extension does, plus the operations the overlay adds — which are
unreachable through the official extension, because nothing there invokes them.

## Works with either server

Point `kotlinLspDev.serverPath` at any unpacked Kotlin language server. The extension starts
whichever launcher the directory has and adjusts to what the server actually advertises:

| Server | Launcher | What you get |
|---|---|---|
| Built by `scripts/install.sh` | `bin/enhanced-server` | Everything, including the overlay refactorings and commands |
| **A stock kotlin-lsp release** | `bin/intellij-server` | Everything the release provides; overlay-only commands are hidden |

Nothing is broken in the stock case — decompiled sources, debugging, workspace export, organize
imports, rename, file templates and the whole built-in feature set work unchanged. What is absent
is what the overlay adds, and the palette hides those entries rather than offering commands that
would fail.

The judgement is made from the server's own `initialize` result — whether it advertises any
`kotlin-lsp.*` command — so it is right regardless of how the server got there, including the
official extension's bundled copy.

## Scope: no download, no JDK discovery

This extension **does not download a server and does not look for a JDK.** A server built by
`scripts/install.sh` carries the release's own JBR 25; a stock release you unpack yourself brings
its own too.

```sh
./scripts/install.sh                        # → ~/.local/share/kotlin-lsp-enhanced
./scripts/install.sh --version 262.9593.0   # a release other than the repository pin
```

Point `kotlinLspDev.serverPath` elsewhere if you installed with `--to`.

Press **F5** from the repository root or this folder for an Extension Development Host; both ship
a launch configuration that installs and compiles first.

**Disable the official *Kotlin by JetBrains* extension first.** Both claim `.kt`, both start a
server, and only one can hold the workspace index. The extension warns about this on activation.

## Parity with the official extension

| Feature | Here |
|---|---|
| Kotlin language + file associations | ✅ also `.kts`, which the official extension omits |
| Language configuration (brackets, comments, indent, `//region` folding) | ✅ written for this repository |
| Decompiled sources — `jar:` / `jrt:` navigation | ✅ via the server's `decompile` command |
| Inlay hints pointing into jars | ✅ rewritten to a navigation command, as upstream does |
| Debugging (breakpoints, launch/attach) | ✅ via the server's `start_debug_server` |
| Restart server | ✅ |
| Clear caches and restart | ✅ scoped to this workspace's index (see below) |
| Reload workspace | ✅ |
| Export workspace to JSON | ✅ |
| Organize imports | ✅ |
| Server trace | ✅ `kotlinLspDev.trace.server` |
| Extra JVM args | ✅ `kotlinLspDev.additionalJvmArgs` |
| Build tool selection | ✅ `kotlinLspDev.buildTool` |
| File templates for new files | ⚠️ implemented, unverified — see below |
| Attach to a running server by port | ✅ `kotlinLspDev.serverPort` |
| Smart typing (tree-sitter Enter/bracket handling) | ❌ see below |
| Database / data-source integration | ❌ needs the Database extension; unrelated to Kotlin support |
| Download/remove a bundled server | ❌ out of scope — `install.sh` owns the server |
| JDK discovery for symbol resolution | ❌ out of scope — the install bundles its runtime |

**File templates are implemented but unverified.** New empty files are offered the templates
configured in `kotlinLspDev.templates`, and the chosen one is interpolated by the server's
`interpolateFileTemplate` — the same call the official extension makes, with the same arguments.
That command returned `null` for every template and file tried in a synthetic workspace, so the
path has never been seen to produce content. It degrades to doing nothing, and the contract check
asserts the command still exists, but treat it as untested until it fills a real file.

**Not ported: the tree-sitter key handler.** The official extension replaces VS Code's
indentation wholesale with ~2,200 lines driven by a `web-tree-sitter` Kotlin grammar, which is why
it sets `editor.autoIndent: "none"`. Here indentation comes from a normal `language-configuration`
with indentation and on-enter rules, so `editor.autoIndent` stays `"full"` — copying their default
without their handler would leave you with no automatic indentation at all. Raw-string trim
margins and list alignment are the cases their handler does better.

**Not bundled: a TextMate grammar.** The official extension ships one, but its directory is
licensed for use rather than reuse, so it cannot be copied here. Colouring therefore comes from
the server's semantic tokens, which is accurate but arrives only once the server is up. Installing
any Kotlin grammar extension alongside restores instant colouring; nothing else depends on it.

## What this adds beyond the official extension

| Command | What it does |
|---|---|
| **Kotlin: Doctor** | Renders the server's health report — modules, source roots, classpath, JDK. The first thing to run when nothing resolves. |
| **Kotlin: Analyze JVM stack trace** | Resolves a pasted trace to files and jumps to a frame. |
| **Kotlin: Find text in dependency jars** | Greps the classpath jars. |
| **Kotlin: Copy fully-qualified name** | At the caret. Also on the editor context menu. |
| **Kotlin: Show enhanced features in this server** | Lists the overlay features actually built in, from the install manifest. |

## Entry points beyond the command palette

| Where | What |
|---|---|
| **Code lenses** | `N usages` and `N implementations` open the peek view; **▶ Run test** runs that one test through the Gradle wrapper with `--tests`. |
| **Terminal** | JVM stack frames — `at pkg.Class.method(File.kt:42)` — become clickable and open the file at the line. |
| **Editor context menu** | Copy fully-qualified name. |
| **Status bar** | Indexing state, driven by the server's own readiness signal. |
| **Debugger** | Breakpoints, launch and attach, via the server's debug adapter. |
| **Testing panel** | Kotlin tests discovered, runnable and debuggable, with per-test results. |
| **Kotlin project view** | Explorer sidebar: modules, source roots, dependency jars browsable down to individual classes. |
| **Call/Type Hierarchy** | Built-in VS Code views, served by the language server — see below. |
| **Run Task** | Gradle lifecycle tasks (`build`, `assemble`, `check`, `test`, `clean`) for workspaces with a wrapper. |
| **Chat** | `#kotlinDoctor` and `#kotlinJars` tools, for questions the source alone cannot answer. |
| **Get Started** | A walkthrough covering install, configuration and checking the import. |

### Hierarchies are already there

`Shift+Alt+H` opens **Call Hierarchy**; the editor context menu has **Show Type Hierarchy**. Both
are built-in VS Code views that the server feeds directly, so neither needs anything from this
extension — which is exactly why they are easy to miss. Call hierarchy is the release's own
(`LSKotlinCallHierarchyProvider`); type hierarchy is one of the overlay features.

Worth checking the *concrete* provider ships and not just the base class before believing a
capability: `workspace/willRenameFiles` was advertised on `263.2689.0` while answering `null`,
because only `LSMoveFileProviderBase` was present. That is what `overlay/features/move-file`
exists to fill.

The run lens carries `pkg.Class.method`, not a bare method name: `--tests` needs the test named
precisely, and a run button that quietly runs the whole suite is worse than one that does nothing.
Which is what these lenses were until now — the server emitted them with no command at all, so
**▶ Run test** looked exactly like a run button and did nothing.

The overlay's editor features — extract function, inline variable, fill named arguments, type
hierarchy, code vision, document highlight, range formatting and the rest — need no wiring here.
They arrive as ordinary LSP responses.

## Indexing status is visible

The status bar shows `starting` → `indexing` → ✓. That last transition is the server's own
`intellij/ready-for-test`, and it matters: **before it arrives, index-backed operations answer
from an incomplete index rather than failing.** A rename can come back with the declaration
renamed and every usage missed, with no error anywhere. Commands that depend on the index warn
when it is not ready yet.

An import failure is also surfaced as an error, not left in a log — when the import fails there
are no modules and no index, and every feature then looks individually broken for one shared
reason.

### Rename waits for the index

Rename is the one operation that is *blocked* rather than merely warned about, because it is the
one that fails silently. Before the index is complete it does not error — it succeeds partially:

```
early rename (index cold):  2 changes, Widget.kt only
after ready-for-test:       Widget.kt + UseIt.kt + file rename
```

The declaration is renamed, usages in other files are missed, and nothing reports a problem. The
result is a project that no longer compiles, from an operation that looked like it worked. Every
other index-backed feature degrades *visibly* — a missing completion or an empty peek view is
obviously incomplete — which is why rename gets a guard and they get a warning.

Renaming while indexing offers **Wait for indexing** (with a cancellable progress notification) or
**Rename anyway**, which stays available for a change known to be local to one file. Waiting is the
default: the request was fine, only the timing was wrong.

The prompt appears after you confirm the new name, not when you press F2. `263.2689.0` advertises
`renameProvider: true` with no `prepareProvider`, so the editor never sends
`textDocument/prepareRename` and the earlier hook cannot fire. It is wired up anyway, so the guard
moves to the better moment on its own if a release adds prepare support.

This lives in the client, so it guards VS Code and not other editors. Doing it in the composition
server would cover every editor, but LSP has no way to ask a question mid-request — the server
could only refuse, with no way to override.

## One server per project

The workspace index lives in a cache keyed by workspace and is **locked** by whichever server
holds it, independently of `--system-path`. A second server on the same project therefore cannot
start, failing with:

```
While lock file: .../index/kotlin-server/rocks/v239/LOCK: Resource temporarily unavailable
```

By default `kotlinLspDev.isolateIndex` gives this workspace its own index under the extension's
storage, so it coexists with the official extension or a server you started yourself. It costs
disk and one re-index. With isolation off you get the shared cache and the conflict above; the
extension detects that failure, stops rather than retrying forever, and explains it.

## Settings

| Setting | Meaning |
|---|---|
| `kotlinLspDev.serverPath` | Server directory; empty means `~/.local/share/kotlin-lsp-enhanced` |
| `kotlinLspDev.serverPort` | Attach to `127.0.0.1:<port>` instead of starting a server; `0` starts one |
| `kotlinLspDev.isolateIndex` | Give this workspace its own index cache (default on) |
| `kotlinLspDev.additionalJvmArgs` | Extra JVM arguments for the server |
| `kotlinLspDev.buildTool` | Build tool to import with (null = any, `""` = none) |
| `kotlinLspDev.log` | `KOTLIN_LSP_DEV_LOG`: `off`, `routing`, `verbose`, `trace` |
| `kotlinLspDev.trace.server` | Trace LSP traffic in the output channel |

`kotlinLspDev.log` applies to a server this extension starts. When attaching, the level belongs to
the process you started:

```sh
KOTLIN_LSP_DEV_LOG=verbose ~/.local/share/kotlin-lsp-enhanced/bin/enhanced-server --socket 9999
```

## Testing panel

Tests appear in VS Code's Testing view, run individually or in groups, and report per-test results.

Discovery reuses what the server already publishes rather than adding an API: the code-vision
**▶ Run test** lens carries a runnable id and the range of the test's name, so asking the editor
for a file's code lenses *is* test discovery — over a path the smoke suite already covers.

Results come from Gradle's JUnit XML, not the exit code. One run covering several tests has a
single exit code, so scoring from it would mark every selected test failed when one failed, and the
panel would point at the wrong test. That is worse than not reporting.

The **Debug** profile adds `--debug-jvm`, which suspends the test JVM before the tests run and waits
on port 5005. The attach therefore has to happen *while* the Gradle task is still going — waiting
for the task to finish would deadlock, since it cannot finish until a debugger attaches.

## Kotlin project view

Modules, their source roots, and their dependencies, in the Explorer sidebar. Expanding a jar walks
its package tree and opens any class as decompiled source.

Every part already existed: `kotlin-lsp.doctor` knows the modules and their classpath,
`kotlin-lsp.listJarClasses` walks a jar one level at a time, and the decompiler content provider
already turns a `jar:` URI into readable source. Expansion is lazy at every level — a few hundred
jars hold hundreds of thousands of classes, so eager listing would spend nearly all its time on
nodes nobody opens.

**One upstream bug is worked around here.** Decompiling a *Kotlin* class on `263.2689.0` fails with
`no stub serializer for kotlin.PACKAGE_DIRECTIVE` when no Kotlin document has been opened in the
session. Measured directly: cold, 0 of 8 `kotlin.collections` classes decompiled; with one Kotlin
document open, 8 of 8 did, from byte-identical URIs. Java classes are unaffected. The tree is the
only way to reach a library class without having navigated from Kotlin source first, so it opens one
Kotlin file — unshown — before decompiling.

## Build and test

```sh
cd editors/vscode
npm install
npm run compile
npm test        # JUnit parser and index-gate tests
```

Two modules are deliberately free of any `vscode` import, which is what makes them testable outside
an editor — the same reason each overlay feature keeps its computation free of LSP types:

- `src/junit.ts` — result parsing, cross-checked against this repo's real Gradle reports
- `src/indexGate.ts` — the wait-for-index mechanism under the rename guard

The rename *prompt* cannot be tested (it is a modal dialog), but what sits under it can, and that
is where the risk is: a settle/cleanup race would either hang a rename forever or leave a stale
waiter behind, neither of which is visible by reading the code. Both test files were checked by
mutation — breaking the waiter cleanup and releasing only the first waiter each fail them.

## Borrowing from

Nothing is copied. When the remaining gaps are built, these are the references worth reading —
both Apache-2.0, so compatible with this repository:

- **doctor as a webview** — [metals-vscode `doctor.ts`](https://github.com/scalameta/metals-vscode)
- **typed custom notifications** — [rust-analyzer `editors/code/src/lsp_ext.ts`](https://github.com/rust-lang/rust-analyzer)
- **test explorer** — either project's `testExplorer` / `test_explorer.ts`
