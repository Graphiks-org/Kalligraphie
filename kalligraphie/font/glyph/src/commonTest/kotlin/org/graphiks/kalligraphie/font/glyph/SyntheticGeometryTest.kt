@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.font.scaler.ScalerGlyphOutline

class SyntheticGeometryTest {
    @Test
    fun expandsAUnitSquareByThePinnedBoldAmountOnEverySide() {
        val outline = success(SyntheticGeometry.apply(square(unitsPerEm = 1_000), bold = true, italic = false))

        assertEquals(DesignBounds(-20, -20, 120, 120), outline.bounds)
        val commands = outline.contours.single().commands
        assertPoint(commands[0], -20.0, -20.0)
        assertPoint(commands[1], 120.0, -20.0)
        assertPoint(commands[2], 120.0, 120.0)
        assertPoint(commands[3], -20.0, 120.0)
        assertEquals(4, outline.pointCount)
    }

    @Test
    fun shearsAUnitSquareByThePinnedItalicTangentAroundTheBaseline() {
        val outline = success(SyntheticGeometry.apply(square(unitsPerEm = 1_000), bold = false, italic = true))

        val commands = outline.contours.single().commands
        assertPoint(commands[0], 0.0, 0.0)
        assertPoint(commands[1], 100.0, 0.0)
        assertPoint(commands[2], 124.93280028431806, 100.0)
        assertPoint(commands[3], 24.93280028431807, 100.0)
        assertEquals(DesignBounds(0, 0, 125, 100), outline.bounds)
    }

    @Test
    fun appliesBoldAfterItalicWhenBothAreRequested() {
        val outline = success(SyntheticGeometry.apply(square(unitsPerEm = 1_000), bold = true, italic = true))

        assertEquals(DesignBounds(-26, -20, 151, 120), outline.bounds)
        assertEquals(4, outline.pointCount)
        val commands = outline.contours.single().commands
        assertEquals(5, commands.size)
        assertPoint(commands[0], -25.59883264386157, -20.0)
        assertPoint(commands[1], 115.62571253013435, -20.0)
        assertPoint(commands[2], 150.53163292817965, 120.0)
        assertPoint(commands[3], 9.307087754183723, 120.0)
        assertIs<GlyphOutlineCommand.Close>(commands[4])
    }

    @Test
    fun growsTheOuterContourAndShrinksAnOppositeWoundCounter() {
        val input = outerWithOppositeWoundHole()
        val outerBefore = absoluteArea(input.contours[0])
        val counterBefore = absoluteArea(input.contours[1])

        val outline = success(SyntheticGeometry.apply(input, bold = true, italic = false))

        val outerAfter = absoluteArea(outline.contours[0])
        val counterAfter = absoluteArea(outline.contours[1])
        assertTrue(outerAfter > outerBefore, "outer contour must grow: $outerBefore -> $outerAfter")
        assertTrue(counterAfter < counterBefore, "counter must shrink: $counterBefore -> $counterAfter")
        assertEquals(57_600.0, outerAfter, 1e-6)
        assertEquals(3_600.0, counterAfter, 1e-6)
    }

    @Test
    fun growsASameWoundNestedContour() {
        val input = sameWoundNested()

        val outline = success(SyntheticGeometry.apply(input, bold = true, italic = false))

        assertEquals(40_000.0, absoluteArea(input.contours[0]), 1e-6)
        assertEquals(10_000.0, absoluteArea(input.contours[1]), 1e-6)
        assertEquals(57_600.0, absoluteArea(outline.contours[0]), 1e-6)
        assertEquals(19_600.0, absoluteArea(outline.contours[1]), 1e-6)
    }

    @Test
    fun keepsASinglePointContour() {
        val input = outlineOf(
            contourOf(
                GlyphOutlineCommand.MoveTo(0, 0),
                GlyphOutlineCommand.Close,
            ),
            pointCount = 1,
        )

        val outline = success(SyntheticGeometry.apply(input, bold = true, italic = true))

        assertEquals(DesignBounds(0, 0, 0, 0), outline.bounds)
        assertEquals(1, outline.pointCount)
    }

