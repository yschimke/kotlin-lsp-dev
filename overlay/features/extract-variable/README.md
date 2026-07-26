# Extract variable (code action)

**Status:** ✅ runnable in the enhanced build (`codeAction` dispatch is additive). Verified over
stdio against the patched `262.8190.0` server: selecting `1 + 2` offers the action and returns the
declaration and replacement as a direct `WorkspaceEdit`.
_Tracking: [kotlin-lsp-dev issue #3](https://github.com/yschimke/kotlin-lsp-dev/issues/3)_

## What this adds

A `refactor.extract` action for an exactly selected expression inside a block. It inserts a local
`val` immediately before the containing statement and replaces the expression with the generated
name. The name is collision-safe within the block (`value`, `value2`, …).

## Layout

| Path | Role | Verified by |
|---|---|---|
| `core/…/codeActions/ExtractVariableComputation.kt` | resolves the selection and computes both edits | `test/ExtractVariableTest.kt` |
| `ext/…/LSKotlinExtractVariableCodeActionProvider.kt` | `LSCodeActionProvider` adapter | `build-server.sh` + live stdio smoke test |

## Upstream target path

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/codeActions/…`

---

## Draft PR body

> **[kotlin] Extract variable code action**
>
> Adds a `refactor.extract` action that extracts an exactly selected expression into a local
> `val`, delivered as a direct `WorkspaceEdit`.
>
> The pure-PSI computation resolves the selection, finds the containing block statement,
> preserves indentation, and generates a block-local collision-free name. Unit tests cover the
> successful edit, name collisions, partial selections, and whole expression statements.
