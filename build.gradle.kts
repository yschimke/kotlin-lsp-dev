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
        // Java PSI (PsiClass, ClassInheritorsSearch, Kotlin light classes) — the real
        // features-impl/kotlin module depends on java-psi-api; type hierarchy needs it.
        bundledPlugin("com.intellij.java")
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
 *
 * The shim is always compiled. The move-file sources are added only when the checked-out ref
 * actually contains them, so the overlay stays green against any kotlin-lsp ref — including
 * upstream `main`, which does not yet carry the single-file move feature. CI relies on this:
 * the always-on `MoveFileHandlerSpikeTest` proves the harness boots against any ref, while the
 * feature test below runs only where the code under test exists.
 */
val moveProcessor = "com/jetbrains/ls/api/features/impl/common/processors/MoveFilesProcessor.kt"
val moveFeaturePresent = file("kotlin-lsp/features-impl/common/src/$moveProcessor").exists()

val upstreamSources = buildList {
    // RefactoringProcessor.kt is deliberately NOT here — see RefactoringProcessorShim.kt.
    add("com/jetbrains/ls/api/features/impl/common/processors/RefactoringProcessorShim.kt")
    if (moveFeaturePresent) {
        add(moveProcessor)
        add("com/jetbrains/ls/api/features/impl/common/processors/RefactoringContext.kt")
    }
}

logger.lifecycle("[kotlin-lsp-dev] move feature present in checkout: $moveFeaturePresent")

/**
 * Pure-PSI computation cores of the feature/lsp-additions providers. Each is written free of
 * closed LSP/server types so it compiles against the plain IntelliJ platform + bundled Kotlin
 * plugin and can be unit-tested here. The LSP adapters that wrap them are verified separately by
 * scripts/compile-check.sh (against the release jars) and run via scripts/patch-server.sh.
 *
 * Only included when present, so the overlay stays green against refs that lack the branch.
 */
val featureComputations = listOf(
    "com/jetbrains/ls/api/features/impl/kotlin/typeHierarchy/KotlinTypeHierarchyComputation.kt",
    "com/jetbrains/ls/api/features/impl/kotlin/codeVision/KotlinCodeVisionComputation.kt",
).filter { file("kotlin-lsp/features-impl/kotlin/src/$it").exists() }

logger.lifecycle("[kotlin-lsp-dev] feature computations present: ${featureComputations.size}")

sourceSets {
    main {
        kotlin {
            setSrcDirs(
                listOf(
                    file("shims"),
                    file("kotlin-lsp/features-impl/common/src"),
                    file("kotlin-lsp/features-impl/kotlin/src"),
                )
            )
            // One shared filter across all roots. Shim, upstream, and feature file names are each
            // unique to their root, so there is no clash.
            setIncludes(upstreamSources + featureComputations)
        }
    }
    // Feature tests import code that only exists on feature/lsp-additions. Drop each when its
    // computation is absent from the checked-out ref, so the overlay stays green against any ref
    // (e.g. upstream main). The spike test always runs.
    if (!moveFeaturePresent) {
        test { kotlin.exclude("**/MoveKotlinFileTest.kt") }
    }
    fun computationPresent(path: String) = featureComputations.any { it.endsWith(path) }
    if (!computationPresent("typeHierarchy/KotlinTypeHierarchyComputation.kt")) {
        test { kotlin.exclude("**/TypeHierarchyTest.kt") }
    }
    if (!computationPresent("codeVision/KotlinCodeVisionComputation.kt")) {
        test { kotlin.exclude("**/CodeVisionTest.kt") }
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
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
