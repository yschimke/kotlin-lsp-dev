// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.unusedimports

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.imports.LSKotlinUnusedImportDiagnosticProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers additive unused-import diagnostics through the extension ServiceLoader. */
class UnusedImportServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinUnusedImportDiagnosticProvider))
}
