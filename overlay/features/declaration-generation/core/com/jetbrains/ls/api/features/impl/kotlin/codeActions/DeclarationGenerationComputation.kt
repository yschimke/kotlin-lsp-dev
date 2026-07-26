// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject

/** Pure-PSI computation for generating implementations and overrides in Kotlin classes. */
object DeclarationGenerationComputation {
    data class Generation(val insertRange: TextRange, val text: String, val memberNames: List<String>)

    enum class Kind { IMPLEMENT, OVERRIDE }

    fun generationAt(file: PsiFile, offset: Int, kind: Kind): Generation? {
        val declaration = file.findElementAt(offset)
            ?.parentOfType<KtClassOrObject>(withSelf = true) ?: return null
        val lightClass = declaration.toLightClass() ?: return null
        if (lightClass.isInterface || lightClass.isAnnotationType) return null

        val declared = lightClass.methods.mapTo(mutableSetOf(), ::signature)
        val inherited = linkedMapOf<String, PsiMethod>()
        collectEffectiveMethods(lightClass, inherited, mutableSetOf())
        val candidates = inherited.values.filter { method ->
            signature(method) !in declared &&
                !method.isConstructor &&
                !method.hasModifierProperty(PsiModifier.STATIC) &&
                !method.hasModifierProperty(PsiModifier.PRIVATE) &&
                method.containingClass?.qualifiedName != "java.lang.Object" &&
                when (kind) {
                    Kind.IMPLEMENT -> method.hasModifierProperty(PsiModifier.ABSTRACT)
                    Kind.OVERRIDE -> !method.hasModifierProperty(PsiModifier.ABSTRACT) &&
                        !method.hasModifierProperty(PsiModifier.FINAL)
                }
        }.distinctBy(::signature).sortedBy { it.name }
        if (candidates.isEmpty()) return null

        val body = declaration.body
        val classIndent = lineIndent(file.text, declaration.textOffset)
        val memberIndent = classIndent + "    "
        val rendered = candidates.joinToString("\n\n") { render(it, memberIndent) }
        val (range, text) = if (body?.rBrace != null) {
            val at = body.rBrace!!.textRange.startOffset
            val prefix = if (body.declarations.isEmpty()) "\n" else "\n\n"
            TextRange(at, at) to "$prefix$rendered\n$classIndent"
        } else {
            val at = declaration.textRange.endOffset
            TextRange(at, at) to " {\n$rendered\n$classIndent}"
        }
        return Generation(range, text, candidates.map { it.name })
    }

    /** Walk nearest supertypes first. A concrete implementation wins over an abstract declaration. */
    private fun collectEffectiveMethods(
        psiClass: PsiClass,
        result: LinkedHashMap<String, PsiMethod>,
        visited: MutableSet<PsiClass>,
    ) {
        for (superClass in psiClass.supers) {
            if (!visited.add(superClass)) continue
            for (method in superClass.methods) {
                val key = signature(method)
                val previous = result[key]
                if (previous == null || previous.hasModifierProperty(PsiModifier.ABSTRACT) &&
                    !method.hasModifierProperty(PsiModifier.ABSTRACT)
                ) result[key] = method
            }
            collectEffectiveMethods(superClass, result, visited)
        }
    }

    private fun signature(method: PsiMethod): String = buildString {
        append(method.name).append('(')
        method.parameterList.parameters.joinTo(this, ",") { it.type.erasureText() }
        append(')')
    }

    private fun PsiType.erasureText(): String = canonicalText.substringBefore('<')

    private fun render(method: PsiMethod, indent: String): String {
        val parameters = method.parameterList.parameters.mapIndexed { index, parameter ->
            val name = parameter.name.takeUnless { it.isNullOrBlank() } ?: "p$index"
            "${escape(name)}: ${kotlinType(parameter.type)}"
        }.joinToString(", ")
        val returnType = method.returnType?.let(::kotlinType) ?: "Unit"
        return "$indent" + "override fun ${escape(method.name)}($parameters): $returnType {\n" +
            "$indent    TODO(\"Not yet implemented\")\n$indent}"
    }

    private fun kotlinType(type: PsiType): String {
        var text = type.canonicalText
            .replace("java.lang.String", "String")
            .replace("java.lang.Object", "Any")
            .replace("java.lang.Boolean", "Boolean")
            .replace("java.lang.Integer", "Int")
            .replace("java.lang.Long", "Long")
            .replace("java.lang.Double", "Double")
            .replace("java.lang.Float", "Float")
            .replace("java.lang.Character", "Char")
        val primitives = mapOf(
            "void" to "Unit", "boolean" to "Boolean", "byte" to "Byte", "short" to "Short",
            "int" to "Int", "long" to "Long", "float" to "Float", "double" to "Double", "char" to "Char",
        )
        text = primitives[text] ?: text
        return if (text.endsWith("[]")) "Array<${kotlinTypeText(text.dropLast(2))}>" else kotlinTypeText(text)
    }

    private fun kotlinTypeText(text: String): String = text
        .replace("? extends ", "out ")
        .replace("? super ", "in ")
        .replace("?", "*")

    private fun escape(name: String): String =
        if (name in KOTLIN_KEYWORDS) "`$name`" else name

    private fun lineIndent(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        return text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    }

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while",
    )
}
