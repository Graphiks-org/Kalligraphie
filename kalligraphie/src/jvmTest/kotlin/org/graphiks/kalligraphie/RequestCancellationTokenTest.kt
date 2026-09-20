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
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        val source = org.graphiks.kalligraphie.api.FontSource(
            sourceBytes = checkNotNull(
                javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf"),
            ) { "fixture font is missing" }.use { it.readBytes() },
            provenance = FontSourceProvenance("Liberation Sans Regular"),
        )
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(listOf(source)),
        ).value
        val faceId = org.graphiks.kalligraphie.api.FontFaceId(source.id, 0)
        val policy = org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "cancellation-token-fixture",
            version = "1",
            candidates = listOf(org.graphiks.kalligraphie.api.FontResolutionCandidate(faceId)),
            lastResortFace = faceId,
        )
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16("AA".toCharArray())),
        ).snapshot
        val constraints = org.graphiks.kalligraphie.api.HorizontalParagraphConstraints(
            region = org.graphiks.kalligraphie.api.LayoutRect(
                LayoutUnit(100f),
                LayoutUnit(50f),
                LayoutUnit(1_500f),
                LayoutUnit(1_250f),
            ),
            lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
        )
        val original = JvmEditableParagraphFacadeRequest(
            snapshot = snapshot,
            constraints = constraints,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        )
        val replacement = CancellationToken.cancelled

        val derived = original.withCancellationToken(replacement)

        assertSame(replacement, derived.cancellationToken)
        assertSame(original.snapshot, derived.snapshot)
        assertEquals(original.sourceRange, derived.sourceRange)
        assertSame(original.constraints, derived.constraints)
        assertEquals(original.baseDirection, derived.baseDirection)
        assertEquals(original.language, derived.language)
        assertSame(original.fontCatalog, derived.fontCatalog)
        assertSame(original.resolutionPolicy, derived.resolutionPolicy)
        assertEquals(original.fontInstanceDescriptor, derived.fontInstanceDescriptor)
        assertEquals(original.features, derived.features)
        assertEquals(original.materialization, derived.materialization)
        assertEquals(original.overflowPolicy, derived.overflowPolicy)
        assertEquals(original.positioning, derived.positioning)
        assertEquals(original.hyphenationMode, derived.hyphenationMode)
        assertEquals(original.hyphenationService, derived.hyphenationService)
        assertEquals(original.inlineObjects, derived.inlineObjects)
        assertEquals(original.textOrientation, derived.textOrientation)
        assertEquals(original.verticalMetricsPolicy, derived.verticalMetricsPolicy)
        assertEquals(original.continuation, derived.continuation)
        assertEquals(original.operationProfile, derived.operationProfile)
    }
}
