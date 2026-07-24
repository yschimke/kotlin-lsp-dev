// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.typeHierarchy

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.resolve.ResolveDataWithConfigurationEntryId
import com.jetbrains.ls.api.features.typeHierarchy.LSTypeHierarchyProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.SymbolKind
import com.jetbrains.lsp.protocol.TypeHierarchyItem
import com.jetbrains.lsp.protocol.TypeHierarchyPrepareParams
import com.jetbrains.lsp.protocol.TypeHierarchySubtypesParams
import com.jetbrains.lsp.protocol.TypeHierarchySupertypesParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/**
 * Kotlin implementation of `textDocument/typeHierarchy`. kotlin-lsp declares
 * [LSTypeHierarchyProvider] but ships no implementation; this supplies one, delegating the
 * PSI-level work to [KotlinTypeHierarchyComputation].
 */
internal object LSKotlinTypeHierarchyProvider : LSTypeHierarchyProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId =
        LSUniqueConfigurationEntry.UniqueId("kotlin.typeHierarchy")

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): List<TypeHierarchyItem>? =
        server.withAnalysisContext {
            readAction {
                val vf = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = vf.findPsiFile(project) ?: return@readAction null
                val document = vf.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(params.position)
                val declaration = KotlinTypeHierarchyComputation.classAt(psiFile, offset) ?: return@readAction null
                listOfNotNull(declaration.toItem())
            }
        }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun supertypes(params: TypeHierarchySupertypesParams): List<TypeHierarchyItem>? =
        related(params.item) { KotlinTypeHierarchyComputation.supertypes(it) }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun subtypes(params: TypeHierarchySubtypesParams): List<TypeHierarchyItem>? =
        related(params.item) { KotlinTypeHierarchyComputation.subtypes(it) }

    context(server: LSServer, handlerContext: LspHandlerContext)
    private suspend fun related(
        item: TypeHierarchyItem,
        relatives: (org.jetbrains.kotlin.psi.KtClassOrObject) -> List<PsiElement>,
    ): List<TypeHierarchyItem>? =
        server.withAnalysisContext {
            readAction {
                val vf = item.uri.findVirtualFile() ?: return@readAction null
                val psiFile = vf.findPsiFile(project) ?: return@readAction null
                val document = vf.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(item.selectionRange.start)
                val declaration = KotlinTypeHierarchyComputation.classAt(psiFile, offset) ?: return@readAction null
                relatives(declaration).mapNotNull { it.toItem() }
            }
        }

    private fun PsiElement.toItem(): TypeHierarchyItem? {
        val location = getLspLocationForDefinition() ?: return null
        val name = (this as? com.intellij.psi.PsiNamedElement)?.name ?: return null
        val kind = when {
            this is KtClass && isInterface() -> SymbolKind.Interface
            this is KtClass && isEnum() -> SymbolKind.Enum
            this is KtObjectDeclaration -> SymbolKind.Object
            this is com.intellij.psi.PsiClass && isInterface -> SymbolKind.Interface
            this is com.intellij.psi.PsiClass && isEnum -> SymbolKind.Enum
            else -> SymbolKind.Class
        }
        val detail = (this as? com.intellij.psi.PsiQualifiedNamedElement)?.qualifiedName
            ?: (this as? org.jetbrains.kotlin.psi.KtClassOrObject)?.fqName?.asString()
        return TypeHierarchyItem(
            name = name,
            kind = kind,
            tags = null,
            detail = detail,
            uri = location.uri,
            range = location.range,
            selectionRange = location.range,
            // Carries our provider id so the server routes supertypes/subtypes back to us; the
            // key is exactly what ResolveDataWithConfigurationEntryId.getConfigurationEntryId reads.
            data = JsonObject(
                mapOf(
                    ResolveDataWithConfigurationEntryId::configurationEntryId.name to JsonPrimitive(uniqueId.value),
                )
            ),
        )
    }
}
