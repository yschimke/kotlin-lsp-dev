package overlay

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.ExtractVariableComputation

class ExtractVariableTest : BasePlatformTestCase() {
    private fun extraction(text: String, selected: String): ExtractVariableComputation.Extraction? {
        val file = myFixture.configureByText("E.kt", text)
        val start = text.indexOf(selected)
        return ExtractVariableComputation.extract(file, TextRange(start, start + selected.length))
    }

    fun testExtractsNestedExpressionBeforeStatement() {
        val result = extraction("fun f() {\n    println(1 + 2)\n}\n", "1 + 2")
        assertNotNull(result)
        assertEquals("val value = 1 + 2\n    ", result!!.declaration)
        assertEquals("value", result.variableName)
    }

    fun testAvoidsExistingName() {
        val result = extraction("fun f() {\n    val value = 0\n    println(1 + 2)\n}\n", "1 + 2")
        assertEquals("value2", result!!.variableName)
        assertEquals("val value2 = 1 + 2\n    ", result.declaration)
    }

    fun testRejectsPartialExpression() {
        assertNull(extraction("fun f() { println(1 + 2) }", "1 +"))
    }

    fun testRejectsWholeExpressionStatement() {
        assertNull(extraction("fun f() {\n    println(1 + 2)\n}\n", "println(1 + 2)"))
    }
}
