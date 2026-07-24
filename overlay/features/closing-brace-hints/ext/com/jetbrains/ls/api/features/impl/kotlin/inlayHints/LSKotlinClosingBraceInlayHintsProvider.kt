// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.inlayHints

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.inlayHints.LSInlayHintsProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.InlayHint
import com.jetbrains.lsp.protocol.InlayHintKind
import com.jetbrains.lsp.protocol.InlayHintParams
import com.jetbrains.lsp.protocol.OrString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Adds a label at the closing `}` of long function/class bodies (e.g. `} fun foo`), via the
 * existing (already-routed) [LSInlayHintsProvider] interface. The server merges these with the
 * built-in type/parameter hints.
 */
internal object LSKotlinClosingBraceInlayHintsProvider : LSInlayHintsProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId =
        LSUniqueConfigurationEntry.UniqueId("kotlin.closingBraceHints")

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getInlayHints(params: InlayHintParams): Flow<InlayHint> = flow {
        val hints = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = vf.findPsiFile(project) ?: return@readAction emptyList()
                val document = vf.findDocument() ?: return@readAction emptyList()
                ClosingBraceHintsComputation.hints(psiFile).map { hint ->
                    InlayHint(
                        position = document.positionByOffset(hint.offset),
                        label = OrString(" ${hint.label}"),
                        kind = InlayHintKind.Type,
                        textEdits = null,
                        tooltip = null,
                        paddingLeft = true,
                        paddingRight = false,
                        data = null,
                    )
                }
            }
        }
        hints.forEach { emit(it) }
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun resolveInlayHint(hint: InlayHint): InlayHint? = null
}
