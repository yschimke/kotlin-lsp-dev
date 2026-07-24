// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package overlay.regionfolding

import com.jetbrains.ls.api.features.LanguageServerExtension
import com.jetbrains.ls.api.features.impl.kotlin.folding.LSKotlinRegionFoldingProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece

/** Registers the region-folding provider via the LanguageServerExtension ServiceLoader. */
class RegionFoldingServerExtension : LanguageServerExtension {
    override val configuration: LSConfigurationPiece
        get() = LSConfigurationPiece(entries = listOf(LSKotlinRegionFoldingProvider))
}
