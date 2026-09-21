@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32
import org.graphiks.kalligraphie.font.sfnt.variationFailure
import org.graphiks.kalligraphie.font.sfnt.variationLimitFailure

/** Bounds applied while decoding an OpenType ItemVariationStore (format 1). */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public data class VariationStoreLimits(
    /** Maximum accepted ItemVariationStore extent in bytes (CFF2 stores keep their deltas inline in the charstring, so this structural extent stays small). */
    public val maxSourceBytes: Int = 1_048_576,
    /** Maximum accepted number of variation axes. */
    public val maxAxes: Int = 64,
    /** Maximum accepted number of variation regions. */
    public val maxRegions: Int = 4_096,
    /** Maximum accepted number of item variation data subtables. */
    public val maxItemData: Int = 4_096,
) {
    init {
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxAxes > 0) { "maxAxes must be positive." }
        require(maxRegions >= 0) { "maxRegions must not be negative." }
        require(maxItemData >= 0) { "maxItemData must not be negative." }
    }
}

/**
 * Parsed OpenType ItemVariationStore (format 1).
 *
 * Regions store a start/peak/end F2Dot14 triple per axis, in the same axis order as the owning
 * table's `fvar`. Item variation data subtables reference regions by index; the delta rows
 * themselves live with the owning table (CFF2 keeps them inline in the charstring). The region
 * references are validated when the store is parsed, so every position below [regionCountAt]
 * resolves to a valid region.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class VariationStore internal constructor(
    /** Number of axes the regions are expressed against. */
    public val axisCount: Int,
    /** Number of regions in the variation region list. */
    public val regionCount: Int,
    internal val regionStarts: List<DoubleArray>,
    internal val regionPeaks: List<DoubleArray>,
    internal val regionEnds: List<DoubleArray>,
    private val itemDataRegionIndexes: List<IntArray>,
) {
    /** Number of regions referenced by item variation data [itemDataIndex], or `0` when absent. */
    public fun regionCountAt(itemDataIndex: Int): Int =
        itemDataRegionIndexes.getOrNull(itemDataIndex)?.size ?: 0

    /**
     * Region index referenced at [position] in item variation data [itemDataIndex].
     *
     * Returns `-1` when [itemDataIndex] or [position] is out of range. Positions below
     * [regionCountAt] always hold a valid region index because [VariationStoreEvaluator.read]
     * rejects an out-of-range reference at parse time.
     */
    public fun regionIndex(itemDataIndex: Int, position: Int): Int =
        itemDataRegionIndexes.getOrNull(itemDataIndex)?.getOrNull(position) ?: -1
}

