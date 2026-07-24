// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.folding

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Pure-PSI core for `//region` … `//endregion` custom folding, free of LSP types so it can be
 * unit-tested directly. Pairs region/endregion line comments with a stack so nesting works.
 */
object RegionFoldingComputation {
    data class Region(val start: PsiComment, val end: PsiComment, val label: String)

    private val REGION = Regex("""^//\s*region\b\s*(.*)$""")
    private val ENDREGION = Regex("""^//\s*endregion\b.*$""")

    fun regions(file: PsiFile): List<Region> {
        val comments = PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java)
            .filter { it.text.startsWith("//") }
            .sortedBy { it.textRange.startOffset }

        val open = ArrayDeque<Pair<PsiComment, String>>()
        val out = ArrayList<Region>()
        for (comment in comments) {
            val text = comment.text.trim()
            val region = REGION.find(text)
            when {
                region != null -> {
                    val label = region.groupValues[1].trim().ifEmpty { "…" }
                    open.addLast(comment to label)
                }
                ENDREGION.matches(text) -> {
                    val (startComment, label) = open.removeLastOrNull() ?: continue
                    out += Region(startComment, comment, label)
                }
            }
        }
        return out
    }
}
