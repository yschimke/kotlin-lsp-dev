package com.jetbrains.ls.api.features.impl.common.processors

import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * Overlay shim for the upstream `RefactoringProcessor` interface.
 *
 * The real declaration lives in
 * `features-impl/common/src/.../processors/RefactoringProcessor.kt`, but that file also
 * contains the `execute(...)` driver, which depends on closed-source types
 * (`LSAnalysisContext`, `FileUrl`, `server.fileChanges()`). Those ship only inside the
 * released `product.jar` — and `fileChanges` isn't even in the pinned release.
 *
 * Pulling the real file in would therefore force a mixed classpath: 2026.2 platform next to
 * closed jars built against 262.8190. Shimming the interface instead keeps the whole build
 * on one self-consistent platform version.
 *
 * This declares only what [MoveFilesProcessor] actually overrides. It is a compile-time
 * stand-in; `scripts/compile-check.sh` still type-checks the *real* interface against the
 * real distribution, so drift between this shim and upstream gets caught there.
 */
interface RefactoringProcessor {
    fun collectConflicts(refUsages: Ref<Array<UsageInfo>>, conflicts: MultiMap<PsiElement, String>)

    fun findUsages(): Array<UsageInfo>?

    fun processUsages(initialUsages: Array<UsageInfo>): Array<UsageInfo>

    fun getFilesToSave(usages: Array<UsageInfo>): List<PsiFile>

    fun performRefactoring(usages: Array<UsageInfo>, transaction: RefactoringTransaction)

    fun createEventData(): RefactoringEventData
}
