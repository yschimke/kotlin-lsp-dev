// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.move

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.impl.common.move.LSMoveFileProviderBase
import com.jetbrains.ls.api.features.impl.common.processors.RefactoringProcessor
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage

/**
 * Serves `workspace/willRenameFiles` for Kotlin: moving a file updates its package declaration
 * and every reference to it.
 *
 * The distribution ships the provider interface, the base class that drives it, and the Kotlin
 * platform handler (`K2MoveFilesHandler`) -- but registers no Kotlin file-move provider, so the
 * capability is advertised and answers nothing. This fills that empty slot; everything above and
 * below it is upstream's.
 */
internal object LSKotlinMoveFileProvider : LSMoveFileProviderBase(setOf(LSKotlinLanguage)) {
    context(_: LSAnalysisContext)
    override fun createProcessor(targetDirectory: PsiDirectory, file: PsiFile): RefactoringProcessor =
        MoveFileProcessor(project, file, targetDirectory)
}
