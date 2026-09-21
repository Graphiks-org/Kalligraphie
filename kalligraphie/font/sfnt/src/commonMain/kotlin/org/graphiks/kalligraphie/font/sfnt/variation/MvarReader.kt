@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.variationFailure
import org.graphiks.kalligraphie.font.sfnt.variationLimitFailure

/** `MVAR` value tags consumed by the portable font-metrics surface. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object MvarValueTags {
    /** `OS/2.sTypoAscender`. */
    public const val HORIZONTAL_ASCENDER: String = "hasc"

    /** `OS/2.sTypoDescender`. */
    public const val HORIZONTAL_DESCENDER: String = "hdsc"

    /** `OS/2.sTypoLineGap`. */
    public const val HORIZONTAL_LINE_GAP: String = "hlgp"

    /** `OS/2.sxHeight`. */
    public const val X_HEIGHT: String = "xhgt"

    /** `OS/2.sCapHeight`. */
    public const val CAP_HEIGHT: String = "cpht"

    /** `post.underlinePosition`. */
    public const val UNDERLINE_OFFSET: String = "undo"

    /** `post.underlineThickness`. */
    public const val UNDERLINE_SIZE: String = "unds"
}

/**
 * Decoded OpenType `MVAR` table.
 *
 * A value record identifies one font-wide target by its four-byte tag and one delta-set index. The
 * [delta] lookup returns `0.0` for a tag the table does not declare, so unsupported or
 * private-use tags are ignored rather than failed.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class MvarData internal constructor(
    private val store: VariationStore?,
    private val valueRecords: Map<String, IntArray>,
) {
    /** Number of value records declared by the table. */
    public val valueRecordCount: Int get() = valueRecords.size

    /** Whether [valueTag] has a value record. */
    public fun hasValueRecord(valueTag: String): Boolean = valueRecords.containsKey(valueTag)

    /** Interpolated delta for [valueTag] at [normalizedAxes], or `0.0` when the tag is absent. */
    public fun delta(valueTag: String, normalizedAxes: List<Double>): Double {
        val activeStore = store ?: return 0.0
        val record = valueRecords[valueTag] ?: return 0.0
        return VariationStoreEvaluator.delta(activeStore, record[0], record[1], normalizedAxes)
    }
}

/**
 * Decodes the OpenType `MVAR` table version 1.0.
 *
 * Every offset is bounds-checked against [table]; the operation is all-or-nothing. `axisCount` must
 * match the owning face's `fvar` axis count. A malformed header or value record fails with
 * `font.variation.invalid-mvar`, an unsupported version with
 * `font.variation.unsupported-mvar-version`, the embedded store keeps its own
 * `font.variation.*-store` codes, and a bounds breach reuses `font.resource-limit-exceeded`.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object MvarReader {
    private const val HEADER_SIZE = 12
    private const val VALUE_RECORD_SIZE = 8

    /**
     * Parses one `MVAR` table.
     *
     * @param table exact bytes of the OpenType `MVAR` table.
     * @param expectedAxisCount axis count that must equal the item variation store axis count.
     * @param limits metric-variation bounds enforced before allocating decoded records.
     * @param storeLimits bounds forwarded to the embedded item variation store parse.
     * @param cancellationToken cooperative cancellation checked before each value record and the store.
     * @return the decoded table or a typed version, malformed-data, or limit failure.
     */
    public fun read(
        table: ByteArray,
        expectedAxisCount: Int,
        limits: MetricVariationLimits = MetricVariationLimits(),
        storeLimits: VariationStoreLimits = VariationStoreLimits(),
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<MvarData> {
        when (
            val result = readMetricVariationVersion(
                table,
                "MVAR",
                HEADER_SIZE,
                "font.variation.unsupported-mvar-version",
                "font.variation.invalid-mvar",
                limits,
                cancellationToken,
            )
        ) {
            is FontOperationResult.Success -> Unit
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val valueRecordSize = readUInt16(table, 6)?.toInt() ?: return invalid("MVAR header is truncated.")
        val valueRecordCount = readUInt16(table, 8)?.toInt() ?: return invalid("MVAR header is truncated.")
        val storeOffset = readUInt16(table, 10)?.toInt() ?: return invalid("MVAR header is truncated.")
        if (valueRecordSize < VALUE_RECORD_SIZE) {
            return invalid("MVAR valueRecordSize $valueRecordSize is smaller than $VALUE_RECORD_SIZE.")
        }
        if (valueRecordCount > limits.maxValueRecords) {
            return variationLimitFailure("MVAR declares $valueRecordCount value records.", "MVAR")
        }
        val recordsEnd = HEADER_SIZE.toLong() + valueRecordCount.toLong() * valueRecordSize.toLong()
        if (recordsEnd > table.size.toLong()) return invalid("MVAR value records are truncated.")
        if (valueRecordCount == 0) {
            return FontOperationResult.Success(MvarData(null, emptyMap()))
        }
        val store = when (
            val result = readMetricVariationStore(
                table,
                "MVAR",
                "font.variation.invalid-mvar",
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
        val records = LinkedHashMap<String, IntArray>(valueRecordCount)
        var previousTag: String? = null
        for (index in 0 until valueRecordCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val recordOffset = HEADER_SIZE + index * valueRecordSize
            val tag = table.decodeAsciiTag(recordOffset)
            if (!isValidValueTag(tag)) {
                return invalid("MVAR value tag at index $index must contain four printable ASCII characters.")
            }
            if (previousTag != null && previousTag >= tag) {
                return invalid("MVAR value records must be strictly ordered by valueTag.")
            }
            previousTag = tag
            val outer = readUInt16(table, recordOffset + 4)?.toInt()
                ?: return invalid("MVAR value record is truncated.")
            val inner = readUInt16(table, recordOffset + 6)?.toInt()
                ?: return invalid("MVAR value record is truncated.")
            records[tag] = intArrayOf(outer, inner)
        }
        return FontOperationResult.Success(MvarData(store, records))
    }

    private fun isValidValueTag(tag: String): Boolean =
        tag.length == 4 && tag.all { character -> character.code in 0x20..0x7E }

    private fun invalid(message: String): FontOperationResult.Failure =
        variationFailure("font.variation.invalid-mvar", message, "MVAR")
}