/**
 * Reads and evaluates an OpenType ItemVariationStore (format 1).
 *
 * The store begins at [offset] with a 16-bit format field. [tag] is the owning table (`CFF2` today;
 * `HVAR`/`MVAR`/`COLR` later) and becomes the diagnostic location. A store whose format is not 1
 * fails with `font.variation.unsupported-store-format`, a truncated store fails with
 * `font.variation.truncated-store`, malformed region references fail with
 * `font.variation.invalid-store`, and a breach of [limits] reuses `font.resource-limit-exceeded`.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object VariationStoreEvaluator {
    /** Parses the ItemVariationStore (format 1) beginning at [offset] in [bytes]. */
    public fun read(
        bytes: ByteArray,
        offset: Int,
        tag: String,
        limits: VariationStoreLimits = VariationStoreLimits(),
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<VariationStore> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val tableSize = bytes.size.toLong()
        if (offset < 0 || offset.toLong() + 8L > tableSize) {
            return variationFailure("font.variation.truncated-store", "Item variation store header is truncated.", tag)
        }
        val format = readUInt16(bytes, offset)?.toInt()
            ?: return truncated("Item variation store header is truncated.", tag)
        if (format != 1) {
            return variationFailure(
                "font.variation.unsupported-store-format",
                "Item variation store format $format is not supported.",
                tag,
            )
        }
        val headerEnd = offset.toLong() + 8L
        val regionListOffset = readUInt32(bytes, offset + 2)?.toLong()
            ?: return truncated("Item variation store header is truncated.", tag)
        val itemDataCount = readUInt16(bytes, offset + 6)?.toInt()
            ?: return truncated("Item variation store header is truncated.", tag)
        if (itemDataCount > limits.maxItemData) {
            return variationLimitFailure("Item variation store declares $itemDataCount item data subtables.", tag)
        }
        val itemDataOffsetsEnd = headerEnd + itemDataCount.toLong() * 4L
        if (itemDataOffsetsEnd - offset > limits.maxSourceBytes.toLong()) {
            return variationLimitFailure("Item variation store spans ${itemDataOffsetsEnd - offset} bytes.", tag)
        }
        if (itemDataOffsetsEnd > tableSize) {
            return truncated("Item variation store offsets are truncated.", tag)
        }
        val itemDataOffsets = LongArray(itemDataCount) { index ->
            readUInt32(bytes, offset + 8 + index * 4)?.toLong()
                ?: return truncated("Item variation store offsets are truncated.", tag)
        }

        val regionListStart = offset.toLong() + regionListOffset
        val regionListHeaderEnd = regionListStart + 4L
        if (regionListHeaderEnd - offset > limits.maxSourceBytes.toLong()) {
            return variationLimitFailure("Item variation store spans ${regionListHeaderEnd - offset} bytes.", tag)
        }
        if (regionListHeaderEnd > tableSize) {
            return truncated("Item variation region list is truncated.", tag)
        }
        val regionList = regionListStart.toInt()
        val axisCount = readUInt16(bytes, regionList)?.toInt()
            ?: return truncated("Item variation region list is truncated.", tag)
        val regionCount = readUInt16(bytes, regionList + 2)?.toInt()
            ?: return truncated("Item variation region list is truncated.", tag)
        if (axisCount > limits.maxAxes) {
            return variationLimitFailure("Item variation store declares $axisCount axes.", tag)
        }
        if (regionCount > limits.maxRegions) {
            return variationLimitFailure("Item variation store declares $regionCount regions.", tag)
        }
        val regionsEnd = regionListHeaderEnd + regionCount.toLong() * axisCount.toLong() * 6L
        if (regionsEnd - offset > limits.maxSourceBytes.toLong()) {
            return variationLimitFailure("Item variation store spans ${regionsEnd - offset} bytes.", tag)
        }
        if (regionsEnd > tableSize) {
            return truncated("Item variation regions are truncated.", tag)
        }
        val starts = ArrayList<DoubleArray>(regionCount)
        val peaks = ArrayList<DoubleArray>(regionCount)
        val ends = ArrayList<DoubleArray>(regionCount)
        var cursor = regionListHeaderEnd.toInt()
        for (region in 0 until regionCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val start = DoubleArray(axisCount)
            val peak = DoubleArray(axisCount)
            val end = DoubleArray(axisCount)
            for (axis in 0 until axisCount) {
                start[axis] = readF2Dot14(bytes, cursor)
                    ?: return truncated("Item variation regions are truncated.", tag)
                cursor += 2
                peak[axis] = readF2Dot14(bytes, cursor)
                    ?: return truncated("Item variation regions are truncated.", tag)
                cursor += 2
                end[axis] = readF2Dot14(bytes, cursor)
                    ?: return truncated("Item variation regions are truncated.", tag)
                cursor += 2
            }
            starts.add(start)
            peaks.add(peak)
            ends.add(end)
        }

        val itemDataRegionIndexes = ArrayList<IntArray>(itemDataCount)
        for (index in 0 until itemDataCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val dataStart = offset.toLong() + itemDataOffsets[index]
            if (dataStart + 6L > tableSize) {
                return truncated("Item variation data is truncated.", tag)
            }
            val data = dataStart.toInt()
            val regionIndexCount = readUInt16(bytes, data + 4)?.toInt()
                ?: return truncated("Item variation data is truncated.", tag)
            if (regionIndexCount > limits.maxRegions) {
                return variationLimitFailure("Item variation data declares $regionIndexCount regions.", tag)
            }
            val dataEnd = dataStart + 6L + regionIndexCount.toLong() * 2L
            if (dataEnd - offset > limits.maxSourceBytes.toLong()) {
                return variationLimitFailure("Item variation store spans ${dataEnd - offset} bytes.", tag)
            }
            if (dataEnd > tableSize) {
                return truncated("Item variation data region indexes are truncated.", tag)
            }
            val indexes = IntArray(regionIndexCount)
            for (position in 0 until regionIndexCount) {
                val regionIndex = readUInt16(bytes, data + 6 + position * 2)?.toInt()
                    ?: return truncated("Item variation data region indexes are truncated.", tag)
                if (regionIndex !in 0 until regionCount) {
                    return variationFailure(
                        "font.variation.invalid-store",
                        "Item variation data references region index $regionIndex but the store declares $regionCount region(s).",
                        tag,
                    )
                }
                indexes[position] = regionIndex
            }
            itemDataRegionIndexes.add(indexes)
        }
        return FontOperationResult.Success(
            VariationStore(axisCount, regionCount, starts, peaks, ends, itemDataRegionIndexes),
        )
    }

    /**
     * Region scalars for item variation data [itemDataIndex] at [normalizedAxes].
     *
     * The returned array has one entry per region index the item variation data references, so its
     * length equals [VariationStore.regionCountAt]`(itemDataIndex)` and not the store's
     * [VariationStore.regionCount]. [normalizedAxes] is indexed in `fvar` axis order; a missing
     * coordinate is treated as zero and a coordinate outside a referenced region contributes `0.0`.
     * An absent [itemDataIndex] returns an empty array. Callers that evaluate repeatedly for the
     * same `(itemDataIndex, normalizedAxes)` should cache the result: a CFF2 charstring calls this
     * once per `blend` operator.
     */
    public fun scalars(
        store: VariationStore,
        itemDataIndex: Int,
        normalizedAxes: List<Double>,
    ): DoubleArray {
        val regionIndexCount = store.regionCountAt(itemDataIndex)
        val result = DoubleArray(regionIndexCount)
        for (position in 0 until regionIndexCount) {
            val region = store.regionIndex(itemDataIndex, position)
            var scalar = 1.0
            for (axis in 0 until store.axisCount) {
                val factor = regionAxisFactor(
                    start = store.regionStarts[region][axis],
                    peak = store.regionPeaks[region][axis],
                    end = store.regionEnds[region][axis],
                    coordinate = normalizedAxes.getOrElse(axis) { 0.0 },
                )
                if (factor == 0.0) {
                    scalar = 0.0
                    break
                }
                scalar *= factor
            }
            result[position] = scalar
        }
        return result
    }

    /**
     * Evaluates the factor one region contributes on one axis at [coordinate].
     *
     * [start]/[peak]/[end] are the region's normalized F2Dot14 bounds on the axis. The factor is
     * `1.0` for an invalid bound ordering (`start > peak || peak > end`), for a region that spans
     * zero (`start < 0.0 && end > 0.0` with `peak != 0.0`), and for a zero peak; it is `0.0` when
     * [coordinate] lies outside `[start, end]`, `1.0` exactly at [peak], and linear between
     * `start → peak` and `peak → end` otherwise. Factors of all axes are multiplied.
     */
    private fun regionAxisFactor(start: Double, peak: Double, end: Double, coordinate: Double): Double {
        if (start > peak || peak > end) return 1.0
        if (start < 0.0 && end > 0.0 && peak != 0.0) return 1.0
        if (peak == 0.0) return 1.0
        if (coordinate < start || coordinate > end) return 0.0
        if (coordinate == peak) return 1.0
        return if (coordinate < peak) (coordinate - start) / (peak - start) else (end - coordinate) / (end - peak)
    }

    private fun truncated(message: String, tag: String): FontOperationResult.Failure =
        variationFailure("font.variation.truncated-store", message, tag)

    /** Decodes one signed F2Dot14 value, or `null` if truncated. */
    private fun readF2Dot14(bytes: ByteArray, offset: Int): Double? =
        readInt16(bytes, offset)?.toDouble()?.div(16_384.0)
}
