package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.NamedArgumentsComputation

class NamedArgumentsTest : BasePlatformTestCase() {
    private fun offsetOf(text: String, at: String): Int =
        text.indexOf(at).also { check(it >= 0) { "caret anchor not found: $at" } }

    private fun fill(text: String, at: String): NamedArgumentsComputation.Fill? {
        val file = myFixture.configureByText("N.kt", text)
        return NamedArgumentsComputation.fillAt(file, offsetOf(text, at))
    }

    fun testFillsEmptyCallWithPlaceholders() {
        val result = fill("fun g(id: Int, label: String) {}\nfun f() {\n    g()\n}\n", "g()")
        assertNotNull(result)
        assertEquals(listOf("id", "label"), result!!.parameters)
        assertEquals("\n        id = TODO(),\n        label = TODO()\n    ", result.text)
    }

    fun testFillDeclinesCallThatAlreadyHasArguments() {
        assertNull(fill("fun g(id: Int) {}\nfun f() { g(1) }\n", "g(1"))
    }

    fun testFillDeclinesParameterlessFunction() {
        assertNull(fill("fun g() {}\nfun f() { g() }\n", "g()"))
    }

    fun testFillDeclinesOverloadedCallee() {
        val text = "fun g(a: Int) {}\nfun g(a: Int, b: Int) {}\nfun f() { g() }\n"
        assertNull(fill(text, "g()"))
    }
}
