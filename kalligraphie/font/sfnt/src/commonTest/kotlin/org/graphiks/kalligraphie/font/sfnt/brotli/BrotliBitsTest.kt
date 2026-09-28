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
}
