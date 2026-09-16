package org.graphiks.kalligraphie.raster.logo

import java.awt.Color
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PngEncoderTest {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    @Test
    fun encodesAValidPngWithUnfilteredScanlines() {
        val pixels = byteArrayOf(
            10, 20, 30, 255.toByte(),
            40, 50, 60, 128.toByte(),
        )

        val png = PngEncoder.encodeRgba8(width = 2, height = 1, pixels = pixels)

        assertContentEquals(signature, png.copyOfRange(0, 8))
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunkTypes(png))

        val header = chunkData(png, "IHDR")
        assertEquals(2, ByteBuffer.wrap(header, 0, 4).int)
        assertEquals(1, ByteBuffer.wrap(header, 4, 4).int)
        assertEquals(8, header[8].toInt())
        assertEquals(6, header[9].toInt())

        val inflated = InflaterInputStream(ByteArrayInputStream(chunkData(png, "IDAT"))).readBytes()
        assertContentEquals(byteArrayOf(0) + pixels, inflated)

        assertEquals(0, chunkData(png, "IEND").size)
        assertTrue(ByteBuffer.wrap(header, 12, 1).get().toInt() == 0)
    }

    @Test
    fun encodesMultipleRowsWithTheRightStride() {
        val width = 3
        val height = 2
        val pixels = ByteArray(width * height * 4) { index ->
            if (index % 4 == 3) 255.toByte() else (index * 7).toByte()
        }

        val png = PngEncoder.encodeRgba8(width, height, pixels)

        val decoded = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = (y * width + x) * 4
                val expected = Color(
                    pixels[base].toInt() and 0xFF,
                    pixels[base + 1].toInt() and 0xFF,
                    pixels[base + 2].toInt() and 0xFF,
                )
                assertEquals(expected.rgb, decoded.getRGB(x, y), "pixel ($x, $y)")
            }
        }
    }

    private fun chunkTypes(png: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var offset = 8
        while (offset < png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            types += String(png, offset + 4, 4, Charsets.US_ASCII)
            crc(png, offset + 4, length + 4).let { expected ->
                assertEquals(expected, ByteBuffer.wrap(png, offset + 8 + length, 4).int, "CRC of ${types.last()}")
            }
            offset += 12 + length
        }
        return types
    }

    private fun chunkData(png: ByteArray, type: String): ByteArray {
        var offset = 8
        while (offset < png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            if (String(png, offset + 4, 4, Charsets.US_ASCII) == type) {
                return png.copyOfRange(offset + 8, offset + 8 + length)
            }
            offset += 12 + length
        }
        error("missing chunk $type")
    }

    private fun crc(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }
}
