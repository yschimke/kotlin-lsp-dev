package overlay

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.ExtractFunctionComputation
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt

class ExtractFunctionTest : BasePlatformTestCase() {
    // Parameter types come from the Analysis API, which refuses to run on the EDT that this
    // fixture uses. The server calls the same code off-EDT inside withAnalysisContext/readAction.
    @OptIn(KaAllowAnalysisOnEdt::class)
    private fun extraction(text: String, selected: String): ExtractFunctionComputation.Extraction? {
        val file = myFixture.configureByText("X.kt", text)
        val start = text.indexOf(selected)
        check(start >= 0) { "selection not found: $selected" }
        return allowAnalysisOnEdt {
            ExtractFunctionComputation.extract(file, TextRange(start, start + selected.length))
        }
    }

    /** Applies the extraction the way a client applies the returned edits. */
    private fun applied(text: String, selected: String): String {
        val result = extraction(text, selected) ?: error("no extraction offered")
        val edits = listOf(
            result.selectionRange.startOffset to result.selectionRange.endOffset to result.callText,
            result.functionInsertOffset to result.functionInsertOffset to result.functionText,
        )
        var out = text
        for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
            out = out.substring(0, range.first) + replacement + out.substring(range.second)
        }
        return out
    }

    fun testExtractsStatementsWithNoCaptures() {
        val text = "fun f() {\n    println(1)\n    println(2)\n    println(3)\n}\n"
        val result = extraction(text, "println(1)\n    println(2)")
        assertNotNull(result)
        assertEquals("extracted", result!!.functionName)
        assertEquals(emptyList<String>(), result.parameters)
        assertEquals("extracted()", result.callText)
    }

    fun testProducesCompilableSource() {
        val text = "fun f() {\n    println(1)\n    println(2)\n    println(3)\n}\n"
        assertEquals(
            "fun f() {\n" +
                "    extracted()\n" +
                "    println(3)\n" +
                "}\n" +
                "\n" +
                "private fun extracted() {\n" +
                "    println(1)\n" +
                "    println(2)\n" +
                "}\n",
            applied(text, "println(1)\n    println(2)"),
        )
    }

    fun testCapturesLocalsAsParametersWithInferredTypes() {
        val text = "fun f() {\n    val a = 1\n    println(a)\n    println(a + 1)\n    val z = 0\n}\n"
        val result = extraction(text, "println(a)\n    println(a + 1)")
        assertNotNull(result)
        assertEquals(listOf("a"), result!!.parameters)
        assertEquals("extracted(a)", result.callText)
        assertTrue(result.functionText, result.functionText.contains("private fun extracted(a: Int)"))
    }

    fun testCapturesFunctionParameters() {
        val text = "fun f(n: Int, s: String) {\n    println(n)\n    println(s)\n    println(0)\n}\n"
        val result = extraction(text, "println(n)\n    println(s)")
        assertEquals(listOf("n", "s"), result!!.parameters)
        assertTrue(
            result.functionText,
            result.functionText.contains("private fun extracted(n: Int, s: String)"),
        )
    }

    fun testAvoidsNameCollision() {
        val text = "fun extracted() {}\nfun f() {\n    println(1)\n    println(2)\n}\n"
        assertEquals("extracted2", extraction(text, "println(1)")!!.functionName)
    }

    // --- the declines ------------------------------------------------------------------------

    fun testDeclinesWhenSelectionDeclaresSomethingUsedLater() {
        // `a` would have to be returned; deciding that is not worth guessing at.
        val text = "fun f() {\n    val a = 1\n    println(a)\n}\n"
        assertNull(extraction(text, "val a = 1"))
    }

    fun testDeclinesReturn() {
        val text = "fun f(): Int {\n    println(1)\n    return 2\n}\n"
        assertNull(extraction(text, "println(1)\n    return 2"))
    }

    fun testDeclinesBreak() {
        val text = "fun f() {\n    while (true) {\n        println(1)\n        break\n    }\n}\n"
        assertNull(extraction(text, "println(1)\n        break"))
    }

    fun testDeclinesWritingToACapturedVariable() {
        // The parameter would be a local copy, so the write would not reach the caller.
        val text = "fun f() {\n    var a = 1\n    a = 2\n    println(a)\n    println(0)\n}\n"
        assertNull(extraction(text, "a = 2\n    println(a)"))
    }

    fun testDeclinesIncrementOfACapturedVariable() {
        val text = "fun f() {\n    var a = 1\n    a++\n    println(a)\n    println(0)\n}\n"
        assertNull(extraction(text, "a++\n    println(a)"))
    }

    fun testDeclinesPartialStatementSelection() {
        // That is extract-variable's job.
        assertNull(extraction("fun f() {\n    println(1 + 2)\n    println(3)\n}\n", "1 + 2"))
    }

    fun testDeclinesWholeBody() {
        assertNull(extraction("fun f() {\n    println(1)\n}\n", "println(1)"))
    }
}
