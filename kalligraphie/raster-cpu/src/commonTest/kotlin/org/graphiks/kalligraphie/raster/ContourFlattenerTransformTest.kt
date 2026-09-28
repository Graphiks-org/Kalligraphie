package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class ContourFlattenerTransformTest {
    @Test
    fun aTranslationMovesFlattenedPoints() {
        val square = GlyphContour(
            listOf(
                GlyphOutlineCommand.MoveTo(0.0, 0.0),
                GlyphOutlineCommand.LineTo(2.0, 0.0),
                GlyphOutlineCommand.LineTo(2.0, 2.0),
                GlyphOutlineCommand.LineTo(0.0, 2.0),
                GlyphOutlineCommand.Close,
            ),
        )
        val contours = ContourFlattener.flattenOutline(
            contours = listOf(square),
            scale = 1.0,
            originX = 0.0,
            originY = 0.0,
            limits = RasterLimits.Default,
            transform = GlyphAffineTransform(xx = 1.0, yx = 0.0, xy = 0.0, yy = 1.0, dx = 10.0, dy = 0.0),
        )
        assertEquals(10.0, contours.single().points.first().x)
    }

    @Test
    fun theDefaultTransformLeavesPointsUnchanged() {
        val square = GlyphContour(
            listOf(
                GlyphOutlineCommand.MoveTo(0.0, 0.0),
                GlyphOutlineCommand.LineTo(2.0, 0.0),
                GlyphOutlineCommand.LineTo(2.0, 2.0),
                GlyphOutlineCommand.Close,
            ),
        )
        val plain = ContourFlattener.flattenOutline(listOf(square), 1.0, 0.0, 0.0, RasterLimits.Default)
        val explicit = ContourFlattener.flattenOutline(
            listOf(square), 1.0, 0.0, 0.0, RasterLimits.Default, GlyphAffineTransform.IDENTITY,
        )
        assertEquals(plain.single().points, explicit.single().points)
    }
}
