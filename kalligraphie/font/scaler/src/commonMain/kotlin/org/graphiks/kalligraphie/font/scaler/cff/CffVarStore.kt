@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Variation data required by a CFF2 charstring's `blend` and `vsindex` operators.
 *
 * Implementations expose the region count and the region scalars for one
 * variation-store index. Scalars are computed for the current instance; the
 * portable route only materializes the default instance (all axis coordinates
 * zero), so [scalars] returns default-instance factors.
 */
internal interface CffVariationSource {
    /** Number of regions in the item variation data at [vsIndex]. */
    fun regionCount(vsIndex: Int): Int

    /** Default-instance region scalars for [vsIndex]. */
    fun scalars(vsIndex: Int): DoubleArray
}

/**
 * Parsed CFF2 ItemVariationStore (format 1).
 *
 * Region scalars are evaluated at the default instance: each axis coordinate is
 * zero, so a region contributes a factor of one only when it spans zero.
 */
internal class CffVarStore private constructor(
    private val axisCount: Int,
    private val regionPeaks: List<DoubleArray>,
    private val regionStarts: List<DoubleArray>,
    private val regionEnds: List<DoubleArray>,
    private val itemDataRegionIndexes: List<IntArray>,
) : CffVariationSource {
    override fun regionCount(vsIndex: Int): Int = itemDataRegionIndexes.getOrNull(vsIndex)?.size ?: 0

    override fun scalars(vsIndex: Int): DoubleArray {
        val indexes = itemDataRegionIndexes.getOrNull(vsIndex) ?: return DoubleArray(0)
        return DoubleArray(indexes.size) { position ->
            val region = indexes[position]
            if (region < 0 || region >= regionPeaks.size) 0.0 else defaultScalar(region)
        }
    }

    private fun defaultScalar(region: Int): Double {
        var scalar = 1.0
        for (axis in 0 until axisCount) {
            val factor = axisFactor(regionStarts[region][axis], regionPeaks[region][axis], regionEnds[region][axis])
            if (factor == 0.0) return 0.0
            scalar *= factor
        }
        return scalar
    }

    private fun axisFactor(start: Double, peak: Double, end: Double): Double {
        if (peak == 0.0) return 1.0
        if (0.0 < start || 0.0 > end) return 0.0
        if (0.0 == peak) return 1.0
        return if (0.0 < peak) (0.0 - start) / (peak - start) else (end - 0.0) / (end - peak)
    }

    companion object {
        private val location: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF2")

        /** Reads a CFF2 VariationStore block beginning at [offset] in [bytes]. */
        fun read(bytes: ByteArray, offset: Int): FontOperationResult<CffVarStore> {
            if (offset < 0 || offset + 2 > bytes.size) return failure("CFF2 variation store is truncated.")
            // The CFF2 VariationStore block is a Card16 length followed by an
            // OpenType ItemVariationStore whose internal offsets are relative to
            // the ItemVariationStore start.
            val base = offset + 2
            if (base + 8 > bytes.size) return failure("CFF2 variation store is truncated.")
            val format = readUInt16(bytes, base)
            if (format != 1) return failure("CFF2 variation store format $format is not supported.")
            val regionListOffset = readUInt32(bytes, base + 2).toInt()
            val itemDataCount = readUInt16(bytes, base + 6)
            val itemDataOffsets = IntArray(itemDataCount) { readUInt32(bytes, base + 8 + it * 4).toInt() }
            if (itemDataCount == 0) return failure("CFF2 variation store has no item variation data.")

            val regionListStart = base + regionListOffset
            if (regionListStart < 0 || regionListStart + 4 > bytes.size) return failure("CFF2 region list is truncated.")
            val axisCount = readUInt16(bytes, regionListStart)
            val regionCount = readUInt16(bytes, regionListStart + 2)
            val regionsStart = regionListStart + 4
            val regionBytes = regionCount.toLong() * axisCount.toLong() * 6L
            if (regionsStart.toLong() + regionBytes > bytes.size.toLong()) return failure("CFF2 variation regions are truncated.")
            val starts = ArrayList<DoubleArray>(regionCount)
            val peaks = ArrayList<DoubleArray>(regionCount)
            val ends = ArrayList<DoubleArray>(regionCount)
            var cursor = regionsStart
            for (region in 0 until regionCount) {
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
                val dataStart = base + itemDataOffsets[index]
                if (dataStart < 0 || dataStart + 6 > bytes.size) return failure("CFF2 item variation data is truncated.")
                val regionIndexCount = readUInt16(bytes, dataStart + 4)
                val indexesStart = dataStart + 6
                if (indexesStart.toLong() + regionIndexCount.toLong() * 2L > bytes.size.toLong()) {
                    return failure("CFF2 item variation data region indexes are truncated.")
                }
                itemDataRegionIndexes.add(IntArray(regionIndexCount) { readUInt16(bytes, indexesStart + it * 2) })
            }
            return FontOperationResult.Success(
                CffVarStore(axisCount, peaks, starts, ends, itemDataRegionIndexes),
            )
        }

        private fun readF2Dot14(bytes: ByteArray, offset: Int): Double {
            val raw = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            return raw.toShort().toDouble() / 16384.0
        }

        private fun readUInt16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        private fun readUInt32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)

        private fun failure(message: String): FontOperationResult.Failure =
            FontOperationResult.Failure(FontError.InvalidFontData(message, location))
    }
}
