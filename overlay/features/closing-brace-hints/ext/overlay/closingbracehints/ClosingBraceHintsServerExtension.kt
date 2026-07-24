// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.closingbracehints

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.inlayHints.LSKotlinClosingBraceInlayHintsProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers the closing-brace inlay-hints provider via the LanguageServerExtension ServiceLoader. */
class ClosingBraceHintsServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinClosingBraceInlayHintsProvider))
}
