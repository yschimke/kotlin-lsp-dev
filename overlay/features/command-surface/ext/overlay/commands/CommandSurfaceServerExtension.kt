// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.commands

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.commands.LSKotlinCommandDescriptorProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

class CommandSurfaceServerExtension : LanguageServerExtension {
    override val configuration get() = LSConfigurationPiece(entries = listOf(LSKotlinCommandDescriptorProvider))
}
