"""Smoke check for the inline-variable code action.

Asserts the action is offered *and* that applying its edits produces the right source. A code
action that comes back with a corrupt or misordered edit set passes a presence-only assertion and
fails here, which is the point.
"""

FIXTURE = """\
package smoke.inlinevariable

fun compute(): Int = 7

fun total(): Int {
    val base = 1 + 2
    return base * 10
}
"""

EXPECTED = """\
package smoke.inlinevariable

fun compute(): Int = 7

fun total(): Int {
    return (1 + 2) * 10
}
"""

# `    val base = 1 + 2` -- line 5, with `base` starting at character 8.
DECLARATION_LINE = 5
DECLARATION_CHARACTER = 8


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


def check(lsp, uri):
    actions = lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {
            "start": {"line": DECLARATION_LINE, "character": DECLARATION_CHARACTER},
            "end": {"line": DECLARATION_LINE, "character": DECLARATION_CHARACTER},
        },
        "context": {"diagnostics": []},
    }) or []

    titles = [a.get("title", "") for a in actions]
    inline = next((a for a in actions if a.get("title", "").startswith("Inline variable")), None)
    if inline is None:
        raise AssertionError("no inline-variable action among %s" % titles)
    if inline.get("kind") != "refactor.inline":
        raise AssertionError("unexpected kind %r" % inline.get("kind"))

    edits = ((inline.get("edit") or {}).get("changes") or {}).get(uri) or []
    if not edits:
        raise AssertionError("inline-variable action carried no edit")

    applied = _apply(FIXTURE, edits)
    if applied != EXPECTED:
        raise AssertionError("unexpected result after applying edits:\n%r" % applied)

    return "%r applied cleanly, %d edit(s), parenthesised and declaration removed" % (
        inline["title"], len(edits))
