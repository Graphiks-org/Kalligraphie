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
    /** Decodes [table], requiring exactly [expectedAxisCount] axes. */
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
        if (table.size < 8) return invalid("avar header is truncated.")
        val major = readUInt16(table, 0)?.toInt() ?: return invalid("avar header is truncated.")
        val minor = readUInt16(table, 2)?.toInt() ?: return invalid("avar header is truncated.")
        if (major != 1 || minor != 0) {
            return variationFailure("font.variation.unsupported-avar-version", "Unsupported avar version $major.$minor.", "avar")
        }
        val axisCount = readUInt16(table, 6)?.toInt() ?: return invalid("avar header is truncated.")
        if (axisCount != expectedAxisCount) {
            return variationFailure("font.variation.invalid-avar", "avar axis count $axisCount does not match fvar $expectedAxisCount.", "avar")
        }
        var offset = 8
        val maps = ArrayList<List<AvarSegment>>(axisCount)
        for (axis in 0 until axisCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val count = readUInt16(table, offset)?.toInt() ?: return invalid("avar segment map is truncated.")
            offset += 2
            if (count < 3) return variationFailure("font.variation.invalid-avar", "avar segment map is too small.", "avar")
            val segments = ArrayList<AvarSegment>(count)
            var previousFrom = Float.NEGATIVE_INFINITY
            for (index in 0 until count) {
                val from = readF2Dot14(table, offset) ?: return invalid("avar segment map is truncated.")
                val to = readF2Dot14(table, offset + 2) ?: return invalid("avar segment map is truncated.")
                offset += 4
                if (from <= previousFrom) {
                    return variationFailure("font.variation.invalid-avar", "avar fromCoordinates must increase.", "avar")
                }
                previousFrom = from
                segments += AvarSegment(from, to)
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
