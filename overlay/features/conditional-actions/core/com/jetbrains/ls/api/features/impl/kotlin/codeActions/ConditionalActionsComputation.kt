// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression

/**
 * Pure-PSI computations for the `if`-shaped code actions that every editor has in some form:
 * inverting a condition, merging a nested `if`, and turning an `if`/`else if` chain into a `when`.
 *
 * All three are text rewrites of a single expression, which is why they share a file: none needs
 * type information, usage search, or anything outside the `if` being acted on.
 */
object ConditionalActionsComputation {
    data class Rewrite(val range: TextRange, val text: String)

    // --- invert condition ------------------------------------------------------------------

    /**
     * Swaps an `if`'s branches and negates its condition. Requires an `else`: without one there is
     * nothing to swap into, and inverting would change what the statement does.
     */
    fun invertAt(file: PsiFile, offset: Int): Rewrite? {
        val ifExpression = ifAt(file, offset) ?: return null
        val condition = ifExpression.condition ?: return null
        val then = ifExpression.then ?: return null
        val elseBranch = ifExpression.`else` ?: return null
        // `else if` chains would need the whole chain restructured, not two branches swapped.
        if (elseBranch is KtIfExpression) return null

        val text = "if (${negate(condition)}) ${elseBranch.text} else ${then.text}"
        return Rewrite(ifExpression.textRange, text)
    }

    // --- merge nested if -------------------------------------------------------------------

    /**
     * `if (a) { if (b) { ... } }` becomes `if (a && b) { ... }`.
     *
     * Only when neither `if` has an `else` and the outer body contains nothing but the inner `if`:
     * anything else and the merge would move or drop code.
     */
    fun mergeNestedAt(file: PsiFile, offset: Int): Rewrite? {
        val outer = ifAt(file, offset) ?: return null
        if (outer.`else` != null) return null
        val outerCondition = outer.condition ?: return null
        val body = outer.then ?: return null

        val inner = when (body) {
            is KtIfExpression -> body
            is KtBlockExpression -> body.statements.singleOrNull() as? KtIfExpression
            else -> null
        } ?: return null
        if (inner.`else` != null) return null
        val innerCondition = inner.condition ?: return null
        val innerBody = inner.then ?: return null

        val merged = "if (${operand(outerCondition)} && ${operand(innerCondition)}) ${innerBody.text}"
        return Rewrite(outer.textRange, merged)
    }

    // --- if chain to when ------------------------------------------------------------------

    /**
     * Turns `if (a) x else if (b) y else z` into a `when`. Needs at least two conditions --
     * a lone `if`/`else` reads better as an `if`.
     */
    fun toWhenAt(file: PsiFile, offset: Int): Rewrite? {
        val ifExpression = ifAt(file, offset) ?: return null
        // Act on the outermost `if` of the chain, whichever branch the caret is in.
        // Walk to the head of the chain. `parent` is not the enclosing `if` -- Kotlin wraps a
        // control-structure body in a KtContainerNodeForControlStructureBody -- so this has to go
        // through parentOfType, which skips it.
        val root = generateSequence(ifExpression) { current ->
            current.parentOfType<KtIfExpression>()?.takeIf { it.`else` === current }
        }.last()

        val branches = mutableListOf<Pair<KtExpression, KtExpression>>()
        var current: KtIfExpression? = root
        var elseBranch: KtExpression? = null
        while (current != null) {
            val condition = current.condition ?: return null
            val then = current.then ?: return null
            branches += condition to then
            val next = current.`else`
            if (next is KtIfExpression) {
                current = next
            } else {
                elseBranch = next
                current = null
            }
        }
        if (branches.size < 2) return null

        val body = buildString {
            append("when {\n")
            for ((condition, then) in branches) {
                append("    ${condition.text} -> ${branchText(then)}\n")
            }
            if (elseBranch != null) append("    else -> ${branchText(elseBranch)}\n")
            append("}")
        }
        return Rewrite(root.textRange, body)
    }

    private fun branchText(branch: KtExpression): String =
        if (branch is KtBlockExpression) branch.text else branch.text

    // --- shared --------------------------------------------------------------------------

    private fun ifAt(file: PsiFile, offset: Int): KtIfExpression? =
        file.findElementAt(offset)?.parentOfType<KtIfExpression>(withSelf = true)

    /**
     * Negates a condition, simplifying rather than piling up `!`: `!a` becomes `a`, `a == b`
     * becomes `a != b`. A doubled negation is harder to read than the thing it replaced.
     */
    private fun negate(condition: KtExpression): String {
        val stripped = (condition as? KtParenthesizedExpression)?.expression ?: condition
        if (stripped is KtPrefixExpression && stripped.operationReference.text == "!") {
            return stripped.baseExpression?.text ?: "!(${stripped.text})"
        }
        if (stripped is KtIsExpression) {
            val negated = if (stripped.isNegated) "is" else "!is"
            return "${stripped.leftHandSide.text} $negated ${stripped.typeReference?.text ?: return fallback(stripped)}"
        }
        if (stripped is KtBinaryExpression) {
            val flipped = when (stripped.operationReference.text) {
                "==" -> "!="
                "!=" -> "=="
                "<" -> ">="
                ">" -> "<="
                "<=" -> ">"
                ">=" -> "<"
                else -> null
            }
            if (flipped != null) {
                val left = stripped.left?.text ?: return fallback(stripped)
                val right = stripped.right?.text ?: return fallback(stripped)
                return "$left $flipped $right"
            }
        }
        return fallback(stripped)
    }

    /** `!a` for something atomic, `!(a && b)` for anything that would rebind. */
    private fun fallback(condition: KtExpression): String =
        if (isAtomicCondition(condition)) "!${condition.text}" else "!(${condition.text})"

    private fun isAtomicCondition(condition: KtExpression): Boolean = when (condition) {
        is org.jetbrains.kotlin.psi.KtNameReferenceExpression,
        is org.jetbrains.kotlin.psi.KtConstantExpression,
        is org.jetbrains.kotlin.psi.KtCallExpression,
        is org.jetbrains.kotlin.psi.KtDotQualifiedExpression,
        is org.jetbrains.kotlin.psi.KtArrayAccessExpression,
        is KtParenthesizedExpression -> true
        else -> false
    }

    /** `&&` binds looser than most things but not everything; parenthesise what is not atomic. */
    private fun operand(condition: KtExpression): String {
        val stripped = (condition as? KtParenthesizedExpression)?.expression ?: condition
        val needsParens = stripped is KtBinaryExpression &&
            stripped.operationReference.text.let { it == "||" || it == "&&" }
        return if (needsParens) "(${stripped.text})" else stripped.text
    }
}
