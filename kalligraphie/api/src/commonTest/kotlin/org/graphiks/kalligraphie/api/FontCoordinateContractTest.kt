package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
