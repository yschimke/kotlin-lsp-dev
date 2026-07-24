// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.typehierarchy

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.typeHierarchy.LSKotlinTypeHierarchyProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers the type-hierarchy provider via the LanguageServerExtension ServiceLoader. */
class TypeHierarchyServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinTypeHierarchyProvider))
}
