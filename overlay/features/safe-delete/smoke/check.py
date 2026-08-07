"""Smoke check for the safe-delete code action.

Requires both halves: the unused declaration is offered, and the *used* one next to it is not.
Offering to delete something in use is the only way this refactoring can do real damage.
"""

FIXTURE = """\
package smoke.safedelete

private fun unusedHelper(): Int = 1

private fun usedHelper(): Int = 2

fun caller(): Int = usedHelper()
"""

UNUSED_LINE, UNUSED_CHARACTER = 2, 12   # `unusedHelper`
USED_LINE, USED_CHARACTER = 4, 12       # `usedHelper`

INDEX_TIMEOUT = 120


def _actions_at(lsp, uri, line, character):
    return lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {"start": {"line": line, "character": character},
                  "end": {"line": line, "character": character}},
        "context": {"diagnostics": []},
    }) or []


def check(lsp, uri):
    # Usage search backs this, so it needs the index; before that everything looks unused and the
    # action would appear for the used declaration too.
    delete = None
    for _ in lsp.poll(INDEX_TIMEOUT):
        actions = _actions_at(lsp, uri, UNUSED_LINE, UNUSED_CHARACTER)
        delete = next((a for a in actions if a.get("title", "").startswith("Safe delete")), None)
        used_actions = _actions_at(lsp, uri, USED_LINE, USED_CHARACTER)
        used_delete = next((a for a in used_actions if a.get("title", "").startswith("Safe delete")), None)
        if delete is not None and used_delete is None:
            break
    if delete is None:
        raise AssertionError("no safe-delete action for the unused declaration")

    used_actions = _actions_at(lsp, uri, USED_LINE, USED_CHARACTER)
    if any(a.get("title", "").startswith("Safe delete") for a in used_actions):
        raise AssertionError("safe-delete was offered for a declaration that IS used")

    edits = ((delete.get("edit") or {}).get("changes") or {}).get(uri) or []
    applied = lsp.apply_edits(FIXTURE, edits)
    if "unusedHelper" in applied:
        raise AssertionError("the declaration was not removed:\n%s" % applied)
    if "usedHelper" not in applied or "caller" not in applied:
        raise AssertionError("more than the unused declaration was removed:\n%s" % applied)
    return "%r removed only the unused declaration" % delete["title"]
