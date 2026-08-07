# Declaration code actions (`refactor.rewrite`)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

Four local rewrites that peers ship in some form:

| Action | Also in |
|---|---|
| **Convert to block body** | rust-analyzer, Roslyn (expression-bodied members) |
| **Add explicit type** | rust-analyzer, TypeScript, gopls |
| **Split declaration and initialization** | Roslyn, JDT-LS |
| **Flip binary expression** | rust-analyzer, Roslyn |

`Convert to block body` is the inverse of [expression-body](../expression-body/), which converts
the other way.

## Types are inferred, and an unresolvable one declines

`Add explicit type` and the split both need the type written as source. That comes from the
Analysis API, rendered *inside* the analysis session — a `KaType` cannot be carried out and
rendered later. An error type declines the action rather than emitting a bare callee name that
would not compile.

`Convert to block body` needs the return type for a different reason: it decides whether the block
gets `return`. A `Unit` function must not gain `return expr`, and a function with no declared type
has to state one, since the expression body was what determined it.

## Flipping keeps the meaning

`a < b` becomes `b > a` — the operator flips with the operands. Commutative operators keep theirs.

`&&` and `||` are declined because they short-circuit: their operand order is semantic, not
cosmetic, and swapping can change whether the right side runs at all. `-`, `/` and `%` are declined
for the plainer reason that they are not commutative.

## Live verification

`smoke/check.py` applies each edit and compares source. The explicit-type action is the one worth
running against a real server: the unit-test fixture cannot infer anything beyond built-ins, so
only the smoke check exercises inference as it will actually behave.
