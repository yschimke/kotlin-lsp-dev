"""Smoke check for the region-folding feature: does the patched server return a folding
range for a //region…//endregion block, without losing the built-in folds?

Loaded by scripts/smoke-test.py — see that file for the module contract.
"""

FIXTURE = """\
package smoke.regionfolding

class Widget {
    //region geometry
    fun area(): Double {
        return 1.0
    }
    //endregion
}
"""

LINE_REGION = 3     # //region geometry
LINE_ENDREGION = 7  # //endregion


def check(lsp, uri):
    ranges = lsp.request("textDocument/foldingRange", {"textDocument": {"uri": uri}}) or []

    # The kind matters. Stock 262.8190.0 already returns a range over these same two lines
    # with kind "comment" (it folds the comment block), so asserting only on line numbers
    # would pass against an un-patched server. The overlay's contribution is the kind.
    region = [r for r in ranges
              if r.get("startLine") == LINE_REGION
              and r.get("endLine") == LINE_ENDREGION
              and r.get("kind") == "region"]
    if not region:
        raise AssertionError(
            "no region-kind fold at lines %d-%d; got %s"
            % (LINE_REGION, LINE_ENDREGION,
               [(r.get("startLine"), r.get("endLine"), r.get("kind")) for r in ranges]))

    # The built-in provider must still be answering — additivity is the whole premise.
    others = [r for r in ranges if r not in region]
    if not others:
        raise AssertionError("only the region fold came back; the built-in folds went missing")
    return "region fold %d-%d + %d built-in fold(s)" % (LINE_REGION, LINE_ENDREGION, len(others))
