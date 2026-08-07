// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.application.readAction
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
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The `if`-shaped code actions: invert a condition, merge a nested `if`, turn a chain into a
 * `when`. Every editor has these in some form; none needs type information.
 */
internal object LSKotlinConditionalCodeActionProvider : LSCodeActionProvider {
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

                val candidates = listOf(
                    "Invert 'if' condition" to ConditionalActionsComputation.invertAt(psiFile, offset),
                    "Merge nested 'if'" to ConditionalActionsComputation.mergeNestedAt(psiFile, offset),
                    "Convert 'if' chain to 'when'" to ConditionalActionsComputation.toWhenAt(psiFile, offset),
                )
                candidates.mapNotNull { (title, rewrite) ->
                    if (rewrite == null) return@mapNotNull null
                    CodeAction(
                        title = title,
                        kind = CodeActionKind.RefactorRewrite,
                        diagnostics = null,
                        isPreferred = null,
                        disabled = null,
                        edit = WorkspaceEdit(changes = mapOf(params.textDocument.uri to listOf(
                            TextEdit(rewrite.range.toLspRange(document), rewrite.text),
                        ))),
                        command = null,
                        data = null,
                    )
                }
            }
        }
        actions.forEach { emit(it) }
    }
}
