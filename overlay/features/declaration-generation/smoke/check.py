"""Live check for declaration-generation code actions and their edits."""

FIXTURE = """\
package smoke.declarations

interface Named {
    fun displayName(prefix: String): String
}

class Person : Named
"""

LINE_CLASS = 6
INDEX_TIMEOUT = 120


def check(lsp, uri):
    actions = []
    matches = []
    for _ in lsp.poll(INDEX_TIMEOUT):
        actions = lsp.request("textDocument/codeAction", {
            "textDocument": {"uri": uri},
            "range": {"start": {"line": LINE_CLASS, "character": 8},
                      "end": {"line": LINE_CLASS, "character": 8}},
            "context": {"diagnostics": []},
        }) or []
        matches = [a for a in actions if a.get("title") == "Implement missing members"]
        if matches:
            break
    if not matches:
        raise AssertionError("no implement-members action; got %s" %
                             [a.get("title", "") for a in actions])
    edit = matches[0].get("edit") or {}
    changes = edit.get("changes", {})
    edits = changes.get(uri, [])
    if not edits or "override fun displayName" not in edits[0].get("newText", ""):
        raise AssertionError("implement-members action has no displayName stub: %r" % edit)
    return "implement-members action with displayName override edit"
