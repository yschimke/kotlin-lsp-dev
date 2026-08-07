package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.InlineFunctionComputation

class InlineFunctionTest : BasePlatformTestCase() {
    private fun inlining(text: String, at: String): InlineFunctionComputation.Inlining? {
        val file = myFixture.configureByText("F.kt", text)
        val offset = text.indexOf(at)
        check(offset >= 0) { "caret anchor not found: $at" }
        return InlineFunctionComputation.inlineAt(file, offset)
    }

    private fun applied(text: String, at: String): String {
        val result = inlining(text, at) ?: error("no inlining offered")
        return text.substring(0, result.callRange.startOffset) +
            result.replacement +
            text.substring(result.callRange.endOffset)
    }

    fun testInlinesExpressionBodyWithArgument() {
        assertEquals(
            "fun twice(n: Int) = n * 2\nfun f() = (3 * 2)\n",
            applied("fun twice(n: Int) = n * 2\nfun f() = twice(3)\n", "twice(3)"),
        )
    }

    fun testParenthesisesNonAtomicArgument() {
        // `1 + 1 * 2` would be 3; `(1 + 1) * 2` is 4.
        assertEquals(
            "fun twice(n: Int) = n * 2\nfun f() = ((1 + 1) * 2)\n",
            applied("fun twice(n: Int) = n * 2\nfun f() = twice(1 + 1)\n", "twice(1 + 1)"),
        )
    }

    fun testNoParenthesesForAtomicBody() {
        assertEquals(
            "fun id(n: Int) = compute(n)\nfun f() = compute(3)\nfun compute(x: Int) = x\n",
            applied("fun id(n: Int) = compute(n)\nfun f() = id(3)\nfun compute(x: Int) = x\n", "id(3)"),
        )
    }

    fun testSupportsNamedArguments() {
        assertEquals(
            "fun sub(a: Int, b: Int) = a - b\nfun f() = (1 - 2)\n",
            applied("fun sub(a: Int, b: Int) = a - b\nfun f() = sub(b = 2, a = 1)\n", "sub(b = 2"),
        )
    }

    fun testRepeatsOnlySafeArguments() {
        // `n` used twice with a plain name argument is fine.
        assertEquals(
            "fun sq(n: Int) = n * n\nfun f(v: Int) = (v * v)\n",
            applied("fun sq(n: Int) = n * n\nfun f(v: Int) = sq(v)\n", "sq(v)"),
        )
    }

    fun testDeclinesRepeatedNonTrivialArgument() {
        // Would call compute() twice where the program calls it once.
        assertNull(inlining("fun sq(n: Int) = n * n\nfun c(): Int = 1\nfun f() = sq(c())\n", "sq(c())"))
    }

    fun testDeclinesBlockBody() {
        assertNull(inlining("fun f2(n: Int): Int { return n }\nfun f() = f2(1)\n", "f2(1)"))
    }

    fun testDeclinesRecursion() {
        assertNull(inlining("fun r(n: Int): Int = r(n)\nfun f() = r(1)\n", "r(1)\n"))
    }

    fun testDeclinesDefaultedParameter() {
        // No argument to substitute for `b`.
        assertNull(inlining("fun g(a: Int, b: Int = 2) = a + b\nfun f() = g(1)\n", "g(1)"))
    }

    fun testDeclinesVararg() {
        assertNull(inlining("fun v(vararg n: Int) = 1\nfun f() = v(1, 2)\n", "v(1, 2)"))
    }

    fun testDeclinesUnresolvedCallee() {
        assertNull(inlining("fun f() = nope(1)\n", "nope(1)"))
    }
}
