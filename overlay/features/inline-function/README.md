# Inline function (`refactor.inline` code action)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

Replaces a call to an expression-bodied function with that expression, substituting arguments for
parameters. Completes the inline pair alongside [inline-variable](../inline-variable/).

## Scope: expression bodies only

`fun f(a: Int) = <expression>` is the overwhelmingly common Kotlin shape, and the one that inlines
by substitution without changing behaviour. A block body would need its statements lifted into the
caller, which is a different and much larger refactoring — declined rather than half-done.

## Correctness

**Arguments are resolved, not name-matched.** Parameter uses inside the body are found by resolving
each reference, so a local shadowing a parameter name is not substituted.

**Non-atomic arguments and bodies are parenthesised.** Inlining `twice(1 + 1)` where
`fun twice(n: Int) = n * 2` must give `((1 + 1) * 2)`. Without the inner parentheses it is
`1 + 1 * 2` — three instead of four, with nothing to indicate anything went wrong.

**An argument used more than once must be repeatable.** `fun sq(n: Int) = n * n` called as
`sq(compute())` would evaluate `compute()` twice where the program evaluates it once. That is a
different program, silently, so it is declined unless the argument is a name, literal or `this`.

## What it declines

| Declined | Reason |
|---|---|
| block body | Needs statements lifted into the caller. |
| recursive function | Would inline forever. |
| extension receiver, or a qualified call | The receiver would have to be substituted too. |
| trailing lambda | Not part of the parenthesised argument list. |
| `vararg`, spread argument | The mapping from arguments to the parameter is not one-to-one. |
| a parameter left to its default | No argument to substitute; guessing the default's text is not the same thing. |
| repeated non-repeatable argument | Would change how many times it is evaluated. |
| unresolved callee | Nothing to inline. |

## Live verification

`smoke/check.py` applies the edit and compares source, so a substitution that drops the
parentheses — and therefore quietly changes the arithmetic — fails rather than passes.
