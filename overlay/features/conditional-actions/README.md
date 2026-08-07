# Conditional code actions (`refactor.rewrite`)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

Three `if`-shaped actions that peers ship in some form:

| Action | Also in |
|---|---|
| **Invert `if` condition** | rust-analyzer, Roslyn, IntelliJ, TypeScript |
| **Merge nested `if`** | rust-analyzer, Roslyn |
| **Convert `if` chain to `when`** | rust-analyzer (`if`→`match`), Roslyn (`if`→`switch`) |

They share a directory because they share everything that matters: each is a text rewrite of one
`if`, needing no type information, no usage search, and nothing outside the expression.

## Inversion simplifies rather than accumulating

`n > 0` inverts to `n <= 0`, not `!(n > 0)`. `!a` inverts to `a`, not `!!a`. `is` becomes `!is`.
A doubled negation is harder to read than what it replaced, so an action that produced one would
be a downgrade dressed as a refactoring.

Only an `if` with an `else` is offered: without one there is nothing to swap into, and inverting
the condition alone changes what the code does. `else if` chains are declined — they need the whole
chain restructured, which is what the `when` conversion is for.

## Merging is refused when it would move code

`if (a) { if (b) { … } }` becomes `if (a && b) { … }` only when neither `if` has an `else` and the
outer body contains *nothing but* the inner `if`. Anything else and the merge would move or drop
statements. A disjunction gets parenthesised — `(a || b) && c` — because `&&` binds tighter and
dropping the parentheses would silently change the condition.

## The chain conversion works from any branch

The caret can be in any `if` of the chain; the action rewrites the whole chain. Finding its head
means walking up through `KtContainerNodeForControlStructureBody` — Kotlin wraps control-structure
bodies, so the enclosing `if` is not the PSI parent, and treating it as one made the action
silently unavailable from every branch but the first.

At least two conditions are required: a lone `if`/`else` reads better as an `if`.

## Live verification

`smoke/check.py` applies each edit and compares source. Inverting a condition is exactly the case
where "an edit came back" proves nothing — a wrong negation produces a plausible edit and a program
that means the opposite.
