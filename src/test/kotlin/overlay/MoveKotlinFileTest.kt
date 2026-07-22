package overlay

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.refactoring.listeners.RefactoringListenerManager
import com.intellij.refactoring.listeners.impl.RefactoringListenerManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.common.processors.MoveFilesProcessor
import com.jetbrains.ls.api.features.impl.common.processors.MoveSingleFileContext
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt

/**
 * Drives the real upstream [MoveFilesProcessor] over a PSI fixture.
 *
 * Goes straight at `findUsages` / `performRefactoring` rather than through the upstream
 * `execute(...)` driver, which needs closed-source LSP server context.
 */
class MoveKotlinFileTest : BasePlatformTestCase() {

    @OptIn(KaAllowAnalysisOnEdt::class, KaAllowAnalysisFromWriteAction::class)
    private fun runMove(targetDirectory: PsiDirectory, file: com.intellij.psi.PsiFile) {
        val processor = MoveFilesProcessor.create(MoveSingleFileContext(targetDirectory, file))
            ?: error("MoveFilesProcessor.create returned null")

        val usages = processor.findUsages()

        val listenerManager =
            RefactoringListenerManager.getInstance(project) as RefactoringListenerManagerImpl
        val transaction = listenerManager.startTransaction()

        // The refactoring runs inside a write action on EDT while the Kotlin move handler
        // resolves the code it rewrites, so analysis has to be explicitly permitted --
        // exactly what LSKotlinMoveFileProvider does in production.
        // K2MoveRenameUsageInfo.retargetUsages calls pushState() on the *global* progress
        // indicator, so one has to be installed or it NPEs. Nothing in MoveFilesProcessor
        // establishes it -- see the note in README.md.
        ProgressManager.getInstance().runProcess({
            CommandProcessor.getInstance().executeCommand(project, {
                WriteAction.run<Throwable> {
                    allowAnalysisOnEdt {
                        allowAnalysisFromWriteAction {
                            processor.performRefactoring(usages, transaction)
                        }
                    }
                    transaction.commit()
                }
            }, "Move", null)
        }, EmptyProgressIndicator())
    }

    fun testMovingAFileUpdatesItsPackageAndTheReferencingImport() {
        val moved = myFixture.addFileToProject(
            "foo/Bar.kt",
            """
            package foo

            class Bar
            """.trimIndent(),
        )
        val referencing = myFixture.addFileToProject(
            "app/UseBar.kt",
            """
            package app

            import foo.Bar

            fun use(): Bar = Bar()
            """.trimIndent(),
        )

        val targetVDir = myFixture.tempDirFixture.findOrCreateDir("bar")
        val targetDirectory = PsiManager.getInstance(project).findDirectory(targetVDir)
            ?: error("no PsiDirectory for $targetVDir")

        runMove(targetDirectory, moved)

        val movedText = targetDirectory.findFile("Bar.kt")?.text
            ?: error("Bar.kt is not in the target directory after the move")

        println("[test] moved file:\n$movedText")
        println("[test] referencing file:\n${referencing.text}")

        assertTrue(
            "package directive was not updated, got:\n$movedText",
            movedText.contains("package bar"),
        )
        assertTrue(
            "import in the referencing file was not retargeted, got:\n${referencing.text}",
            referencing.text.contains("import bar.Bar"),
        )
    }
}
