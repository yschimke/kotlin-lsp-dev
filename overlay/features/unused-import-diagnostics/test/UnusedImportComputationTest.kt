package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.imports.UnusedImportComputation
import org.jetbrains.kotlin.psi.KtFile

class UnusedImportComputationTest : BasePlatformTestCase() {
    private fun unused(text: String): List<String> {
        val file = myFixture.configureByText("Imports.kt", text) as KtFile
        return UnusedImportComputation.find(file).map { it.importedName }
    }

    fun testFindsUnusedExplicitImport() {
        assertEquals(
            listOf("kotlin.collections.ArrayList"),
            unused("import kotlin.collections.ArrayList\n\nfun answer() = 42\n"),
        )
    }

    fun testKeepsUsedImport() {
        assertEmpty(unused("import java.util.UUID\n\nfun id(): UUID = UUID.randomUUID()\n"))
    }

    fun testUnderstandsAliases() {
        assertEmpty(unused("import java.util.UUID as Id\n\nfun id(): Id = Id.randomUUID()\n"))
    }
}
