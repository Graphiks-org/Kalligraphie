package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmEditableLineFacadeTest {
    @Test
    fun acceptsAGraphemeClusterThatSpansAnalyzedBidiLevels() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("\u0600a".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.LayoutOnly,
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            assertEquals(snapshot.range, line.range)
            assertTrue(line.caretCandidates(snapshot.textIndexAtScalarBoundary(1)).isEmpty())
            assertTrue(
                line.selectionGeometry(
                    CaretPosition(snapshot.range.start, CaretAffinity.DOWNSTREAM),
                    CaretPosition(snapshot.range.endExclusive, CaretAffinity.UPSTREAM),
                ).isNotEmpty(),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun resolvesMixedBidiRunsWithoutAnImplicitLeftToRightShapingDirection() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("Aא".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.LayoutOnly,
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            assertEquals(
                listOf("LEFT_TO_RIGHT", "RIGHT_TO_LEFT"),
                line.positionedGlyphRuns.map { it.sourceRun.direction.name }.sorted(),
            )
            assertTrue(line.caretCandidates(snapshot.textIndexAtScalarBoundary(1)).size >= 2)
            assertTrue(line.positionedGlyphRuns.all { it.sourceRun.graphemeClusters.isNotEmpty() })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun publishesACertifiedOutlineLineThroughTheReferenceJvmBackend() {
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf(0x41))),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = fixture.resolver,
                        variant = FontRenderVariantKey.default,
                        outlineProfile = OUTLINE_PROFILE,
                    ),
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            val glyph = line.positionedGlyphRuns.single().glyphs.single()
            assertEquals(snapshot.range, line.range)
            assertEquals("14.3.0", line.positionedGlyphRuns.single().sourceRun.backendIdentity.nativeVersion)
            assertEquals(GlyphId(36), glyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.OUTLINE, glyph.materializationCertificate?.route)
            assertEquals(glyph.renderAssetKey, glyph.materializationCertificate?.assetKey)
            assertEquals(2, line.caretCandidates(snapshot.range.start).size + line.caretCandidates(snapshot.range.endExclusive).size)
            assertTrue(line.positionedGlyphRuns.single().sourceRun.clusters.single().sourceRange == snapshot.range)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    private fun renderableFixture(): RenderableFixture {
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(
                sourceBytes = fixtureBytes(),
                provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
            ),
        ).value
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(catalog.openAssetResolver()).value
        val requirements = FontAccessRequirementsSnapshot.renderable(OUTLINE_PROFILE)
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(FontFaceRequest(faceIndex = 0), requirements),
        ).value
        val font = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(2048f))),
        ).value
        return RenderableFixture(font, resolver)
    }

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }

    private data class RenderableFixture(
        val font: FontInstance,
        val resolver: FontAssetResolverHandle,
    )

    private companion object {
        val OUTLINE_PROFILE: OutlineProfile = OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 256,
            maxPoints = 16_384,
            maxCompositeDepth = 8,
            maxCompositeComponents = 256,
        )
    }
}
