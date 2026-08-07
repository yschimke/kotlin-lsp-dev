"""Smoke check for the inline-function code action.

Applies the edit and compares source, so a substitution that drops parentheses -- and therefore
silently changes the arithmetic -- fails rather than passes.
"""

FIXTURE = """\
package smoke.inlinefunction

fun twice(n: Int): Int = n * 2

fun total(): Int = twice(1 + 1)
"""

EXPECTED = "fun total(): Int = ((1 + 1) * 2)"

CALL_LINE = 4
CALL_CHARACTER = 19  # inside `twice(1 + 1)`


def check(lsp, uri):
    actions = lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {"start": {"line": CALL_LINE, "character": CALL_CHARACTER},
                  "end": {"line": CALL_LINE, "character": CALL_CHARACTER}},
        "context": {"diagnostics": []},
    }) or []

    inline = next((a for a in actions if a.get("title", "").startswith("Inline call")), None)
    if inline is None:
        raise AssertionError("no inline-call action among %s" % [a.get("title") for a in actions])
    if inline.get("kind") != "refactor.inline":
        raise AssertionError("unexpected kind %r" % inline.get("kind"))

    edits = ((inline.get("edit") or {}).get("changes") or {}).get(uri) or []
    if not edits:
        raise AssertionError("inline-call action carried no edit")

    applied = lsp.apply_edits(FIXTURE, edits)
    if EXPECTED not in applied:
        raise AssertionError("expected %r in:\n%s" % (EXPECTED, applied))
    return "%r produced %s" % (inline["title"], EXPECTED)
