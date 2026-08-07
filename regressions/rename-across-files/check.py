"""Regression watch: does the shipped server still rename across files?

Rename is entirely upstream's -- nothing in this overlay implements it. It is watched here because
this project depends on it heavily enough to have built a guard around it (the VS Code client
blocks rename until the index is ready), and because its failure mode is silent: before indexing
completes it does not error, it renames the declaration and misses every usage in other files.

That is exactly what this asserts -- not "rename returned something", but "rename edited the
*other* file too". A check that only counted edits would pass on the broken behaviour, which is
the failure this exists to catch.

Being a regression rather than a feature, `--stock` does *not* invert its verdict: it is supposed
to pass against an unmodified server, and asserting that is the point. See `discover_features()`
in scripts/smoke-test.py.
"""

# The declaration lives in project/src/renamewatch/RenameTarget.kt; this file is opened by the
# harness as the check's own fixture and does nothing but reference it, so a plain workspace is
# still a valid one.
FIXTURE = """\
package renamewatch

fun touch(): String = RenameTarget().label()
"""

# Rename is index-backed, and an incomplete index is precisely the state this watches for -- so
# poll until the answer stops being the broken one rather than racing it.
INDEX_TIMEOUT = 180


def _changed_files(edit):
    """Every document URI a WorkspaceEdit touches, whichever shape the server used."""
    if not isinstance(edit, dict):
        return set()
    touched = set(edit.get("changes") or {})
    for change in edit.get("documentChanges") or []:
        # Rename can also return file operations (`{"kind": "rename", ...}`) alongside text edits.
        document = (change.get("textDocument") or {}) if isinstance(change, dict) else {}
        if document.get("uri"):
            touched.add(document["uri"])
    return touched


def check(lsp, uri):
    if not lsp.capabilities.get("renameProvider"):
        raise AssertionError("server does not advertise renameProvider")

    target = lsp.root_uri + "/src/renamewatch/RenameTarget.kt"
    consumer = lsp.root_uri + "/src/renamewatch/Consumer.kt"
    source = open(lsp.root + "/src/renamewatch/RenameTarget.kt").read()
    lsp.notify("textDocument/didOpen", {"textDocument": {
        "uri": target, "languageId": "kotlin", "version": 1, "text": source}})

    line = next(i for i, text in enumerate(source.splitlines()) if "class RenameTarget" in text)
    character = source.splitlines()[line].index("RenameTarget")

    touched = set()
    for _ in lsp.poll(INDEX_TIMEOUT):
        edit = lsp.request("textDocument/rename", {
            "textDocument": {"uri": target},
            "position": {"line": line, "character": character},
            "newName": "RenamedTarget",
        })
        touched = _changed_files(edit)
        if consumer in touched:
            break

    if not touched:
        raise AssertionError("rename returned no edits at all")
    if target not in touched:
        raise AssertionError("rename did not edit the declaration's own file: %s" % sorted(touched))
    if consumer not in touched:
        # The silent partial rename. Reported as its own case because it is the one that ships a
        # broken project while looking like it worked.
        raise AssertionError(
            "rename edited only %s -- the referring file Consumer.kt was not updated, which is the "
            "silent partial rename this watch exists for" % sorted(touched))

    return "renamed across %d file(s), including the referring Consumer.kt" % len(touched)
