package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.EllipsisSide
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.JustificationMode
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphAlignment
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextOrientation
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.api.VerticalMetricsPolicy
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class RequestCancellationTokenTest {
    @Test
    fun lineRequestWithCancellationTokenPreservesEveryInput() {
        val source = FontSource(
            sourceBytes = checkNotNull(
                javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf"),
            ) { "fixture font is missing" }.use { it.readBytes() },
            provenance = FontSourceProvenance("Liberation Sans Regular"),
        )
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(listOf(source)),
        ).value
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(
                catalog.faces.single().id,
                org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot.layoutOnly(),
            ),
        ).value
        val font = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))),
        ).value
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8(byteArrayOf(0x41))),
        ).snapshot
        val original = JvmEditableLineFacadeRequest(
            snapshot = snapshot,
            font = font,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
            features = emptyList(),
            verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
            materialization = EditableLineMaterialization.LayoutOnly,
            positioning = ParagraphPositioningPolicy(),
            unicodeAnalysisProfile = UnicodeAnalysisProfile(maxScalars = 1024),
            shapingResourceProfile = ShapingResourceProfile(maxScalars = 1024, maxGlyphs = 1024),
            operationProfile = EditorOperationProfile(maxSourceUnits = 4096, maxAnalyzedScalars = 4096),
        )
        val replacement = CancellationToken.cancelled

        val derived = original.withCancellationToken(replacement)

        assertSame(replacement, derived.cancellationToken)
        assertSame(original.snapshot, derived.snapshot)
        assertSame(original.font, derived.font)
        assertEquals(original.baseDirection, derived.baseDirection)
        assertEquals(original.language, derived.language)
        assertEquals(original.featurePolicy, derived.featurePolicy)
        assertEquals(original.features, derived.features)
        assertEquals(original.verticalMetrics, derived.verticalMetrics)
        assertEquals(original.materialization, derived.materialization)
        assertEquals(original.emptyLineBidiLevel, derived.emptyLineBidiLevel)
        assertEquals(original.unicodeAnalysisProfile, derived.unicodeAnalysisProfile)
        assertEquals(original.shapingResourceProfile, derived.shapingResourceProfile)
        assertEquals(original.positioning, derived.positioning)
        assertEquals(original.operationProfile, derived.operationProfile)
    }

    @Test
    fun emptyLineRequestWithCancellationTokenPreservesEveryInput() {
        val source = FontSource(
            sourceBytes = checkNotNull(
                javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf"),
            ) { "fixture font is missing" }.use { it.readBytes() },
            provenance = FontSourceProvenance("Liberation Sans Regular"),
        )
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(listOf(source)),
        ).value
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(
                catalog.faces.single().id,
                org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot.layoutOnly(),
            ),
        ).value
        val font = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))),
        ).value
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8(ByteArray(0))),
        ).snapshot
        val original = JvmEditableLineFacadeRequest(
            snapshot = snapshot,
            font = font,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
            features = emptyList(),
            verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
            materialization = EditableLineMaterialization.LayoutOnly,
            emptyLineBidiLevel = 0,
            positioning = ParagraphPositioningPolicy(),
            unicodeAnalysisProfile = UnicodeAnalysisProfile(maxScalars = 1024),
            shapingResourceProfile = ShapingResourceProfile(maxScalars = 1024, maxGlyphs = 1024),
            operationProfile = EditorOperationProfile(maxSourceUnits = 4096, maxAnalyzedScalars = 4096),
        )
        val replacement = CancellationToken.cancelled

        val derived = original.withCancellationToken(replacement)

        assertSame(replacement, derived.cancellationToken)
        assertEquals(0, derived.emptyLineBidiLevel)
    }

    @Test
    fun paragraphRequestWithCancellationTokenPreservesEveryInput() {
        val source = FontSource(
            sourceBytes = checkNotNull(
                javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf"),
            ) { "fixture font is missing" }.use { it.readBytes() },
            provenance = FontSourceProvenance("Liberation Sans Regular"),
        )
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(listOf(source)),
        ).value
        val faceId = FontFaceId(source.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "cancellation-token-fixture",
            version = "1",
            candidates = listOf(FontResolutionCandidate(faceId)),
            lastResortFace = faceId,
        )
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16("AA".toCharArray())),
        ).snapshot
        val sourceRange = TextRange(
            snapshot.textIndexAtScalarBoundary(0),
            snapshot.textIndexAtScalarBoundary(1),
        )
        val constraints = HorizontalParagraphConstraints(
            region = LayoutRect(
                LayoutUnit(100f),
                LayoutUnit(50f),
                LayoutUnit(1_500f),
                LayoutUnit(1_250f),
            ),
            lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
        )
        val fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f))
        val features = listOf(OpenTypeFeature("kern", 0))
        val positioning = ParagraphPositioningPolicy(
            alignment = ParagraphAlignment.END,
            justificationMode = JustificationMode.INTER_CHARACTER,
        )
        val inlineObjects = InlineObjectSnapshot(emptyList())
        val operationProfile = EditorOperationProfile(maxSourceUnits = 4096, maxAnalyzedScalars = 4096)
        val outlineProfile = OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 256,
            maxPoints = 16_384,
            maxCompositeDepth = 8,
            maxCompositeComponents = 256,
        )
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            catalog.openAssetResolver(),
        ).value
        try {
            val original = JvmEditableParagraphFacadeRequest(
                snapshot = snapshot,
                sourceRange = sourceRange,
                constraints = constraints,
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = "en",
                fontCatalog = catalog,
                resolutionPolicy = policy,
                fontInstanceDescriptor = fontInstanceDescriptor,
                features = features,
                materialization = EditableLineMaterialization.Renderable(
                    resolver = resolver,
                    variant = FontRenderVariantKey.default,
                    outlineProfile = outlineProfile,
                ),
                overflowPolicy = OverflowPolicy.Ellipsis(EllipsisSide.INLINE_END),
                positioning = positioning,
                hyphenationMode = HyphenationMode.NONE,
                inlineObjects = inlineObjects,
                textOrientation = TextOrientation.UPRIGHT,
                verticalMetricsPolicy = VerticalMetricsPolicy.REQUIRE_FONT_METRICS,
                operationProfile = operationProfile,
            )
            val replacement = CancellationToken.cancelled

            val derived = original.withCancellationToken(replacement)

            assertSame(replacement, derived.cancellationToken)
            assertSame(snapshot, derived.snapshot)
            assertEquals(sourceRange, derived.sourceRange)
            assertSame(constraints, derived.constraints)
            assertEquals(BaseDirection.LEFT_TO_RIGHT, derived.baseDirection)
            assertEquals("en", derived.language)
            assertSame(catalog, derived.fontCatalog)
            assertSame(policy, derived.resolutionPolicy)
            assertEquals(fontInstanceDescriptor, derived.fontInstanceDescriptor)
            assertEquals(features, derived.features)
            assertSame(original.materialization, derived.materialization)
            assertEquals(OverflowPolicy.Ellipsis(EllipsisSide.INLINE_END), derived.overflowPolicy)
            assertEquals(positioning, derived.positioning)
            assertEquals(HyphenationMode.NONE, derived.hyphenationMode)
            // A hyphenation service and a replay continuation are engine-produced values that
            // cannot be constructed without a real layout round-trip, so they stay null and are
            // asserted here only as preserved nulls.
            assertNull(derived.hyphenationService)
            assertNull(derived.continuation)
            assertEquals(inlineObjects, derived.inlineObjects)
            assertEquals(TextOrientation.UPRIGHT, derived.textOrientation)
            assertEquals(VerticalMetricsPolicy.REQUIRE_FONT_METRICS, derived.verticalMetricsPolicy)
            assertSame(operationProfile, derived.operationProfile)
        } finally {
            resolver.close()
        }
    }
}
