# Extract function (`refactor.extract` code action)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

The third refactoring in [issue #3](../../../issues/3), completing the extract/inline cluster
alongside [extract-variable](../extract-variable/) and [inline-variable](../inline-variable/).

## What it does

With a selection covering a contiguous run of whole statements inside a function body, replaces
them with a call to a new `private fun` inserted after the enclosing function. Local variables and
parameters the selection reads become parameters of the new function, ordered by declaration.

## Upstream target paths

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/codeActions/…`
- registration in `LSKotlinLanguageConfiguration`

## Scope: deliberately narrow

Extract-function has more ways to silently change behaviour than any other refactoring here, so it
implements the shape it can prove correct and declines the rest. **Every decline below is a case
where the result would be wrong, not merely unpolished.**

| Declined | Reason |
|---|---|
| the selection declares something read afterwards | It would have to be returned. One value is expressible, several are not, and picking is a guess. |
| `return`, `break` or `continue` in the selection | Control flow would no longer leave the original function or loop. |
| assignment or `++`/`--` to a captured variable | The parameter is a local copy; the write would never reach the caller. |
| a partial-statement selection | That is extract-variable's job. |
| the entire function body | Would only rename the function. |
| a captured type that cannot be written as source | See below. |

The result is always `Unit`-valued. A selection whose value is used afterwards falls under the
first row and is declined.

## Types

The type a captured variable contributes to the new signature is taken, in order:

1. **As the user wrote it** — `items: List<String>` is copied verbatim. This preserves their
   spelling, type aliases and nullability, and needs no analysis at all. Function parameters are
   always annotated, so this covers the common case.
2. **Inferred via the Analysis API** — for a local like `val x = compute()`.

If neither yields a type that can be written as source, the action is declined rather than
emitting something that will not compile. That check matters: an unresolved type renders as a bare
callee name — `listOf` where `List<Int>` belongs — which would produce nonsense silently.

**Known environment limitation.** In both the Gradle test fixture and the smoke workspace, Kotlin
*builtin* types (`Int`, `String`) resolve to error types when inferred, because neither workspace
carries resolvable builtins. Source-declared and written types are unaffected. In practice this
means path 1 does the work and path 2 only engages in a fully configured project. It is also why
the smoke fixture asserts a written generic type rather than an inferred one.

## Live verification

`smoke/check.py` applies the returned edits and asserts the generated signature (including the
generic argument), the substituted call site, that the extracted statements *moved* rather than
being duplicated, and that the unselected statement survived.
