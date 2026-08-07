# Change signature (`refactor.*` code actions)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

Two signature changes, both editing **across files** — the call sites are wherever they are, and a
signature changed only at the declaration leaves every caller broken.

| Action | What it does |
|---|---|
| **Remove unused parameter** | Deletes a parameter the body never uses, and the matching argument at every call site. |
| **Introduce parameter** | Lifts a selected expression into a new parameter, passing that expression at every call site. |

## Why only these two

Full change-signature wants a dialog: which parameter, what name, what type, what default, what
each caller should pass. A code action cannot ask, and LSP offers nothing better than
`showMessageRequest`.

These two are the cases where **nothing has to be chosen**. The removed parameter is unused, so
every caller simply drops its argument. The introduced parameter's value at each call site is the
expression that was already there. Names and positions are defaulted — appended to the list,
leaving existing positional arguments untouched — because those are cosmetic and a rename
afterwards is cheap. Shipping a version that guesses the parts with real consequences would be
worse than shipping nothing.

## The rule that makes introduce-parameter correct

**The expression must not depend on anything local to the function.** Lifting `x + 1` where `x` is
a local produces a call site that either does not compile or — worse — silently binds to a
different `x` that happens to be in scope there. So an expression referencing any local or
parameter of the function is declined, which leaves constants and outer-scope references: exactly
the magic-value case worth lifting.

The parameter's type is inferred and rendered as source; an unresolvable type declines the action
rather than emitting something that will not compile.

## What both decline

| Declined | Reason |
|---|---|
| `override`, `open`, `abstract`, `operator`, `external` | Others depend on the signature, or the platform does. |
| a `::function` reference anywhere | It keeps the old arity and would break silently. |
| trailing lambdas, spread arguments | The mapping between arguments and parameters is not positional. |
| `vararg` (removal) | Same. |
| named arguments at a call site (introduce) | Appending a positional argument after a named one is not valid Kotlin. |
| an annotated parameter (removal) | The annotation is often what makes it used from outside. |

## Live verification

`smoke/check.py` uses a multi-file fixture in `smoke/project/` and asserts the edit reaches the
*other* file in both directions — the call sites lose their argument, and the caller gains one.
It waits for the index first: until the callers are visible the action reports zero call sites,
and applying it then would break the build.
