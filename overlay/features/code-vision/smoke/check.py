"""Smoke check for the code-vision feature: does the patched server answer
textDocument/codeLens with our reference, implementation and run-test lenses?

Loaded by scripts/smoke-test.py, which owns the LSP client. Contract:

    FIXTURE  Kotlin source written into the smoke workspace as <NAME>.kt
    check(lsp, uri) -> str   detail line on success; raise on failure

This is the check that could not exist before 263.2689.0: earlier releases neither advertised
codeLensProvider nor shipped LSCodeLensProvider, so the request was unroutable.
"""

FIXTURE = """\
package smoke.codevision

interface Greeter {
    fun greet(): String
}

class LoudGreeter : Greeter {
    override fun greet(): String = "HI"
}

class SoftGreeter : Greeter {
    override fun greet(): String = "hi"
}

fun helper(): Int = 1

fun callsHelper(): Int = helper() + helper()

annotation class Test

@Test
fun testSomething() {
    check(helper() == 1)
}
"""

# Reference and inheritor searches run over the index, which is still being built for a freshly
# opened workspace -- poll rather than race it.
INDEX_TIMEOUT = 120


def _titles(lenses):
    return [(lens.get("command") or {}).get("title", "") for lens in lenses]


def check(lsp, uri):
    # The capability is what makes this reachable at all; assert it before the request so a
    # regression to a release without codeLens reports the real cause.
    if not lsp.capabilities.get("codeLensProvider"):
        raise AssertionError("server does not advertise codeLensProvider")

    titles = []
    for _ in lsp.poll(INDEX_TIMEOUT):
        lenses = lsp.request("textDocument/codeLens", {"textDocument": {"uri": uri}}) or []
        titles = _titles(lenses)
        if any("implementation" in t for t in titles) and any("usage" in t for t in titles):
            break

    usages = [t for t in titles if "usage" in t]
    impls = [t for t in titles if "implementation" in t]
    runs = [t for t in titles if "Run test" in t]

    if not usages:
        raise AssertionError("no usage-count lens among %s" % titles)
    if not impls:
        raise AssertionError("no implementation-count lens among %s" % titles)
    if not runs:
        raise AssertionError("no run-test lens among %s" % titles)
    # `Greeter` has exactly two implementors in the fixture; a wrong count is worse than none.
    if not any(t.startswith("2 implementations") for t in impls):
        raise AssertionError("expected a '2 implementations' lens for Greeter, got %s" % impls)

    # A lens without a command is an inert label that looks like a button. Every lens must carry
    # one, and the run lens must name the test precisely enough for a build tool to select it.
    lenses = lsp.request("textDocument/codeLens", {"textDocument": {"uri": uri}}) or []
    inert = [l for l in lenses if not ((l.get("command") or {}).get("command"))]
    if inert:
        raise AssertionError("lens(es) with no command: %s" % [(l.get("command") or {}).get("title") for l in inert])
    run = next((l for l in lenses if (l.get("command") or {}).get("title") == "\u25b6 Run test"), None)
    if run is None:
        raise AssertionError("no run-test lens")
    arguments = (run.get("command") or {}).get("arguments") or []
    test_id = arguments[1] if len(arguments) > 1 else ""
    if not test_id.endswith(".testSomething") or "." not in test_id.rstrip(".testSomething"):
        raise AssertionError("run lens does not identify the test precisely: %r" % test_id)

    return "%d usage, %d implementation, %d run lens(es), all with commands; run id %r" % (
        len(usages), len(impls), len(runs), test_id)
