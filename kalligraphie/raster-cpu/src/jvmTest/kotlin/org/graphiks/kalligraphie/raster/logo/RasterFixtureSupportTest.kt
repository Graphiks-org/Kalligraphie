package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.raster.fixtureBytes
import org.graphiks.kalligraphie.raster.openRasterFixture
import org.graphiks.kalligraphie.raster.outlineRequirements
import kotlin.test.Test
import kotlin.test.assertEquals

class RasterFixtureSupportTest {
    @Test
    fun opensAFixtureAtTheRequestedLayoutSize() {
        openRasterFixture(
            bytes = fixtureBytes("/fonts/great-vibes/GreatVibes-Regular.ttf"),
            requirements = outlineRequirements(),
            layoutSize = LayoutUnit(1_000f),
        ).use { fixture ->
            assertEquals(LayoutUnit(1_000f), fixture.instance.key.layoutSize)
        }
    }
}
