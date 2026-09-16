package org.graphiks.kalligraphie.raster.logo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoFontsTest {
    @Test
    fun resolvesTheBadgeGlyphFromAmiri() {
        KalligraphieLogoFonts.open().use { fonts ->
            val badge = fonts.badgeGlyph()

            assertEquals(1_000, badge.unitsPerEm)
            assertTrue(badge.contours.isNotEmpty(), "the badge glyph must have ink")
            assertTrue(badge.bounds.maxY > badge.bounds.minY, "the badge glyph must have height")
        }
    }

    @Test
    fun shapesAndPlacesTheWordmarkWithIncreasingPenPositions() {
        KalligraphieLogoFonts.open().use { fonts ->
            val wordmark = fonts.wordmark("Kalligraphie")

            assertEquals(1_000, wordmark.unitsPerEm)
            assertTrue(wordmark.glyphs.size >= 10, "every wordmark letter must be shaped")
            assertTrue(wordmark.glyphs.all { glyph -> glyph.outline.contours.isNotEmpty() })

            val positions = wordmark.glyphs.map { glyph -> glyph.x }
            assertTrue(positions.zipWithNext().all { (left, right) -> right > left })

            // Each outline must carry its own pen position: a stacking regression would
            // leave every glyph at the origin and break this ordering.
            assertTrue(
                wordmark.glyphs.last().outline.bounds.minX > wordmark.glyphs.first().outline.bounds.maxX,
                "the last glyph must sit to the right of the first one",
            )
        }
    }
}
