package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.SafeDeleteComputation

class SafeDeleteTest : BasePlatformTestCase() {
    private fun deletion(text: String, at: String): SafeDeleteComputation.Deletion? {
        val file = myFixture.configureByText("S.kt", text)
        val offset = text.indexOf(at)
        check(offset >= 0) { "anchor not found: $at" }
        return SafeDeleteComputation.deletionAt(file, offset)
    }

    fun testOffersForUnusedLocal() {
        val result = deletion("fun f() {\n    val unused = 1\n    println(2)\n}\n", "unused")
        assertNotNull(result)
        assertEquals("unused", result!!.name)
        assertEquals("local variable", result.kind)
    }

    fun testDeclinesUsedLocal() {
        assertNull(deletion("fun f() {\n    val used = 1\n    println(used)\n}\n", "used = 1"))
    }

    fun testOffersForUnusedPrivateTopLevelFunction() {
        val result = deletion("private fun helper(): Int = 1\nfun f(): Int = 2\n", "helper")
        assertEquals("private function", result!!.kind)
    }

    fun testDeclinesUsedPrivateFunction() {
        assertNull(deletion("private fun helper(): Int = 1\nfun f(): Int = helper()\n", "helper(): Int"))
    }

    fun testDeclinesPublicDeclaration() {
        // Callers can live outside anything a reference search here would see.
        assertNull(deletion("fun exported(): Int = 1\n", "exported"))
    }

    fun testDeclinesOverride() {
        val text = "interface I { fun m() }\nclass C : I {\n    override fun m() {}\n}\n"
        assertNull(deletion(text, "m() {}"))
    }

    fun testDeclinesAnnotatedDeclaration() {
        // An annotation is often exactly what makes something used from outside.
        assertNull(deletion("annotation class A\n@A private fun h() {}\n", "h()"))
    }
}
