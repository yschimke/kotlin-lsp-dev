package overlay

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeVision.KotlinCodeVisionComputation
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Drives [KotlinCodeVisionComputation] — the cores of the reference-count, implementation-count
 * and run-test code lenses — over PSI fixtures.
 */
class CodeVisionTest : BasePlatformTestCase() {

    private fun declNamed(file: PsiFile, name: String): KtNamedDeclaration =
        KotlinCodeVisionComputation.lensableDeclarations(file).first { it.name == name }

    fun testReferenceCount() {
        val api = myFixture.addFileToProject(
            "p/Api.kt",
            """
            package p
            fun target() {}
            fun unused() {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "p/Use.kt",
            """
            package p
            fun caller() {
                target()
                target()
            }
            """.trimIndent(),
        )
        assertEquals(2, KotlinCodeVisionComputation.referenceCount(declNamed(api, "target")))
        assertEquals(0, KotlinCodeVisionComputation.referenceCount(declNamed(api, "unused")))
    }

    fun testImplementationCount() {
        val types = myFixture.addFileToProject(
            "p/Types.kt",
            """
            package p
            interface Shape { fun area(): Double }
            class Circle : Shape { override fun area() = 3.14 }
            class Square : Shape { override fun area() = 4.0 }
            class Leaf
            """.trimIndent(),
        )
        assertEquals(2, KotlinCodeVisionComputation.implementationCount(declNamed(types, "Shape")))
        assertEquals(0, KotlinCodeVisionComputation.implementationCount(declNamed(types, "Leaf")))
        // area() is overridden in both implementors.
        assertEquals(2, KotlinCodeVisionComputation.implementationCount(declNamed(types, "area")))
    }

    fun testTestFunctionDetection() {
        val file = myFixture.configureByText(
            "MyTest.kt",
            """
            package p
            annotation class Test
            class MyTest {
                @Test fun shouldWork() {}
                @Test fun alsoWorks() {}
                fun helper() {}
            }
            """.trimIndent(),
        )
        // Qualified, not bare: the run lens passes this to `--tests`, and a bare method name
        // would select every same-named test in the project -- or none.
        val tests = KotlinCodeVisionComputation.testFunctions(file).map { it.first }.toSet()
        assertEquals(setOf("p.MyTest.shouldWork", "p.MyTest.alsoWorks"), tests)
    }
}
