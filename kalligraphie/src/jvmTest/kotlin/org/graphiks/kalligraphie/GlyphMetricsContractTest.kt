package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.LayoutBounds
import org.graphiks.kalligraphie.api.LayoutUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlyphMetricsContractTest {
    @Test
    fun compositeMetricsUseTheComponentMarkedWithUseMyMetrics() {
        val composite = compositeGlyphWithUseMyMetrics(firstComponentGlyphId = 1, metricsComponentGlyphId = 2)
        val hmtx = hmtxTableForMetrics(
            700 to 0,
            500 to 0,
            900 to 0,
        )
        val bytes = minimalTrueTypeFont(
            glyphCount = 3,
            tables = mapOf(
                "hmtx" to hmtx,
                "loca" to locaFormat0(0, composite.size, composite.size, composite.size),
                "glyf" to composite,
            ),
        )

        val instance = openInstance(size = 2048f, bytes = bytes)
        val metrics = assertIs<FontOperationResult.Success<GlyphMetrics>>(instance.metrics(GlyphId(0))).value

        assertEquals(900, metrics.advanceWidthDesignUnits)
        assertEquals(0, metrics.leftSideBearingDesignUnits)
        assertEquals(900f, metrics.advanceWidth.value)
    }

    @Test
    fun resolvesAndMeasuresLiberationSansGlyphs() {
        val instance = openInstance(size = 2048f)
        val a = assertIs<FontOperationResult.Success<GlyphResolution>>(instance.resolveGlyph(0x41)).value
        assertEquals(36, a.glyphId.value)

        val metrics = assertIs<FontOperationResult.Success<GlyphMetrics>>(instance.metrics(a.glyphId)).value
        assertEquals(1366, metrics.advanceWidthDesignUnits)
        assertEquals(4, metrics.leftSideBearingDesignUnits)
        assertEquals(1366f, metrics.advanceWidth.value)
        assertEquals(DesignBounds(4, 0, 1362, 1409), metrics.bounds)
        assertEquals(
            LayoutBounds(LayoutUnit(4f), LayoutUnit(0f), LayoutUnit(1362f), LayoutUnit(1409f)),
            metrics.scaledBounds,
        )

        val adieresis = assertIs<FontOperationResult.Success<GlyphResolution>>(instance.resolveGlyph(0x00C4)).value
        assertEquals(134, adieresis.glyphId.value)
        val adieresisMetrics =
            assertIs<FontOperationResult.Success<GlyphMetrics>>(instance.metrics(adieresis.glyphId)).value
        assertEquals(1366, adieresisMetrics.advanceWidthDesignUnits)
        assertEquals(DesignBounds(4, 0, 1362, 1714), adieresisMetrics.bounds)
    }

    @Test
    fun scalesInkBoundsAtThePublicLayoutBoundary() {
        val instance = openInstance(size = 1024f)
        val a = assertIs<FontOperationResult.Success<GlyphResolution>>(instance.resolveGlyph(0x41)).value

        val metrics = assertIs<FontOperationResult.Success<GlyphMetrics>>(instance.metrics(a.glyphId)).value

        assertEquals(DesignBounds(4, 0, 1362, 1409), metrics.bounds)
        assertEquals(
            LayoutBounds(LayoutUnit(2f), LayoutUnit(0f), LayoutUnit(681f), LayoutUnit(704.5f)),
            metrics.scaledBounds,
        )
    }

    @Test
    fun usesWideIntermediatesBeforeNarrowingFiniteScaledMetrics() {
        val instance = openInstance(size = Float.MAX_VALUE)
        val a = assertIs<FontOperationResult.Success<GlyphResolution>>(instance.resolveGlyph(0x41)).value

        val metrics = assertIs<FontOperationResult.Success<GlyphMetrics>>(instance.metrics(a.glyphId)).value

        assertTrue(metrics.advanceWidth.value.isFinite())
        assertTrue(metrics.scaledBounds.maxY.value.isFinite())
    }

    @Test
    fun rejectsNonFiniteInstanceSizes() {
        assertFailsWith<IllegalArgumentException> { LayoutUnit(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { LayoutUnit(Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { LayoutUnit(Float.NEGATIVE_INFINITY) }
    }

    @Test
    fun missingCharacterUsesNotdefAndReportsTheDecision() {
        val resolution = assertIs<FontOperationResult.Success<GlyphResolution>>(
            openInstance(2048f).resolveGlyph(0x10ffff),
        )
        assertEquals(0, resolution.value.glyphId.value)
        assertTrue(resolution.diagnostics.any { it.code == "font.cmap.glyph-not-found" })
    }

    private fun openInstance(size: Float, bytes: ByteArray = fixtureBytes()) =
        assertIs<FontOperationResult.Success<FontFace>>(
            assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
                Kalligraphie.embedded(
                    sourceBytes = bytes,
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
