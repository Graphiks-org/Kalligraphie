package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintAlphaInterpolationMode
import org.graphiks.kalligraphie.api.GlyphPaintColorLine
import org.graphiks.kalligraphie.api.GlyphPaintColorStop
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintInterpolationSpace
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PaintGradientTest {
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

    private fun redBlueLine(
        extend: GlyphPaintExtendMode,
        stop0: Double = 0.0,
        stop1: Double = 1.0,
        interpolationSpace: GlyphPaintInterpolationSpace = GlyphPaintInterpolationSpace.LINEAR_SRGB,
        alphaMode: GlyphPaintAlphaInterpolationMode = GlyphPaintAlphaInterpolationMode.PREMULTIPLIED,
    ): GlyphPaintColorLine = GlyphPaintColorLine(
        extendMode = extend,
        colorStops = listOf(
            GlyphPaintColorStop(stop0, GlyphColor(255, 0, 0), 1.0),
            GlyphPaintColorStop(stop1, GlyphColor(0, 0, 255), 1.0),
        ),
        interpolationSpace = interpolationSpace,
        alphaInterpolationMode = alphaMode,
    )

    private fun gradient(
        line: GlyphPaintColorLine,
        p0: GlyphPaintPoint = GlyphPaintPoint(0.0, 2.0),
        p1: GlyphPaintPoint = GlyphPaintPoint(1.0, 2.0),
        p2: GlyphPaintPoint = GlyphPaintPoint(0.0, 3.0),
    ): GlyphPaintNode.LinearGradient = GlyphPaintNode.LinearGradient(line, p0, p1, p2)

    private fun clipped(child: GlyphPaintNode, schema: Int = 2): GlyphPaintIR =
        GlyphPaintIR(
            schemaVersion = schema,
            rootNode = 1,
            nodes = listOf(child, GlyphPaintNode.GlyphClip(clip, paint = 0)),
        )

    @Test
    fun aSolidIsBoundedByItsClip() {
        val solid = GlyphPaintNode.Solid(GlyphColor(10, 20, 30), 1.0)
        val image = PaintCompositor.rasterize(clipped(solid), 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0xFF0A141E.toInt(), image[1, 1])
    }

    @Test
    fun anUnboundedRootIsRefused() {
        val solid = GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 1.0)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 0, nodes = listOf(solid))
        val refusal = assertFailsWith<RasterRequestRejected> {
            PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        }
        assertEquals("nodeKind", refusal.field)
    }

    @Test
    fun aRootClipBoundsBoundsAnUnboundedSolid() {
        val solid = GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 1.0)
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 0,
            nodes = listOf(solid),
            clipBounds = DesignBounds(0, 0, 2, 2),
        )
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(2, image.width)
    }

    @Test
    fun repeatPeriodsOverTheStopSpan() {
        // Stops 0.25..1.125: the period is 0.875, not 1.0. A fract(t)-over-[0,1] implementation
        // would paint the same colour at both edges of this clip.
        val image = PaintCompositor.rasterize(
            clipped(gradient(redBlueLine(GlyphPaintExtendMode.REPEAT, stop0 = 0.25, stop1 = 1.125))),
            1_000.0,
            1_000,
            0,
            0,
            RasterLimits.Default,
        )
        assertNotEquals(image[0, 2], image[3, 2])
    }

    @Test
    fun anUnsupportedInterpolationSpaceIsRefused() {
        val refusal = assertFailsWith<RasterRequestRejected> {
            PaintCompositor.rasterize(
                clipped(gradient(redBlueLine(GlyphPaintExtendMode.PAD, interpolationSpace = GlyphPaintInterpolationSpace.SRGB)), schema = 3),
                1_000.0,
                1_000,
                0,
                0,
                RasterLimits.Default,
            )
        }
        assertEquals("interpolationSpace", refusal.field)
    }

    @Test
    fun anUnsupportedAlphaModeIsRefused() {
        val refusal = assertFailsWith<RasterRequestRejected> {
            PaintCompositor.rasterize(
                clipped(gradient(redBlueLine(GlyphPaintExtendMode.PAD, alphaMode = GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED)), schema = 3),
                1_000.0,
                1_000,
                0,
                0,
                RasterLimits.Default,
            )
        }
        assertEquals("alphaInterpolationMode", refusal.field)
    }

    @Test
    fun aTransformMovesAGradient() {
        val moved = GlyphPaintNode.Transform(
            paint = 0,
            matrix = GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, 2.0, 0.0),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 2,
            nodes = listOf(gradient(redBlueLine(GlyphPaintExtendMode.PAD)), moved, GlyphPaintNode.GlyphClip(clip, paint = 1)),
        )
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        // p0=(0,2) and p1=(1,2) moved to (2,2) and (3,2). At device x=2.5 the translated gradient
        // sits at parameter 0.5 (a red/blue mix) where the untranslated one is already pure blue.
        assertEquals(0xFF, (image[2, 2] ushr 24) and 0xFF)
        assertNotEquals(0xFF0000FF.toInt(), image[2, 2])
    }
}
