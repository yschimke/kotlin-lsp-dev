// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.types.Variance

/**
 * Pure-PSI computation for extracting selected statements into a private function.
 *
 * Deliberately narrow. Extract-function is the refactoring with the most ways to silently change
 * behaviour, so this handles the shape it can prove correct -- a contiguous run of whole statements
 * with no result value and no control flow leaving the selection -- and declines everything else.
 * A declined action is a minor annoyance; a wrong one rewrites working code into broken code.
 */
object ExtractFunctionComputation {
    data class Extraction(
        /** The selected statements, to be replaced by [callText]. */
        val selectionRange: TextRange,
        val callText: String,
        val functionInsertOffset: Int,
        val functionText: String,
        val functionName: String,
        val parameters: List<String>,
    )

    fun extract(file: PsiFile, selection: TextRange): Extraction? {
        val text = file.text
        var start = selection.startOffset.coerceIn(0, text.length)
        var end = selection.endOffset.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return null
        val selected = TextRange(start, end)

        val block = file.findElementAt(start)?.parentOfType<KtBlockExpression>() ?: return null
        val function = block.parent as? KtNamedFunction ?: return null
        if (function.bodyBlockExpression != block) return null

        val statements = block.statements.filter { selected.contains(it.textRange) }
        if (statements.isEmpty()) return null
        // Require the selection to be exactly a run of whole statements: partial coverage means
        // the user selected an expression, which is extract-variable's job.
        if (statements.first().textRange.startOffset != start) return null
        if (statements.last().textRange.endOffset != end) return null
        // Extracting the entire body would only rename the function.
        if (statements.size == block.statements.size) return null

        if (hasEscapingControlFlow(statements)) return null

        val references = statements.flatMap {
            PsiTreeUtil.findChildrenOfType(it, KtSimpleNameExpression::class.java)
        }

        // A variable the selection introduces but the rest of the function still reads would have
        // to come back as a result. Returning one value is expressible; deciding which, and
        // handling more than one, is not worth guessing at.
        if (declaresSomethingUsedLater(function, statements, selected)) return null

        val captured = capturedVariables(function, references, selected)
        // Writing to a captured variable inside the selection would not propagate back to the
        // caller: Kotlin passes by value and the parameter is a local copy.
        if (captured.any { isAssignedIn(it, references) }) return null

        val parameters = renderParameters(captured) ?: return null
        val name = freshName(file)

        val functionIndent = lineIndent(text, function.textRange.startOffset)
        val bodyIndent = "$functionIndent    "
        val originalIndent = lineIndent(text, statements.first().textRange.startOffset)
        val body = reindent(text.substring(start, end), originalIndent, bodyIndent)

        val signature = parameters.joinToString(", ") { "${it.first}: ${it.second}" }
        val functionText = "\n\n${functionIndent}private fun $name($signature) {\n$body\n$functionIndent}"
        val callArguments = parameters.joinToString(", ") { it.first }

        return Extraction(
            selectionRange = selected,
            callText = "$name($callArguments)",
            functionInsertOffset = function.textRange.endOffset,
            functionText = functionText,
            functionName = name,
            parameters = parameters.map { it.first },
        )
    }

    private fun hasEscapingControlFlow(statements: List<PsiElement>): Boolean = statements.any {
        PsiTreeUtil.findChildrenOfType(it, KtReturnExpression::class.java).isNotEmpty() ||
            PsiTreeUtil.findChildrenOfType(it, KtBreakExpression::class.java).isNotEmpty() ||
            PsiTreeUtil.findChildrenOfType(it, KtContinueExpression::class.java).isNotEmpty()
    }

    /** True when the selection declares a name that is still read after it. */
    private fun declaresSomethingUsedLater(
        function: KtNamedFunction,
        statements: List<PsiElement>,
        selected: TextRange,
    ): Boolean {
        val declared = statements.flatMap {
            PsiTreeUtil.findChildrenOfType(it, KtNamedDeclaration::class.java) +
                listOfNotNull(it as? KtNamedDeclaration)
        }.toSet()
        if (declared.isEmpty()) return false
        return PsiTreeUtil.findChildrenOfType(function, KtSimpleNameExpression::class.java)
            .filter { it.textRange.startOffset >= selected.endOffset }
            .any { it.mainReference.resolve() in declared }
    }

    /**
     * Locals and parameters declared outside the selection but used inside it, ordered by their
     * declaration so the parameter list is stable rather than dependent on use order.
     */
    private fun capturedVariables(
        function: KtNamedFunction,
        references: List<KtSimpleNameExpression>,
        selected: TextRange,
    ): List<KtCallableDeclaration> {
        val functionRange = function.textRange
        return references
            .mapNotNull { it.mainReference.resolve() }
            .filterIsInstance<KtCallableDeclaration>()
            .filter { declaration ->
                when (declaration) {
                    is KtParameter, is KtProperty -> true
                    else -> false
                } &&
                    (declaration !is KtProperty || declaration.isLocal) &&
                    functionRange.contains(declaration.textRange) &&
                    !selected.contains(declaration.textRange)
            }
            .distinct()
            .sortedBy { it.textRange.startOffset }
    }

    private fun isAssignedIn(
        declaration: KtCallableDeclaration,
        references: List<KtSimpleNameExpression>,
    ): Boolean = references.any { reference ->
        if (reference.mainReference.resolve() != declaration) return@any false
        val parent = reference.parent
        when (parent) {
            is KtBinaryExpression ->
                parent.left == reference && parent.operationToken.toString().endsWith("EQ")
            // `x++` / `--x` mutate just as an assignment does.
            is KtUnaryExpression -> parent.operationToken.toString().let { it == "PLUSPLUS" || it == "MINUSMINUS" }
            else -> false
        }
    }

    /**
     * Parameter names with their rendered types, or null when any type cannot be written as
     * source. An unresolved type renders as a bare callee name (`listOf` rather than `List<Int>`),
     * which would compile to nonsense -- so an error type declines the whole extraction.
     */
    private fun renderParameters(captured: List<KtCallableDeclaration>): List<Pair<String, String>>? {
        if (captured.isEmpty()) return emptyList()
        val names = captured.map { it.name ?: return null }

        // Prefer the type the user wrote. It is exactly what belongs in the new signature --
        // preserving their spelling, type aliases and nullability -- and it needs no analysis,
        // which matters because a captured function parameter is always annotated.
        val written = captured.map { it.typeReference?.text }
        if (written.all { it != null }) return names.zip(written.map { it!! })

        // Otherwise infer, e.g. for `val x = someCall()`.
        val inferred = analyze(captured.first()) {
            captured.map { declaration ->
                val symbol = declaration.symbol as? KaVariableSymbol ?: return@analyze null
                val type = symbol.returnType
                if (type is KaErrorType) return@analyze null
                val rendered = type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                rendered.ifBlank { return@analyze null }
            }
        } ?: return null
        return names.zip(captured.indices.map { written[it] ?: inferred[it] })
    }

    /** `extracted`, `extracted2`, ... avoiding any name already declared in the file. */
    private fun freshName(file: PsiFile): String {
        val taken = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
            .mapNotNullTo(mutableSetOf()) { it.name }
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) "extracted" else "extracted$it" }
            .first { it !in taken }
    }

    private fun reindent(body: String, from: String, to: String): String =
        body.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                from.isNotEmpty() && line.startsWith(from) -> to + line.removePrefix(from)
                else -> to + line.trimStart()
            }
        }

    private fun lineIndent(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset).takeWhile(Char::isWhitespace)
    }
}
