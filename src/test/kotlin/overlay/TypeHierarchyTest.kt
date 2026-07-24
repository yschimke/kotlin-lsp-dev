package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.typeHierarchy.KotlinTypeHierarchyComputation

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

    private fun names(classes: List<com.intellij.psi.PsiClass>) =
        classes.mapNotNull { it.name }.toSet()

    private fun classNamed(name: String): com.intellij.psi.PsiClass {
        val file = myFixture.configureByText("Types.kt", source)
        val offset = source.indexOf(name).also { check(it >= 0) { "no $name in source" } } + 1
        return KotlinTypeHierarchyComputation.classAt(file, offset)
            ?: error("no class resolved at the declaration of $name")
    }

    fun testSupertypes() {
        assertEquals(setOf("Mammal"), names(KotlinTypeHierarchyComputation.supertypes(classNamed("Dog"))))
        assertEquals(setOf("Animal"), names(KotlinTypeHierarchyComputation.supertypes(classNamed("Mammal"))))
    }

    fun testSubtypes() {
        assertEquals(setOf("Dog"), names(KotlinTypeHierarchyComputation.subtypes(classNamed("Mammal"))))
        assertEquals(setOf("Mammal"), names(KotlinTypeHierarchyComputation.subtypes(classNamed("Animal"))))
    }

    fun testLeafHasNoSubtypesAndRootlessTopHasNoSupertypes() {
        assertEquals(emptySet<String>(), names(KotlinTypeHierarchyComputation.subtypes(classNamed("Dog"))))
        // Animal's only declared supertype is the implicit Any, which is filtered out.
        assertEquals(emptySet<String>(), names(KotlinTypeHierarchyComputation.supertypes(classNamed("Animal"))))
    }
}
