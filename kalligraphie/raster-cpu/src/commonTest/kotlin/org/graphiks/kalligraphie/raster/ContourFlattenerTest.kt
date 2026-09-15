package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintPath
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

    @Test
    fun allowsGeometryExactlyAtThePointBudget() {
        val outline = syntheticOutline(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.LineTo(100, 0),
            GlyphOutlineIR.Command.LineTo(100, 100),
            GlyphOutlineIR.Command.LineTo(0, 100),
            GlyphOutlineIR.Command.Close,
        )
        val limits = RasterLimits.Default.copy(maxTotalPoints = 4)
        val contours = ContourFlattener.flattenOutline(outline.contours, 1.0, 0.0, 0.0, limits)
        assertEquals(4, contours.single().points.size)
    }

    @Test
    fun refusesGeometryOnePointOverTheBudget() {
        val outline = syntheticOutline(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.LineTo(100, 0),
            GlyphOutlineIR.Command.LineTo(100, 100),
            GlyphOutlineIR.Command.LineTo(0, 100),
            GlyphOutlineIR.Command.Close,
        )
        val limits = RasterLimits.Default.copy(maxTotalPoints = 3)
        val refusal = assertFailsWith<RasterLimitReached> {
            ContourFlattener.flattenOutline(outline.contours, 1.0, 0.0, 0.0, limits)
        }
        assertEquals("maxTotalPoints", refusal.field)
        assertEquals(4L, refusal.observed)
        assertEquals(3L, refusal.limit)
    }

    @Test
    fun refusesTooManyOutlineContours() {
        val outline = syntheticOutline(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.LineTo(10, 0),
            GlyphOutlineIR.Command.LineTo(5, 10),
            GlyphOutlineIR.Command.Close,
            GlyphOutlineIR.Command.MoveTo(20, 0),
            GlyphOutlineIR.Command.LineTo(30, 0),
            GlyphOutlineIR.Command.LineTo(25, 10),
            GlyphOutlineIR.Command.Close,
        )
        val limits = RasterLimits.Default.copy(maxContours = 1)
        val refusal = assertFailsWith<RasterLimitReached> {
            ContourFlattener.flattenOutline(outline.contours, 1.0, 0.0, 0.0, limits)
        }
        assertEquals("maxContours", refusal.field)
        assertEquals(2L, refusal.observed)
        assertEquals(1L, refusal.limit)
    }

    @Test
    fun refusesTooManyPathContours() {
        val path = twoTrianglePath()
        val limits = RasterLimits.Default.copy(maxContours = 1)
        val refusal = assertFailsWith<RasterLimitReached> {
            ContourFlattener.flattenPath(path.commands, 1.0, 0.0, 0.0, limits)
        }
        assertEquals("maxContours", refusal.field)
        assertEquals(2L, refusal.observed)
        assertEquals(1L, refusal.limit)
    }

    @Test
    fun appliesScaleAndOrigin() {
        val outline = syntheticOutline(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.LineTo(10, 0),
            GlyphOutlineIR.Command.LineTo(10, 10),
            GlyphOutlineIR.Command.LineTo(0, 10),
            GlyphOutlineIR.Command.Close,
        )
        val points = ContourFlattener.flattenOutline(outline.contours, 2.0, 3.0, -2.0, RasterLimits.Default)
            .single().points
        assertEquals(FlatPoint(3.0, -2.0), points[0])
        assertEquals(FlatPoint(23.0, -2.0), points[1])
        assertEquals(FlatPoint(23.0, 18.0), points[2])
        assertEquals(FlatPoint(3.0, 18.0), points[3])
    }

    @Test
    fun flattensMultiplePathContours() {
        val contours = ContourFlattener.flattenPath(
            twoTrianglePath().commands,
            1.0,
            0.0,
            0.0,
            RasterLimits.Default,
        )
        assertEquals(2, contours.size)
    }

    @Test
    fun dropsDegenerateCurvesInsteadOfDuplicatingPoints() {
        val outline = syntheticOutline(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.QuadraticTo(0, 0, 0, 0),
            GlyphOutlineIR.Command.Close,
        )
        assertEquals(
            emptyList<FlatContour>(),
            ContourFlattener.flattenOutline(outline.contours, 1.0, 0.0, 0.0, RasterLimits.Default),
        )
        ContourFlattener.flattenOutline(
            outline.contours,
            1.0,
            0.0,
            0.0,
            RasterLimits.Default.copy(maxTotalPoints = 1),
        )
    }

    private fun twoTrianglePath(): GlyphPaintPath = GlyphPaintPath(
        listOf(
            GlyphPaintPathCommand.MoveTo(0.0, 0.0),
            GlyphPaintPathCommand.LineTo(10.0, 0.0),
            GlyphPaintPathCommand.LineTo(5.0, 10.0),
            GlyphPaintPathCommand.Close,
            GlyphPaintPathCommand.MoveTo(20.0, 0.0),
            GlyphPaintPathCommand.LineTo(30.0, 0.0),
            GlyphPaintPathCommand.LineTo(25.0, 10.0),
            GlyphPaintPathCommand.Close,
        ),
    )

    private fun syntheticOutline(vararg commands: GlyphOutlineIR.Command): GlyphOutlineIR =
        GlyphOutlineIR(
            glyphId = 1,
            unitsPerEm = 1_000,
            bounds = DesignBounds(0, 0, 100, 100),
            commands = commands.toList(),
        )
}
