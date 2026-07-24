// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.typeHierarchy

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Pure-PSI core of the Kotlin `textDocument/typeHierarchy` feature, free of LSP/server types so
 * it can be unit-tested directly against a PSI fixture.
 *
 * Supertypes come from the Kotlin Analysis API (resolves reliably in the language server, unlike
 * light-class `getSupers()` which needs a fully indexed module). Subtypes use the platform
 * [ClassInheritorsSearch] over the light class, so Java subclasses of Kotlin types are included.
 */
object KotlinTypeHierarchyComputation {
    private val ROOT = setOf("kotlin.Any", "java.lang.Object")

    /** The class/interface/object declaration enclosing [offset]. */
    fun classAt(file: PsiFile, offset: Int): KtClassOrObject? =
        file.findElementAt(offset)?.parentOfType<KtClassOrObject>(withSelf = true)

    /** Direct supertypes (excluding the implicit `Any`/`Object` root), as their declarations. */
    fun supertypes(declaration: KtClassOrObject): List<PsiElement> =
        analyze(declaration) {
            val symbol = declaration.symbol as? KaClassSymbol ?: return@analyze emptyList()
            symbol.superTypes.mapNotNull { type ->
                val classSymbol = (type as? KaClassType)?.symbol ?: return@mapNotNull null
                if (classSymbol.classId?.asFqNameString() in ROOT) null else classSymbol.psi
            }
        }

    /** Direct subtypes found across the project. */
    fun subtypes(declaration: KtClassOrObject): List<PsiElement> {
        val light = declaration.toLightClass() ?: return emptyList()
        val scope = GlobalSearchScope.allScope(declaration.project)
        return ClassInheritorsSearch.search(light, scope, /* checkDeep = */ false)
            .findAll()
            .map { it.navigationElement }
    }
}
