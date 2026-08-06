# Kotlin (kotlin-lsp-dev) — VS Code client

A VS Code client for the enhanced Kotlin server this repository builds. It covers what the
official *Kotlin by JetBrains* extension does, plus the operations the overlay adds — which are
unreachable through the official extension, because nothing there invokes them.

## Scope: standalone server only

This extension **does not download a server and does not look for a JDK.** It expects a
self-contained server directory produced by `scripts/install.sh`, which already carries the
release's own JBR 25.

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

## Build

```sh
cd editors/vscode
npm install
npm run compile
```

## Borrowing from

Nothing is copied. When the remaining gaps are built, these are the references worth reading —
both Apache-2.0, so compatible with this repository:

- **doctor as a webview** — [metals-vscode `doctor.ts`](https://github.com/scalameta/metals-vscode)
- **typed custom notifications** — [rust-analyzer `editors/code/src/lsp_ext.ts`](https://github.com/rust-lang/rust-analyzer)
- **test explorer** — either project's `testExplorer` / `test_explorer.ts`
