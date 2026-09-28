@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/** LSB-first bit reader over a byte array, the bit order RFC 7932 uses for integer fields. */
@KalligraphieInternalApi
internal class BrotliBits(private val input: ByteArray) {
    private var bitPosition = 0

    /** True once a read reached past the last byte; the caller must fail the stream. */
    var overran: Boolean = false
        private set

    fun readBit(): Int = readBits(1)

    fun readBits(count: Int): Int {
        var result = 0
        repeat(count) { shift ->
            val byteIndex = bitPosition ushr 3
            if (byteIndex >= input.size) {
                overran = true
            } else {
                result = result or (((input[byteIndex].toInt() ushr (bitPosition and 7)) and 1) shl shift)
            }
            bitPosition += 1
        }
        return result
    }

    /**
     * Advances to the next byte boundary and returns the bits that were skipped, as an integer
     * whose bit `i` is the `i`-th skipped bit.
     *
     * RFC 7932 §9.2 and §9.3 require those bits to be zero; a caller that discards the result
     * accepts a stream it must reject. Succeeding on a truncated input sets [overran].
     */
    fun alignToByte(): Int {
        val remainder = bitPosition and 7
        if (remainder == 0) return 0
        return readBits(8 - remainder)
    }

    fun hasMore(): Boolean = (bitPosition ushr 3) < input.size
}
