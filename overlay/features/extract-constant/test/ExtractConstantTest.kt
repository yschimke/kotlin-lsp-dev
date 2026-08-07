package overlay

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.ExtractConstantComputation

class ExtractConstantTest : BasePlatformTestCase() {
    private fun extraction(text: String, at: String): ExtractConstantComputation.Extraction? {
        val file = myFixture.configureByText("C.kt", text)
        val start = text.indexOf(at)
        check(start >= 0) { "anchor not found: $at" }
        return ExtractConstantComputation.extract(file, TextRange(start, start + at.length))
    }

    private fun applied(text: String, at: String): String {
        val result = extraction(text, at) ?: error("no extraction offered")
        var out = text
        for (range in result.occurrences.sortedByDescending { it.startOffset }) {
            out = out.substring(0, range.startOffset) + result.constantName + out.substring(range.endOffset)
        }
        return out.substring(0, result.declarationOffset) + result.declaration +
            out.substring(result.declarationOffset)
    }

    fun testLiftsNumberToFileConstant() {
        assertEquals(
            "package p\n\nprivate const val CONSTANT = 42\n\nfun f(): Int = CONSTANT\n",
            applied("package p\n\nfun f(): Int = 42\n", "42"),
        )
    }

    fun testReplacesEveryOccurrence() {
        val result = extraction("package p\n\nfun a(): Int = 7\nfun b(): Int = 7\nfun c(): Int = 8\n", "7")
        assertEquals(2, result!!.occurrences.size)
    }

    fun testNamesStringConstantsFromTheirValue() {
        val result = extraction("package p\n\nfun f(): String = \"max retries\"\n", "\"max retries\"")
        assertEquals("MAX_RETRIES", result!!.constantName)
    }

    fun testAvoidsNameCollision() {
        val text = "package p\n\nval CONSTANT = 1\nfun f(): Int = 42\n"
        assertEquals("CONSTANT_2", extraction(text, "42")!!.constantName)
    }

    fun testDeclinesInterpolatedString() {
        // Not a compile-time constant, so `const val` would not compile.
        assertNull(extraction("package p\n\nfun f(n: Int): String = \"v\$n\"\n", "\"v\$n\""))
    }

    fun testDeclinesExistingConstant() {
        assertNull(extraction("package p\n\nprivate const val X = 42\n", "42"))
    }

    fun testDeclinesNonLiteral() {
        assertNull(extraction("package p\n\nfun g(): Int = 1\nfun f(): Int = g()\n", "g()"))
    }
}
