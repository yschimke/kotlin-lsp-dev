# Named call arguments (`refactor.rewrite` code actions)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

Two actions on a Kotlin call, sharing one resolution step:

- **Add names to arguments** — `configure("localhost", 8080, true)` becomes
  `configure(host = "localhost", port = 8080, secure = true)`.
- **Fill arguments** — `configure()` becomes a `TODO()` placeholder per parameter, one per line.
  This is what [Kotlin/kotlin-lsp#175](https://github.com/Kotlin/kotlin-lsp/issues/175) requested.

At most one applies to a given call: naming needs arguments, filling needs none.

## Upstream target paths

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/codeActions/…`
- registration in `LSKotlinLanguageConfiguration`

## Correctness

**Names come from the overload that actually resolved.** An overload set is not automatically
ambiguous — `g(1)` resolves to `g(a: Int)` and `g("s")` to `g(b: String)`, and each call gets the
name belonging to the function the compiler selected. Only a genuinely unresolvable or
multi-candidate callee is declined. An earlier plan for this feature assumed overloads had to be
refused wholesale; using real resolution is both simpler and more useful, and the unit tests pin
the distinction.

**Java callees are declined.** Named arguments are a Kotlin-only calling convention. Naming the
arguments of a Java method produces code that does not compile, so a call resolving to a
`PsiMethod` gets no action.

**The innermost call containing the caret wins.** In `h(g(1))` a caret inside `g(...)` acts on
`g`. A caret on a callee name acts on that call, which is where it usually sits when someone
reaches for the action.

## What it declines, and why

| Declined | Reason |
|---|---|
| callee unresolved or multi-candidate | Parameter names would be a guess, and a wrong name is a compile error to undo. |
| Java callee | Kotlin does not permit named arguments there. |
| `vararg` parameter | A name changes what the argument means. |
| spread argument (`*xs`) | Fills a vararg positionally; it cannot simply take a name. |
| already fully named | Nothing to do. |
| an unnamed argument *after* a named one | Not a legal argument list to start with; naming part of it does not help. |
| more arguments than parameters | The mapping is not positional, so any name would be wrong. |
| fill on a call that already has arguments | What is written is the user's, not ours to replace. |

Only a **leading run** of unnamed arguments is named, because Kotlin requires positional arguments
to precede named ones — so `g(1, b = 2)` gains a name on `1` alone and stays legal.

## Live verification

`smoke/check.py` requests both actions against a real server, applies the returned edits, and
compares the resulting source — including the multi-line layout of the placeholder fill.
