"""Smoke check for the named-argument code actions.

Exercises both actions against a real server and applies their edits, so a provider returning a
plausible-looking but wrong edit set fails rather than passes.
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

NAMED = """\
    configure(host = "localhost", port = 8080, secure = true)
"""

FILLED = """\
    configure(
        host = TODO(),
        port = TODO(),
        secure = TODO()
    )
"""

# `    configure("localhost", 8080, true)` -- line 5, caret on the callee.
POSITIONAL_LINE, POSITIONAL_CHARACTER = 5, 4
# `    configure()` -- line 9.
EMPTY_LINE, EMPTY_CHARACTER = 9, 4


def _offset(text, position):
    lines = text.splitlines(keepends=True)
    return sum(len(line) for line in lines[:position["line"]]) + position["character"]


def _apply(text, edits):
    positioned = [
        (_offset(text, e["range"]["start"]), _offset(text, e["range"]["end"]), e["newText"])
        for e in edits
    ]
    for start, end, replacement in sorted(positioned, reverse=True):
        text = text[:start] + replacement + text[end:]
    return text


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
    _, name_edits = _action_at(lsp, uri, POSITIONAL_LINE, POSITIONAL_CHARACTER, "Add names")
    named = _apply(FIXTURE, name_edits)
    if NAMED not in named:
        raise AssertionError("naming produced unexpected source:\n%s" % named)

    fill, fill_edits = _action_at(lsp, uri, EMPTY_LINE, EMPTY_CHARACTER, "Fill arguments")
    filled = _apply(FIXTURE, fill_edits)
    if FILLED not in filled:
        raise AssertionError("fill produced unexpected source:\n%s" % filled)

    return "named %d argument(s) and filled 3 placeholder(s)" % len(name_edits)
