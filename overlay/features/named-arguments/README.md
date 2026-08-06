# Fill named call arguments (`refactor.rewrite` code action)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

With the caret on a call written with empty parentheses, fills it with a named `TODO()` per
parameter, one per line:

```kotlin
configure()
// becomes
configure(
    host = TODO(),
    port = TODO(),
    secure = TODO()
)
```

This is what [Kotlin/kotlin-lsp#175](https://github.com/Kotlin/kotlin-lsp/issues/175) asked for.

## Why there is no "add names to existing arguments"

This feature originally shipped a second action that turned `configure("localhost", 8080, true)`
into `configure(host = "localhost", …)`. **It was removed: the server already provides it.**

The stock server offers a built-in intention titled `Add names to call arguments`, of kind
`quickfix`, at exactly the same caret positions. Adding our own put a duplicate entry in every
user's code-action list for no gain.

It was found by `scripts/smoke-test.py --stock`, the negative control that requires every check to
fail against an unmodified server. This check did fail there — but for the wrong reason, tripping
over the built-in's `kind` rather than its absence. That is precisely the signal the control
exists to produce, and it took one run to surface something a passing test suite had hidden.

Worth noting the built-in only appears once analysis is ready, which is why an earlier hand probe
missed it and reported only `Organize Imports`. Any future "is this already built in?" question
needs to poll rather than ask once.

The fill half survives because the same probe showed the stock server offers **nothing** on an
empty call — `configure()` yields only `Organize Imports`, however long you wait.

## Upstream target paths

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/codeActions/…`
- registration in `LSKotlinLanguageConfiguration`

## What it declines, and why

| Declined | Reason |
|---|---|
| callee unresolved or multi-candidate | Parameter names would be a guess, and a wrong name is a compile error to undo. |
| Java callee | Kotlin does not permit named arguments there. |
| `vararg` parameter | A name changes what the argument means. |
| a call that already has arguments | What is written is the user's, not ours to replace. |
| a trailing lambda | The lambda is outside the parentheses; the call is not empty. |
| parameterless function | Nothing to fill. |

Note that an overload set is *not* automatically ambiguous — `g(1)` resolves to `g(a: Int)` and
`g("s")` to `g(b: String)`. Only a callee that genuinely fails to resolve to a single declaration
is declined. (For the empty-call case an overload set usually is ambiguous, since `g()` matches
nothing; the unit tests pin that.)

## Live verification

`smoke/check.py` requests the action against a real server, applies the returned edit, and
compares the resulting source including the multi-line layout.
