package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaintCompositorTest {
    @Test
    fun composesOpaqueLayersInChildOrder() {
        val red = pathNode(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0, color = GlyphColor(255, 0, 0))
        val blue = pathNode(2.0, 0.0, 6.0, 0.0, 6.0, 4.0, 2.0, 4.0, color = GlyphColor(0, 0, 255))
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 2,
            nodes = listOf(red, blue, GlyphPaintNode.Group(listOf(0, 1))),
        )
        val image = PaintCompositor.rasterize(
            paint,
            pixelsPerEm = 1_000.0,
            unitsPerEm = 1_000,
            originX = 0,
            originY = 0,
            limits = RasterLimits.Default,
        )
        assertEquals(6, image.width)
        assertEquals(4, image.height)
        assertEquals(0xFFFF0000.toInt(), image[1, 1])
        assertEquals(0xFF0000FF.toInt(), image[3, 1])
        assertEquals(0xFF0000FF.toInt(), image[5, 1])
    }

    @Test
    fun translucentSourceOverBlackMatchesIntegerFormula() {
        val black = pathNode(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0, color = GlyphColor(0, 0, 0, 255))
        val translucentRed = pathNode(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0, color = GlyphColor(255, 0, 0, 128))
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 2,
            nodes = listOf(black, translucentRed, GlyphPaintNode.Group(listOf(0, 1))),
        )
        val image = PaintCompositor.rasterize(
            paint,
            pixelsPerEm = 1_000.0,
            unitsPerEm = 1_000,
            originX = 0,
            originY = 0,
            limits = RasterLimits.Default,
        )
        assertEquals(0xFF800000.toInt(), image[0, 0])
    }

    @Test
    fun nodeBudgetRefusesOversizedGraph() {
        val node = pathNode(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0, color = GlyphColor(255, 255, 255))
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 1,
            nodes = listOf(node, GlyphPaintNode.Group(listOf(0, 0, 0))),
        )
        val limits = RasterLimits.Default.copy(maxPaintNodes = 2)
        assertFailsWith<RasterLimitReached> {
            PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, limits)
        }
    }

    @Test
    fun refusesAHugeUnionSpanBeforeAllocating() {
        val near = pathNode(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0, color = GlyphColor(255, 0, 0))
        val far = pathNode(
            1_100_000_000.0, 0.0,
            1_100_000_004.0, 0.0,
            1_100_000_004.0, 4.0,
            1_100_000_000.0, 4.0,
            color = GlyphColor(0, 0, 255),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 2,
            nodes = listOf(near, far, GlyphPaintNode.Group(listOf(0, 1))),
        )
        val refusal = assertFailsWith<RasterLimitReached> {
            PaintCompositor.rasterize(paint, 1.0, 1, 0, 0, RasterLimits.Default)
        }
        assertEquals("maxWidthPx", refusal.field)
        assertEquals(1_100_000_004L, refusal.observed)
        assertEquals(RasterLimits.Default.maxWidthPx.toLong(), refusal.limit)
    }

    @Test
    fun refusesAUnionSpanWiderThanIntRangeBeforeAllocating() {
        val near = pathNode(
            -2_100_000_000.0, 0.0,
            -2_099_999_996.0, 0.0,
            -2_099_999_996.0, 4.0,
            -2_100_000_000.0, 4.0,
            color = GlyphColor(255, 0, 0),
        )
        val far = pathNode(
            2_100_000_000.0, 0.0,
            2_100_000_004.0, 0.0,
            2_100_000_004.0, 4.0,
            2_100_000_000.0, 4.0,
            color = GlyphColor(0, 0, 255),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 2,
            nodes = listOf(near, far, GlyphPaintNode.Group(listOf(0, 1))),
        )
        val refusal = assertFailsWith<RasterLimitReached> {
            PaintCompositor.rasterize(paint, 1.0, 1, 0, 0, RasterLimits.Default)
        }
        assertEquals("maxWidthPx", refusal.field)
        assertEquals(4_200_000_004L, refusal.observed)
        assertEquals(RasterLimits.Default.maxWidthPx.toLong(), refusal.limit)
    }

    private fun pathNode(
        vararg coordinates: Double,
        color: GlyphColor,
    ): GlyphPaintNode.Path {
        val points = coordinates.toList().chunked(2) { (x, y) -> x to y }
        val commands = buildList {
            points.forEachIndexed { index, (x, y) ->
                add(if (index == 0) GlyphPaintPathCommand.MoveTo(x, y) else GlyphPaintPathCommand.LineTo(x, y))
            }
            add(GlyphPaintPathCommand.Close)
        }
        return GlyphPaintNode.Path(GlyphPaintPath(commands), color)
    }
}
