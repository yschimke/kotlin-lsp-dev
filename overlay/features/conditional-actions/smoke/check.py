"""Smoke check for the `if`-shaped code actions.

Applies each edit and compares source. Inverting a condition is exactly the kind of rewrite where
"an edit came back" proves nothing -- a wrong negation still produces a plausible-looking edit and
a program that means the opposite.
"""

FIXTURE = """\
package smoke.conditionalactions

fun classify(n: Int): Int = if (n > 0) 1 else 2

fun chain(n: Int): Int = if (n > 0) 1 else if (n < 0) 2 else 3
"""

INVERT_LINE, INVERT_CHARACTER = 2, 28   # the `if` in classify
CHAIN_LINE, CHAIN_CHARACTER = 4, 25     # the `if` in chain


def _action(lsp, uri, line, character, prefix):
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
    return found, edits


def check(lsp, uri):
    _, invert_edits = _action(lsp, uri, INVERT_LINE, INVERT_CHARACTER, "Invert")
    inverted = lsp.apply_edits(FIXTURE, invert_edits)
    # `n > 0 ? 1 : 2` inverted is `n <= 0 ? 2 : 1` -- the comparison flips rather than gaining a `!`.
    if "if (n <= 0) 2 else 1" not in inverted:
        raise AssertionError("unexpected inversion:\n%s" % inverted)

    _, when_edits = _action(lsp, uri, CHAIN_LINE, CHAIN_CHARACTER, "Convert 'if' chain")
    converted = lsp.apply_edits(FIXTURE, when_edits)
    for expected in ("when {", "n > 0 -> 1", "n < 0 -> 2", "else -> 3"):
        if expected not in converted:
            raise AssertionError("missing %r in:\n%s" % (expected, converted))

    return "condition inverted to `n <= 0` with branches swapped, and the chain converted to `when`"
