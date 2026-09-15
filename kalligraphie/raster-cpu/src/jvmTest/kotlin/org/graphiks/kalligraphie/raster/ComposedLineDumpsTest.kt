package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.BaseDirection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposedLineDumpsTest {
    @Test
    fun composesALatinLine() {
        val first = ComposedLineDumps.line(text = "Kalligraphie", language = "en")
        assertEquals("P5\n", first.bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1))
        assertTrue(first.bytes.size > 13)
        val second = ComposedLineDumps.line(text = "Kalligraphie", language = "en")
        assertContentEquals(first.bytes, second.bytes)
    }

    @Test
    fun composesAMixedLineUsingEveryFace() {
        val dump = ComposedLineDumps.line(
            text = "Kalligraphie — العربية — देवनागरी",
            language = "en",
            requiredFaces = 3,
        )
        assertTrue(dump.bytes.size > 13)
    }

    @Test
    fun composesAnArabicLineRightToLeft() {
        val dump = ComposedLineDumps.line(
            text = "الخط العربي",
            language = "ar",
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
        )
        assertTrue(dump.bytes.size > 13)
    }
}
