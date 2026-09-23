package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.PixelFormat

/** Encodes canonical images as binary PGM (coverage) or PPM (RGBA) for human inspection. */
internal object GoldenDumpWriter {
    /** File extension for [format] in dumps. */
    fun extensionFor(format: PixelFormat): String = when (format) {
        PixelFormat.ALPHA_8 -> "pgm"
        PixelFormat.RGBA_8888 -> "ppm"
    }

    /**
     * Returns the binary PGM/PPM bytes for [image]. The header is ASCII, the raster is binary.
     * Straight RGBA is composited over white with the same integer formula the raster-cpu dumps
     * use, so an alpha-only difference stays visible and transparent pixels read as white.
     *
     * The raster is written in image orientation, row zero at the top, whatever the producer
     * declared: a design-oriented render is reversed once through
     * [GoldenImage.toImageOrientation], so every dump opens upright in an image viewer.
     */
    fun encode(image: GoldenImage): ByteArray {
        val pixels = image.toImageOrientation().copyCanonicalBytes()
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
                    val pixelIndex = (index / 3) * 4
                    val alpha = pixels[pixelIndex + 3].toInt() and 0xFF
                    val channel = pixels[pixelIndex + (index % 3)].toInt() and 0xFF
                    ((channel * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
                }
            }
        }
        return header.encodeToByteArray() + raster
    }
}
