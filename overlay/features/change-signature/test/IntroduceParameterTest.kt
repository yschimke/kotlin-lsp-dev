package overlay

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.IntroduceParameterComputation
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt

@OptIn(KaAllowAnalysisOnEdt::class)
class IntroduceParameterTest : BasePlatformTestCase() {
    private fun introduction(text: String, at: String): IntroduceParameterComputation.Introduction? {
        val file = myFixture.configureByText("P.kt", text)
        val start = text.indexOf(at)
        check(start >= 0) { "anchor not found: $at" }
        return allowAnalysisOnEdt {
            IntroduceParameterComputation.introduceAt(file, TextRange(start, start + at.length))
        }
    }

    private fun applied(text: String, at: String): String {
        val result = introduction(text, at) ?: error("no introduction offered")
        val edits = result.edits.single()
        var out = text
        val changes = edits.replacements.map { it.range.startOffset to (it.range.endOffset to it.text) } +
            edits.insertions.map { it.offset to (it.offset to it.text) }
        for ((start, rest) in changes.sortedByDescending { it.first }) {
            val (end, replacement) = rest
            out = out.substring(0, start) + replacement + out.substring(end)
        }
        return out
    }

    fun testLiftsLiteralIntoParameterAndPassesItAtCallSite() {
        assertEquals(
            "fun g(parameter: Int): Int = parameter\nfun f(): Int = g(42)\n",
            applied("fun g(): Int = 42\nfun f(): Int = g()\n", "42"),
        )
    }

    fun testAppendsToAnExistingParameterList() {
        assertEquals(
            "fun g(a: Int, parameter: Int): Int = a + parameter\nfun f(): Int = g(1, 42)\n",
            applied("fun g(a: Int): Int = a + 42\nfun f(): Int = g(1)\n", "42"),
        )
    }

    fun testCountsCallSites() {
        val result = introduction("fun g(): Int = 42\nfun f(): Int = g()\nfun h(): Int = g()\n", "42")
        assertEquals(2, result!!.callSiteCount)
    }

    fun testDeclinesExpressionUsingALocal() {
        // The caller cannot evaluate `x`; lifting it would produce a call site that does not
        // compile, or one that binds to a different `x` in scope there.
        assertNull(introduction("fun g(): Int {\n    val x = 1\n    return x + 1\n}\nfun f() = g()\n", "x + 1"))
    }

    fun testDeclinesExpressionUsingAParameter() {
        assertNull(introduction("fun g(a: Int): Int = a + 1\nfun f() = g(1)\n", "a + 1"))
    }

    fun testDeclinesOverride() {
        val text = "interface I { fun m(): Int }\nclass C : I {\n    override fun m(): Int = 42\n}\n"
        assertNull(introduction(text, "42"))
    }

    fun testDeclinesNamedArgumentCallSite() {
        // Appending a positional argument after a named one is not valid Kotlin.
        val text = "fun g(a: Int): Int = a + 42\nfun f(): Int = g(a = 1)\n"
        assertNull(introduction(text, "42"))
    }

    fun testDeclinesCallableReference() {
        val text = "fun g(): Int = 42\nfun f() = ::g\nfun h() = g()\n"
        assertNull(introduction(text, "42"))
    }
}
