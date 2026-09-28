package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaintLimitsTest {
    private val clip: GlyphOutlineIR = GlyphOutlineIR(
        glyphId = 1,
        unitsPerEm = 1_000,
        bounds = DesignBounds(0, 0, 4, 4),
        commands = listOf(
            GlyphOutlineIR.Command.MoveTo(0.0, 0.0),
            GlyphOutlineIR.Command.LineTo(4.0, 0.0),
            GlyphOutlineIR.Command.LineTo(4.0, 4.0),
            GlyphOutlineIR.Command.LineTo(0.0, 4.0),
            GlyphOutlineIR.Command.Close,
        ),
    )

    @Test
    fun aClipAndTransformChainStillConsumesTheNodeBudget() {
        val solid = GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 1.0)
        val transform = GlyphPaintNode.Transform(paint = 0, matrix = GlyphAffineTransform.IDENTITY)
        val clipNode = GlyphPaintNode.GlyphClip(clip, paint = 1)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 2, nodes = listOf(solid, transform, clipNode))
        val limits = RasterLimits.Default.copy(maxPaintNodes = 2)
        val refusal = assertFailsWith<RasterLimitReached> {
            PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, limits)
        }
        assertEquals("maxPaintNodes", refusal.field)
    }

    @Test
    fun repeatedRunsAreByteIdentical() {
        val solid = GlyphPaintNode.Solid(GlyphColor(10, 20, 30), 0.5)
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 1,
            nodes = listOf(solid, GlyphPaintNode.GlyphClip(clip, paint = 0)),
        )
        val first = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        val second = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(first.copyPixels().toList(), second.copyPixels().toList())
    }
}
