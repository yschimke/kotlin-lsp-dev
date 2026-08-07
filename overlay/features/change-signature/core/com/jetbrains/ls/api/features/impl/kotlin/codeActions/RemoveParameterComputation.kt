// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Pure-PSI computation for the one change-signature variant with a single correct answer:
 * removing a parameter that the function never uses.
 *
 * The general refactoring needs a dialog -- which parameter, what name, what default, what to pass
 * at each call site. Nothing here has to be chosen: the parameter is unused, so every call site
 * simply drops the matching argument. Anything requiring a judgement call is declined instead of
 * guessed at.
 */
object RemoveParameterComputation {
    /** Ranges to delete in one file. */
    data class FileEdits(val file: PsiFile, val ranges: List<TextRange>)

    data class Removal(
        val parameterName: String,
        val functionName: String,
        val edits: List<FileEdits>,
        val callSiteCount: Int,
    )

    fun removalAt(file: PsiFile, offset: Int): Removal? {
        val parameter = file.findElementAt(offset)?.parentOfType<KtParameter>(withSelf = true) ?: return null
        val function = parameter.parentOfType<KtNamedFunction>() ?: return null
        val parameters = function.valueParameters
        val index = parameters.indexOf(parameter)
        if (index < 0) return null
        val name = parameter.name ?: return null

        // A parameter the body uses is not removable without deciding what replaces it.
        val body = function.bodyExpression
        if (body != null) {
            val used = PsiTreeUtil.findChildrenOfType(body, KtSimpleNameExpression::class.java)
                .any { it.mainReference?.resolve() == parameter }
            if (used) return null
        }

        // Signatures that others depend on, or that the search cannot fully see.
        if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
        if (function.hasModifier(KtTokens.OPEN_KEYWORD)) return null
        if (function.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return null
        if (function.hasModifier(KtTokens.OPERATOR_KEYWORD)) return null
        if (function.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return null
        if (parameter.isVarArg) return null
        if (parameter.annotationEntries.isNotEmpty()) return null

        val edits = LinkedHashMap<PsiFile, MutableList<TextRange>>()
        edits.getOrPut(function.containingFile) { mutableListOf() } +=
            rangeWithSeparator(function.containingFile.text, parameter, parameters.map { it.textRange }, index)

        var callSites = 0
        val scope = GlobalSearchScope.allScope(function.project)
        for (reference in ReferencesSearch.search(function, scope, false).findAll()) {
            val element = reference.element
            // `::f` keeps the old arity; changing the signature silently breaks it.
            if (element.parentOfType<KtCallableReferenceExpression>() != null) return null
            val call = element.parentOfType<KtCallExpression>() ?: return null
            if (call.calleeExpression?.textRange?.contains(element.textRange) != true) return null
            if (call.lambdaArguments.isNotEmpty()) return null

            val arguments = call.valueArguments.filterIsInstance<KtValueArgument>()
            if (arguments.any { it.getSpreadElement() != null }) return null
            val argument = argumentFor(arguments, name, index) ?: continue  // relied on a default
            val argumentIndex = arguments.indexOf(argument)
            edits.getOrPut(call.containingFile) { mutableListOf() } +=
                rangeWithSeparator(call.containingFile.text, argument, arguments.map { it.textRange }, argumentIndex)
            callSites++
        }

        return Removal(
            parameterName = name,
            functionName = function.name ?: "function",
            edits = edits.map { (file, ranges) -> FileEdits(file, ranges.sortedBy { it.startOffset }) },
            callSiteCount = callSites,
        )
    }

    /** The argument supplying this parameter: by name where named, else by position. */
    private fun argumentFor(arguments: List<KtValueArgument>, name: String, index: Int): KtValueArgument? {
        arguments.firstOrNull { it.getArgumentName()?.asName?.asString() == name }?.let { return it }
        val positional = arguments.filter { it.getArgumentName() == null }
        return positional.getOrNull(index)
    }

    /**
     * The element's range plus the comma that joins it to its neighbours, so removing it leaves a
     * well-formed list rather than `f(a, , c)`.
     */
    private fun rangeWithSeparator(
        text: String,
        element: PsiElement,
        siblings: List<TextRange>,
        index: Int,
    ): TextRange {
        val range = element.textRange
        if (siblings.size <= 1) return range
        return if (index > 0) {
            // Absorb the preceding comma and the whitespace before it.
            var start = siblings[index - 1].endOffset
            while (start < range.startOffset && text[start] != ',') start++
            TextRange(start, range.endOffset)
        } else {
            // First of several: absorb the following comma and the space after it.
            var end = range.endOffset
            while (end < text.length && text[end] != ',') end++
            if (end < text.length) end++
            while (end < text.length && text[end] == ' ') end++
            TextRange(range.startOffset, end)
        }
    }
}
