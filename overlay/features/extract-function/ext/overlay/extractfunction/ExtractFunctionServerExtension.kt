// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.extractfunction

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.LSKotlinExtractFunctionCodeActionProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers the extract-function code action. */
class ExtractFunctionServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinExtractFunctionCodeActionProvider))
}
