// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression

/**
 * Pure-PSI core for the "convert to expression body" refactoring: a block-bodied function whose
 * body is a single `return <expr>` becomes `= <expr>`. Free of LSP types so it is unit-testable.
 */
object ExpressionBodyComputation {
    data class Conversion(val replaceRange: TextRange, val replacement: String)

    fun convertible(file: PsiFile, offset: Int): Conversion? {
        val function = file.findElementAt(offset)?.parentOfType<KtNamedFunction>(withSelf = true) ?: return null
        val block = function.bodyBlockExpression ?: return null
        val statements = block.statements
        if (statements.size != 1) return null
        val returned = (statements[0] as? KtReturnExpression)?.returnedExpression ?: return null
        return Conversion(block.textRange, "= ${returned.text}")
    }
}
