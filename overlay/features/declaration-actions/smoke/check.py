"""Smoke check for the declaration-shaped code actions.

Applies each edit and compares source. The explicit-type action is the interesting one: it needs
the server to infer a type, which the unit-test fixture cannot do for anything beyond built-ins.
"""

FIXTURE = """\
package smoke.declarationactions

fun answer(): Int = 42

fun locals(): Int {
    val n = 1
    return n
}

fun compare(a: Int, b: Int): Boolean = a < b
"""

BODY_LINE, BODY_CHARACTER = 2, 4       # `answer`
LOCAL_LINE, LOCAL_CHARACTER = 5, 8     # `n` in `val n = 1`
FLIP_LINE, FLIP_CHARACTER = 9, 40      # `a < b`


def _edits(lsp, uri, line, character, prefix):
    actions = lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {"start": {"line": line, "character": character},
                  "end": {"line": line, "character": character}},
        "context": {"diagnostics": []},
    }) or []
    found = next((a for a in actions if a.get("title", "").startswith(prefix)), None)
    if found is None:
        raise AssertionError("no %r action among %s" % (prefix, [a.get("title") for a in actions]))
    edits = ((found.get("edit") or {}).get("changes") or {}).get(uri) or []
    if not edits:
        raise AssertionError("%r carried no edit" % found["title"])
    return edits


def check(lsp, uri):
    block = lsp.apply_edits(FIXTURE, _edits(lsp, uri, BODY_LINE, BODY_CHARACTER, "Convert to block body"))
    if "fun answer(): Int {\n    return 42\n}" not in block:
        raise AssertionError("unexpected block body:\n%s" % block)

    typed = lsp.apply_edits(FIXTURE, _edits(lsp, uri, LOCAL_LINE, LOCAL_CHARACTER, "Add explicit type"))
    if "val n: Int = 1" not in typed:
        raise AssertionError("the inferred type was not written out:\n%s" % typed)

    split = lsp.apply_edits(
        FIXTURE, _edits(lsp, uri, LOCAL_LINE, LOCAL_CHARACTER, "Split declaration"))
    if "val n: Int\n    n = 1" not in split:
        raise AssertionError("unexpected split:\n%s" % split)

    flipped = lsp.apply_edits(FIXTURE, _edits(lsp, uri, FLIP_LINE, FLIP_CHARACTER, "Flip binary"))
    if "= b > a" not in flipped:
        raise AssertionError("the operator was not flipped with the operands:\n%s" % flipped)

    return "block body, explicit type `Int`, split declaration, and `a < b` flipped to `b > a`"