    @Test
    fun keepsConsecutiveEqualPointsAndZeroLengthEdges() {
        val input = outlineOf(
            contourOf(
                GlyphOutlineCommand.MoveTo(0, 0),
                GlyphOutlineCommand.LineTo(0, 0),
                GlyphOutlineCommand.LineTo(100, 0),
                GlyphOutlineCommand.LineTo(100, 100),
                GlyphOutlineCommand.LineTo(0, 100),
                GlyphOutlineCommand.Close,
            ),
            pointCount = 5,
        )

        val outline = success(SyntheticGeometry.apply(input, bold = true, italic = false))

        assertEquals(DesignBounds(-20, -20, 120, 120), outline.bounds)
    }

    @Test
    fun keepsAZeroAreaCollinearContour() {
        val input = outlineOf(
            contourOf(
                GlyphOutlineCommand.MoveTo(0, 0),
                GlyphOutlineCommand.LineTo(50, 0),
                GlyphOutlineCommand.LineTo(100, 0),
                GlyphOutlineCommand.Close,
            ),
            pointCount = 3,
        )

        val outline = success(SyntheticGeometry.apply(input, bold = true, italic = false))

        assertEquals(DesignBounds(0, -20, 100, 20), outline.bounds)
    }

    @Test
    fun failsWithGeometryOverflowWhenTheEnvelopeLeavesTheDesignRange() {
        val input = outlineOf(
            contourOf(
                GlyphOutlineCommand.MoveTo(1.0e18, 1.0e18),
                GlyphOutlineCommand.Close,
            ),
            pointCount = 1,
        )

        val failure = assertIs<FontOperationResult.Failure>(
            SyntheticGeometry.apply(input, bold = true, italic = false),
        )

        assertIs<FontError.GeometryOverflow>(failure.error)
        assertEquals("font.geometry-overflow", failure.error.code)
    }

    @Test
    fun failsWithGeometryOverflowInsteadOfThrowingForANonFiniteTransformedCoordinate() {
        val input = outlineOf(
            contourOf(
                GlyphOutlineCommand.MoveTo(Double.MAX_VALUE, Double.MAX_VALUE),
                GlyphOutlineCommand.Close,
            ),
            pointCount = 1,
        )

        val failure = assertIs<FontOperationResult.Failure>(
            SyntheticGeometry.apply(input, bold = false, italic = true),
        )

        assertIs<FontError.GeometryOverflow>(failure.error)
        assertEquals("font.geometry-overflow", failure.error.code)
    }

    @Test
    fun rejectsAContourWithAnInteriorMoveTo() {
        val input = outlineOf(
            contourOf(
                GlyphOutlineCommand.MoveTo(0, 0),
                GlyphOutlineCommand.LineTo(100, 0),
                GlyphOutlineCommand.MoveTo(50, 50),
                GlyphOutlineCommand.LineTo(150, 50),
                GlyphOutlineCommand.Close,
            ),
            pointCount = 4,
        )

        val failure = assertIs<FontOperationResult.Failure>(
            SyntheticGeometry.apply(input, bold = true, italic = false),
        )

        assertIs<FontError.GeometryOverflow>(failure.error)
        assertEquals("font.geometry-overflow", failure.error.code)
    }

    @Test
    fun returnsTheInputUnchangedWithoutAStyle() {
        val input = square(unitsPerEm = 1_000)

        assertTrue(success(SyntheticGeometry.apply(input, bold = false, italic = false)) === input)
    }

    @Test
    fun returnsTheInputUnchangedForAnEmptyOutline() {
        val input = ScalerGlyphOutline(
            glyphId = 3,
            unitsPerEm = 1_000,
            bounds = DesignBounds.empty,
            contours = emptyList(),
            pointCount = 0,
            components = emptyList(),
        )

        assertTrue(success(SyntheticGeometry.apply(input, bold = true, italic = true)) === input)
    }

    @Test
    fun honoursCancellation() {
        val result = SyntheticGeometry.apply(square(unitsPerEm = 1_000), bold = true, italic = true, CancellationToken.cancelled)

        assertIs<FontOperationResult.Cancelled>(result)
    }

