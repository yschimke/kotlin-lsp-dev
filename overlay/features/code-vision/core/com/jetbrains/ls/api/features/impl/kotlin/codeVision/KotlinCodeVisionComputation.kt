// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeVision

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Pure-PSI cores of the "code vision" code lenses (reference count, implementation/override
 * count) and run-test lens detection. Kept free of LSP/server types so they can be unit-tested
 * directly against a PSI fixture.
 */
object KotlinCodeVisionComputation {

    /** Top-level and member declarations in [file] that a "N usages" lens should annotate. */
    fun lensableDeclarations(file: PsiFile): List<KtNamedDeclaration> =
        file.collectDescendantsOfType<KtNamedDeclaration> { it.nameIdentifier != null }

    /** Number of references to [declaration] across the project. */
    fun referenceCount(declaration: PsiElement): Int {
        val scope = GlobalSearchScope.allScope(declaration.project)
        return ReferencesSearch.search(declaration, scope).findAll().size
    }

    /**
     * Number of implementations/overrides of [declaration]: subclasses for a class/interface,
     * overriding methods for a function. `null` when the declaration is not overridable (so no
     * lens should be shown).
     */
    fun implementationCount(declaration: KtNamedDeclaration): Int? {
        val scope = GlobalSearchScope.allScope(declaration.project)
        return when (declaration) {
            is KtClassOrObject -> {
                val psiClass = declaration.toLightClass() ?: return null
                ClassInheritorsSearch.search(psiClass, scope, /* checkDeep = */ true).findAll().size
            }
            is KtNamedFunction -> {
                val methods = declaration.toLightMethods()
                if (methods.isEmpty()) return null
                methods.sumOf { OverridingMethodsSearch.search(it, scope, /* checkDeep = */ true).findAll().size }
            }
            else -> null
        }
    }

    /** Test functions in [file] (annotated `@Test`), as (name, function) pairs, for a run lens. */
    /**
     * Test functions paired with an id a build tool can run: `pkg.Class.method`.
     *
     * The bare method name is not enough. A run lens has to name the test precisely enough for
     * `--tests` to select it, and a lens that runs the wrong test -- or the whole suite -- is
     * worse than no lens.
     */
    fun testFunctions(file: PsiFile): List<Pair<String, KtNamedFunction>> =
        file.collectDescendantsOfType<KtNamedFunction> { fn -> fn.isTest() }
            .mapNotNull { fn -> testId(fn)?.let { it to fn } }

    private fun testId(function: KtNamedFunction): String? {
        val name = function.name ?: return null
        val owner = function.containingClassOrObject?.fqName?.asString()
        // A top-level test function lives in the file's facade class, e.g. `MyTestsKt`.
        val container = owner ?: facadeClassName(function.containingFile as? KtFile) ?: return name
        return if (container.isEmpty()) name else "$container.$name"
    }

    /** `pkg/Foo.kt` compiles to `pkg.FooKt`, which is what a build tool needs to select the test. */
    private fun facadeClassName(file: KtFile?): String? {
        if (file == null) return null
        val simple = file.name.removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt"
        val packageName = file.packageFqName.asString()
        return if (packageName.isEmpty()) simple else "$packageName.$simple"
    }

    private fun KtNamedFunction.isTest(): Boolean =
        annotationEntries.any { entry ->
            val short = entry.shortName?.asString()
            short == "Test" || short == "ParameterizedTest" || short == "RepeatedTest"
        }

    private inline fun <reified T : PsiElement> PsiElement.collectDescendantsOfType(
        crossinline predicate: (T) -> Boolean,
    ): List<T> {
        val result = ArrayList<T>()
        accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is T && predicate(element)) result.add(element)
                super.visitElement(element)
            }
        })
        return result
    }
}
