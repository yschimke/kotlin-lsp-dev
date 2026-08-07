// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.conditionalactions

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.LSKotlinConditionalCodeActionProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers the `if`-shaped code actions. */
class ConditionalActionsServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinConditionalCodeActionProvider))
}
