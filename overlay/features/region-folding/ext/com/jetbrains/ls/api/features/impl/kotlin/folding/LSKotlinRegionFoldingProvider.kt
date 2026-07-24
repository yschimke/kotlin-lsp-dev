// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.folding

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.foldingRange.LSFoldingRangeProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FoldingRange
import com.jetbrains.lsp.protocol.FoldingRangeKind
import com.jetbrains.lsp.protocol.FoldingRangeParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Adds `//region` … `//endregion` folds. kotlin-lsp's folding (via the platform folding builder)
 * does not fold custom region comments; this augments it — the server merges folds from all
 * [LSFoldingRangeProvider]s for the language.
 */
internal object LSKotlinRegionFoldingProvider : LSFoldingRangeProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun provideFoldingRanges(params: FoldingRangeParams): Flow<FoldingRange> = flow {
        val ranges = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = vf.findPsiFile(project) ?: return@readAction emptyList()
                val document = vf.findDocument() ?: return@readAction emptyList()
                RegionFoldingComputation.regions(psiFile).mapNotNull { region ->
                    val startLine = document.getLineNumber(region.start.textRange.startOffset)
                    val endLine = document.getLineNumber(region.end.textRange.endOffset)
                    if (endLine <= startLine) return@mapNotNull null
                    FoldingRange(
                        startLine = startLine,
                        endLine = endLine,
                        startCharacter = null,
                        endCharacter = null,
                        kind = FoldingRangeKind.Region,
                        collapsedText = region.label,
                    )
                }
            }
        }
        ranges.forEach { emit(it) }
    }
}
