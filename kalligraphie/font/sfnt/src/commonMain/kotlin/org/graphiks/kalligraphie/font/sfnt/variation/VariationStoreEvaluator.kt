@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
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
 * themselves live with the owning table (CFF2 keeps them inline in the charstring).
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
    internal val itemDataRegionIndexes: List<IntArray>,
) {
    /** Number of item variation data subtables. */
    public fun itemDataCount(): Int = itemDataRegionIndexes.size

    /** Region indexes referenced by item variation data [itemDataIndex], or `null` when absent. */
    public fun regionIndexes(itemDataIndex: Int): IntArray? = itemDataRegionIndexes.getOrNull(itemDataIndex)
}

/**
 * Reads and evaluates an OpenType ItemVariationStore (format 1).
 *
 * The store begins at [offset] with a 16-bit format field. [tag] is the owning table (`CFF2` today;
 * `HVAR`/`MVAR`/`COLR` later) and becomes the diagnostic location. A store whose format is not 1
 * fails with `font.variation.unsupported-store-format`, a truncated store fails with
 * `font.variation.truncated-store`, and a breach of [limits] reuses `font.resource-limit-exceeded`.
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
        if (offset < 0 || offset.toLong() + 8L > bytes.size.toLong()) {
            return variationFailure("font.variation.truncated-store", "Item variation store header is truncated.", tag)
        }
        val format = readUInt16(bytes, offset)
        if (format != 1) {
            return variationFailure(
                "font.variation.unsupported-store-format",
                "Item variation store format $format is not supported.",
                tag,
            )
        }
        val regionListOffset = readUInt32(bytes, offset + 2).toInt()
        val itemDataCount = readUInt16(bytes, offset + 6)
        if (itemDataCount > limits.maxItemData) {
            return variationLimitFailure("Item variation store declares $itemDataCount item data subtables.", tag)
        }
        if (offset.toLong() + 8L + itemDataCount.toLong() * 4L > bytes.size.toLong()) {
            return variationFailure("font.variation.truncated-store", "Item variation store offsets are truncated.", tag)
        }
        val itemDataOffsets = LongArray(itemDataCount) { readUInt32(bytes, offset + 8 + it * 4) }

        if (regionListOffset < 0 || offset.toLong() + regionListOffset.toLong() + 4L > bytes.size.toLong()) {
            return variationFailure("font.variation.truncated-store", "Item variation region list is truncated.", tag)
        }
        val regionList = offset + regionListOffset
        val axisCount = readUInt16(bytes, regionList)
        val regionCount = readUInt16(bytes, regionList + 2)
        if (axisCount > limits.maxAxes) {
            return variationLimitFailure("Item variation store declares $axisCount axes.", tag)
        }
        if (regionCount > limits.maxRegions) {
            return variationLimitFailure("Item variation store declares $regionCount regions.", tag)
        }
        val regionsStart = regionList.toLong() + 4L
        if (regionsStart + regionCount.toLong() * axisCount.toLong() * 6L > bytes.size.toLong()) {
            return variationFailure("font.variation.truncated-store", "Item variation regions are truncated.", tag)
        }
        var storeEnd = regionsStart + regionCount.toLong() * axisCount.toLong() * 6L
        val starts = ArrayList<DoubleArray>(regionCount)
        val peaks = ArrayList<DoubleArray>(regionCount)
        val ends = ArrayList<DoubleArray>(regionCount)
        var cursor = regionsStart.toInt()
        for (region in 0 until regionCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val start = DoubleArray(axisCount)
            val peak = DoubleArray(axisCount)
            val end = DoubleArray(axisCount)
            for (axis in 0 until axisCount) {
                start[axis] = readF2Dot14(bytes, cursor); cursor += 2
                peak[axis] = readF2Dot14(bytes, cursor); cursor += 2
                end[axis] = readF2Dot14(bytes, cursor); cursor += 2
            }
            starts.add(start); peaks.add(peak); ends.add(end)
        }

        val itemDataRegionIndexes = ArrayList<IntArray>(itemDataCount)
        for (index in 0 until itemDataCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val dataStart = offset.toLong() + itemDataOffsets[index]
            if (itemDataOffsets[index] < 0L || dataStart + 6L > bytes.size.toLong()) {
                return variationFailure("font.variation.truncated-store", "Item variation data is truncated.", tag)
            }
            val data = dataStart.toInt()
            val regionIndexCount = readUInt16(bytes, data + 4)
            if (dataStart + 6L + regionIndexCount.toLong() * 2L > bytes.size.toLong()) {
                return variationFailure(
                    "font.variation.truncated-store",
                    "Item variation data region indexes are truncated.",
                    tag,
                )
            }
            itemDataRegionIndexes.add(IntArray(regionIndexCount) { readUInt16(bytes, data + 6 + it * 2) })
            storeEnd = maxOf(storeEnd, dataStart + 6L + regionIndexCount.toLong() * 2L)
        }
        if (storeEnd - offset.toLong() > limits.maxSourceBytes.toLong()) {
            return variationLimitFailure("Item variation store spans ${storeEnd - offset} bytes.", tag)
        }
        return FontOperationResult.Success(
            VariationStore(axisCount, regionCount, starts, peaks, ends, itemDataRegionIndexes),
        )
    }

    /**
     * Region scalars for item variation data [itemDataIndex] at [normalizedAxes].
     *
     * [normalizedAxes] is indexed in `fvar` axis order; a missing coordinate is treated as zero.
     * Returns an empty array when [itemDataIndex] has no item variation data.
     */
    public fun scalars(
        store: VariationStore,
        itemDataIndex: Int,
        normalizedAxes: List<Double>,
    ): DoubleArray {
        val indexes = store.itemDataRegionIndexes.getOrNull(itemDataIndex) ?: return DoubleArray(0)
        val result = DoubleArray(indexes.size)
        for (position in indexes.indices) {
            val region = indexes[position]
            if (region < 0 || region >= store.regionCount) continue
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

    private fun regionAxisFactor(start: Double, peak: Double, end: Double, coordinate: Double): Double {
        if (start > peak || peak > end) return 1.0
        if (start < 0.0 && end > 0.0 && peak != 0.0) return 1.0
        if (peak == 0.0) return 1.0
        if (coordinate < start || coordinate > end) return 0.0
        if (coordinate == peak) return 1.0
        return if (coordinate < peak) (coordinate - start) / (peak - start) else (end - coordinate) / (end - peak)
    }

    private fun readF2Dot14(bytes: ByteArray, offset: Int): Double {
        val raw = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        return raw.toShort().toDouble() / 16_384.0
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
