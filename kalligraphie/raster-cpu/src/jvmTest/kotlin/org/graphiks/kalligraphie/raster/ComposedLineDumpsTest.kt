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
        val latin = ComposedLineDumps.line(text = "Kalligraphie", language = "en")
        val mixed = ComposedLineDumps.line(
            text = "Kalligraphie — العربية — देवनागरी",
            language = "en",
            requiredFaces = 3,
        )
        assertTrue(mixed.bytes.size > 13)
        assertTrue(pgmWidth(mixed.bytes) > pgmWidth(latin.bytes))
    }

    @Test
    fun composesAnArabicLineRightToLeft() {
        val first = ComposedLineDumps.line(
            text = "الخط العربي",
            language = "ar",
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
        )
        val second = ComposedLineDumps.line(
            text = "الخط العربي",
            language = "ar",
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
        )
        assertTrue(first.bytes.size > 13)
        assertContentEquals(first.bytes, second.bytes)
    }

    private fun pgmWidth(bytes: ByteArray): Int =
        bytes.copyOfRange(3, 64).toString(Charsets.ISO_8859_1).trim().split(" ")[0].toInt()
}
