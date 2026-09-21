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
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.VerticalGlyphMetrics
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

    /**
     * The fixture's `HVAR` carries no LSB mapping, so the `hmtx` lsb is retained even though
     * fontTools' instancer recomputes it to -8 from the varied outline.
     */
    @Test
    fun appliesTheHvarAdvanceDeltaToPortableMetrics() {
        val default = horizontalMetricsForGraphemeA(normalizedWght = null)
        assertEquals(574, default.advanceWidthDesignUnits)
        assertEquals(11, default.leftSideBearingDesignUnits)

        val varied = horizontalMetricsForGraphemeA(normalizedWght = 1f)
        assertEquals(660, varied.advanceWidthDesignUnits)
        assertEquals(11, varied.leftSideBearingDesignUnits)
    }

    @Test
    fun leavesTheVerticalAdvanceUnchangedWithoutVvar() {
        val default = verticalMetricsForGraphemeA(normalizedWght = null)
        assertEquals(1000f, default.advanceHeight.value)
        assertEquals(154f, default.topSideBearing.value)

        val varied = verticalMetricsForGraphemeA(normalizedWght = 1f)
        assertEquals(1000f, varied.advanceHeight.value)
        assertEquals(154f, varied.topSideBearing.value)
    }

    /**
     * The fixture's `HVAR` is present but maps a zero advance-width delta for glyph 2, so the varied
     * horizontal metrics must equal the `hmtx` base — a present-table zero-delta case.
     */
    @Test
    fun keepsTheHmtxBaseWhenHvarCarriesAZeroDelta() {
        val default = horizontalMetricsForGlyph(glyphId = 2, normalizedWght = null)
        assertEquals(1000, default.advanceWidthDesignUnits)
        assertEquals(199, default.leftSideBearingDesignUnits)

        val varied = horizontalMetricsForGlyph(glyphId = 2, normalizedWght = 1f)
        assertEquals(1000, varied.advanceWidthDesignUnits)
        assertEquals(199, varied.leftSideBearingDesignUnits)
    }

    /**
     * The fixture's `HVAR` item row for glyph 1 is 86, so 574 + 86 = 660; no LSB mapping is
     * present, so the `hmtx` side bearing is unchanged.
     */
    @Test
    fun variesTheAdvanceWidthThroughHvarAtNormalizedWghtOne() {
        val prepared = preparedFixture()
        val default = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.GlyphMetrics>>(
            prepared.readGlyphMetrics(GlyphId(1), 2048f),
        ).value
        assertEquals(574, default.advanceWidthDesignUnits)
        assertEquals(11, default.leftSideBearingDesignUnits)

        val varied = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.GlyphMetrics>>(
            prepared.readGlyphMetrics(GlyphId(1), 2048f, listOf(FontAxisCoordinate("wght", 1f))),
        ).value
        assertEquals(660, varied.advanceWidthDesignUnits)
        assertEquals(11, varied.leftSideBearingDesignUnits)
    }

    @Test
    fun keepsVerticalMetricsFromVmtxWithoutVvar() {
        val prepared = preparedFixture()
        val varied = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.VerticalGlyphMetrics>>(
            prepared.readVerticalGlyphMetrics(GlyphId(1), LAYOUT_SIZE, listOf(FontAxisCoordinate("wght", 1f))),
        ).value
        assertEquals(1000f, varied.advanceHeight.value)
    }

    @Test
    fun readsFontWideMetricsFromOs2WhenMvarIsAbsent() {
        val prepared = preparedFixture()
        val font = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.FontMetrics>>(
            prepared.readFontMetrics(listOf(FontAxisCoordinate("wght", 1f))),
        ).value
        assertEquals(880f, font.ascender)
        assertEquals(-120f, font.descender)
        assertEquals(0f, font.lineGap)
        assertEquals(-125f, font.underlinePosition)
        assertEquals(50f, font.underlineThickness)
        assertEquals(543f, font.xHeight)
        assertEquals(733f, font.capHeight)
    }

    private fun preparedFixture(): PreparedTrueTypeFont {
        val parsed = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("NotoSansJP-VerticalFixture"))),
        ).value
        return PreparedTrueTypeFont(FontSource(bytes, FontSourceProvenance("NotoSansJP-VerticalFixture")), parsed)
    }

    private fun horizontalMetricsForGraphemeA(normalizedWght: Float?): GlyphMetrics =
        horizontalMetricsForGlyph(glyphId = 1, normalizedWght = normalizedWght)

    private fun horizontalMetricsForGlyph(glyphId: Int, normalizedWght: Float?): GlyphMetrics =
        assertIs<FontOperationResult.Success<GlyphMetrics>>(
            preparedFixture().readGlyphMetrics(GlyphId(glyphId), LAYOUT_SIZE, fixtureAxes(normalizedWght)),
        ).value

    private fun verticalMetricsForGraphemeA(normalizedWght: Float?): VerticalGlyphMetrics =
        assertIs<FontOperationResult.Success<VerticalGlyphMetrics>>(
            preparedFixture().readVerticalGlyphMetrics(GlyphId(1), LAYOUT_SIZE, fixtureAxes(normalizedWght)),
        ).value

    private fun fixtureAxes(normalizedWght: Float?): List<FontAxisCoordinate> =
        normalizedWght?.let { listOf(FontAxisCoordinate("wght", it)) } ?: emptyList()

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

    private companion object {
        /** The fixture's `head.unitsPerEm`, so a scaled metric equals its design-unit value. */
        const val LAYOUT_SIZE = 1000f
    }
}
