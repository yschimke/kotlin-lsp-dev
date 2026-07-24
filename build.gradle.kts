import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// Unit-test build for the overlay enhancement *cores*.
//
// This compiles overlay/core (the pure-PSI computation cores of our added features) against a
// plain IntelliJ platform + bundled Kotlin/Java plugins, and runs the tests in src/test. It is
// deliberately separate from the server build (scripts/build-server.sh), which compiles the same
// cores PLUS the LSP adapters in overlay/ext against the pinned release jars. Cores are kept free
// of closed LSP/server types precisely so they can be exercised here.
plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform.module") version "2.18.1"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("overlay.platformVersion"))
        bundledPlugin("org.jetbrains.kotlin")
        // Java PSI (PsiClass, ClassInheritorsSearch, Kotlin light classes).
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

// Auto-discover each feature's computation core under overlay/features/<name>/core. Removing a
// feature directory (e.g. once it lands upstream) drops it from the build with no other edit.
val featureCoreDirs: List<File> =
    file("overlay/features").listFiles()
        ?.mapNotNull { it.resolve("core").takeIf(File::isDirectory) }
        ?.sorted()
        ?: emptyList()

logger.lifecycle("[kotlin-lsp-dev] feature cores: ${featureCoreDirs.map { it.parentFile.name }}")

sourceSets {
    main {
        kotlin {
            setSrcDirs(featureCoreDirs)
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.addAll(
            "org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
            "org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt",
        )
    }
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
