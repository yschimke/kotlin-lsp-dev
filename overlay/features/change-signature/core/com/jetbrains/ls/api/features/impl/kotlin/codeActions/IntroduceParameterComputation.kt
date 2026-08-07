// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.types.Variance

/**
 * Pure-PSI computation for lifting an expression out of a function body into a new parameter,
 * passing the original expression at every call site.
 *
 * The general refactoring asks for a name, a position and what each caller should pass. Only the
 * last has real consequences, and it has one obvious answer -- the expression that was there --
 * so the rest is defaulted: appended to the parameter list (leaving existing positional arguments
 * untouched) with a name derived from the expression.
 */
object IntroduceParameterComputation {
    data class FileEdits(val file: PsiFile, val insertions: List<Insertion>, val replacements: List<Replacement>)
    data class Insertion(val offset: Int, val text: String)
    data class Replacement(val range: TextRange, val text: String)

    data class Introduction(
        val parameterName: String,
        val parameterType: String,
        val functionName: String,
        val edits: List<FileEdits>,
        val callSiteCount: Int,
    )

    fun introduceAt(file: PsiFile, selection: TextRange): Introduction? {
        val text = file.text
        var start = selection.startOffset.coerceIn(0, text.length)
        var end = selection.endOffset.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return null
        val selected = TextRange(start, end)

        val leaf = file.findElementAt(start) ?: return null
        val expression = generateSequence(leaf) { it.parent }
            .filterIsInstance<KtExpression>()
            .firstOrNull { it.textRange == selected } ?: return null

        val function = expression.parentOfType<KtNamedFunction>() ?: return null
        if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
        if (function.hasModifier(KtTokens.OPEN_KEYWORD)) return null
        if (function.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return null
        if (function.hasModifier(KtTokens.OPERATOR_KEYWORD)) return null
        val body = function.bodyExpression ?: return null
        // The expression has to be inside the body, not in the signature or a default value.
        if (!body.textRange.contains(selected)) return null

        // The decisive rule: the caller has to be able to evaluate this expression, so it must not
        // depend on anything that only exists inside the function. Lifting `x + 1` where `x` is a
        // local produces a call site that does not compile -- or worse, silently binds to a
        // different `x` that happens to be in scope there.
        val dependsOnLocals = PsiTreeUtil.findChildrenOfType(expression, KtSimpleNameExpression::class.java)
            .plus(listOfNotNull(expression as? KtSimpleNameExpression))
            .any { reference ->
                val target = reference.mainReference?.resolve() as? KtDeclaration ?: return@any false
                when (target) {
                    is KtParameter -> target.parentOfType<KtNamedFunction>() == function
                    is KtProperty -> target.isLocal && function.textRange.contains(target.textRange)
                    else -> function.textRange.contains(target.textRange)
                }
            }
        if (dependsOnLocals) return null

        val type = renderType(expression) ?: return null
        val taken = PsiTreeUtil.findChildrenOfType(function, org.jetbrains.kotlin.psi.KtNamedDeclaration::class.java)
            .mapNotNullTo(mutableSetOf()) { it.name } + function.valueParameters.mapNotNull { it.name }
        val name = nameFor(expression, taken)

        // Every occurrence of the same expression in the body becomes the parameter.
        val expressionText = expression.text
        // The body itself counts: for `fun g(): Int = 42` the body *is* the expression, and
        // findChildrenOfType does not include the root -- which left the declaration returning the
        // literal it was supposed to stop returning.
        val occurrences = (listOf(body) + PsiTreeUtil.findChildrenOfType(body, KtExpression::class.java))
            .filter { it::class == expression::class && it.text == expressionText }
            .map { it.textRange }
            .sortedBy { it.startOffset }

        val parameterList = function.valueParameterList ?: return null
        val insertAt = (parameterList.rightParenthesis ?: return null).textRange.startOffset
        val declarationText = if (function.valueParameters.isEmpty()) "$name: $type" else ", $name: $type"

        val edits = LinkedHashMap<PsiFile, Pair<MutableList<Insertion>, MutableList<Replacement>>>()
        val own = edits.getOrPut(function.containingFile) { mutableListOf<Insertion>() to mutableListOf() }
        own.first += Insertion(insertAt, declarationText)
        occurrences.forEach { own.second += Replacement(it, name) }

        var callSites = 0
        val scope = GlobalSearchScope.allScope(function.project)
        for (reference in ReferencesSearch.search(function, scope, false).findAll()) {
            val element = reference.element
            if (element.parentOfType<KtCallableReferenceExpression>() != null) return null
            val call = element.parentOfType<KtCallExpression>() ?: return null
            if (call.calleeExpression?.textRange?.contains(element.textRange) != true) return null
            if (call.lambdaArguments.isNotEmpty()) return null
            val arguments = call.valueArguments.filterIsInstance<KtValueArgument>()
            if (arguments.any { it.getSpreadElement() != null }) return null
            // Named arguments would need the new one named too; appending positionally after a
            // named argument is not valid Kotlin.
            if (arguments.any { it.getArgumentName() != null }) return null
            val closing = (call.valueArgumentList?.rightParenthesis ?: return null).textRange.startOffset
            val argumentText = if (arguments.isEmpty()) expressionText else ", $expressionText"
            edits.getOrPut(call.containingFile) { mutableListOf<Insertion>() to mutableListOf() }
                .first += Insertion(closing, argumentText)
            callSites++
        }

        return Introduction(
            parameterName = name,
            parameterType = type,
            functionName = function.name ?: "function",
            edits = edits.map { (file, pair) -> FileEdits(file, pair.first, pair.second) },
            callSiteCount = callSites,
        )
    }

    private fun renderType(expression: KtExpression): String? = try {
        analyze(expression) {
            val type = expression.expressionType ?: return@analyze null
            if (type is KaErrorType) return@analyze null
            type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT).ifBlank { null }
        }
    } catch (_: Throwable) {
        null
    }

    private fun nameFor(expression: KtExpression, taken: Set<String>): String {
        val base = (expression as? KtSimpleNameExpression)?.getReferencedName()
            ?.replaceFirstChar { it.lowercase() }
            ?: "parameter"
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base$it" }.first { it !in taken }
    }
}
