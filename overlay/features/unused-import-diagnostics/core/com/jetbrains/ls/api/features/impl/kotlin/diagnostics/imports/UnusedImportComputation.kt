// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics.imports

import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinOptimizeImportsFacility
import org.jetbrains.kotlin.psi.KtFile

/** Finds imports that Kotlin's own import optimizer has proved unnecessary. */
object UnusedImportComputation {
    data class UnusedImport(val range: TextRange, val importedName: String)

    fun find(file: KtFile): List<UnusedImport> {
        val analysis = KotlinOptimizeImportsFacility.getInstance().analyzeImports(file) ?: return emptyList()
        return analysis.unusedImports.map { directive ->
            UnusedImport(
                range = directive.textRange,
                importedName = directive.importPath?.pathStr ?: directive.text.removePrefix("import "),
            )
        }
    }
}
