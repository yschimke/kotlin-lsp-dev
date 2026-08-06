"""Live check for the composition server's range-formatting capability repair."""

FIXTURE = """\
package smoke.rangeformatting

fun greet(){
println("hello")
}
"""

EXPECTED = """\
package smoke.rangeformatting

fun greet() {
    println("hello")
}
"""


def _offset(text, position):
    lines = text.splitlines(keepends=True)
    return sum(len(line) for line in lines[:position["line"]]) + position["character"]


def _apply_edits(text, edits):
    positioned = [
        (_offset(text, edit["range"]["start"]),
         _offset(text, edit["range"]["end"]),
         edit["newText"])
        for edit in edits
    ]
    for start, end, replacement in sorted(positioned, reverse=True):
        text = text[:start] + replacement + text[end:]
    return text


def check(lsp, uri):
    edits = lsp.request("textDocument/rangeFormatting", {
        "textDocument": {"uri": uri},
        "range": {
            "start": {"line": 2, "character": 0},
            "end": {"line": 4, "character": 1},
        },
        "options": {"tabSize": 4, "insertSpaces": True},
    }) or []

    if not edits:
        raise AssertionError("range-formatting handler returned no edits")

    formatted = _apply_edits(FIXTURE, edits)
    if formatted != EXPECTED:
        raise AssertionError("unexpected formatted text: %r (edits: %r)" % (formatted, edits))
    if lsp.capabilities.get("documentRangeFormattingProvider") is not True:
        raise AssertionError(
            "formatting request worked, but the server did not advertise range formatting"
        )
    return "capability advertised and %d formatting edit(s) applied" % len(edits)
