package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PaintConformanceTest {
    @Test
    fun emojiTwoColorGlyphProducesAStableRgbaFingerprint() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        openRasterFixture(
            fixtureBytes("/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"),
            requirements,
            renderVariant = FontRenderVariantSnapshot(cpalPaletteIndex = 0),
        ).use { fixture ->
            val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
                fixture.instance.resolveGlyph(0x1F600),
            ).value.glyphId
            val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
            ).value
            val paint = assertIs<GlyphRepresentation.Paint>(representation).paint

            val unitsPerEm = paint.nodes
                .filterIsInstance<GlyphPaintNode.SolidOutline>()
                .first()
                .outline
                .unitsPerEm
            val image = assertIs<RasterResult.Success<Rgba8Image>>(
                GlyphRasterizer.rasterizePaint(
                    paint,
                    PaintRasterRequest(pixelsPerEm = 64.0, unitsPerEm = unitsPerEm),
                ),
            ).value

            assertTrue(image.width > 0 && image.height > 0)
            assertEquals(EXPECTED_PAINT_WIDTH_PX, image.width)
            assertEquals(EXPECTED_PAINT_HEIGHT_PX, image.height)
            assertEquals(EXPECTED_PAINT_SHA256, sha256(image.copyPixels()))
        }
    }

    private fun paintProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 8,
            maxReferences = 6,
            maxDepth = 2,
            maxSourceBytes = 200_000,
            maxPaths = 6,
            maxPalettes = 2,
            maxPaletteEntries = 2_000,
            maxColorRecords = 2_000,
            maxBaseGlyphRecords = 3_000,
            maxLayerRecords = 30_000,
        ),
        outlineProfile = outlineProfile(),
    )

    private companion object {
        const val EXPECTED_PAINT_SHA256 = "8146d2e52d2ffb5a673b752fcd0b88694332815752c3add8b2544a6ca68bcc29"
        const val EXPECTED_PAINT_WIDTH_PX = 71
        const val EXPECTED_PAINT_HEIGHT_PX = 72
    }
}
