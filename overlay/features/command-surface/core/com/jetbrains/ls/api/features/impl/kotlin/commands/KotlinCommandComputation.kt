// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.commands

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Path
import java.util.jar.JarFile

data class JvmStackFrame(val className: String, val methodName: String, val fileName: String, val line: Int)

data class JarTextMatch(val jar: String, val entry: String, val line: Int, val text: String)

data class JarClass(val name: String, val entry: String)

/** One level of a jar's package tree: the immediate subpackages and the classes directly in it. */
data class JarListing(val packages: List<String>, val classes: List<JarClass>, val truncated: Boolean)

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

    /**
     * Lists one level of [jar]'s package tree, so a client can expand a dependency lazily.
     *
     * Returning the whole jar at once would mean thousands of entries for something the size of
     * kotlin-stdlib, nearly all of them never looked at. One level per call keeps the cost
     * proportional to what is actually expanded.
     *
     * Only entry *names* are read; the bytecode is never parsed. That is what makes this cheap, and
     * it is why a jar of unreadable classes still lists correctly.
     */
    fun listJarClasses(jar: Path, packagePrefix: String = "", limit: Int = 500): JarListing {
        val directory = if (packagePrefix.isEmpty()) "" else packagePrefix.replace('.', '/') + "/"
        val packages = sortedSetOf<String>()
        val classes = mutableListOf<JarClass>()
        var truncated = false
        runCatching {
            JarFile(jar.toFile()).use { file ->
                val entries = file.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!name.endsWith(CLASS_SUFFIX) || !name.startsWith(directory)) continue
                    val rest = name.substring(directory.length)
                    val separator = rest.indexOf('/')
                    if (separator >= 0) {
                        packages += rest.substring(0, separator)
                        continue
                    }
                    val simpleName = rest.removeSuffix(CLASS_SUFFIX)
                    // An inner class navigates to its outer class's source, so listing it as well
                    // doubles the tree while adding no destination.
                    if ('$' in simpleName) continue
                    if (classes.size >= limit) {
                        truncated = true
                        continue
                    }
                    classes += JarClass(simpleName, name)
                }
            }
        }
        return JarListing(packages.toList(), classes.sortedBy { it.name }, truncated)
    }

    private const val CLASS_SUFFIX = ".class"

    private const val MAX_ENTRY_SIZE = 4L * 1024 * 1024
}
