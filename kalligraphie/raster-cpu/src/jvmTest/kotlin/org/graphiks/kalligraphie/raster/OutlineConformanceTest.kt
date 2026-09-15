package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutlineConformanceTest {
    @Test
    fun liberationSansGlyphAProducesAStableCoverageFingerprint() {
        openRasterFixture(
            fixtureBytes("/fonts/liberation/LiberationSans-Regular.ttf"),
            outlineRequirements(),
        ).use { fixture ->
            val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
                fixture.instance.resolveGlyph(0x41),
            ).value.glyphId
            val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
            ).value
            val outline = assertIs<GlyphRepresentation.Outline>(representation).outline

            val image = assertIs<RasterResult.Success<A8Image>>(
                GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 64.0)),
            ).value

            assertTrue(image.width > 0 && image.height > 0, "glyph A must produce ink at 64 ppem")
            assertEquals(36, outline.glyphId)
            assertEquals(EXPECTED_A_WIDTH_PX, image.width)
            assertEquals(EXPECTED_A_HEIGHT_PX, image.height)
            assertEquals(EXPECTED_A_NON_ZERO_PIXELS, image.countNonZero())
            assertEquals(EXPECTED_A_SHA256, sha256(image.copyPixels()))
        }
    }

    private fun A8Image.countNonZero(): Int = copyPixels().count { sample -> sample.toInt() != 0 }

    private companion object {
        const val EXPECTED_A_SHA256 = "bade575a06ee0217858ff2b2eb9850f0c949323ca47a307666f99495fdbf2ca3"
        const val EXPECTED_A_WIDTH_PX = 43
        const val EXPECTED_A_HEIGHT_PX = 45
        const val EXPECTED_A_NON_ZERO_PIXELS = 687
    }
}
