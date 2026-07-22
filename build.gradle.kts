import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

/**
 * Files built from the kotlin-lsp checkout, by path relative to a source root.
 *
 * Upstream can't be built as a whole (closed-source deps, and ~1 month of drift against the
 * pinned release), so the overlay compiles a curated slice instead — the androidchka
 * `androidx.sources` idea, at file rather than project granularity.
 */
val upstreamSources = listOf(
    "com/jetbrains/ls/api/features/impl/common/processors/MoveFilesProcessor.kt",
    "com/jetbrains/ls/api/features/impl/common/processors/RefactoringContext.kt",
    // RefactoringProcessor.kt is deliberately NOT here — see RefactoringProcessorShim.kt.
    "com/jetbrains/ls/api/features/impl/common/processors/RefactoringProcessorShim.kt",
)

sourceSets {
    main {
        kotlin {
            setSrcDirs(
                listOf(
                    file("shims"),
                    file("kotlin-lsp/features-impl/common/src"),
                )
            )
            // One shared filter across both roots. The shim file name is unique to `shims/`
            // and the upstream file names are unique to the checkout, so there is no clash.
            setIncludes(upstreamSources)
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-parameters", "-jvm-default=enable")
        optIn.addAll(
            "org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
            "org.jetbrains.kotlin.analysis.api.KaIdeApi",
            "org.jetbrains.kotlin.analysis.api.KaContextParameterApi",
            // The move refactoring resolves code from inside a write action on EDT, exactly as
            // LSKotlinMoveFileProvider does in production.
            "org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt",
            "org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction",
            "org.jetbrains.kotlin.analysis.api.permissions.KaAllowProhibitedAnalyzeFromWriteAction",
        )
    }
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
