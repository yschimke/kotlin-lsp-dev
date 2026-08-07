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

/** Lifts a literal into a file-level `private const val`, replacing every occurrence. */
internal object LSKotlinExtractConstantCodeActionProvider : LSCodeActionProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.RefactorExtract)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val action = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = vf.findPsiFile(project) ?: return@readAction null
                val document = vf.findDocument() ?: return@readAction null
                val extraction = ExtractConstantComputation.extract(
                    psiFile,
                    TextRange(
                        document.offsetByPosition(params.range.start),
                        document.offsetByPosition(params.range.end),
                    ),
                ) ?: return@readAction null

                val insertAt = TextRange(extraction.declarationOffset, extraction.declarationOffset)
                    .toLspRange(document).start
                val edits = listOf(TextEdit(Range(insertAt, insertAt), extraction.declaration)) +
                    extraction.occurrences.map { TextEdit(it.toLspRange(document), extraction.constantName) }

                val count = extraction.occurrences.size
                CodeAction(
                    title = if (count == 1) {
                        "Extract constant '${extraction.constantName}'"
                    } else {
                        "Extract constant '${extraction.constantName}' ($count occurrences)"
                    },
                    kind = CodeActionKind.RefactorExtract,
                    diagnostics = null,
                    isPreferred = null,
                    disabled = null,
                    edit = WorkspaceEdit(changes = mapOf(params.textDocument.uri to edits)),
                    command = null,
                    data = null,
                )
            }
        }
        if (action != null) emit(action)
    }
}
