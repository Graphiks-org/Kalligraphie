package org.graphiks.kalligraphie.font.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
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
}
