# Move file (`workspace/willRenameFiles`)

**Status:** runnable and live-verified on the pinned `263.2689.0` release.

Moving a Kotlin file to another directory updates its package declaration and every reference to
it. In an editor this is what makes drag-and-drop in the file tree safe.

## The empty slot

The distribution ships everything except the last link:

| Piece | Ships in `263.2689.0`? |
|---|---|
| `workspace/willRenameFiles` capability, advertised for `**/*` | ✅ |
| `LSMoveFileProvider` (the provider interface) | ✅ |
| `LSMoveFileProviderBase` (drives the LSP side) | ✅ |
| `K2MoveFilesHandler` (the Kotlin platform handler) | ✅ registered |
| `LSJvmMoveDirectoryProvider` (directories) | ✅ registered |
| **a Kotlin file-move provider** | ❌ |

So the server advertises the capability and answers nothing: moving a file returns `null` on 263
and an empty edit on `262.9593.0`. This fills that slot. Everything above and below it is
upstream's — the feature is a provider plus a `RefactoringProcessor`.

Note that the API is new in `263.2689.0`: `LSMoveFileProvider` and `LSMoveFileProviderBase` do not
exist in `262.9593.0`, so `build-server.sh` release-gates this feature away there automatically.

## What the processor does

`MoveFileProcessor` sequences what `MoveFilesOrDirectoriesProcessor` would, without the dialogs
and progress UI a language server cannot show. The actual work belongs to
`MoveFileHandler.forElement(file)`, which resolves to `K2MoveFilesHandler` for Kotlin and knows how
to rewrite the package directive and retarget imports.

**Ordering is load-bearing.** Usages must be found *before* the file moves; `prepareMovedFile` must
run before the move so the handler can record what it needs; `retargetUsages` must run after,
against the map the handler populated. Move first and the old references are gone — the
refactoring then "succeeds" and updates nothing, which is the failure mode worth guarding against.

Conflicts are collected via `MoveFileHandler.detectConflicts` rather than ignored, so a name
already taken in the destination is reported instead of discovered halfway through.

## Live verification

`smoke/check.py` moves `movefile/origin/Movable.kt` to `movefile/destination/` and requires **two**
things: the moved file's package becomes `destination`, and the *referring* file is edited too. The
second is the point — a move that fixes only the moved file leaves the project broken, and would
pass a weaker check.

The fixture lives in `smoke/project/` because a move needs two packages and a cross-file reference
to mean anything.

The fixture deliberately uses no stdlib types. The smoke workspace has no Kotlin standard library
on its module path, and a `kotlin.Int` parameter makes the refactoring report a conflict —
"Class kotlin.Int … will not be accessible in module smoke" — that the fixture, not the feature, is
responsible for. That gap is tracked separately; see the roadmap.

## When to delete this

`SUPERSEDED_BY` names the classes whose appearance in a release means upstream now does this, and
`scripts/check-superseded.sh` fails the build when one shows up. At that point delete this
directory — sources, smoke check and marker together.
