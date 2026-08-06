"""Smoke check for the extract-function code action.

Checks that the generated signature carries the captured variables with their declared types, the
call site is substituted, and the extracted statements moved rather than being duplicated.
"""

FIXTURE = """\
package smoke.extractfunction

fun report(items: List<String>, prefix: String) {
    println(prefix)
    println(items.size)
    println("done")
}
"""

# The first two statements: `println(prefix)` on line 3 and `println(items.size)` on line 4.
# Both captured variables are used inside, and `println("done")` stays behind so the selection is
# not the whole body.
SELECTION = {
    "start": {"line": 3, "character": 4},
    "end": {"line": 4, "character": 23},
}


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
        "range": SELECTION,
        "context": {"diagnostics": []},
    }) or []

    extract = next(
        (a for a in actions if a.get("title", "").startswith("Extract function")), None)
    if extract is None:
        raise AssertionError("no extract-function action among %s"
                             % [a.get("title") for a in actions])
    if extract.get("kind") != "refactor.extract":
        raise AssertionError("unexpected kind %r" % extract.get("kind"))

    edits = ((extract.get("edit") or {}).get("changes") or {}).get(uri) or []
    if not edits:
        raise AssertionError("extract-function action carried no edit")

    result = _apply(FIXTURE, edits)

    # Both captured variables become parameters, carrying the types as written -- including the
    # generic argument, which a naive renderer would drop.
    expected_signature = "private fun extracted(items: List<String>, prefix: String) {"
    if expected_signature not in result:
        raise AssertionError("expected %r in:\n%s" % (expected_signature, result))
    if "    extracted(items, prefix)\n" not in result:
        raise AssertionError("call site not substituted:\n%s" % result)
    # The extracted statements must move, not be duplicated.
    if result.count("println(items.size)") != 1:
        raise AssertionError("statement duplicated rather than moved:\n%s" % result)
    if 'println("done")' not in result:
        raise AssertionError("unselected statement was lost:\n%s" % result)

    return "extracted(items: List<String>, prefix: String) with call substituted"
