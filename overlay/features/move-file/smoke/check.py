"""Live check for the Kotlin file-move refactoring, over `workspace/willRenameFiles`.

The fixture lives in smoke/project/ rather than in FIXTURE, because a move needs two packages and
a cross-file reference to be worth anything: the file that moves, and a file that refers to it and
must gain an import.

Against a stock server this fails: the capability is advertised but no Kotlin provider is
registered, so the request answers with nothing. If it ever starts passing there, the server has
grown its own implementation and this feature should be deleted -- see `--stock` in
scripts/smoke-test.py.
"""

# The harness writes this next to the project files; the move itself acts on smoke/project/.
FIXTURE = """\
package smoke.movefile

// The moved class and its user live in smoke/project/; see this feature's README.
fun movefilePlaceholder(): Int = 0
"""

INDEX_TIMEOUT = 120


def check(lsp, uri):
    root = lsp.root_uri
    old_uri = "%s/src/movefile/origin/Movable.kt" % root
    new_uri = "%s/src/movefile/destination/Movable.kt" % root

    # The move rewrites references, which have to be findable first.
    references = []
    for _ in lsp.poll(INDEX_TIMEOUT):
        references = lsp.request("textDocument/references", {
            "textDocument": {"uri": old_uri},
            "position": {"line": 2, "character": 11},   # `class Movable`
            "context": {"includeDeclaration": True},
        }) or []
        if len(references) > 1:
            break
    if len(references) < 2:
        raise AssertionError(
            "the user of Movable was not indexed after %ds (found %d reference(s)); the move would "
            "silently update nothing" % (INDEX_TIMEOUT, len(references)))

    edit = lsp.request("workspace/willRenameFiles", {
        "files": [{"oldUri": old_uri, "newUri": new_uri}],
    })
    if not edit:
        raise AssertionError("willRenameFiles returned nothing for a Kotlin file move")

    changes = edit.get("documentChanges") or []
    if not changes and edit.get("changes"):
        changes = [{"textDocument": {"uri": u}, "edits": e} for u, e in edit["changes"].items()]
    if not changes:
        raise AssertionError("willRenameFiles returned an empty edit: %r" % edit)

    edited = {}
    for change in changes:
        target = (change.get("textDocument") or {}).get("uri")
        if target:
            edited[target] = change.get("edits") or []

    # The moved file's package declaration must change...
    moved = edited.get(old_uri) or edited.get(new_uri)
    if not moved:
        raise AssertionError("no edit for the moved file itself; got %s" % list(edited))
    moved_text = " ".join(e.get("newText", "") for e in moved)
    if "destination" not in moved_text:
        raise AssertionError("the moved file's package was not updated to the destination: %r" % moved_text)

    # ...and the file referring to it must be updated too, or the project stops compiling.
    user_uri = "%s/src/movefile/origin/User.kt" % root
    user = edited.get(user_uri)
    if not user:
        raise AssertionError(
            "the referring file was not updated (edited: %s); a move that fixes only the moved "
            "file leaves the project broken" % list(edited))

    return "package updated to `destination` and %d edit(s) in the referring file" % len(user)
