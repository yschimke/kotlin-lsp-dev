# Workspace command surface

Status: **runnable on 262.8190.0**; `workspace/executeCommand` combines descriptors from every
provider. Tracking: [kotlin-lsp-dev#6](https://github.com/yschimke/kotlin-lsp-dev/issues/6).

## Commands

All arguments are JSON values in the `arguments` array of `workspace/executeCommand`.

| Command | Arguments | Result |
|---|---|---|
| `kotlin-lsp.doctor` | none | Project name, resolved JDK, modules, source roots, classpath, and a `healthy` summary. |
| `kotlin-lsp.analyzeStackTrace` | JVM stack trace string | LSP `Location[]` for frames whose source file is in the workspace or dependencies. |
| `kotlin-lsp.findTextInDependencyJars` | non-empty search string | Up to 100 `{jar, entry, line, text}` matches across dependency jars. |
| `kotlin-lsp.copyFullyQualifiedName` | document URI string, character offset integer | Fully-qualified name of the enclosing Kotlin declaration, or `null`. |

The names are deliberately namespaced because command-name collisions fail server startup.
Malformed arguments return LSP `InvalidParams` errors. Jar search skips entries over 4 MiB and
unreadable/non-text entries rather than failing the entire search.

## Verification

- Core parsing, PSI name resolution, and jar scanning are covered by Gradle unit tests.
- Live stdio smoke coverage invokes all four registered commands. Verified against a patched
  `262.8190.0` server: the doctor reports the imported smoke module, a pasted frame resolves to its
  Kotlin document, FQN lookup returns `smoke.commands.CommandTarget.execute`, and jar search returns
  its documented array shape.

## Upstream target

The computation belongs under
`features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/commands/`; the descriptor
provider should be added to `LSKotlinConfiguration`.

### Draft PR body

Add four namespaced, editor-agnostic workspace commands: a workspace/JDK health report, JVM stack
trace source resolution, dependency-jar text search, and Kotlin fully-qualified-name lookup. The
commands use the existing additive `LSCommandDescriptorProvider` surface and require no protocol
extension. Tests cover the standalone computations and execute each descriptor over stdio.
