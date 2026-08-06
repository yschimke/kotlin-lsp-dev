"""Smoke check for the type-hierarchy feature: does the patched server answer
textDocument/prepareTypeHierarchy and typeHierarchy/{supertypes,subtypes}?

Loaded by scripts/smoke-test.py, which owns the LSP client. Contract:

    FIXTURE  Kotlin source written into the smoke workspace as <NAME>.kt
    check(lsp, uri) -> str   detail line on success; raise on failure

`lsp.request(method, params)` sends a request and returns its result. Deleting this
directory removes the feature's smoke coverage along with the feature.
"""

FIXTURE = """\
package smoke.typehierarchy

interface Shape {
    fun area(): Double
}

open class Base : Shape {
    override fun area(): Double {
        return 1.0
    }
}

class Circle(val r: Double) : Base() {
    override fun area(): Double = 3.14 * r * r
}
"""

LINE_BASE = 6  # `open class Base : Shape`

# The inheritor search runs over the index, which is still being built for a freshly opened
# workspace — poll rather than race it.
INDEX_TIMEOUT = 120


def check(lsp, uri):
    items = lsp.request("textDocument/prepareTypeHierarchy", {
        "textDocument": {"uri": uri},
        "position": {"line": LINE_BASE, "character": 11},
    })
    if not items:
        raise AssertionError("prepareTypeHierarchy on `Base` returned nothing")
    base = items[0]
    if base.get("name") != "Base":
        raise AssertionError("prepareTypeHierarchy resolved %r, expected Base" % base.get("name"))

    supertypes = lsp.request("typeHierarchy/supertypes", {"item": base}) or []
    names = sorted(item["name"] for item in supertypes)
    if "Shape" not in names:
        raise AssertionError("supertypes of Base = %s, expected to contain Shape" % names)

    # `JavaCircle` is a Java subclass shipped in smoke/project/. Requiring it here is what
    # actually exercises the light-class path the core takes -- a Kotlin-only fixture would pass
    # even if that path were broken.
    names = []
    for _ in lsp.poll(INDEX_TIMEOUT):
        subtypes = lsp.request("typeHierarchy/subtypes", {"item": base}) or []
        names = sorted(item["name"] for item in subtypes)
        if "Circle" in names and "JavaCircle" in names:
            break
    missing = [n for n in ("Circle", "JavaCircle") if n not in names]
    if missing:
        raise AssertionError("subtypes of Base = %s after %ds, missing %s"
                             % (names, INDEX_TIMEOUT, missing))
    return "prepare→Base, supertypes⊇[Shape], subtypes⊇[Circle, JavaCircle (cross-file, Java)]"
