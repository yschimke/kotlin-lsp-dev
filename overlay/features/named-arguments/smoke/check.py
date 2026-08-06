"""Smoke check for the fill-arguments code action.

Applies the returned edit and compares the source, so a provider returning a plausible-looking but
wrong edit fails rather than passes.

There is deliberately no check for "add names to existing arguments": the shipped server already
offers that as a built-in intention, so the overlay does not duplicate it. That was found by
`smoke-test.py --stock`, which caught this check passing against an unmodified server.
"""

FIXTURE = """\
package smoke.namedarguments

fun configure(host: String, port: Int, secure: Boolean) {}

fun positional() {
    configure("localhost", 8080, true)
}

fun empty() {
    configure()
}
"""

FILLED = """\
    configure(
        host = TODO(),
        port = TODO(),
        secure = TODO()
    )
"""

# `    configure()` -- line 9.
EMPTY_LINE, EMPTY_CHARACTER = 9, 4


def _action_at(lsp, uri, line, character, prefix):
    actions = lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {
            "start": {"line": line, "character": character},
            "end": {"line": line, "character": character},
        },
        "context": {"diagnostics": []},
    }) or []
    found = next((a for a in actions if a.get("title", "").startswith(prefix)), None)
    if found is None:
        raise AssertionError("no %r action among %s"
                             % (prefix, [a.get("title") for a in actions]))
    if found.get("kind") != "refactor.rewrite":
        raise AssertionError("unexpected kind %r for %r" % (found.get("kind"), found["title"]))
    edits = ((found.get("edit") or {}).get("changes") or {}).get(uri) or []
    if not edits:
        raise AssertionError("%r carried no edit" % found["title"])
    return found, edits


def check(lsp, uri):
    fill, fill_edits = _action_at(lsp, uri, EMPTY_LINE, EMPTY_CHARACTER, "Fill arguments")
    filled = lsp.apply_edits(FIXTURE, fill_edits)
    if FILLED not in filled:
        raise AssertionError("fill produced unexpected source:\n%s" % filled)
    return "%r filled 3 placeholder(s)" % fill["title"]
