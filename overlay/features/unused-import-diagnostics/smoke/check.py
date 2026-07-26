"""Live check for the additive unused-import diagnostic provider."""

FIXTURE = """\
package smoke.unusedimports

import java.util.UUID
import java.util.ArrayList

fun freshId(): UUID = UUID.randomUUID()
"""


def check(lsp, uri):
    diagnostics = []
    unused = []
    for _ in lsp.poll(120):
        report = lsp.request("textDocument/diagnostic", {
            "textDocument": {"uri": uri},
            "identifier": None,
            "previousResultId": None,
        }) or {}
        diagnostics = report.get("items", [])
        unused = [d for d in diagnostics if d.get("code") == "UNUSED_IMPORT"]
        if unused:
            break
    if len(unused) != 1:
        raise AssertionError("expected one UNUSED_IMPORT diagnostic; got %s" % diagnostics)
    diagnostic = unused[0]
    if "java.util.ArrayList" not in diagnostic.get("message", ""):
        raise AssertionError("wrong unused import: %s" % diagnostic)
    if 1 not in diagnostic.get("tags", []):
        raise AssertionError("diagnostic is not tagged Unnecessary: %s" % diagnostic)
    return "ArrayList warned as UNUSED_IMPORT and tagged Unnecessary"
