package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.raster.fixtureBytes
import org.graphiks.kalligraphie.raster.sha256
import kotlin.test.Test
import kotlin.test.assertEquals

class GreatVibesFixtureTest {
    @Test
    fun bundlesThePinnedGreatVibesFixtureUnchanged() {
        val font = fixtureBytes("/fonts/great-vibes/GreatVibes-Regular.ttf")
        val licence = fixtureBytes("/fonts/great-vibes/OFL.txt")

        assertEquals(457_588, font.size)
        assertEquals("8d509802186f1b51572531ecf313e8098f9a5bfdfaca93f0c9b34467f9982d15", sha256(font))
        assertEquals(4_399, licence.size)
        assertEquals("61093a21f5e63dedf54222b3c09997e54c0fe43e3851d21386e02ddcbc246d49", sha256(licence))
    }
}
