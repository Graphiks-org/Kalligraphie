@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * Literal and distance context modelling of the Brotli format (RFC 7932 §7).
 *
 * A literal's context ID selects one of the literal prefix codes for the current block type. It is
 * derived from the **previous two** decoded bytes, [previousByte] and [secondPreviousByte], never
 * from the byte being decoded, and the block type's context mode. A distance's context ID is
 * derived from the copy length of the command it belongs to. The map from `<block type, context
 * ID>` to a prefix-code index is itself compressed with run-length coding and an optional inverse
 * move-to-front transform.
 */
@KalligraphieInternalApi
internal object BrotliContext {
    /** Context ID is the six least significant bits of the previous byte. */
    const val MODE_LSB6: Int = 0

    /** Context ID is the six most significant bits of the previous byte. */
    const val MODE_MSB6: Int = 1

    /** Context ID is a text-oriented function of the previous two bytes. */
    const val MODE_UTF8: Int = 2

    /** Context ID is a function of the previous two bytes tuned for signed integers. */
    const val MODE_SIGNED: Int = 3

    /** Number of literal context IDs (RFC 7932 §7.1). */
    const val LITERAL_CONTEXT_COUNT: Int = 64

    /** Number of distance context IDs (RFC 7932 §7.2). */
    const val DISTANCE_CONTEXT_COUNT: Int = 4

    /**
     * Context ID of the next literal (RFC 7932 §7.1).
     *
     * [previousByte] is the most recently decoded byte and [secondPreviousByte] the one before it;
     * both are in `0..255` and are zero at the start of the stream.
     *
     * @throws IllegalArgumentException if [contextMode] is not one of the four modes.
     */
    fun literalContextId(contextMode: Int, previousByte: Int, secondPreviousByte: Int): Int {
        require(previousByte in 0..255 && secondPreviousByte in 0..255) {
            "Brotli literal context bytes must be in 0..255."
        }
        return when (contextMode) {
            MODE_LSB6 -> previousByte and 0x3f
            MODE_MSB6 -> previousByte ushr 2
            MODE_UTF8 -> LUT0[previousByte] or LUT1[secondPreviousByte]
            MODE_SIGNED -> (LUT2[previousByte] shl 3) or LUT2[secondPreviousByte]
            else -> throw IllegalArgumentException("Brotli context mode $contextMode is invalid.")
        }
    }

    /** Context ID of a distance code, from the copy length of its command (RFC 7932 §7.2). */
    fun distanceContextId(copyLength: Int): Int = when {
        copyLength < 2 -> throw IllegalArgumentException("Brotli copy length $copyLength is too short.")
        copyLength == 2 -> 0
        copyLength == 3 -> 1
        copyLength == 4 -> 2
        else -> 3
    }

    /**
     * Reads one literal or distance context map (RFC 7932 §7.3).
     *
     * [size] is `64 * NBLTYPESL` for the literal map and `4 * NBLTYPESD` for the distance map;
     * [numTrees] is the already-read `NTREESL` or `NTREESD`. When [numTrees] is one the map is all
     * zeros and no bits are consumed; otherwise a run-length prefix code is read and the map is
     * expanded, then optionally passed through the inverse move-to-front transform.
     *
     * @throws IllegalArgumentException if a zero run would overflow the map, or if the encoded
     * prefix code description is malformed.
     */
    fun readContextMap(bits: BrotliBits, size: Int, numTrees: Int): IntArray {
        require(size >= 0) { "Brotli context map size $size must be non-negative." }
        require(numTrees >= 1) { "Brotli context map needs at least one tree." }
        val map = IntArray(size)
        if (numTrees == 1) return map

        val runLengthPrefixMax = if (bits.readBit() == 0) 0 else bits.readBits(4) + 1
        val runLengthCode = BrotliHuffmanReader.read(bits, numTrees + runLengthPrefixMax)
        var index = 0
        while (index < size) {
            val symbol = runLengthCode.readCode(bits)
            when {
                symbol == 0 -> map[index++] = 0
                symbol <= runLengthPrefixMax -> {
                    val repeat = (1 shl symbol) + bits.readBits(symbol)
                    if (index + repeat > size) {
                        throw IllegalArgumentException("Brotli context-map zero run overflows the map.")
                    }
                    repeat(repeat) { map[index++] = 0 }
                }
                else -> map[index++] = symbol - runLengthPrefixMax
            }
        }
        if (bits.readBit() == 1) inverseMoveToFront(map)
        return map
    }

    /**
     * Applies the inverse move-to-front transform in place (RFC 7932 §7.3).
     *
     * Values must be in `0..255`; the transform maps each value back to a symbol of the freshly
     * initialised move-to-front list.
     */
    fun inverseMoveToFront(values: IntArray) {
        val moveToFront = IntArray(256) { it }
        for (i in values.indices) {
            val index = values[i]
            require(index in 0..255) { "Brotli move-to-front index $index is out of range." }
            val value = moveToFront[index]
            values[i] = value
            var shift = index
            while (shift > 0) {
                moveToFront[shift] = moveToFront[shift - 1]
                shift--
            }
            moveToFront[0] = value
        }
    }

    /** `Lut0` of RFC 7932 §7.1, whose bytes have CRC-32 `0x8e91efb7`. */
    private val LUT0 = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 4, 0, 0, 4, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        8, 12, 16, 12, 12, 20, 12, 16, 24, 28, 12, 12, 32, 12, 36, 12,
        44, 44, 44, 44, 44, 44, 44, 44, 44, 44, 32, 32, 24, 40, 28, 12,
        12, 48, 52, 52, 52, 48, 52, 52, 52, 48, 52, 52, 52, 52, 52, 48,
        52, 52, 52, 52, 52, 48, 52, 52, 52, 52, 52, 24, 12, 28, 12, 12,
        12, 56, 60, 60, 60, 56, 60, 60, 60, 56, 60, 60, 60, 60, 60, 56,
        60, 60, 60, 60, 60, 56, 60, 60, 60, 60, 60, 24, 12, 28, 12, 0,
        0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1,
        0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1,
        0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1,
        0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1,
        2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3,
        2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3,
        2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3,
        2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3,
    )

    /** `Lut1` of RFC 7932 §7.1, whose bytes have CRC-32 `0xd01a32f4`. */
    private val LUT1 = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1,
        1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1,
        1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 1, 1, 1, 1, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    )

    /** `Lut2` of RFC 7932 §7.1, whose bytes have CRC-32 `0x0dd7a0d6`. */
    private val LUT2 = intArrayOf(
        0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7,
    )
}
