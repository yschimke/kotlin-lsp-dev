# Refactoring coverage

The target is the refactorings that appear in **at least two** language servers or IDEs, not just
Kotlin or JVM ones. Each row names peers that ship it, so "common" is a claim with evidence rather
than a guess. "Built-in" means the shipped server provides it and we add nothing.

**All twenty are supported.** Seventeen come from this overlay, three from the server.

| # | Refactoring | Also in | Status |
|---|---|---|---|
| 1 | **Rename symbol** | everywhere | ✅ built-in |
| 2 | **Extract variable** | rust-analyzer, gopls, clangd, Roslyn, JDT-LS | ✅ [extract-variable](../overlay/features/extract-variable/) |
| 3 | **Extract function / method** | rust-analyzer, gopls, clangd, Roslyn, JDT-LS | ✅ [extract-function](../overlay/features/extract-function/) |
| 4 | **Extract constant** | gopls, Roslyn, JDT-LS | ✅ [extract-constant](../overlay/features/extract-constant/) |
| 5 | **Inline variable** | rust-analyzer, gopls, Roslyn, JDT-LS | ✅ [inline-variable](../overlay/features/inline-variable/) |
| 6 | **Inline function** | rust-analyzer, Roslyn, JDT-LS | ✅ [inline-function](../overlay/features/inline-function/) |
| 7 | **Move file / class** | Roslyn, JDT-LS, TypeScript | ✅ [move-file](../overlay/features/move-file/) |
| 8 | **Safe delete** | Roslyn, JDT-LS | ✅ [safe-delete](../overlay/features/safe-delete/) |
| 9 | **Remove unused parameter** | Roslyn, JDT-LS, rust-analyzer | ✅ [change-signature](../overlay/features/change-signature/) |
| 10 | **Introduce parameter** | Roslyn, JDT-LS, rust-analyzer | ✅ [change-signature](../overlay/features/change-signature/) |
| 11 | **Organize imports** | everywhere | ✅ built-in |
| 12 | **Implement / override members** | Roslyn, JDT-LS, Metals, clangd | ✅ [declaration-generation](../overlay/features/declaration-generation/) |
| 13 | **Convert to expression body** | rust-analyzer, Roslyn | ✅ [expression-body](../overlay/features/expression-body/) |
| 14 | **Convert to block body** | rust-analyzer, Roslyn | ✅ [declaration-actions](../overlay/features/declaration-actions/) |
| 15 | **Invert `if` condition** | rust-analyzer, Roslyn, IntelliJ, TypeScript | ✅ [conditional-actions](../overlay/features/conditional-actions/) |
| 16 | **Merge nested `if`** | rust-analyzer, Roslyn | ✅ [conditional-actions](../overlay/features/conditional-actions/) |
| 17 | **Convert `if`-chain to `when`** | rust-analyzer (`if`→`match`), Roslyn (`if`→`switch`) | ✅ [conditional-actions](../overlay/features/conditional-actions/) |
| 18 | **Add explicit type annotation** | rust-analyzer, TypeScript, gopls | ✅ [declaration-actions](../overlay/features/declaration-actions/) |
| 19 | **Split declaration and initialization** | Roslyn, JDT-LS | ✅ [declaration-actions](../overlay/features/declaration-actions/) |
| 20 | **Flip binary expression** | rust-analyzer, Roslyn | ✅ [declaration-actions](../overlay/features/declaration-actions/) |

Adjacent and shipped, though not usually counted as refactorings: fill named call arguments,
unused-import diagnostics, region folding, type hierarchy, code vision, document highlight, range
formatting.

## Rename has a sharp edge

Rename works, including across files, and returns a file-rename operation when the class name
drives the file name. But **before indexing completes it silently returns a partial edit** — the
declaration renamed, every usage missed, no error. Reproduced deliberately:

```
early rename (index cold):  2 changes, Widget.kt only
after ready-for-test:       Widget.kt + UseIt.kt + file rename
```

The VS Code client shows index state in the status bar for exactly this reason, and **now blocks
rename until the index is ready** rather than only displaying the state — offering to wait, or to
rename anyway for a change known to be local to one file. See "Rename waits for the index" in
[the client README](../editors/vscode/README.md).

The guard is client-side. A server-side one would cover every editor, but LSP gives a server no way
to ask a question mid-request: it could only refuse, with no way to override.

## What is deliberately not built

**Open-ended change signature** — reordering parameters, changing types, choosing per-caller
values. Those need a dialog and LSP has no way to ask. The two variants where nothing has to be
chosen (rows 9 and 10) ship; guessing the rest would be worse than not shipping.

**Pull up / push down members** — needs a target-class picker.

**Encapsulate field** — largely meaningless in Kotlin, where properties already are accessors.

## Why not wrap IntelliJ's refactoring actions wholesale

The platform's refactoring engine is on the classpath and does the hard part — usage search,
conflict detection, PSI rewriting. Features here drive it rather than reimplement it, and
`move-file` is the clearest case: the whole feature is a provider plus a processor sequencing
`MoveFileHandler`.

What cannot be reused is the *interaction*. IntelliJ's actions assume a dialog, a preview pane and
a conflicts view. Anything requiring a choice must be narrowed to a case with one right answer, or
left alone.
