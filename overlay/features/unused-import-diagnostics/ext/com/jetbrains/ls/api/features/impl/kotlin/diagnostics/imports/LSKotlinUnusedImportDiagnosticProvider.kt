// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics.imports

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DiagnosticSeverity
import com.jetbrains.lsp.protocol.DiagnosticTag
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.kotlin.psi.KtFile

/** Publishes the unused-import result omitted by the built-in inspection blacklist (LSP-704). */
internal object LSKotlinUnusedImportDiagnosticProvider : LSDiagnosticProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        val diagnostics = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val file = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = file.findDocument() ?: return@readAction emptyList()
                val ktFile = file.findPsiFile(project) as? KtFile ?: return@readAction emptyList()
                UnusedImportComputation.find(ktFile).map { unused ->
                    Diagnostic(
                        range = unused.range.toLspRange(document),
                        severity = DiagnosticSeverity.Warning,
                        code = StringOrInt.string("UNUSED_IMPORT"),
                        source = "Kotlin",
                        message = "Unused import: ${unused.importedName}",
                        tags = listOf(DiagnosticTag.Unnecessary),
                    )
                }
            }
        }
        diagnostics.forEach { emit(it) }
    }
}
