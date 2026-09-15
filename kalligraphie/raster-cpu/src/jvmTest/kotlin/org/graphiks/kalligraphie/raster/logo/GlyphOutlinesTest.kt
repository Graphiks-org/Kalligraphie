package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import kotlin.test.Test
import kotlin.test.assertEquals

class GlyphOutlinesTest {
    private fun square(): GlyphOutlineIR = GlyphOutlineIR(
        glyphId = 7,
        unitsPerEm = 1_000,
        bounds = DesignBounds(minX = 0, minY = 0, maxX = 100, maxY = 200),
        commands = listOf(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.LineTo(100, 0),
            GlyphOutlineIR.Command.QuadraticTo(100, 200, 0, 200),
            GlyphOutlineIR.Command.Close,
        ),
    )

    @Test
    fun translatesEveryCommandAndTheBounds() {
        val translated = square().translated(dx = 5.5, dy = -3.25)

        assertEquals(7, translated.glyphId)
        assertEquals(1_000, translated.unitsPerEm)
        assertEquals(
            listOf(
                GlyphOutlineIR.Command.MoveTo(5.5, -3.25),
                GlyphOutlineIR.Command.LineTo(105.5, -3.25),
                GlyphOutlineIR.Command.QuadraticTo(105.5, 196.75, 5.5, 196.75),
                GlyphOutlineIR.Command.Close,
            ),
            translated.commands,
        )
        assertEquals(DesignBounds(minX = 5, minY = -4, maxX = 106, maxY = 197), translated.bounds)
    }

    @Test
    fun reportsInkBoundsOfPlacedGlyphs() {
        val glyphs = listOf(
            PlacedGlyph(glyphId = 1, x = 0.0, y = 0.0, outline = square()),
            PlacedGlyph(glyphId = 2, x = 0.0, y = 0.0, outline = square().translated(dx = 300.0, dy = 50.0)),
        )

        assertEquals(
            DesignBounds(minX = 0, minY = 0, maxX = 400, maxY = 250),
            inkBoundsOf(glyphs),
        )
    }
}
