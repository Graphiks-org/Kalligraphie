package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaintClipTest {
    private fun squareOutline(left: Double, top: Double, right: Double, bottom: Double): GlyphOutlineIR =
        GlyphOutlineIR(
            glyphId = 1,
            unitsPerEm = 1_000,
            bounds = DesignBounds(0, 0, 1_000, 1_000),
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

    @Test
    fun aClipRestrictsABoundedOutline() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val clip = GlyphPaintNode.GlyphClip(squareOutline(0.0, 0.0, 2.0, 2.0), paint = 0)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 1, nodes = listOf(path, clip))
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0xFFFF0000.toInt(), image[1, 1])
        assertEquals(0, (image[3, 3] ushr 24) and 0xFF)
    }

    @Test
    fun aRootClipBoundsClipsABoundedOutline() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 0,
            nodes = listOf(path),
            clipBounds = DesignBounds(0, 0, 2, 2),
        )
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0, (image[3, 3] ushr 24) and 0xFF)
    }

    @Test
    fun aTransformMovesABoundedOutline() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 2.0, 2.0), GlyphColor(255, 0, 0))
        val moved = GlyphPaintNode.Transform(
            paint = 0,
            matrix = GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, 2.0, 0.0),
        )
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 1, nodes = listOf(path, moved))
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(2, image.left)
        assertEquals(0xFFFF0000.toInt(), image[0, 0])
    }

    @Test
    fun anEmptyClipPaintsNothing() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val empty = GlyphPaintNode.GlyphClip(emptyOutline(), paint = 0)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 1, nodes = listOf(path, empty))
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0, image.width * image.height)
    }

    @Test
    fun aClipAroundAGroupIsRefused() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val group = GlyphPaintNode.Group(listOf(0))
        val clip = GlyphPaintNode.GlyphClip(squareOutline(0.0, 0.0, 2.0, 2.0), paint = 1)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 2, nodes = listOf(path, group, clip))
        val refusal = assertFailsWith<RasterRequestRejected> {
            PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        }
        assertEquals("nodeKind", refusal.field)
    }
}
