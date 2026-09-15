package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContourFlattenerTest {
    @Test
    fun flattensASquareOutlineToFourPixelPoints() {
        val outline = syntheticOutline(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.LineTo(100, 0),
            GlyphOutlineIR.Command.LineTo(100, 100),
            GlyphOutlineIR.Command.LineTo(0, 100),
            GlyphOutlineIR.Command.Close,
        )
        val contours = ContourFlattener.flattenOutline(
            contours = outline.contours,
            scale = 1.0,
            originX = 0.0,
            originY = 0.0,
            limits = RasterLimits.Default,
        )
        assertEquals(1, contours.size)
        assertEquals(4, contours.single().points.size)
        assertEquals(0.0, contours.single().points[0].x)
        assertEquals(100.0, contours.single().points[2].x)
    }

    @Test
    fun flattensAQuadraticWithExactEndpointsAndStableResult() {
        val outline = syntheticOutline(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.QuadraticTo(50, 100, 100, 0),
            GlyphOutlineIR.Command.Close,
        )
        val first = ContourFlattener.flattenOutline(outline.contours, 1.0, 0.0, 0.0, RasterLimits.Default)
        val second = ContourFlattener.flattenOutline(outline.contours, 1.0, 0.0, 0.0, RasterLimits.Default)
        val points = first.single().points
        assertTrue(points.size > 4, "a curved segment must subdivide, got ${points.size} points")
        assertEquals(0.0, points.first().x)
        assertEquals(0.0, points.first().y)
        assertEquals(100.0, points.last().x)
        assertEquals(0.0, points.last().y)
        (1 until points.size).forEach { index ->
            assertTrue(points[index].x > points[index - 1].x, "x must increase monotonically at $index")
        }
        val maximumY = points.maxOf { it.y }
        assertTrue(maximumY in 45.0..55.0, "curve apex must stay near 50, got $maximumY")
        assertEquals(first.single().points, second.single().points)
    }

    @Test
    fun flattensACubicWithinTolerance() {
        val commands = listOf(
            GlyphPaintPathCommand.MoveTo(0.0, 0.0),
            GlyphPaintPathCommand.CubicTo(0.0, 100.0, 100.0, 100.0, 100.0, 0.0),
            GlyphPaintPathCommand.Close,
        )
        val points = ContourFlattener.flattenPath(commands, 1.0, 0.0, 0.0, RasterLimits.Default)
            .single().points
        assertTrue(points.size > 4, "a cubic segment must subdivide, got ${points.size} points")
        assertEquals(0.0, points.first().x)
        assertEquals(100.0, points.last().x)
        assertTrue(points.maxOf { it.y } in 70.0..80.0, "cubic apex must stay near 75")
    }

    @Test
    fun pointBudgetRefusesOversizedGeometry() {
        val commands = listOf(
            GlyphPaintPathCommand.MoveTo(0.0, 0.0),
            GlyphPaintPathCommand.CubicTo(0.0, 100.0, 100.0, 100.0, 100.0, 0.0),
            GlyphPaintPathCommand.Close,
        )
        val limits = RasterLimits.Default.copy(maxTotalPoints = 3)
        assertFailsWith<RasterLimitReached> {
            ContourFlattener.flattenPath(commands, 1.0, 0.0, 0.0, limits)
        }
    }

    private fun syntheticOutline(vararg commands: GlyphOutlineIR.Command): GlyphOutlineIR =
        GlyphOutlineIR(
            glyphId = 1,
            unitsPerEm = 1_000,
            bounds = DesignBounds(0, 0, 100, 100),
            commands = commands.toList(),
        )
}
