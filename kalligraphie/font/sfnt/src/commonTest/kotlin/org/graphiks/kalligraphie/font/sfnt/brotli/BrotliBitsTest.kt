@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliBitsTest {
    @Test
    fun readsLeastSignificantBitFirst() {
        val bits = BrotliBits(byteArrayOf(0b10110001.toByte()))
        assertEquals(0b0001, bits.readBits(4))
        assertEquals(0b1011, bits.readBits(4))
    }

    @Test
    fun readsAcrossByteBoundaries() {
        assertEquals(0x0201, BrotliBits(byteArrayOf(0x01, 0x02)).readBits(16))
    }

    @Test
    fun overrunReturnsZerosAndFlags() {
        val bits = BrotliBits(byteArrayOf(0x00))
        assertEquals(0, bits.readBits(20))
        assertEquals(true, bits.overran)
    }

    @Test
    fun alignToByteReturnsTheSkippedBits() {
        // byte 0b10111001 read least significant bit first: 1,0,0,1,1,1,0,1
        val bits = BrotliBits(byteArrayOf(0b10111001.toByte()))
        assertEquals(0b001, bits.readBits(3))
        // the five skipped bits are 1,1,1,0,1 => 0b10111
        assertEquals(0b10111, bits.alignToByte())
        assertEquals(false, bits.hasMore())
    }

    @Test
    fun alignToByteAtAByteBoundaryConsumesNothing() {
        val bits = BrotliBits(byteArrayOf(0x01, 0x02))
        assertEquals(0x01, bits.readBits(8))
        assertEquals(0, bits.alignToByte())
        assertEquals(0x02, bits.readBits(8))
    }
}
