# Inline variable (`refactor.inline` code action)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

The inverse of [extract-variable](../extract-variable/), and the second of the three refactorings
in [issue #3](../../../issues/3). `textDocument/codeAction` dispatch is additive, so this is an
ordinary in-process provider.

## What it does

With the caret on a local `val` — or on any use of one — replaces every use with the initializer
and removes the declaration, including its line's indentation so no blank line is left behind.

## Upstream target paths

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/codeActions/…`
- registration in `LSKotlinLanguageConfiguration`

## Correctness

Two decisions carry the feature.

**Uses are found by resolution, not by name.** Every candidate `KtSimpleNameExpression` in the
enclosing declaration is resolved and compared against the property. Matching on text would break
on the two cases the unit tests pin: a nested scope that shadows the name, and an unrelated
top-level function that happens to share it. Both are declined or handled correctly for the same
reason — the compiler's own reference resolution decides, not a string comparison.

**The initializer is parenthesised unless it is atomic.** `val x = 1 + 2` used as `x * 10` must
become `(1 + 2) * 10`; without the parentheses it silently becomes `1 + 2 * 10`, a different
number and no error anywhere. Rather than model Kotlin's precedence table against every possible
use site, anything not self-delimiting is wrapped. Redundant parentheses are harmless; a wrong
result is not.

## What it declines, and why

Each of these is a case where the refactoring could not preserve meaning, so no action is offered
at all rather than a lossy one:

| Declined | Reason |
|---|---|
| `var` | May be reassigned between uses, so the initializer is not its value at a use site. |
| member properties | Uses can live in files this computation never sees; it is given one file. |
| delegated properties (`by lazy`) | A delegate is not a value to substitute. |
| no initializer | Nothing to inline. |
| no uses | Nothing to inline into — deleting an unused variable is a different action. |
| any use as an assignment target | Would have to remain a variable. |

A known limitation, deliberately not worked around: the initializer is substituted textually, so
a call with side effects used more than once will be evaluated more than once. IntelliJ warns and
asks in that situation; a code action has nowhere to ask. This is why the action is scoped to
local `val`s, where the pattern is overwhelmingly a named sub-expression rather than an effect.

## Live verification

`smoke/check.py` requests the action, applies the returned edits, and compares against expected
source — it asserts the parenthesisation and the removed declaration line, not merely that an
edit came back. A provider returning a corrupt or misordered edit set fails it.
