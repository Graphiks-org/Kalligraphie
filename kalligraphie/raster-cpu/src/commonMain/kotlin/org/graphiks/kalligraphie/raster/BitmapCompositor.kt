package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.GlyphColor

/**
 * Renders one normalized bitmap strike into a non-premultiplied `Rgba8Image`.
 *
 * `ALPHA_8` samples are tinted with the explicit ink, whose alpha multiplies each sample with
 * round-half-up integer arithmetic; `RGBA_8888` pixels are already straight colour and are
 * copied unchanged, so the ink never recolours or re-alphas a colour bitmap. The strike is
 * rendered one-to-one: no scaling, hinting, or subpixel placement participates, and output
 * bearings are the bitmap's own `originX` and `originY` relative to the glyph origin.
 *
 * Callers must enforce the canvas limits (including `pixels <= Int.MAX_VALUE / 4`) before
 * calling; `rasterize` builds a `width * height * 4`-byte output for every format, expanding
 * `ALPHA_8` input and copying `RGBA_8888` input unchanged.
 */
internal object BitmapCompositor {
    fun rasterize(bitmap: BitmapGlyphIR, ink: GlyphColor): Rgba8Image =
        when (bitmap.pixelFormat) {
            BitmapPixelFormat.ALPHA_8 -> rasterizeAlpha8(bitmap, ink)
            BitmapPixelFormat.RGBA_8888 -> Rgba8Image(
                bitmap.width,
                bitmap.height,
                bitmap.originX,
                bitmap.originY,
                bitmap.copyDecodedPixels(),
            )
        }

    private fun rasterizeAlpha8(bitmap: BitmapGlyphIR, ink: GlyphColor): Rgba8Image {
        val samples = bitmap.copyDecodedPixels()
        require(samples.size <= Int.MAX_VALUE / 4) { "decoded sample count exceeds the allocation guard." }
        val pixels = ByteArray(samples.size * 4)
        for (index in samples.indices) {
            val alpha = (((samples[index].toInt() and 0xFF) * ink.alpha) + 127) / 255
            val base = index * 4
            pixels[base] = ink.red.toByte()
            pixels[base + 1] = ink.green.toByte()
            pixels[base + 2] = ink.blue.toByte()
            pixels[base + 3] = alpha.toByte()
        }
        return Rgba8Image(bitmap.width, bitmap.height, bitmap.originX, bitmap.originY, pixels)
    }
}
