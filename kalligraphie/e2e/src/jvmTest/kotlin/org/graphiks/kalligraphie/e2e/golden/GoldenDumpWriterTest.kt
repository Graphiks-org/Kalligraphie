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
}
