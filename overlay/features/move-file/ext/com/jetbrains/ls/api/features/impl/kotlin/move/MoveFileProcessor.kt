// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.move

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.openapi.util.Ref
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.impl.RefactoringTransaction
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.jetbrains.ls.api.features.impl.common.processors.RefactoringProcessor

/**
 * Moves a single file into a directory, updating its package declaration and every reference to it.
 *
 * The work is the platform's: [MoveFileHandler.forElement] resolves to the language's handler --
 * `K2MoveFilesHandler` for Kotlin, which the distribution registers -- and that handler knows how
 * to rewrite the package directive and retarget imports. This class only sequences the steps that
 * `MoveFilesOrDirectoriesProcessor` would, minus the dialogs and progress UI that a language
 * server has no way to show.
 *
 * Ordering is load-bearing and easy to get wrong: usages must be found *before* the file moves,
 * `prepareMovedFile` must run before the move so the handler can record what it will need, and
 * `retargetUsages` must run after, against the map the handler populated. Doing the move first
 * loses the old references and silently produces an edit that updates nothing.
 */
internal class MoveFileProcessor(
    private val project: Project,
    private val file: PsiFile,
    private val targetDirectory: PsiDirectory,
) : RefactoringProcessor {
    private val handler: MoveFileHandler? = MoveFileHandler.forElement(file)
    private val oldToNewMap: MutableMap<PsiElement, PsiElement> = LinkedHashMap()

    override fun findUsages(): Array<UsageInfo> {
        val found = handler?.findUsages(
            file,
            targetDirectory,
            /* searchInComments = */ true,
            /* searchInNonJavaFiles = */ true,
        ) ?: return emptyArray()
        return found.toTypedArray()
    }

    override fun processUsages(initialUsages: Array<UsageInfo>): Array<UsageInfo> = initialUsages

    override fun collectConflicts(
        refUsages: Ref<Array<UsageInfo>>,
        conflicts: MultiMap<PsiElement, String>,
    ) {
        // Report conflicts (a name already taken in the destination, say) rather than discovering
        // them halfway through. The caller decides what to do; we must not silently proceed.
        MoveFileHandler.detectConflicts(arrayOf(file), refUsages.get() ?: emptyArray(), targetDirectory, conflicts)
    }

    override fun getFilesToSave(usages: Array<UsageInfo>): List<PsiFile> =
        (usages.mapNotNull { it.file } + file).distinct()

    override fun performRefactoring(usages: Array<UsageInfo>, transaction: RefactoringTransaction) {
        val handler = this.handler ?: return
        handler.prepareMovedFile(file, targetDirectory, oldToNewMap)
        MoveFilesOrDirectoriesUtil.doMoveFile(file, targetDirectory)
        handler.updateMovedFile(file)
        handler.retargetUsages(usages.toList(), oldToNewMap)
    }

    override fun createEventData(): RefactoringEventData =
        RefactoringEventData().apply { addElement(file) }
}
