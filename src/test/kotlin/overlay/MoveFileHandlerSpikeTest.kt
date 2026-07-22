package overlay

import com.intellij.psi.PsiFile
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Spike: can we boot an IntelliJ PSI fixture with the bundled Kotlin plugin loaded,
 * and does the platform hand us the Kotlin-specific [MoveFileHandler] for a KtFile?
 *
 * That handler is what [MoveFilesProcessor] delegates the whole language-specific part
 * of the move refactoring to, so if this resolves to K2MoveFilesHandler the processor
 * is testable here. If it doesn't, the whole overlay-test idea is dead.
 */
class MoveFileHandlerSpikeTest : BasePlatformTestCase() {

    fun testKotlinPluginIsLoadedAndProvidesMoveFileHandler() {
        val file: PsiFile = myFixture.addFileToProject(
            "foo/Bar.kt",
            """
            package foo

            class Bar
            """.trimIndent(),
        )

        println("[spike] PSI file class = ${file.javaClass.name}")
        println("[spike] language       = ${file.language.id}")

        assertEquals("kotlin", file.language.id.lowercase())

        val handler = MoveFileHandler.forElement(file)
        println("[spike] MoveFileHandler = ${handler.javaClass.name}")

        assertTrue(
            "expected a Kotlin-specific MoveFileHandler, got ${handler.javaClass.name}",
            handler.javaClass.name.contains("Kotlin") || handler.javaClass.name.contains("K2Move"),
        )
    }
}
