// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.typeHierarchy

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Pure-PSI core of the Kotlin `textDocument/typeHierarchy` feature, kept free of LSP/server types
 * so it can be unit-tested directly against a PSI fixture (see the kotlin-lsp-dev overlay).
 *
 * Works uniformly on Java and Kotlin classes by bridging Kotlin declarations to their light
 * [PsiClass], which is what the platform search APIs ([ClassInheritorsSearch], [PsiClass.getSupers])
 * operate on.
 */
object KotlinTypeHierarchyComputation {
    private val ROOT_TYPES = setOf("java.lang.Object", "kotlin.Any")

    /** The class declaration enclosing [offset], as a [PsiClass] (Kotlin classes via their light class). */
    fun classAt(file: PsiFile, offset: Int): PsiClass? {
        val element = file.findElementAt(offset) ?: return null
        element.parentOfType<PsiClass>(withSelf = true)?.let { return it }
        val ktClass = element.parentOfType<KtClassOrObject>(withSelf = true) ?: return null
        return ktClass.toLightClass()
    }

    /** Direct supertypes, excluding the implicit `Any`/`Object` root. */
    fun supertypes(psiClass: PsiClass): List<PsiClass> =
        psiClass.supers
            .filter { it.qualifiedName !in ROOT_TYPES }
            .distinctBy { it.qualifiedName ?: it }

    /** Direct subtypes found across the project. */
    fun subtypes(psiClass: PsiClass): List<PsiClass> {
        val scope = GlobalSearchScope.allScope(psiClass.project)
        return ClassInheritorsSearch.search(psiClass, scope, /* checkDeep = */ false)
            .findAll()
            .distinctBy { it.qualifiedName ?: it }
    }
}
