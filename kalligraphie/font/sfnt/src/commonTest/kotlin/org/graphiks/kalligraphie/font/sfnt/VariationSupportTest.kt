@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
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
        assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.FontDataFailure>(result.error)
        assertEquals("font.variation.unknown-axis", result.error.code)
        assertEquals(FontDiagnosticLocation.Table("fvar"), result.error.location)
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun buildsTypedResourceLimitFailure() {
        val result = variationLimitFailure("limit", "fvar")
        assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(result.error)
        assertEquals("font.resource-limit-exceeded", result.error.code)
    }
}
