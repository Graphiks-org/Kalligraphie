@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.immutableListSnapshot

/** One piecewise-linear `avar` mapping point. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public data class AvarSegment(
    /** Normalized input coordinate. */
    public val from: Float,
    /** Normalized output coordinate. */
    public val to: Float,
)

/** Decoded `avar` version 1 segment maps, one list per axis in `fvar` order. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class AvarData(axisSegmentMaps: List<List<AvarSegment>>) {
    /** Segment maps in `fvar` axis order. */
    public val axisSegmentMaps: List<List<AvarSegment>> =
        axisSegmentMaps.map { it.immutableListSnapshot() }.immutableListSnapshot()
}

/**
 * Decodes the OpenType `avar` table version 1. Version 2 (variable segment maps) is rejected with
 * `font.variation.unsupported-avar-version` until a later sub-plan implements it.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object AvarReader {
    private const val HEADER_SIZE = 8

    /**
     * Parses one OpenType `avar` table version 1 with [limits].
     *
     * The declared `reserved` field must be zero and [table] must declare exactly
     * [expectedAxisCount] axes, matching the `fvar` axis count. Each axis carries a segment map that
     * is either empty (the axis is unmodified and maps identity) or contains at least three points
     * anchored at `-1.0`, `0.0`, and `1.0`, with strictly increasing `fromCoordinates` and
     * non-decreasing `toCoordinates`.
     *
     * Every offset is bounds-checked against [table] and the operation is all-or-nothing: a failure
     * publishes no decoded segment maps. Version 2 is not yet supported and is reported as a typed
     * unsupported-version failure.
     *
     * @param table exact bytes of the OpenType `avar` table.
     * @param expectedAxisCount axis count the decoded table must match, from `fvar`.
     * @param limits resource bounds enforced before decoding segment maps.
     * @param cancellationToken cooperative cancellation checked before each axis.
     * @return complete portable segment maps or a typed version, malformed-data, or limit failure.
     */
    public fun read(
        table: ByteArray,
        expectedAxisCount: Int,
        limits: VariationLimits = VariationLimits(),
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<AvarData> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (table.size > limits.maxSourceBytes) {
            return variationLimitFailure("avar table exceeds the source-byte limit.", "avar")
        }
        if (table.size < HEADER_SIZE) return invalid("avar header is truncated.")
        val major = readUInt16(table, 0)?.toInt() ?: return invalid("avar header is truncated.")
        val minor = readUInt16(table, 2)?.toInt() ?: return invalid("avar header is truncated.")
        if (major != 1 || minor != 0) {
            return variationFailure("font.variation.unsupported-avar-version", "Unsupported avar version $major.$minor.", "avar")
        }
        val reserved = readUInt16(table, 4)?.toInt() ?: return invalid("avar header is truncated.")
        if (reserved != 0) {
            return variationFailure("font.variation.invalid-avar", "avar reserved field must be zero.", "avar")
        }
        val axisCount = readUInt16(table, 6)?.toInt() ?: return invalid("avar header is truncated.")
        if (axisCount != expectedAxisCount) {
            return variationFailure("font.variation.invalid-avar", "avar axis count $axisCount does not match fvar $expectedAxisCount.", "avar")
        }
        var offset = HEADER_SIZE
        val maps = ArrayList<List<AvarSegment>>(axisCount)
        for (axis in 0 until axisCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val count = readUInt16(table, offset)?.toInt() ?: return invalid("avar segment map is truncated.")
            offset += 2
            if (count in 1..2) {
                return variationFailure("font.variation.invalid-avar", "avar segment map must be empty or contain at least three points.", "avar")
            }
            val segments = ArrayList<AvarSegment>(count)
            var previousFrom = Float.NEGATIVE_INFINITY
            var previousTo = Float.NEGATIVE_INFINITY
            var hasZeroAnchor = false
            for (index in 0 until count) {
                val from = readF2Dot14(table, offset) ?: return invalid("avar segment map is truncated.")
                val to = readF2Dot14(table, offset + 2) ?: return invalid("avar segment map is truncated.")
                offset += 4
                if (from <= previousFrom) {
                    return variationFailure("font.variation.invalid-avar", "avar fromCoordinates must increase.", "avar")
                }
                if (to < previousTo) {
                    return variationFailure("font.variation.invalid-avar", "avar toCoordinates must not decrease.", "avar")
                }
                previousFrom = from
                previousTo = to
                if (from == 0f) hasZeroAnchor = true
                segments += AvarSegment(from, to)
            }
            if (count > 0) {
                if (segments.first().from != -1f) {
                    return variationFailure("font.variation.invalid-avar", "avar segment map must start at -1.0.", "avar")
                }
                if (segments.last().from != 1f) {
                    return variationFailure("font.variation.invalid-avar", "avar segment map must end at 1.0.", "avar")
                }
                if (!hasZeroAnchor) {
                    return variationFailure("font.variation.invalid-avar", "avar segment map must define the 0.0 anchor.", "avar")
                }
            }
            maps += segments
        }
        return FontOperationResult.Success(AvarData(maps))
    }

    private fun invalid(message: String): FontOperationResult.Failure =
        variationFailure("font.variation.invalid-avar", message, "avar")

    private fun readF2Dot14(bytes: ByteArray, offset: Int): Float? {
        val raw = readInt16(bytes, offset) ?: return null
        return raw.toFloat() / 16_384f
    }
}
