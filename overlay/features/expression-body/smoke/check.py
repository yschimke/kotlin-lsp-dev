"""Smoke check for the expression-body feature: does the patched server offer the
"Convert to expression body" code action, and is the edit it carries correct?

Loaded by scripts/smoke-test.py — see that file for the module contract.

This check used to assert only that a non-empty edit came back, which a provider returning a
corrupt TextEdit would have passed. It now applies the edit and compares the source.
"""

FIXTURE = """\
package smoke.expressionbody

class Circle(val r: Double) {
    fun area(): Double {
        return 3.14 * r * r
    }
}
"""

LINE_BODY = 4  # `return 3.14 * r * r`

# The block body must become an expression body -- the assignment form, with the `return` gone.
EXPECTED = "    fun area(): Double = 3.14 * r * r\n"


def check(lsp, uri):
    actions = lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {"start": {"line": LINE_BODY, "character": 8},
                  "end": {"line": LINE_BODY, "character": 8}},
        "context": {"diagnostics": []},
    }) or []
    titles = [a.get("title", "") for a in actions]
    match = [a for a in actions if "expression body" in a.get("title", "").lower()]
    if not match:
        raise AssertionError("no 'expression body' code action in the body of area(); "
                             "got %s" % titles)

    action = match[0]
    edit = action.get("edit")
    if edit is None and action.get("data") is not None:
        action = lsp.request("codeAction/resolve", action)
        edit = action.get("edit")
    changes = (edit or {}).get("changes", {}) or (edit or {}).get("documentChanges", [])
    if not changes:
        raise AssertionError("'%s' resolved to an empty edit" % action.get("title"))

    edits = changes.get(uri) if isinstance(changes, dict) else None
    if not edits:
        raise AssertionError("edit carries no changes for %s: %r" % (uri, changes))
    converted = lsp.apply_edits(FIXTURE, edits)
    if EXPECTED not in converted:
        raise AssertionError("unexpected source after applying the edit:\n%s" % converted)
    return "action %r converted the body to %r" % (action.get("title"), EXPECTED.strip())
