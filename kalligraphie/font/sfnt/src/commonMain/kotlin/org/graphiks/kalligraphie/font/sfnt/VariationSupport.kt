@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.toDiagnostic

/** Bounds applied while decoding font-variation tables. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public data class VariationLimits(
    /** Maximum accepted table length in bytes. */
    public val maxSourceBytes: Int = 65_536,
    /** Maximum accepted number of variation axes. */
    public val maxAxes: Int = 64,
    /** Maximum accepted number of named instances. */
    public val maxInstances: Int = 4_096,
) {
    init {
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxAxes > 0) { "maxAxes must be positive." }
        require(maxInstances >= 0) { "maxInstances must not be negative." }
    }
}

/**
 * Builds a typed variation failure whose diagnostic is attached to the result.
 *
 * The code must be a `font.`-prefixed machine-readable code. [tag] is the SFNT table the failure
 * originates from (`fvar`, `avar`, or `head` for face-level failures).
 */
internal fun variationFailure(code: String, message: String, tag: String): FontOperationResult.Failure {
    val error = FontError.FontDataFailure(code = code, message = message, location = FontDiagnosticLocation.Table(tag))
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}
