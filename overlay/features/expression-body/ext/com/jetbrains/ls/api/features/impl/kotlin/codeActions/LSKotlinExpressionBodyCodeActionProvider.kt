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
 * "Convert to expression body" refactoring code action, via the existing (already-routed,
 * additive) [LSCodeActionProvider] interface. The action carries a direct [WorkspaceEdit] (no
 * command round-trip).
 */
internal object LSKotlinExpressionBodyCodeActionProvider : LSCodeActionProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.RefactorRewrite)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val action = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = vf.findPsiFile(project) ?: return@readAction null
                val document = vf.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(params.range.start)
                val conversion = ExpressionBodyComputation.convertible(psiFile, offset) ?: return@readAction null
                val edit = TextEdit(
                    range = conversion.replaceRange.toLspRange(document),
                    newText = conversion.replacement,
                )
                CodeAction(
                    title = "Convert to expression body",
                    kind = CodeActionKind.RefactorRewrite,
                    diagnostics = null,
                    isPreferred = null,
                    disabled = null,
                    edit = WorkspaceEdit(changes = mapOf(params.textDocument.uri to listOf(edit))),
                    command = null,
                    data = null,
                )
            }
        }
        if (action != null) emit(action)
    }
}
