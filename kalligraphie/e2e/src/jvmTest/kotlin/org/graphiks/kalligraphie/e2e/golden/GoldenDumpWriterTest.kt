package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.e2e.GoldenImage

class GoldenDumpWriterTest {
    @Test
    fun writesABinaryPgmForAnAlphaImage() {
        val image = GoldenImage.alpha8(2, 1, byteArrayOf(0, 127))
        val bytes = GoldenDumpWriter.encode(image)
        assertEquals("P5\n2 1\n255\n", bytes.copyOfRange(0, 11).decodeToString())
        assertEquals(listOf<Byte>(0, 127), bytes.copyOfRange(11, 13).toList())
    }

    @Test
    fun writesABinaryPpmForAnRgbaImage() {
        val image = GoldenImage.rgba8(1, 1, byteArrayOf(10, 20, 30, 255.toByte()))
        val bytes = GoldenDumpWriter.encode(image)
        assertEquals("P6\n1 1\n255\n", bytes.copyOfRange(0, 11).decodeToString())
        assertEquals(listOf<Byte>(10, 20, 30), bytes.copyOfRange(11, 14).toList())
    }

    @Test
    fun writesTheFullRgbaRasterForMultiplePixels() {
        val image = GoldenImage.rgba8(2, 1, byteArrayOf(1, 2, 3, 255.toByte(), 4, 5, 6, 255.toByte()))
        val bytes = GoldenDumpWriter.encode(image)
        assertEquals("P6\n2 1\n255\n", bytes.copyOfRange(0, 11).decodeToString())
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), bytes.copyOfRange(11, 17).toList())
        assertEquals(11 + 6, bytes.size)
    }

    @Test
    fun compositesTransparentRgbaOverWhite() {
        val image = GoldenImage.rgba8(1, 1, byteArrayOf(10, 20, 30, 0))
        val bytes = GoldenDumpWriter.encode(image)
        assertEquals(
            listOf<Byte>(255.toByte(), 255.toByte(), 255.toByte()),
            bytes.copyOfRange(11, 14).toList(),
        )
    }

    @Test
    fun writesAnEmptyAlphaImage() {
        val bytes = GoldenDumpWriter.encode(GoldenImage.alpha8(0, 0, ByteArray(0)))
        assertEquals("P5\n0 0\n255\n", bytes.decodeToString())
    }
}
