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
}
