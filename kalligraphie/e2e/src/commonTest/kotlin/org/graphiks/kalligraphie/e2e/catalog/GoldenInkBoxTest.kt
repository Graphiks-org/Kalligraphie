package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.graphiks.kalligraphie.e2e.GoldenImage

class GoldenInkBoxTest {
    @Test
    fun aFullyTransparentAlphaImageHasNoInkBox() {
        assertNull(GoldenInkBox.of(GoldenImage.alpha8(4, 3, ByteArray(12))))
    }

    @Test
    fun theInkBoxIsTightAroundNonZeroAlphaSamples() {
        // 5x4, single lit pixel at (2, 1).
        val pixels = ByteArray(20)
        pixels[1 * 5 + 2] = 0x40
        assertEquals(InkBox(minX = 2, minY = 1, maxX = 2, maxY = 1), GoldenInkBox.of(GoldenImage.alpha8(5, 4, pixels)))
    }

    @Test
    fun inkBoxDimensionsAreInclusive() {
        val box = InkBox(minX = 1, minY = 2, maxX = 4, maxY = 6)
        assertEquals(4, box.width)
        assertEquals(5, box.height)
    }

    @Test
    fun rgbaInkIsDecidedByTheAlphaChannelOnly() {
        // 2x1: first pixel has colour but no alpha, second has alpha.
        val pixels = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte())
        assertEquals(InkBox(minX = 1, minY = 0, maxX = 1, maxY = 0), GoldenInkBox.of(GoldenImage.rgba8(2, 1, pixels)))
    }

    @Test
    fun aSingleLitPixelInTheCornerIsFound() {
        val pixels = ByteArray(9)
        pixels[8] = 1
        assertEquals(InkBox(minX = 2, minY = 2, maxX = 2, maxY = 2), GoldenInkBox.of(GoldenImage.alpha8(3, 3, pixels)))
    }
}
