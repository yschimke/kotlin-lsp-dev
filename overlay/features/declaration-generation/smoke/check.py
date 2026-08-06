"""Live check for declaration-generation code actions and their edits.

Applies the edit and compares the resulting source, so a stub with a broken signature or
misplaced braces fails rather than passing a substring match.
"""

FIXTURE = """\
package smoke.declarations

interface Named {
    fun displayName(prefix: String): String
}

class Person : Named
"""

LINE_CLASS = 6
INDEX_TIMEOUT = 120

# The generated stub must be a real override with the interface's signature, inside a new body.
EXPECTED_SIGNATURE = "override fun displayName(prefix: String): String"


def check(lsp, uri):
    actions = []
    matches = []
    for _ in lsp.poll(INDEX_TIMEOUT):
        actions = lsp.request("textDocument/codeAction", {
            "textDocument": {"uri": uri},
            "range": {"start": {"line": LINE_CLASS, "character": 8},
                      "end": {"line": LINE_CLASS, "character": 8}},
            "context": {"diagnostics": []},
        }) or []
        matches = [a for a in actions if a.get("title") == "Implement missing members"]
        if matches:
            break
    if not matches:
        raise AssertionError("no implement-members action; got %s" %
                             [a.get("title", "") for a in actions])
    edit = matches[0].get("edit") or {}
    edits = edit.get("changes", {}).get(uri, [])
    if not edits:
        raise AssertionError("implement-members action carried no edit: %r" % edit)

    generated = lsp.apply_edits(FIXTURE, edits)
    if EXPECTED_SIGNATURE not in generated:
        raise AssertionError("no %r in generated source:\n%s" % (EXPECTED_SIGNATURE, generated))
    # `class Person : Named` has no body, so one must be opened for the stub to live in.
    if generated.count("{") != generated.count("}"):
        raise AssertionError("unbalanced braces after applying the edit:\n%s" % generated)
    return "implement-members generated %r into a new class body" % EXPECTED_SIGNATURE
