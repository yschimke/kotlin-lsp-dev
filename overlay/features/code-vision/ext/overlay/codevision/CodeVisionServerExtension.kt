// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.codevision

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.codeVision.LSKotlinCodeVisionCodeLensProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers the code-vision code-lens provider via the LanguageServerExtension ServiceLoader. */
class CodeVisionServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinCodeVisionCodeLensProvider))
}
