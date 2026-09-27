package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.Kalligraphie
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.EllipsisSide
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphProvenance
import org.graphiks.kalligraphie.api.GlyphProvenanceRole
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ParagraphStyleSnapshot
import org.graphiks.kalligraphie.api.ParagraphStyleSpan
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots

/**
 * Pins that an ellipsis marker is measured with the instance that renders it at the anchor.
 *
 * The paragraph uses one variable face with two per-span `wght` selections: a narrow prefix
 * (`wght=100`, whose `U+2026` is narrow) and a wider suffix (`wght=900`, whose `U+2026` is wide).
 * The truncation anchor lands inside the wide span, so a marker measured with the first instance
 * would be too narrow and the truncated line would overflow the region.
 */
class EllipsisMixedInstanceTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun theEllipsisMarkerIsMeasuredWithTheInstanceThatRendersAtTheAnchor() {
        val base = fixture("MMMMMMMM", width = 5_800f)
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(
                    ParagraphStyleSpan(range(base.snapshot, 0, 4), variation = wght(100f)),
                    ParagraphStyleSpan(range(base.snapshot, 4, 8), variation = wght(900f)),
                ),
            ),
        )

        val result = compose(fixture)
        val placed = result.lines.single()
        val line = placed.line
        val truncation = assertNotNull(result.truncation)

        // (a) Every published line stays within the region even though the marker is wider than the
        // first instance's marker.
        assertTrue(
            placed.inlineAdvance.value <= fixture.request.constraints.width.value,
            "The truncated line must not overflow the region: ${placed.inlineAdvance.value} > ${fixture.request.constraints.width.value}",
        )
        result.lines.forEach { composed ->
            assertTrue(composed.inlineAdvance.value <= fixture.request.constraints.width.value)
        }

        // The corrected measurement keeps the anchor inside the wider span so the marker really
        // renders with that instance.
        assertEquals(base.snapshot.textIndexAtScalarBoundary(5), truncation.anchor)
        assertEquals(EllipsisSide.INLINE_END, truncation.side)

        // (b) The marker run is the anchor run, and that run uses the wider (positive) instance.
        val markerRun = line.positionedGlyphRuns.single { run ->
            run.glyphs.any { glyph ->
                (glyph.provenance as? GlyphProvenance.Synthetic)?.role == GlyphProvenanceRole.ELLIPSIS
            }
        }
        val anchorRun = line.positionedGlyphRuns.single { run ->
            run.sourceRun.clusters.any { cluster -> cluster.sourceRange.endExclusive == truncation.anchor }
        }
        assertEquals(anchorRun.fontInstanceKey, markerRun.fontInstanceKey)
        assertTrue(markerRun.fontInstanceKey.geometry.normalizedAxes.single().value > 0f)
    }

    @Test
    fun inlineStartEllipsisKeepsTheSmallestVisibleSuffixWithOneInstance() {
        val fixture = fixture("ABCDE", width = 2_300f, overflowPolicy = OverflowPolicy.Ellipsis(EllipsisSide.INLINE_START))

        val result = compose(fixture)
        val truncation = assertNotNull(result.truncation)

        // Both the last cluster and the last two clusters fit at this width, so the published
        // boundary pins the selection order: the legacy single-instance behavior keeps the smallest
        // visible suffix (the final cluster only), hiding the rest.
        assertEquals(fixture.snapshot.textIndexAtScalarBoundary(4), truncation.anchor)
        assertEquals(range(fixture.snapshot, 0, 4), truncation.hiddenRange)
        assertEquals(EllipsisSide.INLINE_START, truncation.side)
    }

    private fun compose(fixture: Fixture): ParagraphCompositionResult.Success =
        assertIs(ParagraphComposer.compose(fixture.request, EditableLineMaterialization.LayoutOnly))

    private fun wght(value: Float): FontVariationCoordinates =
        FontVariationCoordinates(listOf(FontVariationCoordinate("wght", value)))

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun source(resource: String, declaredName: String): FontSource =
        FontSource(checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }, FontSourceProvenance(declaredName))

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value

    private class Fixture(
        val snapshot: TextSnapshot,
        val request: ParagraphLayoutRequest,
    ) {
        fun withStyles(styleSpans: ParagraphStyleSnapshot): Fixture = Fixture(
            snapshot = snapshot,
            request = ParagraphLayoutRequest(
                snapshot = request.snapshot,
                sourceRange = request.sourceRange,
                unicodeAnalysis = request.unicodeAnalysis,
                lineBreakAnalysis = request.lineBreakAnalysis,
                constraints = request.constraints,
                baseDirection = request.baseDirection,
                language = request.language,
                featurePolicy = request.featurePolicy,
                features = request.features,
                fontCatalog = request.fontCatalog,
                resolutionPolicy = request.resolutionPolicy,
                fontInstanceDescriptor = request.fontInstanceDescriptor,
                shapingBackend = request.shapingBackend,
                materializationIdentity = request.materializationIdentity,
                operationProfile = request.operationProfile,
                overflowPolicy = request.overflowPolicy,
                styleSpans = styleSpans,
            ),
        )
    }

    private fun fixture(
        value: String,
        width: Float,
        overflowPolicy: OverflowPolicy = OverflowPolicy.Ellipsis(EllipsisSide.INLINE_END),
    ): Fixture {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "en"),
        )
        val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(snapshot, unicodeAnalysis)
        val workSans = source("/fonts/worksans/WorkSans[wght].ttf", "Work Sans")
        val dejaVu = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val catalog: FontCatalogSnapshot = Kalligraphie.embedded(listOf(workSans, dejaVu)).successValue()
        val workSansFace = FontFaceId(workSans.id, 0)
        val dejaVuFace = FontFaceId(dejaVu.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "ellipsis-mixed-instance-policy",
            version = "1",
            candidates = listOf(
                FontResolutionCandidate(workSansFace),
                FontResolutionCandidate(dejaVuFace),
            ),
            lastResortFace = dejaVuFace,
        )
        val backend = HarfBuzzShapingBackend.open().successValue().also(openedBackends::add)
        val request = ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = snapshot.range,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(100f + width), LayoutUnit(2_050f)),
                lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
            ),
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            featurePolicy = backend.identity.semantic.featurePolicy,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            shapingBackend = backend,
            materializationIdentity = ParagraphMaterializationIdentity.LayoutOnly,
            operationProfile = EditorOperationProfile.unbounded,
            overflowPolicy = overflowPolicy,
        )
        return Fixture(snapshot, request)
    }
}
