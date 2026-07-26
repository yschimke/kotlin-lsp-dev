package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.DeclarationGenerationComputation

/** Drives declaration generation over Kotlin PSI and light-class inheritance. */
class DeclarationGenerationTest : BasePlatformTestCase() {
    private fun generate(source: String, kind: DeclarationGenerationComputation.Kind) =
        myFixture.configureByText("Declarations.kt", source).let { file ->
            val offset = source.indexOf("Child") + 1
            DeclarationGenerationComputation.generationAt(file, offset, kind)
        }

    fun testImplementsAllMissingMembers() {
        val result = generate(
            """
                interface Parent {
                    fun count(value: Int): String
                    fun enabled(): Boolean
                }
                class Child : Parent
            """.trimIndent(),
            DeclarationGenerationComputation.Kind.IMPLEMENT,
        )
        assertNotNull(result)
        assertEquals(listOf("count", "enabled"), result!!.memberNames)
        assertTrue(result.text.contains("override fun count(value: Int): String"))
        assertTrue(result.text.contains("override fun enabled(): Boolean"))
        assertTrue(result.text.startsWith(" {"))
    }

    fun testDoesNotOfferAlreadyImplementedMember() {
        val result = generate(
            """
                interface Parent { fun count(): Int }
                class Child : Parent { override fun count(): Int = 1 }
            """.trimIndent(),
            DeclarationGenerationComputation.Kind.IMPLEMENT,
        )
        assertNull(result)
    }

    fun testOffersConcreteSuperMemberForOverride() {
        val result = generate(
            """
                open class Parent { open fun label(): String = "parent" }
                class Child : Parent()
            """.trimIndent(),
            DeclarationGenerationComputation.Kind.OVERRIDE,
        )
        assertNotNull(result)
        assertEquals(listOf("label"), result!!.memberNames)
        assertTrue(result.text.contains("override fun label(): String"))
    }

    fun testNoActionOutsideAClass() {
        val source = "fun Child() = Unit"
        val file = myFixture.configureByText("TopLevel.kt", source)
        assertNull(
            DeclarationGenerationComputation.generationAt(
                file,
                source.indexOf("Child") + 1,
                DeclarationGenerationComputation.Kind.IMPLEMENT,
            )
        )
    }
}
