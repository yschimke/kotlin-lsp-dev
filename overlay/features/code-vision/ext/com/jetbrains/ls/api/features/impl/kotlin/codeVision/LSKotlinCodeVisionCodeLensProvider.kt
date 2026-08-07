// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeVision

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.codeLens.LSCodeLensProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeLens
import com.jetbrains.lsp.protocol.CodeLensParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.Range
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * "Code vision" lenses over Kotlin declarations, delegating counting/detection to
 * [KotlinCodeVisionComputation]:
 *  - reference count ("N usages"),
 *  - implementation/override count ("N implementations"),
 *  - a run affordance over `@Test` functions.
 *
 * kotlin-lsp ships only a run-main lens; these add the rest via the existing (already-routed)
 * [LSCodeLensProvider] interface.
 */
internal object LSKotlinCodeVisionCodeLensProvider : LSCodeLensProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeLenses(params: CodeLensParams): Flow<CodeLens> = flow {
        val lenses = server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = vf.findPsiFile(project) ?: return@readAction emptyList()
                val document = vf.findDocument() ?: return@readAction emptyList()
                val out = ArrayList<CodeLens>()

                val uri = params.textDocument.uri.uri.toString()
                for (decl in KotlinCodeVisionComputation.lensableDeclarations(psiFile)) {
                    val range = decl.nameIdentifier?.textRange?.toLspRange(document) ?: continue
                    val refs = KotlinCodeVisionComputation.referenceCount(decl)
                    if (refs > 0) {
                        out += lens(range, plural(refs, "usage", "usages"),
                            "kotlinLspDev.showReferences", uri, range.start.line, range.start.character)
                    }
                    val impls = KotlinCodeVisionComputation.implementationCount(decl)
                    if (impls != null && impls > 0) {
                        out += lens(range, plural(impls, "implementation", "implementations"),
                            "kotlinLspDev.showImplementations", uri, range.start.line, range.start.character)
                    }
                }
                for ((testId, fn) in KotlinCodeVisionComputation.testFunctions(psiFile)) {
                    val range = fn.nameIdentifier?.textRange?.toLspRange(document) ?: continue
                    out += runLens(range, testId, uri)
                }
                out
            }
        }
        lenses.forEach { emit(it) }
    }

    private fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

    /**
     * Lenses carry a command, because one that looks clickable and is not is worse than none --
     * a "▶ Run test" label that does nothing reads as a broken run button.
     *
     * The commands are the client's, not the server's: peeking references and starting a test run
     * are editor actions. A client that does not register them simply shows an inert label, which
     * is the old behaviour rather than an error.
     */
    private fun lens(range: Range, title: String, command: String, uri: String, line: Int, character: Int): CodeLens =
        CodeLens(
            range,
            Command(title, command, listOf(JsonPrimitive(uri), JsonPrimitive(line), JsonPrimitive(character))),
            null,
        )

    private fun runLens(range: Range, testId: String, uri: String): CodeLens =
        CodeLens(
            range,
            Command("▶ Run test", "kotlinLspDev.runTest", listOf(JsonPrimitive(uri), JsonPrimitive(testId))),
            null,
        )
}
