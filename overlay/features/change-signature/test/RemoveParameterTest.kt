package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.RemoveParameterComputation

class RemoveParameterTest : BasePlatformTestCase() {
    private fun removal(text: String, at: String): RemoveParameterComputation.Removal? {
        val file = myFixture.configureByText("R.kt", text)
        val offset = text.indexOf(at)
        check(offset >= 0) { "anchor not found: $at" }
        return RemoveParameterComputation.removalAt(file, offset)
    }

    /** Applies every range for the single fixture file. */
    private fun applied(text: String, at: String): String {
        val result = removal(text, at) ?: error("no removal offered")
        val ranges = result.edits.single().ranges.sortedByDescending { it.startOffset }
        var out = text
        for (range in ranges) out = out.substring(0, range.startOffset) + out.substring(range.endOffset)
        return out
    }

    fun testRemovesMiddleParameterAndArgument() {
        assertEquals(
            "fun g(a: Int, c: Int) = a + c\nfun f() = g(1, 3)\n",
            applied("fun g(a: Int, b: Int, c: Int) = a + c\nfun f() = g(1, 2, 3)\n", "b: Int"),
        )
    }

    fun testRemovesFirstParameter() {
        assertEquals(
            "fun g(b: Int) = b\nfun f() = g(2)\n",
            applied("fun g(a: Int, b: Int) = b\nfun f() = g(1, 2)\n", "a: Int"),
        )
    }

    fun testRemovesLastParameter() {
        assertEquals(
            "fun g(a: Int) = a\nfun f() = g(1)\n",
            applied("fun g(a: Int, b: Int) = a\nfun f() = g(1, 2)\n", "b: Int"),
        )
    }

    fun testRemovesOnlyParameter() {
        assertEquals(
            "fun g() = 0\nfun f() = g()\n",
            applied("fun g(a: Int) = 0\nfun f() = g(1)\n", "a: Int"),
        )
    }

    fun testHandlesNamedArguments() {
        assertEquals(
            "fun g(a: Int, c: Int) = a + c\nfun f() = g(a = 1, c = 3)\n",
            applied("fun g(a: Int, b: Int, c: Int) = a + c\nfun f() = g(a = 1, b = 2, c = 3)\n", "b: Int"),
        )
    }

    fun testCountsCallSites() {
        val result = removal("fun g(a: Int, b: Int) = a\nfun f() = g(1, 2)\nfun h() = g(3, 4)\n", "b: Int")
        assertEquals(2, result!!.callSiteCount)
    }

    fun testDeclinesUsedParameter() {
        assertNull(removal("fun g(a: Int, b: Int) = a + b\nfun f() = g(1, 2)\n", "b: Int"))
    }

    fun testDeclinesOverride() {
        val text = "interface I { fun m(a: Int) }\nclass C : I {\n    override fun m(a: Int) {}\n}\n"
        assertNull(removal(text, "a: Int) {}"))
    }

    fun testDeclinesOpenFunction() {
        assertNull(removal("open class C {\n    open fun m(a: Int) {}\n}\n", "a: Int"))
    }

    fun testDeclinesWhenReferencedByCallableReference() {
        // `::g` still expects the old arity.
        val text = "fun g(a: Int, b: Int) = a\nfun f() = ::g\nfun h() = g(1, 2)\n"
        assertNull(removal(text, "b: Int"))
    }

    fun testDeclinesVararg() {
        assertNull(removal("fun g(a: Int, vararg b: Int) = a\nfun f() = g(1)\n", "vararg b"))
    }
}
