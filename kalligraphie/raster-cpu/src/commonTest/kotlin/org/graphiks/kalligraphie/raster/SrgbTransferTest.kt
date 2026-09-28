package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SrgbTransferTest {
    @Test
    fun theEndpointsAreExact() {
        assertEquals(0, SrgbTransfer.toLinear(0))
        assertEquals(65535, SrgbTransfer.toLinear(255))
        assertEquals(0, SrgbTransfer.toSrgb(0))
        assertEquals(255, SrgbTransfer.toSrgb(65535))
    }

    @Test
    fun theTransferIsMonotone() {
        for (channel in 1..255) {
            assertTrue(SrgbTransfer.toLinear(channel - 1) <= SrgbTransfer.toLinear(channel))
        }
        for (linear in 1..65535) {
            assertTrue(SrgbTransfer.toSrgb(linear - 1) <= SrgbTransfer.toSrgb(linear))
        }
    }

    @Test
    fun halfLinearIsBrighterThanHalfSrgb() {
        // sRGB encoding is non-linear: 50% linear light sits above the 0x80 sRGB literal.
        assertTrue(SrgbTransfer.toSrgb(32768) > 0x80)
    }
}
