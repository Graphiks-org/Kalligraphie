package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.e2e.PixelFormat

class ComposedLineScenesTest {
    @Test
    fun composesALatinLine() {
        val first = ComposedLineScenes.line(text = "Kalligraphie", language = "en")
        assertEquals(PixelFormat.ALPHA_8, first.format)
        assertTrue(first.width > 0 && first.height > 0, "a composed line must contain ink")
        val second = ComposedLineScenes.line(text = "Kalligraphie", language = "en")
        assertContentEquals(first.copyCanonicalBytes(), second.copyCanonicalBytes())
    }

    @Test
    fun composesAMixedLineUsingEveryFace() {
        val latin = ComposedLineScenes.line(text = "Kalligraphie", language = "en")
        val mixed = ComposedLineScenes.line(
            text = "Kalligraphie — العربية — देवनागरी",
            language = "en",
            requiredFaces = 3,
        )
        assertTrue(mixed.width > 0 && mixed.height > 0)
        assertTrue(mixed.width > latin.width, "a mixed multi-face line is wider than the latin one")
    }

    @Test
    fun composesAnArabicLineRightToLeft() {
        val first = ComposedLineScenes.line(
            text = "الخط العربي",
            language = "ar",
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
        )
        val second = ComposedLineScenes.line(
            text = "الخط العربي",
            language = "ar",
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
        )
        assertTrue(first.width > 0 && first.height > 0)
        assertContentEquals(first.copyCanonicalBytes(), second.copyCanonicalBytes())
    }
}
