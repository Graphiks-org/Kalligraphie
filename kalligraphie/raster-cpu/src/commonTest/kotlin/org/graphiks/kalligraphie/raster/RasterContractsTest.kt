package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RasterContractsTest {
    @Test
    fun defaultLimitsArePositive() {
        val limits = RasterLimits.Default
        assertTrue(limits.maxWidthPx > 0)
        assertTrue(limits.maxHeightPx > 0)
        assertTrue(limits.maxPixelsPerImage > 0)
        assertTrue(limits.maxContours > 0)
        assertTrue(limits.maxTotalPoints > 0)
        assertTrue(limits.maxPaintNodes > 0)
        assertTrue(limits.maxPaintDepth > 0)
    }

    @Test
    fun limitsRejectNonPositiveValues() {
        assertFailsWith<IllegalArgumentException> {
            RasterLimits(
                maxWidthPx = 0,
                maxHeightPx = 1,
                maxPixelsPerImage = 1,
                maxContours = 1,
                maxTotalPoints = 1,
                maxPaintNodes = 1,
                maxPaintDepth = 1,
            )
        }
    }

    @Test
    fun failureCarriesAtLeastOneDiagnostic() {
        val failure = assertIs<RasterResult.Failure>(
            RasterResult.Failure(listOf(RasterDiagnostic.InvalidRequest("pixelsPerEm", "must be positive"))),
        )
        assertEquals("pixelsPerEm", failure.diagnostics.single().field)
        assertFailsWith<IllegalArgumentException> { RasterResult.Failure(emptyList()) }
    }

    @Test
    fun limitExceededKeepsObservedAndLimit() {
        val diagnostic = RasterDiagnostic.LimitExceeded("maxPixelsPerImage", observed = 10, limit = 4)
        assertEquals(10L, diagnostic.observed)
        assertEquals(4L, diagnostic.limit)
    }
}