    private fun outerWithOppositeWoundHole(): ScalerGlyphOutline = ScalerGlyphOutline(
        glyphId = 1,
        unitsPerEm = 1_000,
        bounds = DesignBounds(0, 0, 200, 200),
        contours = listOf(
            contourOf(
                GlyphOutlineCommand.MoveTo(0, 0),
                GlyphOutlineCommand.LineTo(200, 0),
                GlyphOutlineCommand.LineTo(200, 200),
                GlyphOutlineCommand.LineTo(0, 200),
                GlyphOutlineCommand.Close,
            ),
            contourOf(
                GlyphOutlineCommand.MoveTo(50, 50),
                GlyphOutlineCommand.LineTo(50, 150),
                GlyphOutlineCommand.LineTo(150, 150),
                GlyphOutlineCommand.LineTo(150, 50),
                GlyphOutlineCommand.Close,
            ),
        ),
        pointCount = 8,
        components = emptyList(),
    )

    private fun sameWoundNested(): ScalerGlyphOutline = ScalerGlyphOutline(
        glyphId = 1,
        unitsPerEm = 1_000,
        bounds = DesignBounds(0, 0, 200, 200),
        contours = listOf(
            contourOf(
                GlyphOutlineCommand.MoveTo(0, 0),
                GlyphOutlineCommand.LineTo(200, 0),
                GlyphOutlineCommand.LineTo(200, 200),
                GlyphOutlineCommand.LineTo(0, 200),
                GlyphOutlineCommand.Close,
            ),
            contourOf(
                GlyphOutlineCommand.MoveTo(50, 50),
                GlyphOutlineCommand.LineTo(150, 50),
                GlyphOutlineCommand.LineTo(150, 150),
                GlyphOutlineCommand.LineTo(50, 150),
                GlyphOutlineCommand.Close,
            ),
        ),
        pointCount = 8,
        components = emptyList(),
    )

    private fun outlineOf(contour: GlyphContour, pointCount: Int): ScalerGlyphOutline = ScalerGlyphOutline(
        glyphId = 1,
        unitsPerEm = 1_000,
        bounds = DesignBounds(0, 0, 100, 100),
        contours = listOf(contour),
        pointCount = pointCount,
        components = emptyList(),
    )

    private fun contourOf(vararg commands: GlyphOutlineCommand): GlyphContour = GlyphContour(commands.toList())

    private fun absoluteArea(contour: GlyphContour): Double {
        val anchors = contour.commands.mapNotNull { command ->
            when (command) {
                is GlyphOutlineCommand.MoveTo -> command.x to command.y
                is GlyphOutlineCommand.LineTo -> command.x to command.y
                is GlyphOutlineCommand.QuadraticTo -> command.endX to command.endY
                is GlyphOutlineCommand.CubicTo -> command.endX to command.endY
                GlyphOutlineCommand.Close -> null
            }
        }
        var twiceArea = 0.0
        for (index in anchors.indices) {
            val (x1, y1) = anchors[index]
            val (x2, y2) = anchors[(index + 1) % anchors.size]
            twiceArea += x1 * y2 - x2 * y1
        }
        return kotlin.math.abs(twiceArea) / 2.0
    }

    private fun square(unitsPerEm: Int): ScalerGlyphOutline = ScalerGlyphOutline(
        glyphId = 1,
        unitsPerEm = unitsPerEm,
        bounds = DesignBounds(0, 0, 100, 100),
        contours = listOf(
            GlyphContour(
                listOf(
                    GlyphOutlineCommand.MoveTo(0, 0),
                    GlyphOutlineCommand.LineTo(100, 0),
                    GlyphOutlineCommand.LineTo(100, 100),
                    GlyphOutlineCommand.LineTo(0, 100),
                    GlyphOutlineCommand.Close,
                ),
            ),
        ),
        pointCount = 4,
        components = emptyList(),
    )

    private fun assertPoint(command: GlyphOutlineCommand, x: Double, y: Double) {
        when (command) {
            is GlyphOutlineCommand.MoveTo -> {
                assertEquals(x, command.x, 1e-9)
                assertEquals(y, command.y, 1e-9)
            }

            is GlyphOutlineCommand.LineTo -> {
                assertEquals(x, command.x, 1e-9)
                assertEquals(y, command.y, 1e-9)
            }

            else -> error("Unexpected command: $command")
        }
    }

    private fun success(result: FontOperationResult<ScalerGlyphOutline>): ScalerGlyphOutline =
        assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(result).value
}
