"""Smoke check for the extract-variable code action and its direct workspace edit."""

FIXTURE = """\
package smoke.extractvariable

fun total(): Int {
    return (1 + 2) * 3
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
    new_texts = {edit.get("newText") for edit in changes}
    if "value" not in new_texts or "val value = 1 + 2\n    " not in new_texts:
        raise AssertionError("unexpected extract-variable edits: %r" % changes)
    return "action 'Extract variable' with declaration and replacement edits"
