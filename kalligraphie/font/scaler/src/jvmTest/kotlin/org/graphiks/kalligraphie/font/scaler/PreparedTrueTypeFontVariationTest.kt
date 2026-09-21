@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SfntReader

class PreparedTrueTypeFontVariationTest {
    // Shared OFL variable TrueType fixture (wght 100/100/900, gvar present). Glyph 1 is 'A'.
    private val bytes: ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"),
    ).readBytes()

    @Test
    fun exposesDecodedPhantomDeltasAtNormalizedWghtOne() {
        val outline = outlineForGraphemeA(normalizedWght = 1f)
        val phantoms = requireNotNull(outline.variationPhantoms)
        assertEquals(0.0, phantoms.leftX)
        assertEquals(86.0, phantoms.rightX)
        assertEquals(86.0, phantoms.horizontalAdvanceDelta)
        assertEquals(0.0, phantoms.topY)
        assertEquals(0.0, phantoms.bottomY)
        assertEquals(0.0, phantoms.verticalAdvanceDelta)
    }

    @Test
    fun defaultInstanceCarriesNoPhantomDeltas() {
        assertNull(outlineForGraphemeA(normalizedWght = null).variationPhantoms)
        assertNull(outlineForGraphemeA(normalizedWght = 0f).variationPhantoms)
    }

    private fun outlineForGraphemeA(normalizedWght: Float?): ScalerGlyphOutline {
        val parsed = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("NotoSansJP-VerticalFixture"))),
        ).value
        val prepared = PreparedTrueTypeFont(
            FontSource(bytes, FontSourceProvenance("NotoSansJP-VerticalFixture")),
            parsed,
        )
        val axes = normalizedWght?.let { listOf(FontAxisCoordinate("wght", it)) } ?: emptyList()
        return assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            prepared.readGlyphOutline(
                GlyphId(1),
                OutlineProfile(
                    maxBytes = 4_000_000,
                    maxContours = 256,
                    maxPoints = 16_384,
                    maxCompositeDepth = 8,
                    maxCompositeComponents = 256,
                ),
                CancellationToken.none,
                axes,
            ),
        ).value
    }

    @Test
    fun keepsTheCff2ApexAtTheDefaultInstance() {
        assertEquals(200.0, cff2ApexY(normalizedWght = null))
        assertEquals(200.0, cff2ApexY(normalizedWght = 0f))
    }

    @Test
    fun blendsTheCff2ApexAtNormalizedWghtOne() {
        assertEquals(300.0, cff2ApexY(normalizedWght = 1f))
    }

    private fun cff2ApexY(normalizedWght: Float?): Double {
        val cff2Bytes = requireNotNull(
            javaClass.getResourceAsStream("/fonts/cff2-variable/SyntheticVariable-CFF2.otf"),
        ).readBytes()
        val parsed = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(cff2Bytes, FontSourceProvenance("SyntheticVariable-CFF2"))),
        ).value
        val prepared = PreparedTrueTypeFont(
            FontSource(cff2Bytes, FontSourceProvenance("SyntheticVariable-CFF2")),
            parsed,
        )
        val axes = normalizedWght?.let { listOf(FontAxisCoordinate("wght", it)) } ?: emptyList()
        val outline = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            prepared.readGlyphOutline(
                GlyphId(1),
                OutlineProfile(
                    maxBytes = 1_000_000,
                    maxContours = 1_000,
                    maxPoints = 100_000,
                    maxCompositeDepth = 32,
                    maxCompositeComponents = 1_024,
                ),
                CancellationToken.none,
                axes,
            ),
        ).value
        val third = assertIs<GlyphOutlineCommand.LineTo>(outline.contours.single().commands[2])
        assertEquals(0.0, third.x)
        return third.y
    }
}
