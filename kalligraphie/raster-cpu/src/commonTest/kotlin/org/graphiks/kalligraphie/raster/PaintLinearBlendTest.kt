package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class PaintLinearBlendTest {
    @Test
    fun aSchemaTwoGroupBlendsInLinearLight() {
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 2,
            nodes = listOf(black(), translucentRed(), GlyphPaintNode.Group(listOf(0, 1))),
        )
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0xFFBB0000.toInt(), image[0, 0])
    }

    @Test
    fun aSchemaOneGroupKeepsTheHistoricalIntegerBlend() {
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 2,
            nodes = listOf(black(), translucentRed(), GlyphPaintNode.Group(listOf(0, 1))),
        )
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0xFF800000.toInt(), image[0, 0])
    }

    private fun black(): GlyphPaintNode.Path = square(GlyphColor(0, 0, 0))

    private fun translucentRed(): GlyphPaintNode.Path = square(GlyphColor(255, 0, 0, 128))

    private fun square(color: GlyphColor): GlyphPaintNode.Path =
        GlyphPaintNode.Path(
            GlyphPaintPath(
                listOf(
                    GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                    GlyphPaintPathCommand.LineTo(4.0, 0.0),
                    GlyphPaintPathCommand.LineTo(4.0, 4.0),
                    GlyphPaintPathCommand.LineTo(0.0, 4.0),
                    GlyphPaintPathCommand.Close,
                ),
            ),
            color,
        )
}
