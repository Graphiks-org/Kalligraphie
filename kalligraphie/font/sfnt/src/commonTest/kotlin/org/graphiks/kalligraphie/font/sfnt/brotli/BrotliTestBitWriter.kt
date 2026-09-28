@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * LSB-first bit writer that mirrors [BrotliBits], used to build meta-block headers and context
 * maps in tests.
 */
@KalligraphieInternalApi
internal class BrotliTestBitWriter {
    private val bytes = ArrayList<Byte>()
    private var current = 0
    private var bitCount = 0

    fun writeBit(bit: Int): BrotliTestBitWriter {
        current = current or ((bit and 1) shl bitCount)
        bitCount++
        if (bitCount == 8) flush()
        return this
    }

    /** Writes the low [count] bits of [value], least significant bit first. */
    fun writeBits(value: Int, count: Int): BrotliTestBitWriter {
        repeat(count) { writeBit(value ushr it) }
        return this
    }

    fun alignToByte(): BrotliTestBitWriter {
        while (bitCount != 0) writeBit(0)
        return this
    }

    fun toByteArray(): ByteArray {
        if (bitCount != 0) flush()
        return bytes.toByteArray()
    }

    private fun flush() {
        bytes.add(current.toByte())
        current = 0
        bitCount = 0
    }
}
