// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/**
 * Pure-PSI computation for inlining a local `val` into its use sites.
 *
 * The inverse of [ExtractVariableComputation]. Deliberately conservative: it declines anything
 * whose meaning it cannot preserve, because a refactoring that silently changes behaviour is worse
 * than one that does not appear.
 */
object InlineVariableComputation {
    data class Replacement(val range: TextRange, val text: String)

    data class Inlining(
        /** The declaration itself, including its line's indent and newline when it stands alone. */
        val declarationRange: TextRange,
        val replacements: List<Replacement>,
        val variableName: String,
    )

    fun inlineAt(file: PsiFile, offset: Int): Inlining? {
        val property = propertyAt(file, offset) ?: return null
        val name = property.name ?: return null
        val initializer = property.initializer ?: return null

        // `var` can be reassigned between uses, so its value at a use site is not the initializer.
        if (property.isVar) return null
        // Only locals. A member property may be read from anywhere, including other files, and
        // this computation only ever sees one.
        if (!property.isLocal) return null
        // A delegate (`by lazy { ... }`) is not a value to substitute.
        if (property.hasDelegate()) return null

        val scope = property.parentOfType<KtDeclaration>() ?: return null
        val references = PsiTreeUtil.findChildrenOfType(scope, KtSimpleNameExpression::class.java)
            .filter { it.mainReference.resolve() == property }
        if (references.isEmpty()) return null
        // A use as an assignment target would have to stay a variable.
        if (references.any { isAssignmentTarget(it) }) return null

        val replacementText = substitutionText(initializer)
        return Inlining(
            declarationRange = declarationRangeIncludingLine(file, property),
            replacements = references.map { Replacement(it.textRange, replacementText) },
            variableName = name,
        )
    }

    private fun propertyAt(file: PsiFile, offset: Int): KtProperty? {
        // Offer the action from the declaration or from any use of it, which is where a user is
        // more likely to be looking when they decide the name is not earning its place.
        val at = file.findElementAt(offset) ?: return null
        at.parentOfType<KtProperty>(withSelf = true)?.let { property ->
            // Only when the caret is on the name, not somewhere inside the initializer -- there
            // the extract-variable action is the one that makes sense.
            val nameRange = property.nameIdentifier?.textRange
            if (nameRange != null && offset >= nameRange.startOffset && offset <= nameRange.endOffset) {
                return property
            }
        }
        val reference = at.parentOfType<KtSimpleNameExpression>(withSelf = true) ?: return null
        return reference.mainReference.resolve() as? KtProperty
    }

    private fun isAssignmentTarget(reference: KtSimpleNameExpression): Boolean {
        val parent = reference.parent
        return parent is KtBinaryExpression &&
            parent.left == reference &&
            parent.operationToken.toString().let { it == "EQ" || it.endsWith("EQ") }
    }

    /**
     * Wrap the initializer in parentheses unless it is already atomic.
     *
     * `val x = a + b` used in `x * 2` must become `(a + b) * 2`, not `a + b * 2`. Rather than model
     * Kotlin's precedence table against every possible use site, parenthesise anything that is not
     * self-delimiting. Redundant parentheses are harmless; a wrong result is not.
     */
    private fun substitutionText(initializer: KtExpression): String {
        val text = initializer.text
        return if (isAtomic(initializer)) text else "($text)"
    }

    private fun isAtomic(expression: KtExpression): Boolean = when (expression) {
        is KtNameReferenceExpression,
        is KtConstantExpression,
        is KtCallExpression,
        is KtDotQualifiedExpression,
        is KtArrayAccessExpression,
        is KtParenthesizedExpression,
        is KtThisExpression,
        is KtStringTemplateExpression -> true
        else -> false
    }

    /**
     * The range to delete. When the declaration is the only thing on its line, take the whole line
     * including its indent and newline, so inlining does not leave a blank line behind.
     */
    private fun declarationRangeIncludingLine(file: PsiFile, property: KtProperty): TextRange {
        val text = file.text
        val range = property.textRange
        val lineStart = text.lastIndexOf('\n', range.startOffset - 1) + 1
        val indentOnly = text.substring(lineStart, range.startOffset).all(Char::isWhitespace)
        if (!indentOnly) return range

        var end = range.endOffset
        while (end < text.length && text[end] != '\n' && text[end].isWhitespace()) end++
        if (end < text.length && text[end] == '\n') end++ else return range
        return TextRange(lineStart, end)
    }
}
