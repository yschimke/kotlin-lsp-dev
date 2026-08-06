package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.InlineVariableComputation

class InlineVariableTest : BasePlatformTestCase() {
    /** Places the caret at the first occurrence of [at] and runs the computation there. */
    private fun inlining(text: String, at: String): InlineVariableComputation.Inlining? {
        val file = myFixture.configureByText("I.kt", text)
        val offset = text.indexOf(at)
        check(offset >= 0) { "caret anchor not found: $at" }
        return InlineVariableComputation.inlineAt(file, offset)
    }

    /** Applies the inlining to [text] the way a client applies the returned edits. */
    private fun applied(text: String, at: String): String {
        val result = inlining(text, at) ?: error("no inlining offered")
        val edits = result.replacements.map { it.range to it.text } +
            (result.declarationRange to "")
        var out = text
        for ((range, replacement) in edits.sortedByDescending { it.first.startOffset }) {
            out = out.substring(0, range.startOffset) + replacement + out.substring(range.endOffset)
        }
        return out
    }

    fun testInlinesLocalValIntoItsSingleUse() {
        val result = inlining("fun f() {\n    val x = 1 + 2\n    println(x)\n}\n", "x = 1")
        assertNotNull(result)
        assertEquals("x", result!!.variableName)
        assertEquals(1, result.replacements.size)
    }

    fun testRemovesTheWholeDeclarationLine() {
        assertEquals(
            "fun f() {\n    println((1 + 2))\n}\n",
            applied("fun f() {\n    val x = 1 + 2\n    println(x)\n}\n", "x = 1"),
        )
    }

    fun testParenthesisesNonAtomicInitializer() {
        // Without parentheses this would be `1 + 2 * 10`, which is a different number.
        assertEquals(
            "fun f() {\n    println((1 + 2) * 10)\n}\n",
            applied("fun f() {\n    val x = 1 + 2\n    println(x * 10)\n}\n", "x = 1"),
        )
    }

    fun testDoesNotParenthesiseAtomicInitializer() {
        assertEquals(
            "fun f() {\n    println(compute() * 10)\n}\n",
            applied("fun f() {\n    val x = compute()\n    println(x * 10)\n}\n", "x = compute"),
        )
    }

    fun testInlinesEveryUse() {
        val result = inlining("fun f() {\n    val x = 1\n    println(x + x + x)\n}\n", "x = 1")
        assertEquals(3, result!!.replacements.size)
    }

    fun testOfferedFromAUseSiteToo() {
        val result = inlining("fun f() {\n    val x = 1\n    println(x)\n}\n", "x)")
        assertNotNull(result)
        assertEquals("x", result!!.variableName)
    }

    fun testDeclinesVar() {
        // A `var` may be reassigned between uses, so the initializer is not its value there.
        assertNull(inlining("fun f() {\n    var x = 1\n    x = 2\n    println(x)\n}\n", "x = 1"))
    }

    fun testDeclinesMemberProperty() {
        // Uses may live in files this computation never sees.
        assertNull(inlining("class C {\n    val x = 1\n    fun f() = x\n}\n", "x = 1"))
    }

    fun testDeclinesDelegatedProperty() {
        assertNull(inlining("fun f() {\n    val x by lazy { 1 }\n    println(x)\n}\n", "x by"))
    }

    fun testDeclinesWithoutInitializer() {
        assertNull(inlining("fun f(c: Boolean) {\n    val x: Int\n    if (c) x = 1 else x = 2\n}\n", "x: Int"))
    }

    fun testDeclinesUnusedVariable() {
        // Nothing to inline into; deleting it is a different action.
        assertNull(inlining("fun f() {\n    val x = 1\n    println(2)\n}\n", "x = 1"))
    }

    fun testDoesNotConfuseAShadowingDeclaration() {
        // The inner `x` shadows the outer one, so the outer has no uses to inline.
        val text = "fun f() {\n    val x = 1\n    run {\n        val x = 2\n        println(x)\n    }\n}\n"
        assertNull(inlining(text, "x = 1"))
    }

    fun testDoesNotTouchAnUnrelatedSameNamedSymbol() {
        val text = "fun x() = 9\nfun f() {\n    val x = 1\n    println(x)\n    println(x())\n}\n"
        val result = inlining(text, "x = 1")
        // Only the bare `x`, never the call to the top-level `x()`.
        assertEquals(1, result!!.replacements.size)
    }
}
