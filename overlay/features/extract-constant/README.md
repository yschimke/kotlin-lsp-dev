# Extract constant (`refactor.extract` code action)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

Lifts a literal into a file-level `private const val` and replaces **every** occurrence of it in
the file.

Replacing every occurrence is the point. A magic number appearing three times is the case the
refactoring exists for; replacing only the selected one leaves the problem behind and is barely
worth offering.

## Naming

String literals name themselves — `"max retries"` becomes `MAX_RETRIES`. Anything else becomes
`CONSTANT`, suffixed to avoid colliding with an existing declaration. The declaration is inserted
above the first declaration in the file, where a reader expects file-level constants and where
nothing can shadow it.

## What it declines

| Declined | Reason |
|---|---|
| interpolated string | Not a compile-time constant, so `const val` would not compile. |
| a literal already inside a `const val` | Lifting it again only adds indirection. |
| anything that is not a literal | `const val` requires a compile-time constant; a call is not one. |

## Live verification

`smoke/check.py` extracts a literal that appears twice and asserts both occurrences are replaced,
the declaration is created, and an unrelated nearby literal is left alone.
