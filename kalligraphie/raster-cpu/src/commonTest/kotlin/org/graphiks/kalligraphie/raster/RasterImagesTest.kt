package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class RasterImagesTest {
    @Test
    fun a8ImageReadsSamplesAndCopiesDefensively() {
        val source = byteArrayOf(1, 2, 3, 4)
        val image = A8Image(width = 2, height = 2, left = -1, top = 3, pixels = source)
        source[0] = 99
        assertEquals(1, image[0, 0])
        assertEquals(2, image[1, 0])
        assertEquals(3, image[0, 1])
        assertEquals(4, image[1, 1])
        assertEquals(-1, image.left)
        assertEquals(3, image.top)

        val copy = image.copyPixels()
        copy[0] = 42
        assertEquals(1, image[0, 0])
    }

    @Test
    fun a8ImageReadsAreBoundsChecked() {
        val image = A8Image(width = 2, height = 2, left = 0, top = 0, pixels = byteArrayOf(1, 2, 3, 4))
        assertFailsWith<IllegalArgumentException> { image[-1, 0] }
        assertFailsWith<IllegalArgumentException> { image[0, image.height] }
        assertFailsWith<IllegalArgumentException> { image[image.width, 0] }
    }

    @Test
    fun a8ImageRejectsMismatchedSize() {
        assertFailsWith<IllegalArgumentException> { A8Image(2, 2, 0, 0, byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { A8Image(-1, 0, 0, 0, ByteArray(0)) }
    }

    @Test
    fun a8ImageRejectsOverflowingDimensions() {
        assertFailsWith<IllegalArgumentException> { A8Image(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0, ByteArray(0)) }
    }

    @Test
    fun a8EqualityUsesContent() {
        assertEquals(A8Image(1, 1, 0, 0, byteArrayOf(7)), A8Image(1, 1, 0, 0, byteArrayOf(7)))
        assertNotEquals(A8Image(1, 1, 0, 0, byteArrayOf(7)), A8Image(1, 1, 0, 0, byteArrayOf(8)))
    }

    @Test
    fun a8EqualityIncludesBearings() {
        val image = A8Image(1, 1, 0, 0, byteArrayOf(7))
        assertNotEquals(image, A8Image(1, 1, 1, 0, byteArrayOf(7)))
        assertNotEquals(image, A8Image(1, 1, 0, 1, byteArrayOf(7)))
    }

    @Test
    fun rgbaImagePacksNonPremultipliedArgb() {
        val pixels = byteArrayOf(
            10, 20, 30, 40,
            50, 60, 70, 80,
        )
        val image = Rgba8Image(width = 2, height = 1, left = 0, top = 0, pixels = pixels)
        assertEquals(0x280A141E, image[0, 0])
        assertEquals(0x50323C46, image[1, 0])
    }

    @Test
    fun rgbaImageCopiesDefensively() {
        val source = byteArrayOf(10, 20, 30, 40)
        val image = Rgba8Image(width = 1, height = 1, left = 0, top = 0, pixels = source)
        source[0] = 99
        val copy = image.copyPixels()
        copy[1] = 99
        assertEquals(0x280A141E, image[0, 0])
    }

    @Test
    fun rgbaImageReadsAreBoundsChecked() {
        val image = Rgba8Image(width = 1, height = 1, left = 0, top = 0, pixels = byteArrayOf(10, 20, 30, 40))
        assertFailsWith<IllegalArgumentException> { image[-1, 0] }
        assertFailsWith<IllegalArgumentException> { image[0, image.height] }
        assertFailsWith<IllegalArgumentException> { image[image.width, 0] }
    }

    @Test
    fun rgbaImageRejectsMismatchedSize() {
        assertFailsWith<IllegalArgumentException> { Rgba8Image(1, 1, 0, 0, ByteArray(3)) }
        assertFailsWith<IllegalArgumentException> { Rgba8Image(-1, 0, 0, 0, ByteArray(0)) }
    }

    @Test
    fun rgbaImageRejectsOverflowingDimensions() {
        assertFailsWith<IllegalArgumentException> { Rgba8Image(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0, ByteArray(4)) }
    }
}
