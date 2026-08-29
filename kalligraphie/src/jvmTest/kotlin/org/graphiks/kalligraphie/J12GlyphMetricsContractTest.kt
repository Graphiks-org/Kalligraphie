package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class J12GlyphMetricsContractTest {
    @Test
    fun resolvesAndMeasuresAuditedGlyphs() {
        val instance = openInstance(size = 2048f)
        val a = assertIs<FontOperationResult.Success<GlyphResolution>>(instance.resolveGlyph(0x41)).value
        assertEquals(36, a.glyphId.value)

        val metrics = assertIs<FontOperationResult.Success<GlyphMetrics>>(instance.metrics(a.glyphId)).value
        assertEquals(1366, metrics.advanceWidthDesignUnits)
        assertEquals(4, metrics.leftSideBearingDesignUnits)
        assertEquals(1366f, metrics.advanceWidth.value)

        val adieresis = assertIs<FontOperationResult.Success<GlyphResolution>>(instance.resolveGlyph(0x00C4)).value
        assertEquals(134, adieresis.glyphId.value)
        val adieresisMetrics =
            assertIs<FontOperationResult.Success<GlyphMetrics>>(instance.metrics(adieresis.glyphId)).value
        assertEquals(1366, adieresisMetrics.advanceWidthDesignUnits)
    }

    @Test
    fun missingCharacterUsesNotdefAndReportsTheDecision() {
        val resolution = assertIs<FontOperationResult.Success<GlyphResolution>>(
            openInstance(2048f).resolveGlyph(0x10ffff),
        )
        assertEquals(0, resolution.value.glyphId.value)
        assertTrue(resolution.diagnostics.any { it.code == "font.cmap.glyph-not-found" })
    }

    private fun openInstance(size: Float) =
        assertIs<FontOperationResult.Success<FontFace>>(
            assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
                Kalligraphie.embedded(
                    sourceBytes = fixtureBytes(),
                    provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
                ),
            ).value.resolveFace(FontFaceRequest(faceIndex = 0), FontAccessRequirementsSnapshot.layoutOnly()),
        ).value.instantiate(org.graphiks.kalligraphie.api.FontInstanceDescriptor(org.graphiks.kalligraphie.api.LayoutUnit(size)))
            .let { assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.FontInstance>>(it).value }

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }
}
