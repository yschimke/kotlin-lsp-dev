"""Smoke check for the extract-variable code action and its direct workspace edit.

Applies the edits and compares the resulting source, rather than inspecting `newText` in
isolation: two individually plausible edits can still combine into wrong output.
"""

FIXTURE = """\
package smoke.extractvariable

fun total(): Int {
    return (1 + 2) * 3
}
"""

# The selection is the inner `1 + 2`, not `(1 + 2)`, so the fixture's own parentheses stay put
# and wrap the substituted name. That is correct: the action replaces exactly what was selected.
EXPECTED = """\
fun total(): Int {
    val value = 1 + 2
    return (value) * 3
}
"""


def check(lsp, uri):
    actions = lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {"start": {"line": 3, "character": 12},
                  "end": {"line": 3, "character": 17}},
        "context": {"diagnostics": [], "only": ["refactor.extract"]},
    }) or []
    matches = [action for action in actions if action.get("title") == "Extract variable"]
    if not matches:
        raise AssertionError("no 'Extract variable' action; got %s" %
                             [action.get("title", "") for action in actions])
    changes = (matches[0].get("edit") or {}).get("changes", {}).get(uri, [])
    if len(changes) != 2:
        raise AssertionError("Extract variable should carry two direct text edits; got %r" % changes)

    extracted = lsp.apply_edits(FIXTURE, changes)
    if EXPECTED not in extracted:
        raise AssertionError("unexpected source after applying the edits:\n%s" % extracted)
    return "action 'Extract variable' produced `val value = 1 + 2` and substituted the use"
