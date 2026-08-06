// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Two code actions on a Kotlin call: name its positional arguments, or fill an empty argument list
 * with a `TODO()` placeholder per parameter.
 *
 * Both are `refactor.rewrite`: they restructure a call without extracting or inlining anything.
 * At most one applies to any given call -- naming needs arguments, filling needs none.
 */
internal object LSKotlinNamedArgumentsCodeActionProvider : LSCodeActionProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.RefactorRewrite)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val actions = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = vf.findPsiFile(project) ?: return@readAction emptyList()
                val document = vf.findDocument() ?: return@readAction emptyList()
                val offset = document.offsetByPosition(params.range.start)
                val out = ArrayList<CodeAction>(1)

                NamedArgumentsComputation.namingAt(psiFile, offset)?.let { naming ->
                    val edits = naming.insertions.map { insertion ->
                        val at = TextRange(insertion.offset, insertion.offset).toLspRange(document).start
                        TextEdit(Range(at, at), insertion.text)
                    }
                    out += action("Add names to arguments of '${naming.calleeName}'", params, edits)
                }
                NamedArgumentsComputation.fillAt(psiFile, offset)?.let { fill ->
                    val edits = listOf(TextEdit(fill.range.toLspRange(document), fill.text))
                    out += action(
                        "Fill arguments of '${fill.calleeName}' (${fill.parameters.size} parameter(s))",
                        params,
                        edits,
                    )
                }
                out
            }
        }
        actions.forEach { emit(it) }
    }

    private fun action(title: String, params: CodeActionParams, edits: List<TextEdit>): CodeAction =
        CodeAction(
            title = title,
            kind = CodeActionKind.RefactorRewrite,
            diagnostics = null,
            isPreferred = null,
            disabled = null,
            edit = WorkspaceEdit(changes = mapOf(params.textDocument.uri to edits)),
            command = null,
            data = null,
        )
}
