// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.inlayHints

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Pure-PSI core for "closing-brace" inlay hints: a label at the `}` of a function or class body
 * that spans enough lines to be worth annotating (e.g. `} fun foo`). Free of LSP types so it can
 * be unit-tested directly. Not covered by kotlin-lsp's built-in (type/parameter) hints.
 */
object ClosingBraceHintsComputation {
    /** Minimum number of lines a body must span before its closing brace is labelled. */
    const val MIN_LINES: Int = 4

    data class Hint(val offset: Int, val label: String)

    fun hints(file: PsiFile): List<Hint> {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyList()
        val out = ArrayList<Hint>()
        for (fn in PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java)) {
            val body = fn.bodyBlockExpression ?: continue
            addIfLong(document, out, body, "fun ${fn.name ?: continue}")
        }
        for (cls in PsiTreeUtil.collectElementsOfType(file, KtClassOrObject::class.java)) {
            val body = cls.body ?: continue
            val kw = if (cls is org.jetbrains.kotlin.psi.KtObjectDeclaration) "object" else "class"
            addIfLong(document, out, body, "$kw ${cls.name ?: continue}")
        }
        return out.sortedBy { it.offset }
    }

    private fun addIfLong(
        document: com.intellij.openapi.editor.Document,
        out: MutableList<Hint>,
        body: PsiElement,
        label: String,
    ) {
        val range = body.textRange
        val startLine = document.getLineNumber(range.startOffset)
        val endLine = document.getLineNumber(range.endOffset)
        if (endLine - startLine < MIN_LINES) return
        out += Hint(range.endOffset, label)
    }
}
