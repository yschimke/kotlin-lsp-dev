// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.declarationgeneration

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.LSKotlinDeclarationGenerationCodeActionProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers declaration-generation code actions through the additive code-action dispatcher. */
class DeclarationGenerationServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinDeclarationGenerationCodeActionProvider))
}
