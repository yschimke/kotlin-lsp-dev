# Kotlin (kotlin-lsp-dev) — VS Code client

A thin VS Code client for the enhanced Kotlin server this repository builds.

## Scope: standalone server only

This extension **does not download a server and does not look for a JDK.** It expects a
self-contained server directory produced by `scripts/install.sh`, which already carries the
release's own JBR 25.

That is a deliberate limit. Server download, mirror handling, JDK discovery and VM-option parsing
are most of the bulk of a mature LSP client (Metals has five files for it), and none of it is
needed when the server ships its own runtime and is installed by one command.

```sh
./scripts/install.sh            # → ~/.local/share/kotlin-lsp-enhanced
```

Point `kotlinLspDev.serverPath` elsewhere if you installed with `--to`.

## Why this exists

The official *Kotlin by JetBrains* extension works, but it constrains us in ways that matter:

- **Our commands are unreachable.** `workspace/executeCommand` names are advertised by the server,
  but nothing invokes them, so the doctor report, stack-trace analysis, dependency-jar search and
  copy-FQN are invisible in VS Code. Here they are palette commands.
- **Readiness is invisible.** The server emits `intellij/ready-for-test` once the workspace is
  imported and indexed. Before that, index-backed operations answer from an incomplete index
  *without failing* — a rename can come back with the declaration renamed and every usage missed,
  and nothing tells you why. This client puts that state in the status bar.
- **Transport.** It launches the server directly instead of requiring `intellij.dev.serverPort`
  and a server started by hand in another terminal.

It is also written from scratch rather than forked: the JetBrains extension is not licensed for
derivative works (its directory was moved off Apache-2.0 deliberately, in `91f49d32` upstream),
and the parts of Metals and rust-analyzer worth borrowing are the ones we have not built yet.

## Commands

| Command | Server command |
|---|---|
| Kotlin: Doctor (workspace health report) | `kotlin-lsp.doctor` |
| Kotlin: Analyze JVM stack trace | `kotlin-lsp.analyzeStackTrace` |
| Kotlin: Find text in dependency jars | `kotlin-lsp.findTextInDependencyJars` |
| Kotlin: Copy fully-qualified name | `kotlin-lsp.copyFullyQualifiedName` |
| Kotlin: Restart language server | — |

## Settings

| Setting | Meaning |
|---|---|
| `kotlinLspDev.serverPath` | Server directory; empty means `~/.local/share/kotlin-lsp-enhanced` |
| `kotlinLspDev.serverPort` | Attach to `127.0.0.1:<port>` instead of starting a server. `0` (default) starts one over stdio |
| `kotlinLspDev.log` | `KOTLIN_LSP_DEV_LOG` for the composition server: `off`, `routing`, `verbose`, `trace` |
| `kotlinLspDev.trace.server` | Trace LSP traffic in the output channel |

## Build and run

Press **F5** — from either the repository root or this folder. Both ship a
`Run Kotlin extension (dev host)` launch configuration, and each runs `npm install` and compiles
first, so there is nothing to do beforehand. (Without a launch configuration VS Code asks you to
"Select debugger" and offers Node/Python/Chrome, none of which are right.)

Or build by hand:

```sh
cd editors/vscode
npm install
npm run compile
```

In the Extension Development Host, **open a folder containing Kotlin sources** — the extension
activates on `onLanguage:kotlin`, so nothing happens until a `.kt` file is opened.

**Disable the official *Kotlin by JetBrains* extension first** — two clients claiming `.kt` files
will both start a server and fight over the same files.

## One server per project

The server's index lives in a shared cache keyed by workspace
(`~/.cache/JetBrains/analyzer/workspaces/<hash>`) and is **locked** while a server holds it —
independently of `--system-path`. So a second server on the same project cannot start, and fails
with:

```
While lock file: .../index/kotlin-server/rocks/v239/LOCK: Resource temporarily unavailable
```

Restarting cannot fix that, because the lock belongs to another process. The extension detects it,
stops rather than retrying forever, and offers to open the `kotlinLspDev.serverPort` setting.

If you already run a server yourself — to watch its routing log, or to keep the JVM warm across
editor reloads — attach to it instead of starting a second one:

```sh
~/.local/share/kotlin-lsp-enhanced/bin/enhanced-server --socket 9999
```

```jsonc
// settings.json
{ "kotlinLspDev.serverPort": 9999 }
```

## Borrowing from

Nothing is copied yet. When these are built, these are the references worth reading, both
Apache-2.0 and so compatible with this repository:

- **doctor UI** — [metals-vscode `doctor.ts`](https://github.com/scalameta/metals-vscode)
- **typed custom notifications** — [rust-analyzer `editors/code/src/lsp_ext.ts`](https://github.com/rust-lang/rust-analyzer)
- **decompiled-class viewer** — metals-vscode `classFileCustomEditor.ts` (kotlin-lsp ships a
  `decompile` command we do not surface yet)
