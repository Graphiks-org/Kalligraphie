package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RasterContractsTest {
    @Test
    fun defaultLimitsArePositive() {
        val limits = RasterLimits.Default
        assertEquals(4_096, limits.maxWidthPx)
        assertEquals(4_096, limits.maxHeightPx)
        assertEquals(1 shl 22, limits.maxPixelsPerImage)
        assertEquals(4_096, limits.maxContours)
        assertEquals(262_144, limits.maxTotalPoints)
        assertEquals(4_096, limits.maxPaintNodes)
        assertEquals(64, limits.maxPaintDepth)
    }

    @Test
    fun limitsRejectNonPositiveValuesForEveryField() {
        val violations: List<(Int) -> RasterLimits> = listOf(
            { value -> RasterLimits.Default.copy(maxWidthPx = value) },
            { value -> RasterLimits.Default.copy(maxHeightPx = value) },
            { value -> RasterLimits.Default.copy(maxPixelsPerImage = value) },
            { value -> RasterLimits.Default.copy(maxContours = value) },
            { value -> RasterLimits.Default.copy(maxTotalPoints = value) },
            { value -> RasterLimits.Default.copy(maxPaintNodes = value) },
            { value -> RasterLimits.Default.copy(maxPaintDepth = value) },
        )
        violations.forEachIndexed { index, build ->
            listOf(0, -1).forEach { invalid ->
                assertFailsWith<IllegalArgumentException>("field index $index must reject $invalid") { build(invalid) }
            }
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
    fun failureSnapshotsItsDiagnostics() {
        val source = mutableListOf<RasterDiagnostic>(RasterDiagnostic.InvalidRequest("pixelsPerEm", "must be positive"))
        val failure = RasterResult.Failure(source)
        source.clear()
        assertEquals(1, failure.diagnostics.size)
    }

    @Test
    fun limitExceededKeepsObservedAndLimit() {
        val diagnostic = RasterDiagnostic.LimitExceeded("maxPixelsPerImage", observed = 10, limit = 4)
        assertEquals(10L, diagnostic.observed)
        assertEquals(4L, diagnostic.limit)
    }
}
