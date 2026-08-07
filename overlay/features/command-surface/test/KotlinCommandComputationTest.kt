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

    fun testListsOneLevelOfAJarPackageTree() {
        val jar = jarOf(
            "sample/api/Client.class",
            "sample/api/Client\$Builder.class",
            "sample/api/Codec.class",
            "sample/api/internal/Buffer.class",
            "sample/other/Ignored.class",
            "META-INF/MANIFEST.MF",
        )

        val root = KotlinCommandComputation.listJarClasses(jar)
        assertEquals(listOf("sample"), root.packages)
        assertEquals(emptyList<String>(), root.classes.map { it.name })

        val api = KotlinCommandComputation.listJarClasses(jar, "sample.api")
        // Subpackages are listed, but only classes *directly* in the package -- otherwise expanding
        // one node would show the whole subtree flattened into it.
        assertEquals(listOf("internal"), api.packages)
        assertEquals(listOf("Client", "Codec"), api.classes.map { it.name })
        assertEquals("sample/api/Client.class", api.classes.first().entry)
        assertFalse(api.truncated)
    }

    fun testReportsTruncationRatherThanSilentlyShortening() {
        val jar = jarOf(*Array(5) { "sample/Class$it.class" })
        val listing = KotlinCommandComputation.listJarClasses(jar, "sample", limit = 2)
        assertEquals(2, listing.classes.size)
        assertTrue(listing.truncated)
    }

    fun testListsAJarWhoseClassesCannotBeParsed() {
        // Entry names are all that is read, so a jar of unreadable bytecode still lists. This is
        // what keeps expansion cheap, and it is worth pinning: parsing here would be a silent
        // performance cliff on large dependencies.
        val jar = jarOf("sample/Broken.class")
        assertEquals(listOf("Broken"), KotlinCommandComputation.listJarClasses(jar, "sample").classes.map { it.name })
    }

    private fun jarOf(vararg entries: String): java.nio.file.Path {
        val jar = Files.createTempFile("command-list", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            entries.forEach { entry ->
                output.putNextEntry(JarEntry(entry))
                output.write(byteArrayOf(0))
            }
        }
        return jar
    }
}
