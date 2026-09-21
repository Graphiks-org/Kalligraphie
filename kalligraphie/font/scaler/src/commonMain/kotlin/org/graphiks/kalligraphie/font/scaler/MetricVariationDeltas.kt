package org.graphiks.kalligraphie.font.scaler

import kotlin.math.roundToInt

/**
 * Rounds an interpolated metric to a design unit; ties round toward positive infinity, matching
 * fontTools `otRound`. Shared by the horizontal and vertical metric readers so both round the same
 * way.
 */
internal fun roundMetric(value: Double): Int = value.roundToInt()

/**
 * Additive advance and side-bearing deltas applied to a glyph's base metric at a variation instance.
 *
 * The values are in design units and are added to the `hmtx`/`vmtx` base before scaling. A source
 * that cannot supply a side bearing, such as the `gvar` phantom-point fallback, carries a zero
 * [sideBearing] so the base side bearing is retained.
 */
internal data class MetricVariationDeltas(
    val advance: Double = 0.0,
    val sideBearing: Double = 0.0,
)
