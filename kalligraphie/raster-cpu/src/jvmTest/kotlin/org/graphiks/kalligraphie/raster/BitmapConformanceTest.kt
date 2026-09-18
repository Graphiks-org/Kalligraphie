package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BitmapConformanceTest {
    @Test
    fun ebdtStrikeProducesAStableRgbaFingerprint() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        openRasterFixture(
            fixtureBytes("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"),
            requirements,
        ).use { fixture ->
            val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
                fixture.instance.resolveGlyph(0x1F600),
            ).value.glyphId
            assertEquals(3, glyph.value)
            val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
            ).value
            val bitmap = assertIs<GlyphRepresentation.Bitmap>(representation).bitmap
            assertEquals(BitmapStrike(16, 16, 1), bitmap.strike)

            val image = assertIs<RasterResult.Success<Rgba8Image>>(
                GlyphRasterizer.rasterizeBitmap(bitmap, BitmapRasterRequest(GlyphColor(0, 0, 0, 255))),
            ).value
            val pixels = image.copyPixels()
            assertEquals(13, image.width)
            assertEquals(13, image.height)
            assertEquals(0, image.left)
            assertEquals(13, image.top)
            assertTrue(pixels.any { sample -> sample.toInt() != 0 })
            assertEquals(EXPECTED_BITMAP_SHA256, sha256(pixels))
        }
    }

    private companion object {
        const val EXPECTED_BITMAP_SHA256 = "90963133c14da9c3d9c37cdd964bbc709e857d1b69e5b97aa2b417f912d8933f"
    }
}
