package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class GoldenImageOrientationTest {
    @Test
    fun anImageDefaultsToImageOrientation() {
        assertEquals(GoldenOrientation.IMAGE, GoldenImage.alpha8(1, 1, byteArrayOf(7)).orientation)
        assertEquals(GoldenOrientation.IMAGE, GoldenImage.rgba8(1, 1, ByteArray(4)).orientation)
    }

    @Test
    fun aDeclaredDesignOrientationIsKept() {
        val image = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4), GoldenOrientation.DESIGN)
        assertEquals(GoldenOrientation.DESIGN, image.orientation)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), image.copyCanonicalBytes())
    }

    @Test
    fun orientationIsPartOfImageIdentity() {
        val pixels = byteArrayOf(1, 2, 3, 4)
        val imageOriented = GoldenImage.alpha8(2, 2, pixels)
        val designOriented = GoldenImage.alpha8(2, 2, pixels, GoldenOrientation.DESIGN)
        assertNotEquals(imageOriented, designOriented)
        assertNotEquals(imageOriented.hashCode(), designOriented.hashCode())
    }

    @Test
    fun anImageOrientedImageIsReturnedUnchanged() {
        val image = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        assertSame(image, image.toImageOrientation())
    }

    @Test
    fun aDesignOrientedAlphaImageIsReversedRowByRow() {
        val image = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4), GoldenOrientation.DESIGN)
        val upright = image.toImageOrientation()
        assertEquals(GoldenOrientation.IMAGE, upright.orientation)
        assertContentEquals(byteArrayOf(3, 4, 1, 2), upright.copyCanonicalBytes())
        assertEquals(2, upright.width)
        assertEquals(2, upright.height)
    }

    @Test
    fun aDesignOrientedColourImageKeepsEachRowWhole() {
        val image = GoldenImage.rgba8(
            width = 2,
            height = 2,
            pixels = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
            orientation = GoldenOrientation.DESIGN,
        )
        assertContentEquals(
            byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16, 1, 2, 3, 4, 5, 6, 7, 8),
            image.toImageOrientation().copyCanonicalBytes(),
        )
    }

    @Test
    fun anEmptyDesignOrientedImageStaysEmpty() {
        val upright = GoldenImage.alpha8(0, 0, ByteArray(0), GoldenOrientation.DESIGN).toImageOrientation()
        assertEquals(GoldenOrientation.IMAGE, upright.orientation)
        assertEquals(0, upright.copyCanonicalBytes().size)
    }

    @Test
    fun aSingleRowDesignOrientedImageIsItsOwnReversal() {
        val image = GoldenImage.alpha8(3, 1, byteArrayOf(1, 2, 3), GoldenOrientation.DESIGN)
        assertContentEquals(byteArrayOf(1, 2, 3), image.toImageOrientation().copyCanonicalBytes())
    }
}
