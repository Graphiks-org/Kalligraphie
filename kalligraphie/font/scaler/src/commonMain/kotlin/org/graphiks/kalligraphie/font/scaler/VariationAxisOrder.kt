package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.FontAxisCoordinate

/**
 * Maps tag-keyed [normalizedAxes] into [axisTags] order — the face's `fvar` axis order.
 *
 * An axis declared in [axisTags] but absent from [normalizedAxes] defaults to `0.0`, so a default
 * or partial selection yields the neutral coordinate for every missing axis. An empty [axisTags]
 * yields an empty list. This is the single mapping shared by the `gvar` and CFF2 outline routes.
 */
internal fun orderedNormalizedAxes(
    axisTags: List<String>,
    normalizedAxes: List<FontAxisCoordinate>,
): List<Double> = List(axisTags.size) { index ->
    val tag = axisTags[index]
    normalizedAxes.firstOrNull { it.tag == tag }?.value?.toDouble() ?: 0.0
}
