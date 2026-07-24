// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.typeHierarchy.LSKotlinTypeHierarchyProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/**
 * Registers the overlay's added providers with the server via the standard
 * `META-INF/services/com.jetbrains.ls.api.features.LanguageServerExtension` ServiceLoader.
 * The server merges this configuration piece alongside the built-in ones, so our providers are
 * routed by the existing (closed) request dispatch without touching upstream code.
 */
class OverlayLanguageServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(
            entries = listOf(
                LSKotlinTypeHierarchyProvider,
            ),
        )
}
