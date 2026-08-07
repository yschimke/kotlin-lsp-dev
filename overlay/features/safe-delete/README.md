# Safe delete (`refactor.rewrite` code action)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

Deletes a declaration that a project-wide reference search shows nothing uses.

## "Safe" is the whole feature

The action only appears when `ReferencesSearch` finds no references. It never deletes something in
use, and never asks you to accept breakage — where IntelliJ would show a conflicts dialog, this
simply does not appear. A language server has no way to ask, so the answer has to be "don't offer".

That makes the index a correctness dependency, not a performance one: **before indexing completes
everything looks unused.** The smoke check waits for the used declaration to stop being offered
before it asserts anything, and the VS Code client shows index state in the status bar.

## Scope

Only shapes whose removal is local and provable:

- local variables and local functions
- `private` top-level functions and properties

Public declarations, class members, overrides, `operator` functions and anything annotated are
declined. Each can be reached in ways a reference search under-reports — reflection,
serialisation, dependency injection, a subclass in another module — and deleting on that evidence
would be reckless rather than safe.

## Live verification

`smoke/check.py` requires both halves: the unused declaration is offered and removed, and the
**used** declaration beside it is *not* offered. Offering to delete something in use is the only
way this refactoring can do real damage, so the negative half matters more than the positive one.
