@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
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
        val commands = success(SyntheticGeometry.apply(square(unitsPerEm = 1_000), bold = true, italic = true))
            .contours.single().commands

        assertPoint(commands[0], -25.59883264386157, -20.0)
        assertPoint(commands[2], 150.53163292817965, 120.0)
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
