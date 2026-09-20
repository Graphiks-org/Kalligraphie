@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontOperationResult

class VariationSupportTest {
    @Test
    fun rejectsNonPositiveLimits() {
        assertFailsWith<IllegalArgumentException> { VariationLimits(maxSourceBytes = 0) }
        assertFailsWith<IllegalArgumentException> { VariationLimits(maxAxes = 0) }
        assertFailsWith<IllegalArgumentException> { VariationLimits(maxInstances = -1) }
    }

    @Test
    fun buildsTypedFailureWithCodeAndDiagnostic() {
        val result = variationFailure("font.variation.unknown-axis", "Unknown axis.", "fvar")
        assertTrue(result is FontOperationResult.Failure)
        assertEquals("font.variation.unknown-axis", result.error.code)
        assertEquals(1, result.diagnostics.size)
    }
}
