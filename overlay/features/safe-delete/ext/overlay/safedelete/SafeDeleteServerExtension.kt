// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.safedelete

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.LSKotlinSafeDeleteCodeActionProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers the safe-delete code action. */
class SafeDeleteServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinSafeDeleteCodeActionProvider))
}
