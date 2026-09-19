package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FontCoordinateContractTest {
    @Test
    fun outlineCoordinatesRejectNonFiniteValuesAndNormalizeNegativeZero() {
        val move = GlyphOutlineCommand.MoveTo(-0.0, 1.25)
        val line = GlyphOutlineIR.Command.LineTo(-0.0, -2.5)
        val transform = GlyphComponentTransform(-0.0, 0.0)

        assertEquals(0.0, move.x)
        assertEquals(0.0, line.x)
        assertEquals(0.0, transform.translationX)
        assertEquals(1.25, move.y)
        assertEquals(-2.5, line.y)
        assertFailsWith<IllegalArgumentException> { GlyphOutlineCommand.MoveTo(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { GlyphOutlineCommand.LineTo(Double.POSITIVE_INFINITY, 0.0) }
        assertFailsWith<IllegalArgumentException> { GlyphOutlineCommand.QuadraticTo(0.0, 0.0, 0.0, Double.NEGATIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { GlyphOutlineIR.Command.MoveTo(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { GlyphComponentTransform(0.0, Double.NaN) }
    }

    @Test
    fun cubicCommandsCanonicalizeCoordinatesAndRejectNonFiniteValues() {
        val structured = GlyphOutlineCommand.CubicTo(-0.0, 1.0, 2.0, 3.0, -0.0, 4.0)
        val legacy = GlyphOutlineIR.Command.CubicTo(-0.0, 1.0, 2.0, 3.0, -0.0, 4.0)

        assertEquals(0.0, structured.control1X)
        assertEquals(0.0, structured.endX)
        assertEquals(0.0, legacy.control1X)
        assertEquals(0.0, legacy.endX)
        assertFailsWith<IllegalArgumentException> {
            GlyphOutlineCommand.CubicTo(0.0, 0.0, Double.NaN, 0.0, 0.0, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            GlyphOutlineIR.Command.CubicTo(0.0, 0.0, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun legacyCubicCommandsRoundTripIntoStructuredContours() {
        val outline = GlyphOutlineIR(
            glyphId = 1,
            unitsPerEm = 1000,
            bounds = DesignBounds.empty,
            commands = listOf(
                GlyphOutlineIR.Command.MoveTo(0.0, 0.0),
                GlyphOutlineIR.Command.CubicTo(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                GlyphOutlineIR.Command.Close,
            ),
        )

        val cubic = assertIs<GlyphOutlineCommand.CubicTo>(outline.contours.single().commands[1])
        assertEquals(5.0, cubic.endX)
        assertEquals(6.0, cubic.endY)
    }
}
