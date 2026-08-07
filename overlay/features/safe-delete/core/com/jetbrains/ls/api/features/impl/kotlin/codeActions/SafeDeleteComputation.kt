// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Pure-PSI computation for deleting a declaration that nothing uses.
 *
 * "Safe" is the whole point: the refactoring is only offered when a project-wide search finds no
 * references. It never deletes something in use and never asks the user to accept breakage --
 * where IntelliJ would show a conflicts dialog, this simply does not appear.
 */
object SafeDeleteComputation {
    data class Deletion(
        /** The declaration's range, including the line it sits on. */
        val range: TextRange,
        val name: String,
        val kind: String,
    )

    fun deletionAt(file: PsiFile, offset: Int): Deletion? {
        val declaration = file.findElementAt(offset)
            ?.parentOfType<KtNamedDeclaration>(withSelf = true) ?: return null
        val name = declaration.name ?: return null

        // Only shapes whose removal is local and obvious. A class or an interface member can be
        // referenced in ways a plain reference search under-reports (reflection, serialization,
        // overrides), and deleting those on that evidence would be reckless.
        val kind = when {
            declaration is KtNamedFunction && declaration.isLocal -> "local function"
            declaration is KtNamedFunction && declaration.isTopLevel && declaration.isPrivate() -> "private function"
            declaration is KtProperty && declaration.isLocal -> "local variable"
            declaration is KtProperty && declaration.isTopLevel && declaration.isPrivate() -> "private property"
            else -> return null
        }

        // An override or an operator has callers the search cannot see.
        if (declaration.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD)) return null
        if (declaration.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPERATOR_KEYWORD)) return null
        if (declaration.annotationEntries.isNotEmpty()) return null

        val scope = GlobalSearchScope.allScope(declaration.project)
        val used = ReferencesSearch.search(declaration, scope, false).findFirst() != null
        if (used) return null

        return Deletion(range = rangeIncludingLine(file, declaration), name = name, kind = kind)
    }

    /** Take the whole line when the declaration owns it, so no blank line is left behind. */
    private fun rangeIncludingLine(file: PsiFile, declaration: PsiElement): TextRange {
        val text = file.text
        val range = declaration.textRange
        val lineStart = text.lastIndexOf('\n', range.startOffset - 1) + 1
        if (!text.substring(lineStart, range.startOffset).all(Char::isWhitespace)) return range
        var end = range.endOffset
        while (end < text.length && text[end] != '\n' && text[end].isWhitespace()) end++
        if (end < text.length && text[end] == '\n') end++ else return range
        return TextRange(lineStart, end)
    }

    private fun KtNamedDeclaration.isPrivate(): Boolean =
        hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)
}
