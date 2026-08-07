"""Smoke check for the extract-constant code action.

Asserts that *every* occurrence is replaced, not just the selected one -- a literal appearing
three times is the case the refactoring exists for, and replacing one of them is barely worth
offering.
"""

FIXTURE = """\
package smoke.extractconstant

fun first(): Int = 3600

fun second(): Int = 3600

fun other(): Int = 60
"""

SELECTED_LINE = 2
SELECTED_START = 19
SELECTED_END = 23  # `3600`


def check(lsp, uri):
    actions = lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {"start": {"line": SELECTED_LINE, "character": SELECTED_START},
                  "end": {"line": SELECTED_LINE, "character": SELECTED_END}},
        "context": {"diagnostics": []},
    }) or []

    extract = next((a for a in actions if a.get("title", "").startswith("Extract constant")), None)
    if extract is None:
        raise AssertionError("no extract-constant action among %s" % [a.get("title") for a in actions])

    edits = ((extract.get("edit") or {}).get("changes") or {}).get(uri) or []
    if not edits:
        raise AssertionError("extract-constant action carried no edit")

    applied = lsp.apply_edits(FIXTURE, edits)
    if "private const val CONSTANT = 3600" not in applied:
        raise AssertionError("no constant declaration in:\n%s" % applied)
    # Both uses replaced, and the unrelated 60 left alone.
    if applied.count("= CONSTANT") != 2:
        raise AssertionError("expected both occurrences replaced:\n%s" % applied)
    if "= 60" not in applied:
        raise AssertionError("an unrelated literal was replaced:\n%s" % applied)
    return "declared CONSTANT and replaced both occurrences, leaving 60 alone"
