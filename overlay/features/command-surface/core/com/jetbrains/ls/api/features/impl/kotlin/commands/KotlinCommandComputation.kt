// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.commands

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Path
import java.util.jar.JarFile

data class JvmStackFrame(val className: String, val methodName: String, val fileName: String, val line: Int)

data class JarTextMatch(val jar: String, val entry: String, val line: Int, val text: String)

object KotlinCommandComputation {
    private val stackFrame = Regex("""^\s*at\s+([\w.$]+)\.([\w$<>]+)\(([^:()]+):(\d+)\)\s*$""")

    fun parseStackTrace(text: String): List<JvmStackFrame> = text.lineSequence().mapNotNull { line ->
        val match = stackFrame.matchEntire(line) ?: return@mapNotNull null
        JvmStackFrame(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4].toInt())
    }.toList()

    fun fullyQualifiedName(element: PsiElement?): String? = generateSequence(element) { it.parent }
        .filterIsInstance<KtNamedDeclaration>()
        .firstNotNullOfOrNull { it.fqName?.asString() }

    fun findTextInJars(jars: Iterable<Path>, query: String, limit: Int = 100): List<JarTextMatch> {
        require(query.isNotEmpty()) { "query must not be empty" }
        if (limit <= 0) return emptyList()
        val matches = mutableListOf<JarTextMatch>()
        for (path in jars.distinct()) {
            if (matches.size >= limit) break
            runCatching {
                JarFile(path.toFile()).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements() && matches.size < limit) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || entry.size !in 0..MAX_ENTRY_SIZE) continue
                        val text = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                        text.lineSequence().forEachIndexed { index, line ->
                            if (query in line && matches.size < limit) {
                                matches += JarTextMatch(path.toString(), entry.name, index + 1, line.trim())
                            }
                        }
                    }
                }
            }
        }
        return matches
    }

    private const val MAX_ENTRY_SIZE = 4L * 1024 * 1024
}
