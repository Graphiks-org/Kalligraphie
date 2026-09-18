package org.graphiks.kalligraphie.raster.logo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Minimal deterministic PNG encoder for non-premultiplied RGBA images.
 *
 * Every row uses filter type zero and the stream is compressed with a fixed
 * deflater level, so the same pixels always produce the same file on one JDK.
 * Callers must not compare file bytes across runtimes: `Deflater` depends on the
 * zlib build bundled with the JDK. Determinism of the *pixels* is what the
 * conformance test pins.
 */
internal object PngEncoder {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun encodeRgba8(width: Int, height: Int, pixels: ByteArray): ByteArray {
        require(width > 0 && height > 0) { "PNG dimensions must be positive." }
        require(pixels.size.toLong() == width.toLong() * height.toLong() * 4L) {
            "Pixel buffer does not match the declared dimensions."
        }
        val raw = ByteArray(height * (1 + width * 4))
        var source = 0
        var target = 0
        repeat(height) {
            raw[target++] = 0
            pixels.copyInto(raw, target, source, source + width * 4)
            source += width * 4
            target += width * 4
        }
        return signature +
            chunk("IHDR", ihdr(width, height)) +
            chunk("IDAT", deflate(raw)) +
            chunk("IEND", ByteArray(0))
    }

    private fun ihdr(width: Int, height: Int): ByteArray = ByteBuffer.allocate(13).apply {
        putInt(width)
        putInt(height)
        put(8)
        put(6)
        put(0)
        put(0)
        put(0)
    }.array()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        return ByteBuffer.allocate(12 + data.size).apply {
            putInt(data.size)
            put(typeBytes)
            put(data)
            putInt(crc.value.toInt())
        }.array()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        Deflater(Deflater.BEST_COMPRESSION).use { deflater ->
            DeflaterOutputStream(output, deflater).use { stream -> stream.write(raw) }
        }
        return output.toByteArray()
    }
}
