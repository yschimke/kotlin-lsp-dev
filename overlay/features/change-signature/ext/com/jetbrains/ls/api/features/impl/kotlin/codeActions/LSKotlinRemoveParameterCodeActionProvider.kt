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
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Removes a parameter the function never uses, and the matching argument at every call site.
 *
 * Unlike the other code actions here, the edit spans files -- the call sites are wherever they
 * are. A parameter removed only from the declaration leaves every caller broken, so the edit has
 * to be all-or-nothing.
 */
internal object LSKotlinRemoveParameterCodeActionProvider : LSCodeActionProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.RefactorRewrite)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val action = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = vf.findPsiFile(project) ?: return@readAction null
                val document = vf.findDocument() ?: return@readAction null
                val removal = RemoveParameterComputation.removalAt(
                    psiFile,
                    document.offsetByPosition(params.range.start),
                ) ?: return@readAction null

                val changes = LinkedHashMap<DocumentUri, List<TextEdit>>()
                for (fileEdits in removal.edits) {
                    val editedFile = fileEdits.file.virtualFile ?: return@readAction null
                    val editedDocument = editedFile.findDocument() ?: return@readAction null
                    changes[DocumentUri(editedFile.uri)] =
                        fileEdits.ranges.map { TextEdit(it.toLspRange(editedDocument), "") }
                }
                if (changes.isEmpty()) return@readAction null

                val callSites = removal.callSiteCount
                val where = when {
                    callSites == 0 -> "no call sites"
                    callSites == 1 -> "1 call site"
                    else -> "$callSites call sites"
                }
                CodeAction(
                    title = "Remove unused parameter '${removal.parameterName}' from " +
                        "'${removal.functionName}' ($where)",
                    kind = CodeActionKind.RefactorRewrite,
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
