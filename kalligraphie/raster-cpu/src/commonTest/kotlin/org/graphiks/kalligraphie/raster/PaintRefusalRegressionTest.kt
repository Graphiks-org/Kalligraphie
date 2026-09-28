package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintColorLine
import org.graphiks.kalligraphie.api.GlyphPaintColorStop
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphPaintPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PaintRefusalRegressionTest {
    private fun squareOutline(left: Double, top: Double, right: Double, bottom: Double): GlyphOutlineIR =
        GlyphOutlineIR(
            glyphId = 1,
            unitsPerEm = 1_000,
            bounds = DesignBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()),
            commands = listOf(
                GlyphOutlineIR.Command.MoveTo(left, top),
                GlyphOutlineIR.Command.LineTo(right, top),
                GlyphOutlineIR.Command.LineTo(right, bottom),
                GlyphOutlineIR.Command.LineTo(left, bottom),
                GlyphOutlineIR.Command.Close,
            ),
        )

    private fun emptyOutline(): GlyphOutlineIR =
        GlyphOutlineIR(
            glyphId = 2,
            unitsPerEm = 1_000,
            bounds = DesignBounds(0, 0, 0, 0),
            commands = emptyList(),
        )

    private fun squarePath(left: Double, top: Double, right: Double, bottom: Double): GlyphPaintPath =
        GlyphPaintPath(
            listOf(
                GlyphPaintPathCommand.MoveTo(left, top),
                GlyphPaintPathCommand.LineTo(right, top),
                GlyphPaintPathCommand.LineTo(right, bottom),
                GlyphPaintPathCommand.LineTo(left, bottom),
                GlyphPaintPathCommand.Close,
            ),
        )

    private fun refusalField(paint: GlyphPaintIR): String {
        val result = GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(1_000.0, 1_000))
        val failure = assertIs<RasterResult.Failure>(result)
        val diagnostic = assertIs<RasterDiagnostic.InvalidRequest>(failure.diagnostics.first())
        return diagnostic.field
    }

    @Test
    fun aTransformOverflowIsRefusedTypedNotThrown() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val huge = GlyphAffineTransform(1e200, 1.0, 0.0, 1.0, 0.0, 0.0)
        val inner = GlyphPaintNode.Transform(paint = 0, matrix = huge)
        val outer = GlyphPaintNode.Transform(paint = 1, matrix = huge)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 2, nodes = listOf(path, inner, outer))
        assertEquals("transform", refusalField(paint))
    }

    @Test
    fun disjointClipEnvelopesDoNotOverflowAndPaintNothing() {
        val solid = GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 1.0)
        val far = GlyphPaintNode.GlyphClip(squareOutline(2_000_000_000.0, 0.0, 2_000_000_002.0, 2.0), paint = 0)
        val near = GlyphPaintNode.GlyphClip(squareOutline(0.0, 0.0, 2.0, 2.0), paint = 1)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 2, nodes = listOf(solid, far, near))
        val result = GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(1_000.0, 1_000))
        val image = assertIs<RasterResult.Success<Rgba8Image>>(result).value
        assertEquals(0, image.width * image.height)
    }

    @Test
    fun padKeepsTheFirstDuplicateBelowTheFirstOffset() {
        val line = GlyphPaintColorLine(
            extendMode = GlyphPaintExtendMode.PAD,
            colorStops = listOf(
                GlyphPaintColorStop(1.0, GlyphColor(255, 0, 0), 1.0),
                GlyphPaintColorStop(1.0, GlyphColor(0, 0, 255), 1.0),
            ),
        )
        val gradient = GlyphPaintNode.LinearGradient(
            colorLine = line,
            p0 = GlyphPaintPoint(0.0, 2.0),
            p1 = GlyphPaintPoint(1.0, 2.0),
            p2 = GlyphPaintPoint(0.0, 3.0),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 1,
            nodes = listOf(gradient, GlyphPaintNode.GlyphClip(squareOutline(0.0, 0.0, 4.0, 4.0), paint = 0)),
        )
        val image = assertIs<RasterResult.Success<Rgba8Image>>(
            GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(1_000.0, 1_000)),
        ).value
        // The pixel left of offset 1.0 is the first duplicate (red); the one right of it is the last.
        assertEquals(0xFFFF0000.toInt(), image[0, 0])
        assertEquals(0xFF0000FF.toInt(), image[2, 0])
    }

    @Test
    fun anOverflowingColourLineSpanIsRefusedTyped() {
        val line = GlyphPaintColorLine(
            extendMode = GlyphPaintExtendMode.PAD,
            colorStops = listOf(
                GlyphPaintColorStop(-1e308, GlyphColor(255, 0, 0), 1.0),
                GlyphPaintColorStop(1e308, GlyphColor(0, 0, 255), 1.0),
            ),
        )
        val gradient = GlyphPaintNode.LinearGradient(
            colorLine = line,
            p0 = GlyphPaintPoint(0.0, 2.0),
            p1 = GlyphPaintPoint(1.0, 2.0),
            p2 = GlyphPaintPoint(0.0, 3.0),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 1,
            nodes = listOf(gradient, GlyphPaintNode.GlyphClip(squareOutline(0.0, 0.0, 4.0, 4.0), paint = 0)),
        )
        assertEquals("gradient", refusalField(paint))
    }

    @Test
    fun aNonFiniteGradientParameterIsRefusedTyped() {
        val line = GlyphPaintColorLine(
            extendMode = GlyphPaintExtendMode.PAD,
            colorStops = listOf(
                GlyphPaintColorStop(0.0, GlyphColor(255, 0, 0), 1.0),
                GlyphPaintColorStop(1.0, GlyphColor(0, 0, 255), 1.0),
            ),
        )
        // finite points, finite denominator, but the sample numerator overflows to infinity.
        val gradient = GlyphPaintNode.LinearGradient(
            colorLine = line,
            p0 = GlyphPaintPoint(0.0, 0.0),
            p1 = GlyphPaintPoint(1e-300, 0.0),
            p2 = GlyphPaintPoint(0.0, 1e308),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 1,
            nodes = listOf(gradient, GlyphPaintNode.GlyphClip(squareOutline(0.0, 0.0, 4.0, 4.0), paint = 0)),
        )
        assertEquals("gradient", refusalField(paint))
    }

    @Test
    fun aNonFiniteFlattenedCoordinateIsRefusedTyped() {
        val path = GlyphPaintNode.Path(squarePath(2.0, 2.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val huge = GlyphPaintNode.Transform(
            paint = 0,
            matrix = GlyphAffineTransform(1e308, 0.0, 0.0, 1.0, 0.0, 0.0),
        )
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 1, nodes = listOf(path, huge))
        assertEquals("transform", refusalField(paint))
    }

    @Test
    fun anEmptyClipStillConsumesTheNodeBudget() {
        val solid = GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 1.0)
        val first = GlyphPaintNode.Transform(paint = 0, matrix = GlyphAffineTransform.IDENTITY)
        val second = GlyphPaintNode.Transform(paint = 1, matrix = GlyphAffineTransform.IDENTITY)
        val clip = GlyphPaintNode.GlyphClip(emptyOutline(), paint = 2)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 3, nodes = listOf(solid, first, second, clip))
        val limits = RasterLimits.Default.copy(maxPaintNodes = 2)
        val refusal = kotlin.test.assertFailsWith<RasterLimitReached> {
            PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, limits)
        }
        assertTrue(refusal.field == "maxPaintNodes")
    }
}
