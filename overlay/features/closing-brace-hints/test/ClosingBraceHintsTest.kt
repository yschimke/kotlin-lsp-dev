package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.inlayHints.ClosingBraceHintsComputation

/** Drives [ClosingBraceHintsComputation] over PSI fixtures. */
class ClosingBraceHintsTest : BasePlatformTestCase() {

    private fun labelsOf(text: String) =
        ClosingBraceHintsComputation.hints(myFixture.configureByText("H.kt", text)).map { it.label }

    fun testLongFunctionAndClassGetHints() {
        val labels = labelsOf(
            """
            package p
            class Big {
                fun long() {
                    val a = 1
                    val b = 2
                    val c = 3
                }
            }
            """.trimIndent(),
        )
        assertTrue("expected a class hint, got $labels", labels.any { it == "class Big" })
        assertTrue("expected a function hint, got $labels", labels.any { it == "fun long" })
    }

    fun testShortBodiesGetNoHint() {
        val labels = labelsOf(
            """
            package p
            fun short() { val a = 1 }
            class Small { val x = 1 }
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), labels)
    }
}
