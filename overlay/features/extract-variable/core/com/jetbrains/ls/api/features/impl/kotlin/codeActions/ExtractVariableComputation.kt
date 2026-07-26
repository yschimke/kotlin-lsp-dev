// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Pure-PSI computation for extracting a selected Kotlin expression into a local `val`. */
object ExtractVariableComputation {
    data class Extraction(
        val declarationOffset: Int,
        val declaration: String,
        val expressionRange: TextRange,
        val variableName: String,
    )

    fun extract(file: PsiFile, selection: TextRange): Extraction? {
        if (selection.isEmpty) return null
        val text = file.text
        var start = selection.startOffset.coerceIn(0, text.length)
        var end = selection.endOffset.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start == end) return null

        val selectedRange = TextRange(start, end)
        val leaf = file.findElementAt(start) ?: return null
        val expression = generateSequence(leaf) { it.parent }
            .filterIsInstance<KtExpression>()
            .firstOrNull { it.textRange == selectedRange }
            ?: return null
        val block = generateSequence(expression.parent) { it.parent }
            .filterIsInstance<KtBlockExpression>()
            .firstOrNull()
            ?: return null
        val statement = block.statements.firstOrNull { it.textRange.contains(selectedRange) } ?: return null
        // Extracting an entire expression statement would only replace it with an unused name.
        if (statement.textRange == selectedRange) return null

        val usedNames = PsiTreeUtil.findChildrenOfType(block, KtNamedDeclaration::class.java)
            .mapNotNullTo(mutableSetOf()) { it.name }
        val variableName = generateSequence(1) { it + 1 }
            .map { if (it == 1) "value" else "value$it" }
            .first { it !in usedNames }

        val lineStart = text.lastIndexOf('\n', statement.textRange.startOffset - 1) + 1
        val prefix = text.substring(lineStart, statement.textRange.startOffset)
        val indent = prefix.takeIf { it.all(Char::isWhitespace) }.orEmpty()
        return Extraction(
            declarationOffset = statement.textRange.startOffset,
            declaration = "val $variableName = ${expression.text}\n$indent",
            expressionRange = selectedRange,
            variableName = variableName,
        )
    }
}
