@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Immutable view of one CFF INDEX structure.
 *
 * Both CFF1 and CFF2 use the same INDEX layout: a big-endian `count`, an `offSize`
 * in `1..4`, `count + 1` offsets relative to the byte preceding the data, then the
 * item data. An empty INDEX occupies exactly the two count bytes.
 *
 * The backing array is treated as immutable by the caller; items are returned as
 * defensive copies so a consumer cannot mutate the decoded source. Offsets are
 * validated to be `1`-based, non-decreasing and in bounds before this value is
 * published, so an item access cannot read outside the source.
 *
 * @property itemCount number of items in this INDEX.
 * @property endOffset absolute offset immediately after this INDEX.
 */
internal class CffIndex private constructor(
    private val source: ByteArray,
    private val starts: IntArray,
    private val ends: IntArray,
    /** Absolute offset of the first data byte, or `endOffset - 1` for an empty INDEX. */
    val dataOffset: Int,
    val itemCount: Int,
    val endOffset: Int,
) {
    init {
        require(itemCount >= 0) { "CFF INDEX item count must be non-negative." }
        require(starts.size >= itemCount && ends.size >= itemCount) { "CFF INDEX bounds must cover every item." }
        require(endOffset >= dataOffset) { "CFF INDEX end offset must not precede its data." }
    }

    /** Absolute start offset of item [index] within the backing source. */
    fun itemStart(index: Int): Int {
        requireIndex(index)
        return starts[index]
    }

    /** Absolute end offset (exclusive) of item [index] within the backing source. */
    fun itemEnd(index: Int): Int {
        requireIndex(index)
        return ends[index]
    }

    /** Length in bytes of item [index]. */
    fun itemSize(index: Int): Int {
        requireIndex(index)
        return ends[index] - starts[index]
    }

    /** Defensive copy of item [index]. */
    fun item(index: Int): ByteArray {
        requireIndex(index)
        return source.copyOfRange(starts[index], ends[index])
    }

    private fun requireIndex(index: Int) {
        require(index in 0 until itemCount) { "CFF INDEX item index out of range: $index" }
    }

    companion object {
        private val location: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF ")

        /**
         * Reads one INDEX beginning at [offset] in [bytes].
         *
         * @return the parsed INDEX, or a typed [FontError.OutOfBounds] /
         * [FontError.InvalidFontData] failure for truncated or malformed data.
         */
        fun read(bytes: ByteArray, offset: Int, countSize: Int = 2): FontOperationResult<CffIndex> {
            if (countSize != 2 && countSize != 4) {
                return FontOperationResult.Failure(FontError.InvalidFontData("CFF INDEX count size must be 2 or 4.", location))
            }
            if (offset < 0 || offset + countSize > bytes.size) {
                return outOfBounds("CFF INDEX header is truncated.")
            }
            val count = if (countSize == 2) {
                ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            } else {
                val value = ((bytes[offset].toLong() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                    (bytes[offset + 3].toLong() and 0xFF)
                if (value > Int.MAX_VALUE) return outOfBounds("CFF INDEX count exceeds the supported range.")
                value.toInt()
            }
            if (count == 0) {
                return FontOperationResult.Success(
                    CffIndex(bytes, IntArray(0), IntArray(0), dataOffset = offset + countSize, itemCount = 0, endOffset = offset + countSize),
                )
            }
            val offSizeOffset = offset + countSize
            if (offSizeOffset >= bytes.size) {
                return outOfBounds("CFF INDEX offSize is truncated.")
            }
            val offSize = bytes[offSizeOffset].toInt() and 0xFF
            if (offSize !in 1..4) {
                return FontOperationResult.Failure(
                    FontError.InvalidFontData("CFF INDEX offSize must be between 1 and 4.", location),
                )
            }
            val offsetsStart = offSizeOffset + 1
            val offsetsLength = (count + 1) * offSize
            val dataOffset = offsetsStart + offsetsLength
            if (dataOffset > bytes.size) {
                return outOfBounds("CFF INDEX offsets exceed the source length.")
            }
            val raw = IntArray(count + 1)
            for (index in 0..count) {
                var value = 0
                val base = offsetsStart + index * offSize
                for (byteIndex in 0 until offSize) {
                    value = (value shl 8) or (bytes[base + byteIndex].toInt() and 0xFF)
                }
                raw[index] = value
            }
            if (raw[0] != 1) {
                return FontOperationResult.Failure(
                    FontError.InvalidFontData("CFF INDEX first offset must be 1.", location),
                )
            }
            for (index in 0 until count) {
                if (raw[index] > raw[index + 1]) {
                    return FontOperationResult.Failure(
                        FontError.InvalidFontData("CFF INDEX offsets must be non-decreasing.", location),
                    )
                }
            }
            val dataEnd = dataOffset + raw[count] - 1
            if (dataEnd > bytes.size) {
                return outOfBounds("CFF INDEX item data exceeds the source length.")
            }
            val starts = IntArray(count)
            val ends = IntArray(count)
            for (index in 0 until count) {
                starts[index] = dataOffset + raw[index] - 1
                ends[index] = dataOffset + raw[index + 1] - 1
            }
            return FontOperationResult.Success(
                CffIndex(bytes, starts, ends, dataOffset = dataOffset, itemCount = count, endOffset = dataEnd),
            )
        }

        private fun outOfBounds(message: String): FontOperationResult.Failure =
            FontOperationResult.Failure(
                FontError.OutOfBounds(
                    message = message,
                    location = location,
                ),
            )
    }
}
