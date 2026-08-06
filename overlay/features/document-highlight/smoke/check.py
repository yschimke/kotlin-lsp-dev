"""Live check for the composition server's locally-answered documentHighlight.

The shipped server has no highlight handler and no provider interface, so this exercises the
one path that can serve it: the composition server advertises the capability and answers the
request out of the child's own textDocument/references.

Against the stock launcher both halves of this check fail -- the capability is absent and the
request goes unanswered -- which is the negative control the harness wants.
"""

FIXTURE = """\
package smoke.documenthighlight

fun target(): Int = 1

fun useOnce(): Int = target()

fun useTwice(): Int = target() + target()

fun decoy(): Int = 2
"""

# `fun target(): Int = 1` -- the declaration is on line 2, `target` starts at character 4.
DECLARATION_LINE = 2
DECLARATION_CHARACTER = 4

# References run over the index, which is still being built for a freshly opened workspace.
INDEX_TIMEOUT = 120


def check(lsp, uri):
    if lsp.capabilities.get("documentHighlightProvider") is not True:
        raise AssertionError("server does not advertise documentHighlightProvider")

    highlights = []
    for _ in lsp.poll(INDEX_TIMEOUT):
        highlights = lsp.request("textDocument/documentHighlight", {
            "textDocument": {"uri": uri},
            "position": {"line": DECLARATION_LINE, "character": DECLARATION_CHARACTER},
        }) or []
        # declaration + three call sites
        if len(highlights) >= 4:
            break

    if not highlights:
        raise AssertionError("documentHighlight returned nothing for `target`")

    for highlight in highlights:
        if "range" not in highlight:
            raise AssertionError("highlight without a range: %r" % highlight)

    lines = sorted(h["range"]["start"]["line"] for h in highlights)
    # The declaration (2), the single use (4) and both uses on line 6. `decoy` must not appear.
    if lines != [2, 4, 6, 6]:
        raise AssertionError(
            "expected highlights on lines [2, 4, 6, 6] for `target`, got %s" % lines)

    return "%d highlights for `target` on lines %s (declaration + 3 uses)" % (len(highlights), lines)
