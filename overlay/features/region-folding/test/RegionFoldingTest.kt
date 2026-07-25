package overlay

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.ls.api.features.impl.kotlin.folding.RegionFoldingComputation

/** Drives [RegionFoldingComputation] over PSI fixtures. */
class RegionFoldingTest : BasePlatformTestCase() {

    private fun regionsOf(text: String) =
        RegionFoldingComputation.regions(myFixture.configureByText("R.kt", text))

    fun testSingleLabelledRegion() {
        val regions = regionsOf(
            """
            package p
            //region Helpers
            fun a() {}
            fun b() {}
            //endregion
            """.trimIndent(),
        )
        assertEquals(1, regions.size)
        assertEquals("Helpers", regions.single().label)
    }

    fun testNestedRegions() {
        val regions = regionsOf(
            """
            package p
            //region Outer
            //region Inner
            fun a() {}
            //endregion
            //endregion
            """.trimIndent(),
        )
        assertEquals(setOf("Outer", "Inner"), regions.map { it.label }.toSet())
    }

    fun testUnlabelledRegionAndUnbalancedEndregionIgnored() {
        val regions = regionsOf(
            """
            package p
            //region
            fun a() {}
            //endregion
            //endregion
            """.trimIndent(),
        )
        assertEquals(1, regions.size)
        assertEquals("…", regions.single().label)
    }
}
