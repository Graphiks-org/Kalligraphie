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
 * entries than the number of targets the last entry applies to every larger index, except that an
 * empty map is the implicit identity mapping (`outer = 0, inner = targetIndex`) rather than a
 * delta-set-zero lookup, mirroring HarfBuzz's `DeltaSetIndexMap::map`. Both format 0 and format 1
 * headers are accepted.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class DeltaSetIndexMap internal constructor(
    private val outerIndexes: IntArray,
    private val innerIndexes: IntArray,
) {
    /** Number of entries in the map; `0` denotes the implicit identity mapping. */
    public val entryCount: Int get() = outerIndexes.size

    /**
     * Delta-set outer index for [targetIndex].
     *
     * The last entry is reused for larger indexes; an empty map resolves every target to outer
     * index `0`.
     */
    public fun outerIndex(targetIndex: Int): Int =
        if (entryCount == 0) 0 else outerIndexes[minOf(maxOf(targetIndex, 0), entryCount - 1)]

    /**
     * Delta-set inner index for [targetIndex].
     *
     * The last entry is reused for larger indexes; an empty map resolves every target to its own
     * index (the implicit identity mapping).
     */
    public fun innerIndex(targetIndex: Int): Int =
        if (entryCount == 0) maxOf(targetIndex, 0) else innerIndexes[minOf(maxOf(targetIndex, 0), entryCount - 1)]
}

/**
 * Reads the delta-set index map referenced by the Offset32 at [offsetField] in [table].
 *
 * Returns `null` when the offset is absent (zero). An offset that does not fit the table fails with
 * [errorCode]; the map's own failures are produced by [readDeltaSetIndexMap]. Shared by the metric
 * tables (`HVAR`/`VVAR`) so both report the same diagnostics for the same bytes.
 *
 * @param table exact bytes of the owning metric-variation table.
 * @param offsetField byte offset of the Offset32 that points at the map.
 * @param tag four-character table tag used as the diagnostic location.
 * @param errorCode failure code for an offset that does not fit [table].
 * @param limits metric-variation bounds forwarded to [readDeltaSetIndexMap].
 * @param cancellationToken cooperative cancellation; a cancelled token yields a cancelled result.
 * @return the decoded map, `null` when absent, or a typed failure.
 */
internal fun readMetricMapping(
    table: ByteArray,
    offsetField: Int,
    tag: String,
    errorCode: String,
    limits: MetricVariationLimits,
    cancellationToken: CancellationToken,
): FontOperationResult<DeltaSetIndexMap?> {
    val mapOffset = readOffset32(table, offsetField)
        ?: return variationFailure(errorCode, "$tag delta-set index map offset is out of range.", tag)
    return readDeltaSetIndexMap(table, mapOffset, tag, limits, cancellationToken)
}

/**
 * Reads a big-endian 32-bit offset as a non-negative `Int`.
 *
 * Returns `null` when the field is truncated or its unsigned value exceeds `Int.MAX_VALUE`, so a
 * caller can treat an out-of-range offset as a malformed-table failure. Shared by the metric tables
 * (`HVAR`/`VVAR`) and their delta-set index map reader.
 *
 * @param bytes source buffer, which is not modified.
 * @param offset byte offset of the value.
 */
internal fun readOffset32(bytes: ByteArray, offset: Int): Int? {
    val value = readUInt32(bytes, offset)?.toLong() ?: return null
    return if (value > Int.MAX_VALUE.toLong()) null else value.toInt()
}

/**
 * Reads a `DeltaSetIndexMap` at [offset], or returns `null` when [offset] is zero.
 *
 * Shared by the metric tables (`HVAR`/`VVAR`) and, later, `COLR`. Both the format 0 and format 1
 * header layouts are accepted. A truncated header or entry run fails with
 * `font.variation.truncated-store`, an unsupported format with `font.variation.invalid-store`, a map
 * declaring more than [MetricVariationLimits.maxDeltaSetIndexEntries] entries with
 * `font.resource-limit-exceeded`, and a cancelled [cancellationToken] with a cancelled result.
 */
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
    val mapCountLong: Long
    val dataOffset: Int
    when (format) {
        0 -> {
            mapCountLong = readUInt16(bytes, offset + 2)?.toInt()?.toLong()
                ?: return variationFailure("font.variation.truncated-store", "$tag delta-set index map is truncated.", tag)
            dataOffset = offset + 4
        }

        1 -> {
            if (offset.toLong() + 6L > bytes.size.toLong()) {
                return variationFailure("font.variation.truncated-store", "$tag delta-set index map is truncated.", tag)
            }
            mapCountLong = readUInt32(bytes, offset + 2)?.toLong()
                ?: return variationFailure("font.variation.truncated-store", "$tag delta-set index map is truncated.", tag)
            dataOffset = offset + 6
        }

        else -> return variationFailure(
            "font.variation.invalid-store",
            "$tag delta-set index map format $format is unsupported.",
            tag,
        )
    }
    if (mapCountLong > limits.maxDeltaSetIndexEntries.toLong()) {
        return variationLimitFailure("$tag delta-set index map declares $mapCountLong entries.", tag)
    }
    if (mapCountLong > Int.MAX_VALUE.toLong()) {
        return variationLimitFailure("$tag delta-set index map declares $mapCountLong entries.", tag)
    }
    val mapCount = mapCountLong.toInt()
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

