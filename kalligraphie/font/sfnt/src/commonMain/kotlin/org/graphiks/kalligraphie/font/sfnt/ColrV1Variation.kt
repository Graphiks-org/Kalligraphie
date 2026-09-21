@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.variation.DeltaSetIndexMap
import org.graphiks.kalligraphie.font.sfnt.variation.MetricVariationLimits
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStore
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStoreEvaluator
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStoreLimits
import org.graphiks.kalligraphie.font.sfnt.variation.readDeltaSetIndexMap

/** The COLR v1 "no variation" `VarIndexBase` sentinel (`0xFFFFFFFF`). */
internal const val NO_VARIATION_INDEX = 0xFFFF_FFFFL

/**
 * Resolves COLR v1 `VarIndexBase` deltas at one normalized location.
 *
 * The COLR table carries one [VariationStore] and, optionally, one [DeltaSetIndexMap]. A field at
 * [ordinal] inside a paint whose `VarIndexBase` is [varIndexBase] addresses the map at
 * `varIndexBase + ordinal`; a null map uses the specification's implicit 16-bit split of the
 * unsigned 32-bit target (`outer = target ushr 16`, the high word, and `inner = target and 0xFFFF`,
 * the low word). The target is treated as an unsigned 32-bit value, so only a target above
 * `0xFFFFFFFF` is out of range; when a map is present a target above `Int.MAX_VALUE` resolves to
 * the map's last entry exactly as the shared [DeltaSetIndexMap] does (no map can hold that many
 * entries). The delta is the sum over the addressed row's regions of `regionDelta * regionScalar`
 * ([VariationStoreEvaluator.delta]), in the field's raw fixed-point or font-unit space.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class ColrV1Variation internal constructor(
    private val store: VariationStore,
    private val indexMap: DeltaSetIndexMap?,
) {
    /** Raw delta for [ordinal] of a field whose `VarIndexBase` is [varIndexBase]. */
    public fun delta(varIndexBase: Long, ordinal: Int, orderedAxes: List<Double>): Double {
        if (varIndexBase == NO_VARIATION_INDEX) return 0.0
        val target = varIndexBase + ordinal.toLong()
        if (target < 0L || target > 0xFFFF_FFFFL) return 0.0
        val map = indexMap
        if (map == null) {
            return VariationStoreEvaluator.delta(store, (target ushr 16).toInt(), (target and 0xFFFFL).toInt(), orderedAxes)
        }
        val lookup = if (target > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else target.toInt()
        return VariationStoreEvaluator.delta(store, map.outerIndex(lookup), map.innerIndex(lookup), orderedAxes)
    }
}

/**
 * Parses the COLR v1 `VarIndexMap` and `VarStore` referenced by [indexes].
 *
 * Returns `null` when the `itemVariationStoreOffset` is absent, so a non-variable COLR font (or the
 * default instance) never allocates a variation store. A malformed store keeps the shared
 * `font.variation.*-store` codes; a `varIndexMapOffset` or `varStoreOffset` whose unsigned value
 * exceeds `Int.MAX_VALUE` reports `font.variation.invalid-colr`, while an in-range but truncated
 * offset surfaces as `font.variation.truncated-store` from the shared readers. An axis-count
 * mismatch reports `font.variation.invalid-colr`, a breach of [limits] (including the
 * [MetricVariationLimits.maxSourceBytes] table bound) reuses `font.resource-limit-exceeded`, and a
 * cancelled [cancellationToken] returns a cancelled result without touching the table.
 */
internal fun readColrVariation(
    table: ByteArray,
    indexes: ColrV1Indexes,
    expectedAxisCount: Int,
    limits: MetricVariationLimits,
    storeLimits: VariationStoreLimits,
    cancellationToken: CancellationToken,
): FontOperationResult<ColrV1Variation?> {
    if (indexes.varStoreOffset == 0L) return FontOperationResult.Success(null)
    if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
    if (indexes.varStoreOffset > Int.MAX_VALUE.toLong() || indexes.varIndexMapOffset > Int.MAX_VALUE.toLong()) {
        return variationFailure("font.variation.invalid-colr", "COLR variation offset is out of range.", "COLR")
    }
    if (table.size > limits.maxSourceBytes) {
        return variationLimitFailure("COLR variation table exceeds the source-byte limit.", "COLR")
    }
    if (limits.maxVariationStores < 1) {
        return variationLimitFailure("COLR requires a variation store but the limit forbids one.", "COLR")
    }
    val indexMap = when (
        val result = readDeltaSetIndexMap(table, indexes.varIndexMapOffset.toInt(), "COLR", limits, cancellationToken)
    ) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> return result
        is FontOperationResult.Cancelled -> return result
    }
    val store = when (
        val result = VariationStoreEvaluator.read(
            table,
            indexes.varStoreOffset.toInt(),
            "COLR",
            storeLimits,
            cancellationToken,
            includeDeltas = true,
        )
    ) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> return result
        is FontOperationResult.Cancelled -> return result
    }
    if (store.axisCount != expectedAxisCount) {
        return variationFailure(
            "font.variation.invalid-colr",
            "COLR axis count ${store.axisCount} does not match fvar $expectedAxisCount.",
            "COLR",
        )
    }
    return FontOperationResult.Success(ColrV1Variation(store, indexMap))
}
