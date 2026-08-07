// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Pure-PSI computation for inlining a call to an expression-bodied function.
 *
 * Scoped to `fun f(a: Int) = <expression>`, which is the overwhelmingly common shape in Kotlin and
 * the one that can be inlined by substitution without changing behaviour. A block body would need
 * statements lifted into the caller, and that is a different, much larger refactoring.
 */
object InlineFunctionComputation {
    data class Inlining(
        /** The call to replace. */
        val callRange: TextRange,
        val replacement: String,
        val functionName: String,
    )

    fun inlineAt(file: PsiFile, offset: Int): Inlining? {
        val leaf = file.findElementAt(offset) ?: return null
        val call = leaf.parentOfType<KtCallExpression>(withSelf = true) ?: return null
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null

        val function = callee.mainReference?.resolve() as? KtNamedFunction ?: return null
        val body = function.bodyExpression ?: return null
        // Only an expression body. `hasBlockBody` covers `fun f() { ... }`.
        if (function.hasBlockBody()) return null
        // A function that calls itself would inline forever.
        if (PsiTreeUtil.findChildrenOfType(body, KtNameReferenceExpression::class.java)
                .any { it.mainReference?.resolve() == function }
        ) return null
        // An extension receiver would have to be substituted too, which needs more care than this.
        if (function.receiverTypeReference != null) return null
        if (call.parent is KtDotQualifiedExpression) return null
        if (call.lambdaArguments.isNotEmpty()) return null

        val parameters = function.valueParameters
        if (parameters.any { it.isVarArg }) return null
        val arguments = call.valueArguments.filterIsInstance<KtValueArgument>()
        if (arguments.any { it.getSpreadElement() != null }) return null

        // Map parameters to argument text, by name where named, else positionally. A parameter
        // left to its default has no argument to substitute, so decline rather than guess.
        val byName = arguments.mapNotNull { argument ->
            val name = argument.getArgumentName()?.asName?.asString() ?: return@mapNotNull null
            val expression = argument.getArgumentExpression() ?: return@mapNotNull null
            name to expression
        }.toMap()
        val positional = arguments.filter { it.getArgumentName() == null }
        val substitution = LinkedHashMap<KtParameter, KtExpression>()
        var positionalIndex = 0
        for (parameter in parameters) {
            val name = parameter.name ?: return null
            val expression = byName[name] ?: positional.getOrNull(positionalIndex)
                ?.also { positionalIndex++ }
                ?.getArgumentExpression()
            substitution[parameter] = expression ?: return null
        }
        if (positionalIndex != positional.size) return null

        // Every use of each parameter inside the body, resolved rather than name-matched.
        val uses = PsiTreeUtil.findChildrenOfType(body, KtSimpleNameExpression::class.java)
            .mapNotNull { use ->
                val target = use.mainReference?.resolve() as? KtParameter ?: return@mapNotNull null
                if (target !in substitution) null else use to target
            }
        // Substituting a non-trivial argument into more than one position would evaluate it more
        // than once. `compute()` used twice is a different program, silently.
        val useCounts = uses.groupingBy { it.second }.eachCount()
        for ((parameter, argument) in substitution) {
            if ((useCounts[parameter] ?: 0) > 1 && !isRepeatable(argument)) return null
        }

        val bodyText = body.text
        val bodyStart = body.textRange.startOffset
        val replacements = uses
            .map { (use, parameter) ->
                val range = use.textRange
                Triple(range.startOffset - bodyStart, range.endOffset - bodyStart, substitution.getValue(parameter))
            }
            .sortedByDescending { it.first }

        var inlined = bodyText
        for ((start, end, argument) in replacements) {
            inlined = inlined.substring(0, start) + parenthesise(argument) + inlined.substring(end)
        }

        // The inlined expression takes the call's place inside a larger expression, so it needs
        // parentheses unless it is self-delimiting -- the same reasoning as inline-variable.
        val needsParens = !isAtomicText(body)
        return Inlining(
            callRange = call.textRange,
            replacement = if (needsParens) "($inlined)" else inlined,
            functionName = function.name ?: "function",
        )
    }

    /** Safe to substitute more than once: no side effects and no cost worth worrying about. */
    private fun isRepeatable(expression: KtExpression): Boolean =
        expression is KtNameReferenceExpression ||
            expression is org.jetbrains.kotlin.psi.KtConstantExpression ||
            expression is org.jetbrains.kotlin.psi.KtThisExpression

    private fun parenthesise(expression: KtExpression): String =
        if (isAtomicText(expression)) expression.text else "(${expression.text})"

    private fun isAtomicText(expression: KtExpression): Boolean = when (expression) {
        is KtNameReferenceExpression,
        is org.jetbrains.kotlin.psi.KtConstantExpression,
        is KtCallExpression,
        is KtDotQualifiedExpression,
        is org.jetbrains.kotlin.psi.KtArrayAccessExpression,
        is org.jetbrains.kotlin.psi.KtParenthesizedExpression,
        is org.jetbrains.kotlin.psi.KtThisExpression,
        is org.jetbrains.kotlin.psi.KtStringTemplateExpression -> true
        else -> false
    }
}
