# Refactoring coverage for JVM projects

What a Kotlin developer in VS Code can actually do, against the refactorings that dominate JVM
usage. "Built-in" means the shipped server provides it and we add nothing.

**All ten are supported.** Seven come from this overlay, three from the server.

| # | Refactoring | Status | Where it comes from |
|---|---|---|---|
| 1 | **Rename symbol** | ✅ built-in | `textDocument/rename`, upstream `LSKotlinRenameProvider` |
| 2 | **Extract variable** | ✅ overlay | [extract-variable](../overlay/features/extract-variable/) |
| 3 | **Extract function** | ✅ overlay | [extract-function](../overlay/features/extract-function/) |
| 4 | **Inline variable** | ✅ overlay | [inline-variable](../overlay/features/inline-variable/) |
| 5 | **Move file / class** | ✅ overlay | [move-file](../overlay/features/move-file/) |
| 6 | **Organize imports** | ✅ built-in | `source.organizeImports` |
| 7 | **Implement / override members** | ✅ overlay | [declaration-generation](../overlay/features/declaration-generation/) |
| 8 | **Inline function** | ✅ overlay | [inline-function](../overlay/features/inline-function/) |
| 9 | **Extract constant** | ✅ overlay | [extract-constant](../overlay/features/extract-constant/) |
| 10 | **Safe delete** | ✅ overlay | [safe-delete](../overlay/features/safe-delete/) |
| — | Change signature | ⬜ see below | — |
| — | Introduce parameter | ⬜ see below | — |
| — | Pull up / push down members | ⬜ not planned | — |

Also shipped and adjacent, though not usually counted as refactorings: fill named call arguments,
convert to expression body, unused-import diagnostics.

## Rename has a sharp edge

Rename works, including across files, and returns a file-rename operation when the class name
drives the file name. But **before indexing completes it silently returns a partial edit** — the
declaration renamed, every usage missed, no error. Reproduced deliberately:

```
early rename (index cold):  2 changes, Widget.kt only
after ready-for-test:       Widget.kt + UseIt.kt + file rename
```

The VS Code client shows index state in the status bar for exactly this reason. It is the strongest
argument for the "regression watch" test category discussed in issue #8: this is upstream
behaviour we depend on, and nothing currently guards it.

## On change signature and introduce parameter

Both are genuinely useful and both want a dialog — which parameter, what name, what default, what
to do at each call site. A code action has no way to ask, and LSP has no counter-offer beyond
`showMessageRequest`.

That does not rule them out, but it narrows them to the variants with one obvious answer:

- **Remove unused parameter** — no question to ask; the parameter is unused and every call site
  drops the matching argument.
- **Add parameter with a default** — call sites need no edit at all.

The open-ended forms are better left to the day the protocol or the client can carry a form.
Shipping a guessing version of change-signature would be worse than not shipping it.

## Why not just wrap IntelliJ's refactoring actions wholesale

The platform's refactoring engine is on the classpath and does the hard part — usage search,
conflict detection, PSI rewriting. Every feature here drives it rather than reimplementing it, and
`move-file` is the clearest case: the whole feature is a provider plus a processor that sequences
`MoveFileHandler`.

What cannot be reused is the *interaction*. IntelliJ's actions assume a dialog, a preview pane and
a conflicts view. Anything requiring a choice has to be either narrowed to a case with one right
answer, or left alone.
