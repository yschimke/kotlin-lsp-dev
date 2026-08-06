package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.NamedArgumentsComputation

class NamedArgumentsTest : BasePlatformTestCase() {
    private fun offsetOf(text: String, at: String): Int =
        text.indexOf(at).also { check(it >= 0) { "caret anchor not found: $at" } }

    private fun naming(text: String, at: String): NamedArgumentsComputation.Naming? {
        val file = myFixture.configureByText("N.kt", text)
        return NamedArgumentsComputation.namingAt(file, offsetOf(text, at))
    }

    private fun fill(text: String, at: String): NamedArgumentsComputation.Fill? {
        val file = myFixture.configureByText("N.kt", text)
        return NamedArgumentsComputation.fillAt(file, offsetOf(text, at))
    }

    private fun namedSource(text: String, at: String): String {
        val result = naming(text, at) ?: error("no naming offered")
        var out = text
        for (insertion in result.insertions.sortedByDescending { it.offset }) {
            out = out.substring(0, insertion.offset) + insertion.text + out.substring(insertion.offset)
        }
        return out
    }

    // --- add names to existing arguments ---------------------------------------------------

    fun testNamesPositionalArguments() {
        assertEquals(
            "fun g(id: Int, label: String) {}\nfun f() { g(id = 1, label = \"a\") }\n",
            namedSource("fun g(id: Int, label: String) {}\nfun f() { g(1, \"a\") }\n", "g(1"),
        )
    }

    fun testNamesConstructorArguments() {
        assertEquals(
            "class P(val x: Int, val y: Int)\nfun f() { P(x = 1, y = 2) }\n",
            namedSource("class P(val x: Int, val y: Int)\nfun f() { P(1, 2) }\n", "P(1"),
        )
    }

    fun testActsOnTheInnermostCallContainingTheCaret() {
        // The caret is inside g(...), so the action must name g's argument, not f's.
        val text = "fun g(inner: Int) = inner\nfun h(outer: Int) = outer\nfun f() { h(g(1)) }\n"
        val result = naming(text, "1)")
        assertEquals(1, result!!.insertions.size)
        assertEquals("inner = ", result.insertions.single().text)
    }

    fun testDeclinesWhenAlreadyNamed() {
        assertNull(naming("fun g(id: Int) {}\nfun f() { g(id = 1) }\n", "g(id"))
    }

    fun testNamesOnlyTheLeadingUnnamedRun() {
        val result = naming("fun g(a: Int, b: Int) {}\nfun f() { g(1, b = 2) }\n", "g(1")
        assertEquals(1, result!!.insertions.size)
        assertEquals("a = ", result.insertions.single().text)
    }

    fun testDeclinesUnnamedAfterNamed() {
        // Not a legal argument list to begin with; naming part of it would not help.
        assertNull(naming("fun g(a: Int, b: Int) {}\nfun f() { g(a = 1, 2) }\n", "g(a"))
    }

    fun testDeclinesVararg() {
        assertNull(naming("fun g(vararg xs: Int) {}\nfun f() { g(1, 2) }\n", "g(1"))
    }

    fun testUsesTheOverloadThatActuallyResolved() {
        // An overload set is not automatically ambiguous: `g(1)` resolves to the Int overload and
        // `g("s")` to the String one, so each gets the name belonging to the function selected.
        val overloads = "fun g(a: Int) {}\nfun g(b: String) {}\n"
        assertEquals(
            "a = ",
            naming(overloads + "fun f() { g(1) }\n", "g(1)")!!.insertions.single().text,
        )
        assertEquals(
            "b = ",
            naming(overloads + "fun f() { g(\"s\") }\n", "g(\"s\")")!!.insertions.single().text,
        )
    }

    fun testDeclinesUnresolvableCallee() {
        // Nothing to take names from; guessing would produce a compile error the user must undo.
        assertNull(naming("fun f() { noSuchFunction(1) }\n", "noSuchFunction(1"))
    }

    fun testDeclinesJavaCallee() {
        // Kotlin does not allow named arguments for Java methods.
        assertNull(naming("fun f() { java.lang.Integer.valueOf(1) }\n", "valueOf(1"))
    }

    // --- fill an empty call with placeholders (upstream #175) -------------------------------

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
