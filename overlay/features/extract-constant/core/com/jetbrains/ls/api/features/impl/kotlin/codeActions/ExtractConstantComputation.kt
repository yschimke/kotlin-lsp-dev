// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Pure-PSI computation for lifting a literal into a file-level `private const val`.
 *
 * The counterpart to [ExtractVariableComputation]: that one names a sub-expression where it is
 * used, this one lifts a constant out to the top of the file so the same literal has one home.
 * Every occurrence of the literal in the file is replaced, which is the point -- a "magic number"
 * appearing three times is exactly the case worth fixing.
 */
object ExtractConstantComputation {
    data class Extraction(
        /** Where the `private const val` declaration is inserted. */
        val declarationOffset: Int,
        val declaration: String,
        /** Every occurrence replaced, in file order. */
        val occurrences: List<TextRange>,
        val constantName: String,
    )

    fun extract(file: PsiFile, selection: TextRange): Extraction? {
        if (file !is KtFile) return null
        val text = file.text
        var start = selection.startOffset.coerceIn(0, text.length)
        var end = selection.endOffset.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--

        val literal = literalAt(file, start, if (end > start) TextRange(start, end) else null) ?: return null
        // A constant has to be a compile-time constant; anything else cannot be `const val`.
        val literalText = literal.text
        if (literalText.isBlank()) return null
        // Only literals whose value is knowable here. A string template with an interpolation is
        // not constant, and `const val` would not compile.
        if (literal is KtStringTemplateExpression && literal.hasInterpolation()) return null

        // Already a constant's initializer? Lifting it again just adds a level of indirection.
        val enclosingProperty = literal.parentOfType<org.jetbrains.kotlin.psi.KtProperty>()
        if (enclosingProperty != null && enclosingProperty.hasModifier(
                org.jetbrains.kotlin.lexer.KtTokens.CONST_KEYWORD)
        ) return null

        val occurrences = PsiTreeUtil.findChildrenOfType(file, KtExpression::class.java)
            .filter { it::class == literal::class && it.text == literalText }
            .filter { it.parentOfType<org.jetbrains.kotlin.psi.KtImportDirective>() == null }
            .map { it.textRange }
            .sortedBy { it.startOffset }
        if (occurrences.isEmpty()) return null

        val taken = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
            .mapNotNullTo(mutableSetOf()) { it.name }
        val name = nameFor(literal, literalText, taken)

        // After the imports and the package directive, before the first declaration -- where a
        // reader expects file-level constants, and where nothing can shadow it.
        val firstDeclaration = file.declarations.minByOrNull { it.textRange.startOffset }
        val insertAt = firstDeclaration?.let { declarationStart(text, it) } ?: text.length
        return Extraction(
            declarationOffset = insertAt,
            declaration = "private const val $name = $literalText\n\n",
            occurrences = occurrences,
            constantName = name,
        )
    }

    /** The literal to extract: the selected one, or the one under the caret. */
    private fun literalAt(file: PsiFile, offset: Int, selected: TextRange?): KtExpression? {
        val leaf = file.findElementAt(offset) ?: return null
        val candidates = generateSequence(leaf) { it.parent }
            .filterIsInstance<KtExpression>()
            .filter { it is KtConstantExpression || it is KtStringTemplateExpression }
        return if (selected != null) {
            candidates.firstOrNull { it.textRange == selected }
        } else {
            candidates.firstOrNull()
        }
    }

    /**
     * A name from the value where that reads well (`MAX_RETRIES` for a string "max retries" is
     * beyond us, but `TIMEOUT_MS` style screaming snake case from a string literal is not), else a
     * generic one. Never collides with an existing declaration.
     */
    private fun nameFor(literal: KtExpression, literalText: String, taken: Set<String>): String {
        val base = if (literal is KtStringTemplateExpression) {
            literalText.trim('"')
                .uppercase()
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
                .takeIf { it.isNotEmpty() && !it.first().isDigit() }
                ?: "CONSTANT"
        } else {
            "CONSTANT"
        }
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "${base}_$it" }.first { it !in taken }
    }

    /** Start of the declaration's line, so the constant lands above any annotation or comment. */
    private fun declarationStart(text: String, declaration: KtDeclaration): Int {
        val offset = declaration.textRange.startOffset
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return lineStart
    }
}
