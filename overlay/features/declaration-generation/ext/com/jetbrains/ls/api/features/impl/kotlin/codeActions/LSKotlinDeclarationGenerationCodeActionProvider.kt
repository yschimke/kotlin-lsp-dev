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

/** Generates all missing abstract members, or all available concrete overrides, without a picker. */
internal object LSKotlinDeclarationGenerationCodeActionProvider : LSCodeActionProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.RefactorRewrite)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val actions = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val file = vf.findPsiFile(project) ?: return@readAction emptyList()
                val document = vf.findDocument() ?: return@readAction emptyList()
                val offset = document.offsetByPosition(params.range.start)
                DeclarationGenerationComputation.Kind.entries.mapNotNull { kind ->
                    val generation = DeclarationGenerationComputation.generationAt(file, offset, kind)
                        ?: return@mapNotNull null
                    val title = when (kind) {
                        DeclarationGenerationComputation.Kind.IMPLEMENT -> "Implement missing members"
                        DeclarationGenerationComputation.Kind.OVERRIDE -> "Override members"
                    }
                    CodeAction(
                        title = title,
                        kind = CodeActionKind.RefactorRewrite,
                        diagnostics = null,
                        isPreferred = kind == DeclarationGenerationComputation.Kind.IMPLEMENT,
                        disabled = null,
                        edit = WorkspaceEdit(
                            changes = mapOf(
                                params.textDocument.uri to listOf(
                                    TextEdit(generation.insertRange.toLspRange(document), generation.text)
                                )
                            )
                        ),
                        command = null,
                        data = null,
                    )
                }
            }
        }
        actions.forEach { emit(it) }
    }
}
