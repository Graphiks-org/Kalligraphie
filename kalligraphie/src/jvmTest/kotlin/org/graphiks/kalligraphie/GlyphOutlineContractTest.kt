package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GlyphOutlineContractTest {
    @Test
    fun materializesAuditedSimpleGlyphAInDesignUnits() {
        val font = openRenderableFont(fixtureBytes())
        val a = assertIs<FontOperationResult.Success<GlyphResolution>>(font.instance.resolveGlyph(0x41)).value
        assertEquals(36, a.glyphId.value)

        val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
            font.asset.resolveGlyph(FontGlyphRequest(a.glyphId)),
        ).value
        val outline = assertIs<GlyphRepresentation.Outline>(representation).outline

        assertEquals(36, outline.glyphId)
        assertEquals(2048, outline.unitsPerEm)
        assertEquals(DesignBounds(4, 0, 1362, 1409), outline.bounds)
        assertEquals(2, outline.contours.size)
        assertEquals(17, outline.pointCount)
    }

    @Test
    fun resolvesAuditedCompositeAdieresisComponentsInDesignUnits() {
        val font = openRenderableFont(fixtureBytes())
        val adieresis = assertIs<FontOperationResult.Success<GlyphResolution>>(font.instance.resolveGlyph(0x00C4)).value
        assertEquals(134, adieresis.glyphId.value)

        val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
            font.asset.resolveGlyph(FontGlyphRequest(adieresis.glyphId)),
        ).value
        val outline = assertIs<GlyphRepresentation.Outline>(representation).outline

        assertEquals(DesignBounds(4, 0, 1362, 1714), outline.bounds)
        assertEquals(listOf(36, 2338), outline.components.map { it.glyphId })
        assertEquals(364, outline.components[1].transform.translationX)
        assertEquals(0, outline.components[1].transform.translationY)
    }

    @Test
    fun truncatedGlyfRangeReturnsTypedFailure() {
        val bytes = minimalTrueTypeFont(
            glyphCount = 1,
            tables = mapOf(
                "loca" to locaFormat0(0, 8),
                "glyf" to ByteArray(8),
            ),
        )
        val font = openRenderableFont(bytes)

        val result = font.asset.resolveGlyph(FontGlyphRequest(GlyphId(0)))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.glyf.truncated", failure.error.code)
    }

    @Test
    fun outOfRangeLocaEntryReturnsTypedFailure() {
        val bytes = minimalTrueTypeFont(
            glyphCount = 1,
            tables = mapOf(
                "loca" to locaFormat0(0, 8),
                "glyf" to ByteArray(4),
            ),
        )
        val font = openRenderableFont(bytes)

        val result = font.asset.resolveGlyph(FontGlyphRequest(GlyphId(0)))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.loca.out-of-range", failure.error.code)
    }

    @Test
    fun selfReferentialCompositeReturnsCycleFailure() {
        val bytes = minimalTrueTypeFont(
            glyphCount = 1,
            tables = mapOf(
                "loca" to locaFormat0(0, 18),
                "glyf" to compositeGlyphSelfCycle(),
            ),
        )
        val font = openRenderableFont(bytes)

        val result = font.asset.resolveGlyph(FontGlyphRequest(GlyphId(0)))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.glyf.composite-cycle", failure.error.code)
    }

    private fun openRenderableFont(bytes: ByteArray): RenderableFont {
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(
                sourceBytes = bytes,
                provenance = FontSourceProvenance(declaredName = "Composite outline contract test font"),
            ),
        ).value
        val resolver = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.FontAssetResolverHandle>>(
            catalog.openAssetResolver(),
        ).value
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(FontFaceRequest(faceIndex = 0), FontAccessRequirementsSnapshot.renderable(outlineProfile())),
        ).value
        val instance = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))),
        ).value
        val asset = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, FontAccessRequirementsSnapshot.renderable(outlineProfile())),
        ).value
        return RenderableFont(instance, asset)
    }

    private fun outlineProfile(): OutlineProfile =
        OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 256,
            maxPoints = 16_384,
            maxCompositeDepth = 8,
            maxCompositeComponents = 256,
        )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }
}

private data class RenderableFont(
    val instance: FontInstance,
    val asset: FontRenderAssetHandle,
)
