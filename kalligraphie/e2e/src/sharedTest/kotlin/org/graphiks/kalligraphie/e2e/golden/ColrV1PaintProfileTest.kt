package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphOutlineLimits
import org.graphiks.kalligraphie.api.GlyphPaintColorLine
import org.graphiks.kalligraphie.api.GlyphPaintColorStop
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPoint
import kotlin.test.Test
import kotlin.test.assertTrue

class ColrV1PaintProfileTest {
    @Test
    fun theProfileAcceptsARealFontGroupGraph() {
        val outline = GlyphOutlineIR(
            glyphId = 1,
            unitsPerEm = 1_000,
            bounds = DesignBounds(0, 0, 1_000, 1_000),
            contours = listOf(
                GlyphContour(
                    listOf(
                        GlyphOutlineCommand.MoveTo(0.0, 0.0),
                        GlyphOutlineCommand.LineTo(500.0, 0.0),
                        GlyphOutlineCommand.LineTo(500.0, 500.0),
                        GlyphOutlineCommand.LineTo(0.0, 500.0),
                        GlyphOutlineCommand.Close,
                    ),
                ),
            ),
            pointCount = 4,
            limits = GlyphOutlineLimits(
                maxBytes = 1_000,
                maxContours = 8,
                maxPoints = 64,
                maxCompositeDepth = 4,
                maxCompositeComponents = 4,
            ),
        )
        val gradient = GlyphPaintNode.LinearGradient(
            colorLine = GlyphPaintColorLine(
                extendMode = GlyphPaintExtendMode.PAD,
                colorStops = listOf(
                    GlyphPaintColorStop(0.0, GlyphColor(255, 0, 0), 1.0),
                    GlyphPaintColorStop(1.0, GlyphColor(0, 0, 255), 1.0),
                ),
            ),
            p0 = GlyphPaintPoint(0.0, 0.0),
            p1 = GlyphPaintPoint(1_000.0, 0.0),
            p2 = GlyphPaintPoint(0.0, 1_000.0),
        )
        val clip = GlyphPaintNode.GlyphClip(outline, paint = 0)
        val group = GlyphPaintNode.Group(listOf(1))
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 2, nodes = listOf(gradient, clip, group))
        assertTrue(colrV1PaintProfile().accepts(paint))
    }
}
