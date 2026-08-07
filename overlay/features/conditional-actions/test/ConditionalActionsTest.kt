package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.ConditionalActionsComputation

class ConditionalActionsTest : BasePlatformTestCase() {
    private fun at(text: String, anchor: String): Int =
        text.indexOf(anchor).also { check(it >= 0) { "anchor not found: $anchor" } }

    private fun apply(text: String, rewrite: ConditionalActionsComputation.Rewrite?): String {
        val r = rewrite ?: error("no rewrite offered")
        return text.substring(0, r.range.startOffset) + r.text + text.substring(r.range.endOffset)
    }

    private fun file(text: String) = myFixture.configureByText("K.kt", text)

    // --- invert ---------------------------------------------------------------------------

    fun testInvertSwapsBranchesAndNegates() {
        val text = "fun f(a: Boolean) = if (a) 1 else 2\n"
        val r = ConditionalActionsComputation.invertAt(file(text), at(text, "if ("))
        assertEquals("fun f(a: Boolean) = if (!a) 2 else 1\n", apply(text, r))
    }

    fun testInvertSimplifiesComparison() {
        val text = "fun f(n: Int) = if (n > 0) 1 else 2\n"
        val r = ConditionalActionsComputation.invertAt(file(text), at(text, "if ("))
        assertEquals("fun f(n: Int) = if (n <= 0) 2 else 1\n", apply(text, r))
    }

    fun testInvertRemovesDoubleNegation() {
        val text = "fun f(a: Boolean) = if (!a) 1 else 2\n"
        val r = ConditionalActionsComputation.invertAt(file(text), at(text, "if ("))
        assertEquals("fun f(a: Boolean) = if (a) 2 else 1\n", apply(text, r))
    }

    fun testInvertDeclinesWithoutElse() {
        val text = "fun f(a: Boolean) {\n    if (a) println(1)\n}\n"
        assertNull(ConditionalActionsComputation.invertAt(file(text), at(text, "if (")))
    }

    fun testInvertDeclinesElseIfChain() {
        val text = "fun f(n: Int) = if (n > 0) 1 else if (n < 0) 2 else 3\n"
        assertNull(ConditionalActionsComputation.invertAt(file(text), at(text, "if (n > 0)")))
    }

    // --- merge nested ---------------------------------------------------------------------

    fun testMergesNestedIf() {
        val text = "fun f(a: Boolean, b: Boolean) {\n    if (a) {\n        if (b) {\n            println(1)\n        }\n    }\n}\n"
        val r = ConditionalActionsComputation.mergeNestedAt(file(text), at(text, "if (a)"))
        assertTrue(apply(text, r), apply(text, r).contains("if (a && b)"))
    }

    fun testMergeParenthesisesDisjunction() {
        val text = "fun f(a: Boolean, b: Boolean, c: Boolean) {\n    if (a || b) {\n        if (c) {\n            println(1)\n        }\n    }\n}\n"
        val out = apply(text, ConditionalActionsComputation.mergeNestedAt(file(text), at(text, "if (a ||")))
        assertTrue(out, out.contains("if ((a || b) && c)"))
    }

    fun testMergeDeclinesWhenOuterHasElse() {
        val text = "fun f(a: Boolean, b: Boolean) {\n    if (a) {\n        if (b) println(1)\n    } else println(2)\n}\n"
        assertNull(ConditionalActionsComputation.mergeNestedAt(file(text), at(text, "if (a)")))
    }

    fun testMergeDeclinesWhenOuterBodyHasMore() {
        val text = "fun f(a: Boolean, b: Boolean) {\n    if (a) {\n        println(0)\n        if (b) println(1)\n    }\n}\n"
        assertNull(ConditionalActionsComputation.mergeNestedAt(file(text), at(text, "if (a)")))
    }

    // --- if chain to when -----------------------------------------------------------------

    fun testConvertsChainToWhen() {
        val text = "fun f(n: Int) = if (n > 0) 1 else if (n < 0) 2 else 3\n"
        val out = apply(text, ConditionalActionsComputation.toWhenAt(file(text), at(text, "if (n > 0)")))
        assertTrue(out, out.contains("when {"))
        assertTrue(out, out.contains("n > 0 -> 1"))
        assertTrue(out, out.contains("n < 0 -> 2"))
        assertTrue(out, out.contains("else -> 3"))
    }

    fun testConvertsFromInnerBranchToo() {
        // The caret in the second branch still rewrites the whole chain.
        val text = "fun f(n: Int) = if (n > 0) 1 else if (n < 0) 2 else 3\n"
        val out = apply(text, ConditionalActionsComputation.toWhenAt(file(text), at(text, "if (n < 0)")))
        assertTrue(out, out.contains("n > 0 -> 1"))
    }

    fun testWhenDeclinesSingleIf() {
        val text = "fun f(a: Boolean) = if (a) 1 else 2\n"
        assertNull(ConditionalActionsComputation.toWhenAt(file(text), at(text, "if (")))
    }
}
