@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.variationFailure

/**
 * Decoded OpenType `VVAR` table.
 *
 * The advance-height variations are always present and are addressed by glyph ID unless an
 * advance-height mapping is supplied. TSB and BSB variations are optional and require their mapping
 * subtable; with no mapping the corresponding delta is `0.0` and the `vmtx` value is unchanged.
 * Vertical-origin mapping data is not consumed: it is only defined for CFF2 vertical origins and is
 * outside the portable glyph-metrics surface.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class VvarData internal constructor(
    /** Number of variation axes the item variation store is expressed against. */
    public val axisCount: Int,
    private val store: VariationStore,
    private val advanceHeightMap: DeltaSetIndexMap?,
    private val tsbMap: DeltaSetIndexMap?,
    private val bsbMap: DeltaSetIndexMap?,
) {
    /** Advance-height adjustment for [glyphId] at [normalizedAxes]. */
    public fun advanceHeightDelta(glyphId: Int, normalizedAxes: List<Double>): Double {
        if (glyphId < 0) return 0.0
        val outer = advanceHeightMap?.outerIndex(glyphId) ?: 0
        val inner = advanceHeightMap?.innerIndex(glyphId) ?: glyphId
        return VariationStoreEvaluator.delta(store, outer, inner, normalizedAxes)
    }

    /** Top side-bearing adjustment for [glyphId], or `0.0` when no TSB mapping is present. */
    public fun topSideBearingDelta(glyphId: Int, normalizedAxes: List<Double>): Double {
        if (glyphId < 0) return 0.0
        val map = tsbMap ?: return 0.0
        return VariationStoreEvaluator.delta(store, map.outerIndex(glyphId), map.innerIndex(glyphId), normalizedAxes)
    }

    /** Bottom side-bearing adjustment for [glyphId], or `0.0` when no BSB mapping is present. */
    public fun bottomSideBearingDelta(glyphId: Int, normalizedAxes: List<Double>): Double {
        if (glyphId < 0) return 0.0
        val map = bsbMap ?: return 0.0
        return VariationStoreEvaluator.delta(store, map.outerIndex(glyphId), map.innerIndex(glyphId), normalizedAxes)
    }
}

/**
 * Decodes the OpenType `VVAR` table version 1.0.
 *
 * Every offset is bounds-checked against [table]; the operation is all-or-nothing. `axisCount` must
 * match the owning face's `fvar` axis count. A malformed header or record fails with
 * `font.variation.invalid-vvar`, an unsupported version with
 * `font.variation.unsupported-vvar-version`, the embedded store keeps its own
 * `font.variation.*-store` codes, and a bounds breach reuses `font.resource-limit-exceeded`.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object VvarReader {
    private const val HEADER_SIZE = 24

    /**
     * Parses one `VVAR` table.
     *
     * @param table exact bytes of the OpenType `VVAR` table.
     * @param expectedAxisCount axis count that must equal the item variation store axis count.
     * @param limits metric-variation bounds enforced before allocating decoded records.
     * @param storeLimits bounds forwarded to the embedded item variation store parse.
     * @param cancellationToken cooperative cancellation checked before each store and map.
     * @return the decoded table or a typed version, malformed-data, or limit failure.
     */
    public fun read(
        table: ByteArray,
        expectedAxisCount: Int,
        limits: MetricVariationLimits = MetricVariationLimits(),
        storeLimits: VariationStoreLimits = VariationStoreLimits(),
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<VvarData> {
        when (
            val result = readMetricVariationVersion(
                table,
                "VVAR",
                HEADER_SIZE,
                "font.variation.unsupported-vvar-version",
                "font.variation.invalid-vvar",
                limits,
                cancellationToken,
            )
        ) {
            is FontOperationResult.Success -> Unit
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val storeOffset = readOffset32(table, 4)
            ?: return invalid("VVAR item variation store offset is out of range.")
        val store = when (
            val result = readMetricVariationStore(
                table,
                "VVAR",
                "font.variation.invalid-vvar",
                storeOffset,
                expectedAxisCount,
                limits,
                storeLimits,
                cancellationToken,
            )
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val advanceHeightMap = when (
            val result = readMetricMapping(table, 8, "VVAR", "font.variation.invalid-vvar", limits, cancellationToken)
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val tsbMap = when (
            val result = readMetricMapping(table, 12, "VVAR", "font.variation.invalid-vvar", limits, cancellationToken)
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val bsbMap = when (
            val result = readMetricMapping(table, 16, "VVAR", "font.variation.invalid-vvar", limits, cancellationToken)
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return FontOperationResult.Success(VvarData(store.axisCount, store, advanceHeightMap, tsbMap, bsbMap))
    }

    private fun invalid(message: String): FontOperationResult.Failure =
        variationFailure("font.variation.invalid-vvar", message, "VVAR")
}
