package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapGlyphMetrics
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import kotlin.test.Test
import kotlin.test.assertEquals

class BitmapCompositorTest {
    @Test
    fun tintsSamplesWithTheInkAlpha() {
        val bitmap = BitmapGlyphIR(
            glyphId = GlyphId(3),
            strike = BitmapStrike(16, 16, 1),
            width = 2,
            height = 2,
            originX = -1,
            originY = 2,
            metrics = BitmapGlyphMetrics(advanceX = 16, advanceY = 0),
            pixelFormat = BitmapPixelFormat.ALPHA_8,
            colorSpace = GlyphColorSpace.SRGB,
            decodedPixels = byteArrayOf(0, -1, -128, 64),
        )
        val image = BitmapCompositor.rasterize(bitmap, GlyphColor(10, 20, 30, 200))
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(-1, image.left)
        assertEquals(2, image.top)
        assertEquals(0x000A141E, image[0, 0])
        assertEquals(0xC80A141E.toInt(), image[1, 0])
        assertEquals(0x640A141E, image[0, 1])
        assertEquals(0x320A141E, image[1, 1])
    }

    @Test
    fun roundsHalfUpInsteadOfTruncating() {
        val bitmap = BitmapGlyphIR(
            glyphId = GlyphId(3),
            strike = BitmapStrike(16, 16, 1),
            width = 1,
            height = 1,
            originX = 0,
            originY = 0,
            metrics = BitmapGlyphMetrics(advanceX = 16, advanceY = 0),
            pixelFormat = BitmapPixelFormat.ALPHA_8,
            colorSpace = GlyphColorSpace.SRGB,
            decodedPixels = byteArrayOf(1),
        )
        val image = BitmapCompositor.rasterize(bitmap, GlyphColor(10, 20, 30, 128))
        assertEquals(0x010A141E, image[0, 0])
    }

    @Test
    fun keepsInkChannelsUnderATransparentAlpha() {
        val bitmap = BitmapGlyphIR(
            glyphId = GlyphId(3),
            strike = BitmapStrike(16, 16, 1),
            width = 1,
            height = 1,
            originX = 0,
            originY = 0,
            metrics = BitmapGlyphMetrics(advanceX = 16, advanceY = 0),
            pixelFormat = BitmapPixelFormat.ALPHA_8,
            colorSpace = GlyphColorSpace.SRGB,
            decodedPixels = byteArrayOf(-1),
        )
        val image = BitmapCompositor.rasterize(bitmap, GlyphColor(10, 20, 30, 0))
        assertEquals(0x000A141E, image[0, 0])
    }
}
