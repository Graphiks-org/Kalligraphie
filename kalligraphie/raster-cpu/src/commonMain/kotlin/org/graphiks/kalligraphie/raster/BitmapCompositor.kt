package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.GlyphColor

/**
 * Renders a normalized `ALPHA_8` bitmap strike with one foreground ink.
 *
 * The strike is rendered one-to-one: no scaling, hinting, or subpixel placement
 * participates, so the exact strike identity is preserved. Output bearings are
 * the bitmap's own `originX` and `originY` relative to the glyph origin, and the
 * ink's alpha multiplies each sample with round-to-nearest integer arithmetic.
 */
internal object BitmapCompositor {
    fun rasterize(bitmap: BitmapGlyphIR, ink: GlyphColor): Rgba8Image {
        val samples = bitmap.copyDecodedPixels()
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
