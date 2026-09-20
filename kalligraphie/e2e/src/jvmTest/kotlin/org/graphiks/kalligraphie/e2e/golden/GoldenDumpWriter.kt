package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.PixelFormat

/** Encodes canonical images as binary PGM (coverage) or PPM (RGBA) for human inspection. */
internal object GoldenDumpWriter {
    /** Returns the binary PGM/PPM bytes for [image]. The header is ASCII, the raster is binary. */
    fun encode(image: GoldenImage): ByteArray {
        val pixels = image.copyCanonicalBytes()
        val header: String
        val raster: ByteArray
        when (image.format) {
            PixelFormat.ALPHA_8 -> {
                header = "P5\n${image.width} ${image.height}\n255\n"
                raster = pixels
            }

            PixelFormat.RGBA_8888 -> {
                header = "P6\n${image.width} ${image.height}\n255\n"
                raster = ByteArray(image.width * image.height * 3) { index ->
                    val pixelIndex = (index / 3) * 4 + (index % 3)
                    pixels[pixelIndex]
                }
            }
        }
        return header.encodeToByteArray() + raster
    }
}
