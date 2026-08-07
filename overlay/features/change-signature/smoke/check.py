"""Smoke check for removing an unused parameter, across files.

The fixture lives in smoke/project/ because the point of this refactoring is the call sites: a
parameter removed from the declaration alone leaves every caller broken, so the check asserts the
edit reaches the *other* file.
"""

FIXTURE = """\
package smoke.changesignature

// The declaration and its callers live in smoke/project/; see this feature's README.
fun changeSignaturePlaceholder(): Int = 0
"""

INDEX_TIMEOUT = 120


def check(lsp, uri):
    root = lsp.root_uri
    declaration_uri = "%s/src/changesig/Configure.kt" % root
    callers_uri = "%s/src/changesig/Callers.kt" % root

    # `unusedPort` sits on line 2 of Configure.kt.
    action = None
    for _ in lsp.poll(INDEX_TIMEOUT):
        actions = lsp.request("textDocument/codeAction", {
            "textDocument": {"uri": declaration_uri},
            "range": {"start": {"line": 2, "character": 34}, "end": {"line": 2, "character": 34}},
            "context": {"diagnostics": []},
        }) or []
        action = next((a for a in actions if a.get("title", "").startswith("Remove unused parameter")), None)
        # Wait for the index: until callers are visible the action reports 0 call sites and the
        # edit would leave them broken.
        if action is not None and "2 call sites" in action.get("title", ""):
            break
    if action is None:
        raise AssertionError("no remove-parameter action offered on `unusedPort`")
    if "2 call sites" not in action["title"]:
        raise AssertionError(
            "expected both call sites to be found, got %r -- removing the parameter without "
            "updating every caller would break the build" % action["title"])

    changes = (action.get("edit") or {}).get("changes") or {}
    if declaration_uri not in changes:
        raise AssertionError("the declaration was not edited; got %s" % list(changes))
    if callers_uri not in changes:
        raise AssertionError("the calling file was not edited; got %s" % list(changes))

    declaration = "package changesig\n\nfun configure(host: String, unusedPort: Int, secure: Boolean): String = host + secure\n"
    callers = ("package changesig\n\nfun callOne(): String = configure(\"a\", 1, true)\n\n"
               "fun callTwo(): String = configure(\"b\", 2, false)\n")

    new_declaration = lsp.apply_edits(declaration, changes[declaration_uri])
    if "unusedPort" in new_declaration:
        raise AssertionError("the parameter survived:\n%s" % new_declaration)
    if "host: String, secure: Boolean" not in new_declaration:
        raise AssertionError("the parameter list is malformed:\n%s" % new_declaration)

    new_callers = lsp.apply_edits(callers, changes[callers_uri])
    if 'configure("a", true)' not in new_callers or 'configure("b", false)' not in new_callers:
        raise AssertionError("call sites were not updated correctly:\n%s" % new_callers)

    # --- introduce parameter: the inverse, and also cross-file -------------------------------
    # `42` on line 2 of Introduce.kt.
    introduce_uri = "%s/src/changesig/Introduce.kt" % root
    introduced = None
    for _ in lsp.poll(INDEX_TIMEOUT):
        actions = lsp.request("textDocument/codeAction", {
            "textDocument": {"uri": introduce_uri},
            "range": {"start": {"line": 2, "character": 20}, "end": {"line": 2, "character": 22}},
            "context": {"diagnostics": []},
        }) or []
        introduced = next(
            (a for a in actions if a.get("title", "").startswith("Introduce parameter")), None)
        if introduced is not None and "1 call site" in introduced.get("title", ""):
            break
    if introduced is None:
        raise AssertionError("no introduce-parameter action offered")
    if "Int" not in introduced["title"]:
        raise AssertionError("the parameter type was not inferred: %r" % introduced["title"])

    introduce_changes = (introduced.get("edit") or {}).get("changes") or {}
    caller_uri = "%s/src/changesig/IntroduceCaller.kt" % root
    if introduce_uri not in introduce_changes or caller_uri not in introduce_changes:
        raise AssertionError("expected edits in both files, got %s" % list(introduce_changes))

    declaration_source = "package changesig\n\nfun answer(): Int = 42\n"
    caller_source = "package changesig\n\nfun askAnswer(): Int = answer()\n"
    new_decl = lsp.apply_edits(declaration_source, introduce_changes[introduce_uri])
    new_call = lsp.apply_edits(caller_source, introduce_changes[caller_uri])
    if "parameter: Int" not in new_decl or "= parameter" not in new_decl:
        raise AssertionError("declaration not rewritten:\n%s" % new_decl)
    if "answer(42)" not in new_call:
        raise AssertionError("call site does not pass the expression:\n%s" % new_call)

    return ("parameter removed across files; parameter introduced with inferred type and "
            "passed at the call site")
