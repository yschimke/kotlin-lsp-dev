package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.DeclarationActionsComputation
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt

@OptIn(KaAllowAnalysisOnEdt::class)
class DeclarationActionsTest : BasePlatformTestCase() {
    private fun at(text: String, anchor: String): Int =
        text.indexOf(anchor).also { check(it >= 0) { "anchor not found: $anchor" } }

    private fun apply(text: String, rewrite: DeclarationActionsComputation.Rewrite?): String {
        val r = rewrite ?: error("no rewrite offered")
        return text.substring(0, r.range.startOffset) + r.text + text.substring(r.range.endOffset)
    }

    private fun file(text: String) = myFixture.configureByText("D.kt", text)

    // --- convert to block body -------------------------------------------------------------

    fun testConvertsExpressionBodyToBlock() {
        val text = "fun f(): Int = 42\n"
        val r = allowAnalysisOnEdt { DeclarationActionsComputation.toBlockBodyAt(file(text), at(text, "fun f")) }
        assertEquals("fun f(): Int {\n    return 42\n}\n", apply(text, r))
    }

    fun testConvertDeclinesBlockBody() {
        val text = "fun f(): Int {\n    return 42\n}\n"
        assertNull(allowAnalysisOnEdt { DeclarationActionsComputation.toBlockBodyAt(file(text), at(text, "fun f")) })
    }

    // --- add explicit type ------------------------------------------------------------------

    fun testAddsExplicitType() {
        val text = "fun f() {\n    val n = 1\n}\n"
        val r = allowAnalysisOnEdt { DeclarationActionsComputation.addExplicitTypeAt(file(text), at(text, "n = 1")) }
        assertEquals("fun f() {\n    val n: Int = 1\n}\n", apply(text, r))
    }

    fun testAddTypeDeclinesWhenAlreadyTyped() {
        val text = "fun f() {\n    val n: Int = 1\n}\n"
        assertNull(allowAnalysisOnEdt {
            DeclarationActionsComputation.addExplicitTypeAt(file(text), at(text, "n: Int"))
        })
    }

    // --- split declaration ------------------------------------------------------------------

    fun testSplitsDeclarationAndInitialization() {
        val text = "fun f() {\n    val n = 1\n    println(n)\n}\n"
        val r = allowAnalysisOnEdt { DeclarationActionsComputation.splitDeclarationAt(file(text), at(text, "n = 1")) }
        assertEquals("fun f() {\n    val n: Int\n    n = 1\n    println(n)\n}\n", apply(text, r))
    }

    fun testSplitKeepsVar() {
        val text = "fun f() {\n    var n = 1\n}\n"
        val out = apply(text, allowAnalysisOnEdt {
            DeclarationActionsComputation.splitDeclarationAt(file(text), at(text, "n = 1"))
        })
        assertTrue(out, out.contains("var n: Int"))
    }

    fun testSplitDeclinesTopLevelProperty() {
        // No statement position to move the assignment to.
        val text = "val n = 1\n"
        assertNull(allowAnalysisOnEdt {
            DeclarationActionsComputation.splitDeclarationAt(file(text), at(text, "n = 1"))
        })
    }

    // --- flip binary expression --------------------------------------------------------------

    fun testFlipsComparison() {
        val text = "fun f(a: Int, b: Int) = a < b\n"
        val r = DeclarationActionsComputation.flipBinaryAt(file(text), at(text, "a < b"))
        assertEquals("fun f(a: Int, b: Int) = b > a\n", apply(text, r))
    }

    fun testFlipKeepsCommutativeOperator() {
        val text = "fun f(a: Int, b: Int) = a + b\n"
        val r = DeclarationActionsComputation.flipBinaryAt(file(text), at(text, "a + b"))
        assertEquals("fun f(a: Int, b: Int) = b + a\n", apply(text, r))
    }

    fun testFlipDeclinesShortCircuit() {
        // `&&` short-circuits, so operand order is semantic rather than cosmetic.
        val text = "fun f(a: Boolean, b: Boolean) = a && b\n"
        assertNull(DeclarationActionsComputation.flipBinaryAt(file(text), at(text, "a && b")))
    }

    fun testFlipDeclinesSubtraction() {
        val text = "fun f(a: Int, b: Int) = a - b\n"
        assertNull(DeclarationActionsComputation.flipBinaryAt(file(text), at(text, "a - b")))
    }
}
