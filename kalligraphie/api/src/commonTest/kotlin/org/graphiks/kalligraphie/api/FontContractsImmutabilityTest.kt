package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FontContractsImmutabilityTest {
    @Test
    fun glyphContourSnapshotsCommandsAndRejectsMutableListCasts() {
        val commands = mutableListOf<GlyphOutlineCommand>(
            GlyphOutlineCommand.MoveTo(1, 2),
            GlyphOutlineCommand.Close,
        )

        val contour = GlyphContour(commands)
        commands.clear()

        assertEquals(2, contour.commands.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (contour.commands as MutableList<GlyphOutlineCommand>).clear()
        }
        assertEquals(2, contour.commands.size)
    }

    @Test
    fun glyphOutlineSnapshotsNestedCollectionsAndRejectsMutableListCasts() {
        val contour = GlyphContour(
            listOf(
                GlyphOutlineCommand.MoveTo(1, 2),
                GlyphOutlineCommand.Close,
            ),
        )
        val contours = mutableListOf(contour)
        val components = mutableListOf(GlyphComponentReference(7, GlyphComponentTransform(0, 0)))

        val outline = GlyphOutlineIR(
            glyphId = 3,
            unitsPerEm = 1_000,
            bounds = DesignBounds(1, 2, 1, 2),
            contours = contours,
            pointCount = 1,
            components = components,
            limits = GlyphOutlineLimits(1_024, 4, 4, 2, 2),
        )
        contours.clear()
        components.clear()

        assertEquals(1, outline.contours.size)
        assertEquals(1, outline.components.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (outline.contours as MutableList<GlyphContour>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (outline.components as MutableList<GlyphComponentReference>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (outline.commands as MutableList<GlyphOutlineIR.Command>).clear()
        }
    }
}
