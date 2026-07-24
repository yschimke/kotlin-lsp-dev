package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.ExpressionBodyComputation

/** Drives [ExpressionBodyComputation] over PSI fixtures. */
class ExpressionBodyTest : BasePlatformTestCase() {

    private fun conversionAt(text: String, marker: String): ExpressionBodyComputation.Conversion? {
        val file = myFixture.configureByText("E.kt", text)
        val offset = text.indexOf(marker) + 1
        return ExpressionBodyComputation.convertible(file, offset)
    }

    fun testSingleReturnConverts() {
        val c = conversionAt("package p\nfun f(): Int { return 1 + 2 }\n", "f(")
        assertNotNull(c)
        assertEquals("= 1 + 2", c!!.replacement)
    }

    fun testMultiStatementBodyIsNotConvertible() {
        val c = conversionAt("package p\nfun f(): Int {\n    val x = 1\n    return x\n}\n", "f(")
        assertNull(c)
    }

    fun testAlreadyExpressionBodyIsNotConvertible() {
        val c = conversionAt("package p\nfun f() = 1\n", "f(")
        assertNull(c)
    }
}
