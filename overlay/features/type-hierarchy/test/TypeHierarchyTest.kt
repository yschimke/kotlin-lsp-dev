package overlay

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.typeHierarchy.KotlinTypeHierarchyComputation
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Drives the real [KotlinTypeHierarchyComputation] (the core of the new Kotlin
 * `textDocument/typeHierarchy` feature) over a PSI fixture.
 */
class TypeHierarchyTest : BasePlatformTestCase() {

    private val source = """
        package p

        interface Animal
        open class Mammal : Animal
        class Dog : Mammal()
    """.trimIndent()

    private fun names(elements: List<PsiElement>) =
        elements.mapNotNull { (it as? PsiNamedElement)?.name }.toSet()

    private fun classNamed(name: String): KtClassOrObject {
        val file = myFixture.configureByText("Types.kt", source)
        val offset = source.indexOf(name).also { check(it >= 0) { "no $name in source" } } + 1
        return KotlinTypeHierarchyComputation.classAt(file, offset)
            ?: error("no class resolved at the declaration of $name")
    }

    @OptIn(KaAllowAnalysisOnEdt::class)
    private fun supertypes(name: String) =
        allowAnalysisOnEdt { names(KotlinTypeHierarchyComputation.supertypes(classNamed(name))) }

    private fun subtypes(name: String) =
        names(KotlinTypeHierarchyComputation.subtypes(classNamed(name)))

    fun testSupertypes() {
        assertEquals(setOf("Mammal"), supertypes("Dog"))
        assertEquals(setOf("Animal"), supertypes("Mammal"))
    }

    fun testSubtypes() {
        assertEquals(setOf("Dog"), subtypes("Mammal"))
        assertEquals(setOf("Mammal"), subtypes("Animal"))
    }

    fun testLeafHasNoSubtypesAndRootlessTopHasNoSupertypes() {
        assertEquals(emptySet<String>(), subtypes("Dog"))
        // Animal's only supertype is the implicit Any, which is filtered out.
        assertEquals(emptySet<String>(), supertypes("Animal"))
    }
}
