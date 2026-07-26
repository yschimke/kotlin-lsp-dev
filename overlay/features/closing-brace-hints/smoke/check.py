"""Live check for closing-brace inlay hints."""

FIXTURE = """\
package smoke

class Big {
    fun longFunction() {
        val one = 1
        val two = 2
        val three = 3
    }
}
"""


def check(lsp, uri):
    hints = lsp.request("textDocument/inlayHint", {
        "textDocument": {"uri": uri},
        "range": {
            "start": {"line": 0, "character": 0},
            "end": {"line": 20, "character": 0},
        },
    })
    labels = [hint.get("label") for hint in hints]
    assert " fun longFunction" in labels, "closing-brace function hint missing: %r" % labels
    assert " class Big" in labels, "closing-brace class hint missing: %r" % labels
    return "function and class closing-brace hints returned"
