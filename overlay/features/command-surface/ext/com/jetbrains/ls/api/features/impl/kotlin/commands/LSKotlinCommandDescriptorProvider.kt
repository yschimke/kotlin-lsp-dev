// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.commands

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.Path

object LSKotlinCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors get() = listOf(doctor, analyzeStackTrace, findTextInDependencyJars, copyFullyQualifiedName)

    private val doctor = descriptor("Kotlin workspace health report", "kotlin-lsp.doctor") { arguments ->
        expect(arguments, 0)
        contextOf<LSServer>().withAnalysisContext {
            readAction {
                val roots = ProjectRootManager.getInstance(project)
                buildJsonObject {
                    put("project", project.name)
                    put("jdk", roots.projectSdk?.let { buildJsonObject { put("name", it.name); put("home", it.homePath) } } ?: JsonNull)
                    putJsonArray("modules") {
                        ModuleManager.getInstance(project).modules.forEach { module ->
                            addJsonObject {
                                put("name", module.name)
                                putJsonArray("sourceRoots") { rootsFor(module, true).forEach { add(it) } }
                                putJsonArray("classpath") { rootsFor(module, false).forEach { add(it) } }
                            }
                        }
                    }
                    put("healthy", ModuleManager.getInstance(project).modules.isNotEmpty() && roots.projectSdk != null)
                }
            }
        }
    }

    private val analyzeStackTrace = descriptor("Analyze JVM stack trace", "kotlin-lsp.analyzeStackTrace") { arguments ->
        val text = stringArgument(arguments, 0, 1)
        contextOf<LSServer>().withAnalysisContext {
            readAction {
                buildJsonArray {
                    KotlinCommandComputation.parseStackTrace(text).forEach { frame ->
                        val file = FilenameIndex.getVirtualFilesByName(frame.fileName, GlobalSearchScope.allScope(project)).firstOrNull()
                            ?: return@forEach
                        addJsonObject {
                            put("uri", Path(file.path).toUri().toString())
                            putJsonObject("range") {
                                putJsonObject("start") { put("line", frame.line - 1); put("character", 0) }
                                putJsonObject("end") { put("line", frame.line - 1); put("character", 0) }
                            }
                        }
                    }
                }
            }
        }
    }

    private val findTextInDependencyJars = descriptor("Find text in dependency jars", "kotlin-lsp.findTextInDependencyJars") { arguments ->
        val query = stringArgument(arguments, 0, 1)
        contextOf<LSServer>().withAnalysisContext {
            val jars = readAction { dependencyJars() }
            buildJsonArray {
                KotlinCommandComputation.findTextInJars(jars, query).forEach { match ->
                    addJsonObject {
                        put("jar", match.jar)
                        put("entry", match.entry)
                        put("line", match.line)
                        put("text", match.text)
                    }
                }
            }
        }
    }

    private val copyFullyQualifiedName = descriptor("Fully-qualified Kotlin name", "kotlin-lsp.copyFullyQualifiedName") { arguments ->
        expect(arguments, 2)
        val uri = LSP.json.decodeFromJsonElement<DocumentUri>(arguments[0])
        val offset = (arguments[1] as? JsonPrimitive)?.intOrNull ?: invalid("Argument 2 must be a character offset")
        contextOf<LSServer>().withAnalysisContext {
            readAction {
                val file = uri.findVirtualFile()?.findPsiFile(project) ?: invalid("File not found: ${uri.uri}")
                KotlinCommandComputation.fullyQualifiedName(file.findElementAt(offset))?.let(::JsonPrimitive) ?: JsonNull
            }
        }
    }

    private fun descriptor(title: String, name: String, execute: suspend context(LSServer, LspHandlerContext) (List<JsonElement>) -> JsonElement) =
        LSCommandDescriptor(title, name, execute)

    private fun rootsFor(module: com.intellij.openapi.module.Module, sources: Boolean): List<String> {
        val roots = ModuleRootManager.getInstance(module)
        return if (sources) roots.sourceRoots.map { it.url } else roots.orderEntries().classes().roots.map { it.url }
    }

    context(_: LSAnalysisContext)
    private fun dependencyJars(): List<Path> = ProjectRootManager.getInstance(project).orderEntries().classes().roots
        .mapNotNull { root -> root.url.removePrefix("jar://").removeSuffix("!/").takeIf { root.fileSystem.protocol == "jar" }?.let(::Path) }

    private fun expect(arguments: List<JsonElement>, count: Int) {
        if (arguments.size != count) invalid("Expected $count arguments, got ${arguments.size}")
    }

    private fun stringArgument(arguments: List<JsonElement>, index: Int, count: Int): String {
        expect(arguments, count)
        return (arguments[index] as? JsonPrimitive)?.contentOrNull ?: invalid("Argument ${index + 1} must be a string")
    }

    private fun invalid(message: String): Nothing = throwLspError(ExecuteCommand, message, Unit, ErrorCodes.InvalidParams, null)
}
