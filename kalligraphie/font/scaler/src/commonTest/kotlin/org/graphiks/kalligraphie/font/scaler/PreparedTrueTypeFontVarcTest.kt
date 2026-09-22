@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.scaler.cff.buildCff2WithGlyph
import org.graphiks.kalligraphie.font.sfnt.FontFlavor
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SfntReader

class PreparedTrueTypeFontVarcTest {
    @Test
    fun rejectsTheOutlineAtANonDefaultLocationWhenVarcIsPresent() {
        val prepared = preparedFont(
            extraTables = mapOf(
                "fvar" to singleAxisFvarTable(),
                "VARC" to varcTable(),
            ),
        )

        val failure = assertIs<FontOperationResult.Failure>(
            prepared.readGlyphOutline(GlyphId(0), OUTLINE_PROFILE, CancellationToken.none, axes(1f)),
        )
        assertEquals("font.variation.varc-unsupported", failure.error.code)
        val location = assertIs<FontDiagnosticLocation.Table>(failure.error.location)
        assertEquals("VARC", location.tag)
    }

    @Test
    fun rendersTheStaticCompositeAtTheDefaultInstanceOnAVarcFace() {
        val prepared = preparedFont(
            extraTables = mapOf(
                "fvar" to singleAxisFvarTable(),
                "VARC" to varcTable(),
            ),
        )

        assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            prepared.readGlyphOutline(GlyphId(0), OUTLINE_PROFILE, CancellationToken.none, emptyList()),
        )
        assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            prepared.readGlyphOutline(GlyphId(0), OUTLINE_PROFILE, CancellationToken.none, axes(0f)),
        )
    }

    @Test
    fun leavesAVariableFaceWithoutVarcUnchangedAtANonDefaultLocation() {
        val prepared = preparedFont(extraTables = mapOf("fvar" to singleAxisFvarTable()))

        assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            prepared.readGlyphOutline(GlyphId(0), OUTLINE_PROFILE, CancellationToken.none, axes(1f)),
        )
    }

    @Test
    fun leavesANonVariableFaceUnchangedAtANonDefaultLocation() {
        val prepared = preparedFont(extraTables = emptyMap())

        assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            prepared.readGlyphOutline(GlyphId(0), OUTLINE_PROFILE, CancellationToken.none, axes(1f)),
        )
    }

    @Test
    fun exposesTheVarcTableFlagOnTheParsedFont() {
        assertTrue(parsedFont(mapOf("fvar" to singleAxisFvarTable(), "VARC" to varcTable())).hasVarcTable)
        assertFalse(parsedFont(mapOf("fvar" to singleAxisFvarTable())).hasVarcTable)
    }

    @Test
    fun classifiesACff2FaceWithVarcAsCff2() {
        val parsed = parsedFont(cff2FontBytes())

        assertEquals(FontFlavor.CFF2, parsed.flavor)
        assertTrue(parsed.hasVarcTable)
    }

    @Test
    fun rejectsTheCff2OutlineAtANonDefaultLocationWhenVarcIsPresent() {
        val prepared = preparedFont(cff2FontBytes())

        val failure = assertIs<FontOperationResult.Failure>(
            prepared.readGlyphOutline(GlyphId(0), OUTLINE_PROFILE, CancellationToken.none, axes(1f)),
        )
        assertEquals("font.variation.varc-unsupported", failure.error.code)
        val location = assertIs<FontDiagnosticLocation.Table>(failure.error.location)
        assertEquals("VARC", location.tag)
    }

    @Test
    fun rendersTheCff2DefaultInstanceOnAVarcFace() {
        val prepared = preparedFont(cff2FontBytes())

        assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            prepared.readGlyphOutline(GlyphId(0), OUTLINE_PROFILE, CancellationToken.none, emptyList()),
        )
        assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            prepared.readGlyphOutline(GlyphId(0), OUTLINE_PROFILE, CancellationToken.none, axes(0f)),
        )
    }

    private fun axes(wght: Float): List<FontAxisCoordinate> = listOf(FontAxisCoordinate("wght", wght))

    private fun parsedFont(bytes: ByteArray): ParsedTrueTypeFont {
        val source = FontSource(bytes, PROVENANCE)
        return assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(SfntReader.readMetadata(source)).value
    }

    private fun preparedFont(bytes: ByteArray): PreparedTrueTypeFont =
        PreparedTrueTypeFont(FontSource(bytes, PROVENANCE), parsedFont(bytes))

    private fun cff2FontBytes(): ByteArray {
        val glyph = singlePointGlyph(0, 0)
        return minimalTrueTypeFont(
            glyphCount = 1,
            scalerType = OTTO_SCALAR_TYPE,
            maxpVersion = CFF_MAXP_VERSION,
            tables = mapOf("loca" to locaFormat0(0, glyph.size), "glyf" to glyph),
            extraTables = mapOf(
                "fvar" to singleAxisFvarTable(),
                "VARC" to varcTable(),
                "CFF2" to buildCff2WithGlyph(CFF2_CHARSTRING),
            ),
        )
    }

    private fun parsedFont(extraTables: Map<String, ByteArray>): ParsedTrueTypeFont {
        val source = FontSource(fontBytes(extraTables), PROVENANCE)
        return assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(SfntReader.readMetadata(source)).value
    }

    private fun preparedFont(extraTables: Map<String, ByteArray>): PreparedTrueTypeFont {
        val source = FontSource(fontBytes(extraTables), PROVENANCE)
        val parsed = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(SfntReader.readMetadata(source)).value
        return PreparedTrueTypeFont(source, parsed)
    }

    private fun fontBytes(extraTables: Map<String, ByteArray>): ByteArray {
        val glyph = singlePointGlyph(0, 0)
        return minimalTrueTypeFont(
            glyphCount = 1,
            tables = mapOf("loca" to locaFormat0(0, glyph.size), "glyf" to glyph),
            extraTables = extraTables,
        )
    }

    private companion object {
        val PROVENANCE = FontSourceProvenance("varc-typed-rejection.ttf")
        const val OTTO_SCALAR_TYPE = 0x4F54544F
        const val CFF_MAXP_VERSION = 0x00005000
        val CFF2_CHARSTRING = byteArrayOf(139.toByte(), 139.toByte(), 21, 149.toByte(), 139.toByte(), 5)
        val OUTLINE_PROFILE = OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 1_000,
            maxPoints = 100_000,
            maxCompositeDepth = 32,
            maxCompositeComponents = 1_024,
        )
    }
}
