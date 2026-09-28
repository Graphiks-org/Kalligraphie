@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.readUInt32

/**
 * One decoded table destined for a reassembled SFNT.
 *
 * @property tag four-character SFNT table tag.
 * @property data decoded table bytes, unpadded.
 * @property originalChecksum checksum carried by a WOFF 1.0 directory, or `null` when it must be
 * recomputed. For `head`, a supplied value must have been computed with `checkSumAdjustment`
 * zeroed, per the WOFF 1.0 specification.
 */
internal class SfntTable(
    val tag: String,
    val data: ByteArray,
    val originalChecksum: UInt? = null,
)

/**
 * Builds a standalone SFNT font from decoded tables.
 *
 * The directory is written tag-sorted with OpenType `searchRange`, `entrySelector` and
 * `rangeShift` values, each table is 4-byte padded, and the checksum sequencing that the SFNT
 * specification requires for `head.checkSumAdjustment` is honoured: the field is zeroed before
 * the `head` checksum is computed, the directory records it, and only afterwards is the
 * adjustment patched into the table. Duplicate tags are the caller's responsibility.
 */
internal object SfntReassembler {
    private const val HEADER_BYTES: Int = 12
    private const val DIRECTORY_RECORD_BYTES: Int = 16
    private const val CHECKSUM_MAGIC: UInt = 0xB1B0AFBAu
    private const val HEAD_TAG: String = "head"
    private const val HEAD_ADJUSTMENT_OFFSET: Int = 8

    /**
     * Reassembles [tables] into an SFNT whose version is [flavor].
     *
     * @return the completed font, or [FontError.ResourceLimitExceeded] when the combined size
     * exceeds [maxAssembledBytes]. The combined size is checked before any allocation.
     */
    fun assemble(flavor: UInt, tables: List<SfntTable>, maxAssembledBytes: Long): FontOperationResult<ByteArray> {
        val sorted = tables.sortedBy { it.tag }
        val directoryBytes = HEADER_BYTES.toLong() + DIRECTORY_RECORD_BYTES.toLong() * sorted.size
        var combinedBytes = directoryBytes
        for (table in sorted) {
            combinedBytes += align4(table.data.size.toLong())
        }
        if (combinedBytes > maxAssembledBytes || combinedBytes > Int.MAX_VALUE.toLong()) {
            return FontOperationResult.Failure(
                FontError.ResourceLimitExceeded(
                    "The reassembled SFNT would be $combinedBytes bytes, over the $maxAssembledBytes-byte limit.",
                    FontDiagnosticLocation.Source,
                ),
            )
        }

        val out = ByteArray(combinedBytes.toInt())
        writeUInt32(out, 0, flavor)
        writeUInt16(out, 4, sorted.size)
        val searchParameters = searchParameters(sorted.size)
        writeUInt16(out, 6, searchParameters.searchRange)
        writeUInt16(out, 8, searchParameters.entrySelector)
        writeUInt16(out, 10, searchParameters.rangeShift)

        val offsets = IntArray(sorted.size)
        var cursor = directoryBytes.toInt()
        for (index in sorted.indices) {
            offsets[index] = cursor
            cursor += align4(sorted[index].data.size.toLong()).toInt()
        }

        for ((index, table) in sorted.withIndex()) {
            val recordOffset = HEADER_BYTES + DIRECTORY_RECORD_BYTES * index
            val tableBytes = zeroHeadAdjustment(table)
            writeTag(out, recordOffset, table.tag)
            writeUInt32(out, recordOffset + 4, table.originalChecksum ?: tableChecksum(tableBytes))
            writeUInt32(out, recordOffset + 8, offsets[index].toUInt())
            writeUInt32(out, recordOffset + 12, table.data.size.toUInt())
            tableBytes.copyInto(out, offsets[index])
        }

        val headIndex = sorted.indexOfFirst { it.tag == HEAD_TAG && it.data.size >= HEAD_ADJUSTMENT_OFFSET + 4 }
        if (headIndex >= 0) {
            val adjustment = CHECKSUM_MAGIC - wholeFontChecksum(out)
            writeUInt32(out, offsets[headIndex] + HEAD_ADJUSTMENT_OFFSET, adjustment)
        }
        return FontOperationResult.Success(out)
    }

    /**
     * Sums [bytes] as big-endian 32-bit words, zero-padding a trailing partial word.
     */
    fun tableChecksum(bytes: ByteArray): UInt {
        var sum = 0u
        var offset = 0
        while (offset + 4 <= bytes.size) {
            sum += readUInt32(bytes, offset)!!
            offset += 4
        }
        if (offset < bytes.size) {
            var word = 0u
            while (offset < bytes.size) {
                word = (word shl 8) or (bytes[offset].toUInt() and 0xFFu)
                offset++
            }
            sum += word shl (8 * (4 - bytes.size % 4))
        }
        return sum
    }

    /**
     * Sums the finished [font] as big-endian 32-bit words, zero-padding a trailing partial word.
     */
    fun wholeFontChecksum(font: ByteArray): UInt = tableChecksum(font)

    private fun zeroHeadAdjustment(table: SfntTable): ByteArray {
        if (table.tag != HEAD_TAG || table.data.size < HEAD_ADJUSTMENT_OFFSET + 4) return table.data
        return table.data.copyOf().also {
            for (index in HEAD_ADJUSTMENT_OFFSET until HEAD_ADJUSTMENT_OFFSET + 4) {
                it[index] = 0
            }
        }
    }

    private fun searchParameters(numTables: Int): SearchParameters {
        if (numTables <= 0) return SearchParameters(0, 0, 0)
        var entrySelector = 0
        while ((1 shl (entrySelector + 1)) <= numTables) entrySelector++
        val searchRange = (1 shl entrySelector) * DIRECTORY_RECORD_BYTES
        return SearchParameters(searchRange, entrySelector, numTables * DIRECTORY_RECORD_BYTES - searchRange)
    }

    private fun align4(value: Long): Long = (value + 3L) / 4L * 4L

    private fun writeUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8 and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt32(target: ByteArray, offset: Int, value: UInt) {
        target[offset] = (value shr 24 and 0xFFu).toByte()
        target[offset + 1] = (value shr 16 and 0xFFu).toByte()
        target[offset + 2] = (value shr 8 and 0xFFu).toByte()
        target[offset + 3] = (value and 0xFFu).toByte()
    }

    private fun writeTag(target: ByteArray, offset: Int, tag: String) {
        for (index in 0 until 4) {
            target[offset + index] = if (index < tag.length) tag[index].code.toByte() else 0
        }
    }

    private class SearchParameters(val searchRange: Int, val entrySelector: Int, val rangeShift: Int)
}
