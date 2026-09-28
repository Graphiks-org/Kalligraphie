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

    fun alignToByte() {
        val remainder = bitPosition and 7
        if (remainder != 0) bitPosition += 8 - remainder
    }

    fun hasMore(): Boolean = (bitPosition ushr 3) < input.size
}
