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
 * Builds a typed data failure whose diagnostic is attached to the result.
 *
 * [code] must be a `font.`-prefixed machine-readable code and [message] must not be blank; both
 * constraints are enforced by [FontError.FontDataFailure], which throws [IllegalArgumentException]
 * when either is violated. [tag] is the SFNT table the failure originates from (`fvar`, `avar`,
 * `gvar`, `VARC`, or `head` for face-level failures).
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun variationFailure(code: String, message: String, tag: String): FontOperationResult.Failure {
    val error = FontError.FontDataFailure(code = code, message = message, location = FontDiagnosticLocation.Table(tag))
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}

/** Builds a typed resource-limit failure for a variation table bound. */
internal fun variationLimitFailure(message: String, tag: String): FontOperationResult.Failure {
    val error = FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Table(tag))
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}

/**
 * The OpenType variation-region axis factor shared by `gvar`/`cvar` tuple variation stores and the
 * `ItemVariationStore`.
 *
 * [start], [peak] and [end] are a region's normalized F2Dot14 bounds on one axis and [coordinate] is
 * the instance's normalized coordinate on that axis. The factor is `1.0` for an invalid bound
 * ordering (`start > peak || peak > end`), for a region that spans zero (`start < 0.0 && end > 0.0`
 * with `peak != 0.0`), and for a zero peak; it is `0.0` when [coordinate] lies outside `[start,
 * end]`, `1.0` exactly at [peak], and linear between `start → peak` and `peak → end` otherwise. This
 * is the OpenType "Algorithm for Interpolation of Instance Values", matching fontTools
 * `supportScalar(..., ot = true)`.
 *
 * A malformed or zero-crossing region is deliberately treated as having no effect — the axis is
 * ignored (`1.0`) rather than dropped — because fontTools' partial `varLib.instancer` discards such
 * tents while the specification and the render-time (`ttGlyphSet`) and whole-font (`varLib.mutator`)
 * paths apply this fallback. This engine is a renderer, so it follows the specification and the
 * runtime rule.
 */
internal object VariationRegionAxisFactor {
    /** Factor one region contributes on one axis at [coordinate]. */
    fun factor(start: Double, peak: Double, end: Double, coordinate: Double): Double {
        if (start > peak || peak > end) return 1.0
        if (start < 0.0 && end > 0.0 && peak != 0.0) return 1.0
        if (peak == 0.0) return 1.0
        if (coordinate < start || coordinate > end) return 0.0
        if (coordinate == peak) return 1.0
        return if (coordinate < peak) {
            (coordinate - start) / (peak - start)
        } else {
            (end - coordinate) / (end - peak)
        }
    }
}
