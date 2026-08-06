// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Pure-PSI computation for filling an empty Kotlin call with a named `TODO()` per parameter,
 * which is what [Kotlin/kotlin-lsp#175](https://github.com/Kotlin/kotlin-lsp/issues/175) asked for.
 *
 * It deliberately does **not** offer "add names to existing arguments". The shipped server already
 * provides that as a built-in `quickfix` intention titled "Add names to call arguments" -- adding
 * our own would put a duplicate entry in every user's code-action list for no gain. That was found
 * by running the smoke suite against a stock server (`smoke-test.py --stock`), which is exactly
 * what that negative control exists to catch.
 *
 * Named arguments are a Kotlin-only calling convention, so a call resolving to a Java method is
 * declined -- naming its arguments would not compile.
 */
object NamedArgumentsComputation {
    /** Placeholder argument list for a call written with empty parentheses. */
    data class Fill(
        val range: TextRange,
        val text: String,
        val calleeName: String,
        val parameters: List<String>,
    )

    fun fillAt(file: PsiFile, offset: Int): Fill? {
        val call = callAt(file, offset) ?: return null
        val argumentList = call.valueArgumentList ?: return null
        // Only an empty call: anything already written is the user's, not ours to replace.
        if (argumentList.arguments.isNotEmpty()) return null
        if (call.lambdaArguments.isNotEmpty()) return null
        val leftParenthesis = argumentList.leftParenthesis ?: return null
        val rightParenthesis = argumentList.rightParenthesis ?: return null

        val parameters = parametersOf(call) ?: return null
        if (parameters.isEmpty()) return null
        if (parameters.any { it.isVarArg }) return null
        val names = parameters.map { it.name ?: return null }

        val indent = lineIndent(file.text, call.textRange.startOffset)
        val body = names.joinToString(",\n") { "$indent    $it = TODO()" }
        return Fill(
            range = TextRange(leftParenthesis.textRange.endOffset, rightParenthesis.textRange.startOffset),
            text = "\n$body\n$indent",
            calleeName = calleeName(call),
            parameters = names,
        )
    }

    private fun callAt(file: PsiFile, offset: Int): KtCallExpression? {
        if (file !is KtFile) return null
        val at = file.findElementAt(offset) ?: return null
        val enclosing = generateSequence(at.parentOfType<KtCallExpression>(withSelf = true)) {
            it.parentOfType<KtCallExpression>()
        }.toList()
        // Prefer the call whose argument list actually contains the caret, so that in `h(g(1))`
        // a caret inside `g(...)` acts on `g` rather than on the outer call. Falling back to the
        // innermost enclosing call keeps the action available from the callee name too, which is
        // where a caret usually sits when someone reaches for it.
        return enclosing.firstOrNull { call ->
            val list = call.valueArgumentList?.textRange ?: return@firstOrNull false
            offset >= list.startOffset && offset <= list.endOffset
        } ?: enclosing.firstOrNull()
    }

    /** Resolved parameters of the callee, or null when it is absent, ambiguous, or not Kotlin. */
    private fun parametersOf(call: KtCallExpression): List<KtParameter>? {
        val callee = call.calleeExpression ?: return null
        val reference = callee.mainReference ?: return null
        // An overloaded call resolves to more than one candidate; parameter names would then be a
        // guess, and a wrong name is a compile error the user has to undo.
        val candidates = reference.multiResolve(false).mapNotNull { it.element }
        val target = when {
            candidates.size == 1 -> candidates.single()
            candidates.isEmpty() -> reference.resolve() ?: return null
            else -> return null
        }
        return when (target) {
            is KtFunction -> target.valueParameters
            is KtConstructor<*> -> target.valueParameters
            // `Foo(1, 2)` resolves to the class; its primary constructor holds the names.
            is KtClass -> target.primaryConstructor?.valueParameters
            else -> null // Java methods have no named-argument support at Kotlin call sites.
        }
    }

    private fun calleeName(call: KtCallExpression): String =
        call.calleeExpression?.text ?: "call"

    private fun lineIndent(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset).takeWhile(Char::isWhitespace)
    }
}
