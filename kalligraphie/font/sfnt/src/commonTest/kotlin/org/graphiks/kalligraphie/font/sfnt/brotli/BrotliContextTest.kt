@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BrotliContextTest {
    @Test
    fun lsb6AndMsb6ReadThePreviousByte() {
        assertEquals(0x2B, BrotliContext.literalContextId(BrotliContext.MODE_LSB6, 0xAB, 0x00))
        assertEquals(0x2A, BrotliContext.literalContextId(BrotliContext.MODE_MSB6, 0xAB, 0x00))
    }

    @Test
    fun utf8AndSignedUseBothPreviousBytes() {
        // 'a' = 0x61, 'b' = 0x62; Lut0[0x61] = 56, Lut1[0x62] = 3, Lut2[0x61] = Lut2[0x62] = 3.
        assertEquals(56 or 3, BrotliContext.literalContextId(BrotliContext.MODE_UTF8, 0x61, 0x62))
        assertEquals((3 shl 3) or 3, BrotliContext.literalContextId(BrotliContext.MODE_SIGNED, 0x61, 0x62))
    }

    /** Recovering each table through the public lookup and checking the RFC's CRC-32 pins them. */
    @Test
    fun theLookupTablesMatchTheRfcCrc32Checks() {
        val lut0 = IntArray(256) { BrotliContext.literalContextId(BrotliContext.MODE_UTF8, it, 0) }
        val lut1 = IntArray(256) { BrotliContext.literalContextId(BrotliContext.MODE_UTF8, 0, it) }
        val lut2 = IntArray(256) { BrotliContext.literalContextId(BrotliContext.MODE_SIGNED, it, 0) ushr 3 }
        assertEquals(0x8e91efb7L, crc32AsLong(lut0))
        assertEquals(0xd01a32f4L, crc32AsLong(lut1))
        assertEquals(0x0dd7a0d6L, crc32AsLong(lut2))
    }

    @Test
    fun distanceContextFollowsTheCopyLength() {
        assertEquals(0, BrotliContext.distanceContextId(2))
        assertEquals(1, BrotliContext.distanceContextId(3))
        assertEquals(2, BrotliContext.distanceContextId(4))
        assertEquals(3, BrotliContext.distanceContextId(5))
        assertEquals(3, BrotliContext.distanceContextId(1_000))
    }

    @Test
    fun inverseMoveToFrontReplaysTheListUpdates() {
        val values = intArrayOf(2, 0, 1)
        BrotliContext.inverseMoveToFront(values)
        assertContentEquals(intArrayOf(2, 2, 0), values)
    }

    @Test
    fun contextMapExpandsZeroRuns() {
        val writer = BrotliTestBitWriter()
        writer.writeBit(1)            // RLEMAX present
        writer.writeBits(0, 4)        // RLEMAX = 1
        // A 3-symbol simple code assigns length 1 to the first symbol and 2 to the others, so the
        // canonical codes are 0="0", 1="10", 2="11".
        writer.writeBits(1, 2)        // simple
        writer.writeBits(2, 2)        // NSYM - 1 = 2
        writer.writeBits(0, 2)        // symbols[0] = 0
        writer.writeBits(1, 2)        // symbols[1] = 1
        writer.writeBits(2, 2)        // symbols[2] = 2
        writer.writeBit(0)            // symbol 0: one zero
        writer.writeBits(0b01, 2)     // symbol 1: zero run, code "10"
        writer.writeBit(0)            // symbol 1's extra bit: repeat 2
        writer.writeBits(0b11, 2)     // symbol 2: value 1, code "11"
        writer.writeBit(0)            // no inverse move-to-front

        val map = BrotliContext.readContextMap(BrotliBits(writer.toByteArray()), size = 4, numTrees = 2)
        assertContentEquals(intArrayOf(0, 0, 0, 1), map)
    }

    @Test
    fun contextMapAppliesInverseMoveToFront() {
        val writer = BrotliTestBitWriter()
        writer.writeBit(0)            // RLEMAX = 0
        writer.writeBits(1, 2)        // simple
        writer.writeBits(2, 2)        // NSYM - 1 = 2
        writer.writeBits(0, 2)        // symbols[0] = 0
        writer.writeBits(1, 2)        // symbols[1] = 1
        writer.writeBits(2, 2)        // symbols[2] = 2
        writer.writeBits(0b11, 2)     // value 2, code "11"
        writer.writeBit(0)            // value 0, code "0"
        writer.writeBits(0b01, 2)     // value 1, code "10"
        writer.writeBit(1)            // inverse move-to-front

        val map = BrotliContext.readContextMap(BrotliBits(writer.toByteArray()), size = 3, numTrees = 3)
        assertContentEquals(intArrayOf(2, 2, 0), map)
    }

    private fun crc32AsLong(values: IntArray): Long {
        var crc = 0xffffffffu
        for (value in values) {
            var c = (crc xor value.toUInt()) and 0xffu
            repeat(8) { c = if (c and 1u != 0u) 0xedb88320u xor (c shr 1) else c shr 1 }
            crc = c xor (crc shr 8)
        }
        return (crc xor 0xffffffffu).toLong() and 0xffffffffL
    }
}
