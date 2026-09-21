@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32
import org.graphiks.kalligraphie.font.sfnt.variationFailure
import org.graphiks.kalligraphie.font.sfnt.variationLimitFailure

/**
 * Bounds applied while decoding the metric-variation tables (`HVAR`/`VVAR`/`MVAR`).
 *
 * This is an internal limits value in the same family as `GvarLimits`/`VariationStoreLimits`, not a
 * fingerprint-bearing profile field. `maxVariationStores` bounds the number of `ItemVariationStore`
 * structures one metric route may parse; every metric table carries exactly one store today, so the
 * field is a guard for the multi-store colour consumer and is checked before the store is parsed.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public data class MetricVariationLimits(
    /** Maximum accepted metric-variation table length in bytes. */
    public val maxSourceBytes: Int = 1_048_576,
    /** Maximum number of variation stores one metric route may parse. */
    public val maxVariationStores: Int = 4,
    /** Maximum accepted `MVAR` value records. */
    public val maxValueRecords: Int = 4_096,
    /** Maximum accepted `DeltaSetIndexMap` entries. */
    public val maxDeltaSetIndexEntries: Int = 1_000_000,
) {
    init {
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxVariationStores >= 0) { "maxVariationStores must not be negative." }
        require(maxValueRecords >= 0) { "maxValueRecords must not be negative." }
        require(maxDeltaSetIndexEntries >= 0) { "maxDeltaSetIndexEntries must not be negative." }
    }
}

/**
 * Parsed OpenType `DeltaSetIndexMap`.
 *
 * Each target index resolves to an `(outer, inner)` delta-set index. When the map declares fewer
 * entries than the number of targets, the last entry applies to every larger index, matching the
 * Common Table Formats specification. Both format 0 and format 1 headers are accepted.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class DeltaSetIndexMap internal constructor(
    private val outerIndexes: IntArray,
    private val innerIndexes: IntArray,
) {
    /** Number of entries in the map. */
    public val entryCount: Int get() = outerIndexes.size

    /** Delta-set outer index for [targetIndex]; the last entry is reused for larger indexes. */
    public fun outerIndex(targetIndex: Int): Int =
        if (entryCount == 0) 0 else outerIndexes[minOf(maxOf(targetIndex, 0), entryCount - 1)]

    /** Delta-set inner index for [targetIndex]; the last entry is reused for larger indexes. */
    public fun innerIndex(targetIndex: Int): Int =
        if (entryCount == 0) 0 else innerIndexes[minOf(maxOf(targetIndex, 0), entryCount - 1)]
}

/** Reads a `DeltaSetIndexMap` at [offset], or returns `null` when [offset] is zero. */
internal fun readDeltaSetIndexMap(
    bytes: ByteArray,
    offset: Int,
    tag: String,
    limits: MetricVariationLimits,
    cancellationToken: CancellationToken,
): FontOperationResult<DeltaSetIndexMap?> {
    if (offset == 0) return FontOperationResult.Success(null)
    if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
    if (offset < 0 || offset.toLong() + 4L > bytes.size.toLong()) {
        return variationFailure("font.variation.truncated-store", "$tag delta-set index map is truncated.", tag)
    }
    val format = bytes[offset].toInt() and 0xFF
    val entryFormat = bytes[offset + 1].toInt() and 0xFF
    val mapCount: Int
    val dataOffset: Int
    when (format) {
        0 -> {
            mapCount = readUInt16(bytes, offset + 2)?.toInt()
                ?: return variationFailure("font.variation.truncated-store", "$tag delta-set index map is truncated.", tag)
            dataOffset = offset + 4
        }

        1 -> {
            if (offset.toLong() + 6L > bytes.size.toLong()) {
                return variationFailure("font.variation.truncated-store", "$tag delta-set index map is truncated.", tag)
            }
            val count = readUInt32(bytes, offset + 2)?.toLong()
                ?: return variationFailure("font.variation.truncated-store", "$tag delta-set index map is truncated.", tag)
            if (count > limits.maxDeltaSetIndexEntries.toLong()) {
                return variationLimitFailure("$tag delta-set index map declares $count entries.", tag)
            }
            mapCount = count.toInt()
            dataOffset = offset + 6
        }

        else -> return variationFailure(
            "font.variation.invalid-store",
            "$tag delta-set index map format $format is unsupported.",
            tag,
        )
    }
    if (mapCount > limits.maxDeltaSetIndexEntries) {
        return variationLimitFailure("$tag delta-set index map declares $mapCount entries.", tag)
    }
    val entrySize = ((entryFormat and 0x30) shr 4) + 1
    val innerBitCount = (entryFormat and 0x0F) + 1
    val dataEnd = dataOffset.toLong() + mapCount.toLong() * entrySize.toLong()
    if (dataEnd > bytes.size.toLong()) {
        return variationFailure("font.variation.truncated-store", "$tag delta-set index map data is truncated.", tag)
    }
    val outer = IntArray(mapCount)
    val inner = IntArray(mapCount)
    val innerMask = (1 shl innerBitCount) - 1
    var cursor = dataOffset
    for (index in 0 until mapCount) {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        var entry = 0
        repeat(entrySize) {
            entry = (entry shl 8) or (bytes[cursor].toInt() and 0xFF)
            cursor += 1
        }
        outer[index] = entry ushr innerBitCount
        inner[index] = entry and innerMask
    }
    return FontOperationResult.Success(DeltaSetIndexMap(outer, inner))
}
