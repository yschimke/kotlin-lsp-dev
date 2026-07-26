package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.commands.KotlinCommandComputation
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class KotlinCommandComputationTest : BasePlatformTestCase() {
    fun testParsesJvmFramesAndIgnoresOtherLines() {
        val frames = KotlinCommandComputation.parseStackTrace("""
            java.lang.IllegalStateException: boom
                at sample.Service.run(Service.kt:42)
                at sample.MainKt.main(Main.kt:7)
                at native.call(Native Method)
        """.trimIndent())
        assertEquals(listOf("sample.Service", "sample.MainKt"), frames.map { it.className })
        assertEquals(listOf(42, 7), frames.map { it.line })
    }

    fun testFindsFullyQualifiedKotlinDeclaration() {
        val text = "package sample\nclass Outer { fun answer() = 42 }"
        val file = myFixture.configureByText("Names.kt", text)
        assertEquals("sample.Outer.answer", KotlinCommandComputation.fullyQualifiedName(file.findElementAt(text.indexOf("answer") + 1)))
    }

    fun testSearchesTextEntriesInJar() {
        val jar = Files.createTempFile("command-search", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("config/defaults.conf"))
            output.write("first=true\nfeature.magic=enabled\n".toByteArray())
        }
        val matches = KotlinCommandComputation.findTextInJars(listOf(jar), "feature.magic")
        assertEquals(1, matches.size)
        assertEquals("config/defaults.conf", matches.single().entry)
        assertEquals(2, matches.single().line)
    }
}