/**
 * Reads and validates the metric-variation table header shared by `HVAR`/`VVAR`/`MVAR`.
 *
 * Checks [cancellationToken] before any read, the [MetricVariationLimits.maxSourceBytes] bound, the
 * fixed [headerSize], and the `1.0` version field. [tag] is the four-character table tag used as the
 * diagnostic location; [unsupportedVersionCode] and [invalidCode] are the owning table's typed
 * failure codes. The store offset and the store itself are read by [readMetricVariationStore],
 * because their field width and placement differ per table (`MVAR` carries an Offset16 and parses
 * its store before the value records, which is table-specific).
 *
 * Both the major and minor version fields must be `1` and `0`: a non-zero minor version is rejected
 * with [unsupportedVersionCode], which is stricter than the specification's minor-version
 * forward-compatibility intent but matches the existing table readers.
 *
 * @param table exact bytes of the owning metric-variation table.
 * @param tag four-character table tag used as the diagnostic location.
 * @param headerSize fixed header extent in bytes that [table] must cover.
 * @param unsupportedVersionCode failure code for a version other than `1.0`.
 * @param invalidCode failure code for a malformed or truncated header.
 * @param limits metric-variation bounds; [MetricVariationLimits.maxSourceBytes] is enforced here.
 * @param cancellationToken cooperative cancellation checked before any read.
 * @return success, or a typed version or malformed-data failure.
 */
internal fun readMetricVariationVersion(
    table: ByteArray,
    tag: String,
    headerSize: Int,
    unsupportedVersionCode: String,
    invalidCode: String,
    limits: MetricVariationLimits,
    cancellationToken: CancellationToken,
): FontOperationResult<Unit> {
    if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
    if (table.size > limits.maxSourceBytes) {
        return variationLimitFailure("$tag table exceeds the source-byte limit.", tag)
    }
    if (table.size < headerSize) {
        return variationFailure(invalidCode, "$tag header is truncated.", tag)
    }
    val major = readUInt16(table, 0)?.toInt()
        ?: return variationFailure(invalidCode, "$tag header is truncated.", tag)
    val minor = readUInt16(table, 2)?.toInt()
        ?: return variationFailure(invalidCode, "$tag header is truncated.", tag)
    if (major != 1 || minor != 0) {
        return variationFailure(unsupportedVersionCode, "Unsupported $tag version $major.$minor.", tag)
    }
    return FontOperationResult.Success(Unit)
}

/**
 * Reads the format-1 item variation store shared by `HVAR`/`VVAR`/`MVAR`.
 *
 * Enforces [MetricVariationLimits.maxVariationStores], bounds-checks [storeOffset] against [table],
 * parses the store with deltas retained, and requires [expectedAxisCount] to equal the store's axis
 * count. Store diagnostics keep their own `font.variation.*-store` codes, and a cancelled
 * [cancellationToken] propagates as a cancelled result. [storeOffset] is read by the caller at the
 * owning table's field width (`Offset32` for `HVAR`/`VVAR`, `Offset16` for `MVAR`) because that
 * width is table-specific.
 *
 * @param table exact bytes of the owning metric-variation table.
 * @param tag four-character table tag used as the diagnostic location.
 * @param invalidCode failure code for an out-of-range offset or an axis-count mismatch.
 * @param storeOffset byte offset of the store within [table].
 * @param expectedAxisCount axis count that must equal the store axis count.
 * @param limits metric-variation bounds; [MetricVariationLimits.maxVariationStores] is enforced here.
 * @param storeLimits bounds forwarded to the embedded store parse.
 * @param cancellationToken cooperative cancellation checked before the store parse.
 * @return the parsed store, or a typed failure.
 */
internal fun readMetricVariationStore(
    table: ByteArray,
    tag: String,
    invalidCode: String,
    storeOffset: Int,
    expectedAxisCount: Int,
    limits: MetricVariationLimits,
    storeLimits: VariationStoreLimits,
    cancellationToken: CancellationToken,
): FontOperationResult<VariationStore> {
    if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
    if (limits.maxVariationStores < 1) {
        return variationLimitFailure("$tag requires a variation store but the limit forbids one.", tag)
    }
    if (storeOffset <= 0 || storeOffset >= table.size) {
        return variationFailure(invalidCode, "$tag item variation store offset is out of range.", tag)
    }
    val store = when (
        val result = VariationStoreEvaluator.read(
            table,
            storeOffset,
            tag,
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
        return variationFailure(invalidCode, "$tag axis count ${store.axisCount} does not match fvar $expectedAxisCount.", tag)
    }
    return FontOperationResult.Success(store)
}
