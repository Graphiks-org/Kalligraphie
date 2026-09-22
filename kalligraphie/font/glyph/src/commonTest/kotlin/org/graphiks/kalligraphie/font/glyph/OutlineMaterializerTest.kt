@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.scaler.ScalerGlyphOutline

class OutlineMaterializerTest {
    @Test
    fun reportsTheCheckedLongByteBudgetBeforePublishingAnOutline() {
        val outline = ScalerGlyphOutline(
            glyphId = 7,
            unitsPerEm = 2_048,
            bounds = DesignBounds(0, 0, 10, 10),
            contours = listOf(
                GlyphContour(
                    listOf(
                        GlyphOutlineCommand.MoveTo(0, 0),
                        GlyphOutlineCommand.Close,
                    ),
                ),
            ),
            pointCount = 1,
            components = emptyList(),
        )
        val profile = OutlineProfile(
            maxBytes = 32,
            maxContours = 1,
            maxPoints = 1,
            maxCompositeDepth = 1,
            maxCompositeComponents = 1,
        )

        val result = OutlineMaterializer.materialize(outline, profile)

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals(64L, failure.diagnostics.single().data.observedValue)
        assertEquals(32L, failure.diagnostics.single().data.limit)
    }

    @Test
    fun appliesSyntheticBoldWhenTheMaterializedOutlineIsBuilt() {
        val result = OutlineMaterializer.materialize(squareOutline(), squareProfile(), syntheticBold = true)

        val materialized = assertIs<GlyphRepresentation.Outline>(
            assertIs<FontOperationResult.Success<GlyphRepresentation>>(result).value,
        ).outline

        assertEquals(DesignBounds(-20, -20, 120, 120), materialized.bounds)
        assertEquals(4, materialized.pointCount)
        val move = assertIs<GlyphOutlineCommand.MoveTo>(materialized.contours.single().commands.first())
        assertEquals(-20.0, move.x, 1e-9)
        assertEquals(-20.0, move.y, 1e-9)
    }

    @Test
    fun leavesTheMaterializedOutlineUnchangedWithoutSyntheticFlags() {
        val result = OutlineMaterializer.materialize(squareOutline(), squareProfile())

        val materialized = assertIs<GlyphRepresentation.Outline>(
            assertIs<FontOperationResult.Success<GlyphRepresentation>>(result).value,
        ).outline

        assertEquals(DesignBounds(0, 0, 100, 100), materialized.bounds)
    }

    private fun squareOutline(): ScalerGlyphOutline = ScalerGlyphOutline(
        glyphId = 1,
        unitsPerEm = 1_000,
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

    private fun squareProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 4_096,
        maxContours = 1,
        maxPoints = 4,
        maxCompositeDepth = 1,
        maxCompositeComponents = 1,
    )
}
