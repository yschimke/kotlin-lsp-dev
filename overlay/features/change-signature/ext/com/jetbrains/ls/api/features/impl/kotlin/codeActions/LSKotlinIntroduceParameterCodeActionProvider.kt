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
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Lifts a selected expression into a new parameter, passed by every call site. */
internal object LSKotlinIntroduceParameterCodeActionProvider : LSCodeActionProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.RefactorExtract)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val action = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = vf.findPsiFile(project) ?: return@readAction null
                val document = vf.findDocument() ?: return@readAction null
                val introduction = IntroduceParameterComputation.introduceAt(
                    psiFile,
                    TextRange(
                        document.offsetByPosition(params.range.start),
                        document.offsetByPosition(params.range.end),
                    ),
                ) ?: return@readAction null

                val changes = LinkedHashMap<DocumentUri, List<TextEdit>>()
                for (fileEdits in introduction.edits) {
                    val editedFile = fileEdits.file.virtualFile ?: return@readAction null
                    val editedDocument = editedFile.findDocument() ?: return@readAction null
                    val edits = fileEdits.replacements.map {
                        TextEdit(it.range.toLspRange(editedDocument), it.text)
                    } + fileEdits.insertions.map {
                        val at = TextRange(it.offset, it.offset).toLspRange(editedDocument).start
                        TextEdit(Range(at, at), it.text)
                    }
                    changes[DocumentUri(editedFile.uri)] = edits
                }
                if (changes.isEmpty()) return@readAction null

                val callSites = introduction.callSiteCount
                CodeAction(
                    title = "Introduce parameter '${introduction.parameterName}: " +
                        "${introduction.parameterType}' in '${introduction.functionName}' " +
                        "(${callSites} call site${if (callSites == 1) "" else "s"})",
                    kind = CodeActionKind.RefactorExtract,
                    diagnostics = null,
                    isPreferred = null,
                    disabled = null,
                    edit = WorkspaceEdit(changes = changes),
                    command = null,
                    data = null,
                )
            }
        }
        if (action != null) emit(action)
    }
}
