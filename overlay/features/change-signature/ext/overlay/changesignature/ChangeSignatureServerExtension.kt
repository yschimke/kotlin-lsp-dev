// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.changesignature

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.LSKotlinIntroduceParameterCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.LSKotlinRemoveParameterCodeActionProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers the change-signature code actions. */
class ChangeSignatureServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(
            LSKotlinRemoveParameterCodeActionProvider,
            LSKotlinIntroduceParameterCodeActionProvider,
        ))
}
