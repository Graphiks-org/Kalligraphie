@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32
import org.graphiks.kalligraphie.font.sfnt.variationFailure
import org.graphiks.kalligraphie.font.sfnt.variationLimitFailure

/**
 * Decoded OpenType `HVAR` table.
 *
 * The advance-width variations are always present and are addressed by glyph ID (implicit
 * `outer = 0, inner = glyphId`) unless an advance-width mapping is supplied. Side-bearing
 * variations are optional and require their mapping subtable: with no LSB or RSB mapping the
 * corresponding delta is `0.0` and the `hmtx` side bearing is left unchanged, per the `HVAR`
 * specification ("variation data for side bearings are optional. If included, mapping tables are
 * required").
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class HvarData internal constructor(
    /** Number of variation axes the item variation store is expressed against. */
    public val axisCount: Int,
    private val store: VariationStore,
    private val advanceWidthMap: DeltaSetIndexMap?,
    private val lsbMap: DeltaSetIndexMap?,
    private val rsbMap: DeltaSetIndexMap?,
) {
    /** Advance-width adjustment for [glyphId] at [normalizedAxes]. */
    public fun advanceWidthDelta(glyphId: Int, normalizedAxes: List<Double>): Double {
        if (glyphId < 0) return 0.0
        val outer = advanceWidthMap?.outerIndex(glyphId) ?: 0
        val inner = advanceWidthMap?.innerIndex(glyphId) ?: glyphId
        return VariationStoreEvaluator.delta(store, outer, inner, normalizedAxes)
    }

    /** Left side-bearing adjustment for [glyphId], or `0.0` when no LSB mapping is present. */
    public fun leftSideBearingDelta(glyphId: Int, normalizedAxes: List<Double>): Double {
        if (glyphId < 0) return 0.0
        val map = lsbMap ?: return 0.0
        return VariationStoreEvaluator.delta(store, map.outerIndex(glyphId), map.innerIndex(glyphId), normalizedAxes)
    }

    /** Right side-bearing adjustment for [glyphId], or `0.0` when no RSB mapping is present. */
    public fun rightSideBearingDelta(glyphId: Int, normalizedAxes: List<Double>): Double {
        if (glyphId < 0) return 0.0
        val map = rsbMap ?: return 0.0
        return VariationStoreEvaluator.delta(store, map.outerIndex(glyphId), map.innerIndex(glyphId), normalizedAxes)
    }
}

/**
 * Decodes the OpenType `HVAR` table version 1.0.
 *
 * Every offset is bounds-checked against [table]; the operation is all-or-nothing. `axisCount` must
 * match the owning face's `fvar` axis count. A malformed header or record fails with
 * `font.variation.invalid-hvar`, an unsupported version with
 * `font.variation.unsupported-hvar-version`, the embedded store keeps its own
 * `font.variation.*-store` codes, and a bounds breach reuses `font.resource-limit-exceeded`.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object HvarReader {
    private const val HEADER_SIZE = 20

    /**
     * Parses one `HVAR` table.
     *
     * @param table exact bytes of the OpenType `HVAR` table.
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
    ): FontOperationResult<HvarData> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (table.size > limits.maxSourceBytes) {
            return variationLimitFailure("HVAR table exceeds the source-byte limit.", "HVAR")
        }
        if (table.size < HEADER_SIZE) return invalid("HVAR header is truncated.")
        val major = readUInt16(table, 0)?.toInt() ?: return invalid("HVAR header is truncated.")
        val minor = readUInt16(table, 2)?.toInt() ?: return invalid("HVAR header is truncated.")
        if (major != 1 || minor != 0) {
            return variationFailure("font.variation.unsupported-hvar-version", "Unsupported HVAR version $major.$minor.", "HVAR")
        }
        if (limits.maxVariationStores < 1) {
            return variationLimitFailure("HVAR requires a variation store but the limit forbids one.", "HVAR")
        }
        val storeOffset = readOffset32(table, 4) ?: return invalid("HVAR item variation store offset is out of range.")
        if (storeOffset <= 0 || storeOffset >= table.size) {
            return invalid("HVAR item variation store offset is out of range.")
        }
        val store = when (
            val result = VariationStoreEvaluator.read(
                table,
                storeOffset,
                "HVAR",
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
            return invalid("HVAR axis count ${store.axisCount} does not match fvar $expectedAxisCount.")
        }
        val advanceWidthMap = when (val result = readMap(table, 8, limits, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val lsbMap = when (val result = readMap(table, 12, limits, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val rsbMap = when (val result = readMap(table, 16, limits, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return FontOperationResult.Success(HvarData(store.axisCount, store, advanceWidthMap, lsbMap, rsbMap))
    }

    private fun readMap(
        table: ByteArray,
        offset: Int,
        limits: MetricVariationLimits,
        cancellationToken: CancellationToken,
    ): FontOperationResult<DeltaSetIndexMap?> {
        val mapOffset = readOffset32(table, offset)
            ?: return invalid("HVAR delta-set index map offset is out of range.")
        return readDeltaSetIndexMap(table, mapOffset, "HVAR", limits, cancellationToken)
    }

    internal fun readOffset32(bytes: ByteArray, offset: Int): Int? {
        val value = readUInt32(bytes, offset)?.toLong() ?: return null
        return if (value > Int.MAX_VALUE.toLong()) null else value.toInt()
    }

    internal fun invalid(message: String): FontOperationResult.Failure =
        variationFailure("font.variation.invalid-hvar", message, "HVAR")
}
