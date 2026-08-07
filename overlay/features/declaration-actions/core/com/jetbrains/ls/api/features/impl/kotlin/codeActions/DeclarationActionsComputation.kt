// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

/**
 * Pure-PSI computations for declaration-shaped code actions: giving a function a block body,
 * writing out an inferred type, and splitting a declaration from its initialization.
 *
 * Each is the inverse or complement of something the editor already offers, and each is a single
 * local rewrite.
 */
object DeclarationActionsComputation {
    data class Rewrite(val range: TextRange, val text: String)

    /**
     * `fun f() = expr` becomes `fun f() { return expr }`. The inverse of convert-to-expression-body.
     *
     * Needs a declared return type to know whether to emit `return`: without one, an expression
     * body is what determines the type, and a block body would have to state it.
     */
    fun toBlockBodyAt(file: PsiFile, offset: Int): Rewrite? {
        val function = file.findElementAt(offset)?.parentOfType<KtNamedFunction>(withSelf = true) ?: return null
        if (function.hasBlockBody()) return null
        val body = function.bodyExpression ?: return null
        val equals = function.equalsToken ?: return null

        val returnType = function.typeReference?.text
        // Unit-returning functions must not gain a `return expr`, and a function with no declared
        // type has to state one before it can have a block body -- which needs inference, so this
        // asks for it rather than guessing.
        val declaredType = returnType ?: inferredReturnType(function) ?: return null
        val statement = if (declaredType == "Unit") body.text else "return ${body.text}"
        // Start at the end of the signature, not at `=`: the space before it is already there, and
        // leaving it produces `fun f(): Int  {`.
        var signatureEnd = equals.textRange.startOffset
        val text = file.text
        while (signatureEnd > 0 && text[signatureEnd - 1].isWhitespace()) signatureEnd--
        val typeSuffix = if (returnType == null) ": $declaredType" else ""
        return Rewrite(
            range = TextRange(signatureEnd, function.textRange.endOffset),
            text = "$typeSuffix {\n    $statement\n}",
        )
    }

    /**
     * `val x = expr` becomes `val x: T = expr`, with T the inferred type written out.
     */
    fun addExplicitTypeAt(file: PsiFile, offset: Int): Rewrite? {
        val property = file.findElementAt(offset)?.parentOfType<KtProperty>(withSelf = true) ?: return null
        if (property.typeReference != null) return null
        val nameIdentifier = property.nameIdentifier ?: return null
        property.initializer ?: return null

        val type = inferredType(property) ?: return null
        val at = nameIdentifier.textRange.endOffset
        return Rewrite(TextRange(at, at), ": $type")
    }

    /**
     * `val x = expr` becomes `val x: T` plus `x = expr`. Only for locals: a property outside a
     * function body has no statement position to move the assignment to.
     */
    fun splitDeclarationAt(file: PsiFile, offset: Int): Rewrite? {
        val property = file.findElementAt(offset)?.parentOfType<KtProperty>(withSelf = true) ?: return null
        if (!property.isLocal) return null
        val name = property.name ?: return null
        val initializer = property.initializer ?: return null
        // `val` split into declaration and assignment must become `var`? No -- Kotlin allows a
        // deferred `val` assignment, so the keyword stays as written.
        val type = property.typeReference?.text ?: inferredType(property) ?: return null
        val keyword = if (property.isVar) "var" else "val"

        val indent = lineIndent(file.text, property.textRange.startOffset)
        return Rewrite(
            range = property.textRange,
            text = "$keyword $name: $type\n$indent$name = ${initializer.text}",
        )
    }

    /**
     * Swaps the operands of a binary expression, flipping the operator so the meaning is kept:
     * `a < b` becomes `b > a`. Commutative operators keep theirs.
     *
     * Declined where swapping changes behaviour rather than reading order: `&&` and `||`
     * short-circuit, so their operand order is semantic, and `-`, `/` and `%` are not commutative.
     */
    fun flipBinaryAt(file: PsiFile, offset: Int): Rewrite? {
        val binary = file.findElementAt(offset)
            ?.parentOfType<org.jetbrains.kotlin.psi.KtBinaryExpression>(withSelf = true) ?: return null
        val left = binary.left ?: return null
        val right = binary.right ?: return null
        val operator = binary.operationReference.text
        val flipped = when (operator) {
            "==", "!=", "&", "|", "^", "+", "*", "===", "!==" -> operator
            "<" -> ">"
            ">" -> "<"
            "<=" -> ">="
            ">=" -> "<="
            else -> return null
        }
        return Rewrite(binary.textRange, "${right.text} $flipped ${left.text}")
    }

    // Rendering is a KaSession member, so it happens inside the analysis block -- a type cannot
    // be carried out and rendered later. An unresolvable type renders as a bare callee name,
    // which would not compile, so an error type declines the action instead.
    private fun inferredType(property: KtProperty): String? = try {
        analyze(property) {
            val symbol = property.symbol as? KaVariableSymbol ?: return@analyze null
            val type = symbol.returnType
            if (type is KaErrorType) return@analyze null
            type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT).ifBlank { null }
        }
    } catch (_: Throwable) {
        null
    }

    private fun inferredReturnType(function: KtNamedFunction): String? = try {
        analyze(function) {
            val type = function.returnType
            if (type is KaErrorType) return@analyze null
            type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT).ifBlank { null }
        }
    } catch (_: Throwable) {
        null
    }

    private fun lineIndent(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset).takeWhile(Char::isWhitespace)
    }
}
