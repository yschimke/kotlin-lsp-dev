# Convert to expression body (code action)

**Status:** ✅ runnable in the enhanced build (`codeAction` API ships in `262.8190.0`, and
code-action dispatch is additive). Verified over stdio: the action is offered on a
single-`return` function with the correct edit, alongside the built-in actions.
_Tracking: (add the upstream PR/issue URL here once opened)_

## What this adds

A `refactor.rewrite` code action that turns a block-bodied function whose body is a single
`return <expr>` into an expression body: `fun f(): Int { return 1 + 2 }` → `fun f(): Int = 1 + 2`.
Carried as a direct `WorkspaceEdit` (no command round-trip).

## Layout

| Path | Role | Verified by |
|---|---|---|
| `core/…/codeActions/ExpressionBodyComputation.kt` | detects convertible functions, computes the replacement | `src/test/kotlin/overlay/ExpressionBodyTest.kt` |
| `ext/…/LSKotlinExpressionBodyCodeActionProvider.kt` | `LSCodeActionProvider` adapter (direct edit) | `build-server.sh` + live stdio round-trip |

## Upstream target path

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/codeActions/…`

---

## Draft PR body

> **[kotlin] "Convert to expression body" code action**
>
> Adds a `refactor.rewrite` action turning a single-`return` block body into an expression body
> (`{ return e }` → `= e`), delivered as a direct `WorkspaceEdit`.
>
> **Structure** — `ExpressionBodyComputation` holds the PSI detection/replacement (free of LSP
> types); `LSKotlinExpressionBodyCodeActionProvider` maps it to a `CodeAction`.
>
> **Testing** — unit tests for single-return (converts), multi-statement (no), and
> already-expression-body (no).
