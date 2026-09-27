package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.Kalligraphie
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFallbackReason
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
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
 * Pins per-unit effective candidate order and span face preference through the paragraph path.
 *
 * The resolution policy is `[Liberation Sans, DejaVu Sans, Amiri (last resort)]`. Every scalar used
 * here is mapped by Liberation Sans and, where the assertion needs a non-covering preferred face,
 * deliberately unmapped by DejaVu Sans (U+1D6B). The tests require the fallback resolver to try the
 * span-preferred face first while keeping the declared last resort last.
 */
class StyleSpanFacePreferenceTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun aSpanPreferringAMiddleCandidatePromotesOnlyThatRange() {
        val base = fixture("abcdef")
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(range(base.snapshot, 2, 4), face = base.dejaVuFace)),
            ),
        )

        val line = compose(fixture).lines.single().line

        assertEquals(
            listOf(base.liberationFace, base.dejaVuFace, base.liberationFace),
            line.positionedGlyphRuns.map { it.fontInstanceKey.face },
        )
        assertTrue(line.diagnostics.none { it.code == "font.fallback.span-face-unavailable" })
    }

    @Test
    fun aSpanPreferringTheFirstCandidateKeepsThePolicyOrder() {
        val base = fixture("abcdef")
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(range(base.snapshot, 2, 4), face = base.liberationFace)),
            ),
        )

        val line = compose(fixture).lines.single().line

        assertEquals(
            listOf(base.liberationFace),
            line.positionedGlyphRuns.map { it.fontInstanceKey.face }.distinct(),
        )
    }

    @Test
    fun aSpanPreferringTheLastResortNeverPromotesIt() {
        val base = fixture("abcdef")
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(range(base.snapshot, 2, 4), face = base.amiriFace)),
            ),
        )

        val line = compose(fixture).lines.single().line

        assertEquals(
            listOf(base.liberationFace),
            line.positionedGlyphRuns.map { it.fontInstanceKey.face }.distinct(),
        )
        assertTrue(line.positionedGlyphRuns.none { it.fontInstanceKey.face == base.amiriFace })
        assertTrue(line.diagnostics.none { it.code == "font.fallback.span-face-unavailable" })
    }

    @Test
    fun aSpanPreferringANonCoveringCandidateFallsBackWithAWarningAndEffectiveRank() {
        val base = fixture("a\u1D6Bb")
        val fixture = base.withStyles(
            ParagraphStyleSnapshot(
                listOf(ParagraphStyleSpan(base.snapshot.range, face = base.dejaVuFace)),
            ),
        )

        val line = compose(fixture).lines.single().line

        assertEquals(
            setOf(base.dejaVuFace, base.liberationFace),
            line.positionedGlyphRuns.map { it.fontInstanceKey.face }.toSet(),
        )
        assertEquals(
            base.liberationFace,
            line.positionedGlyphRuns.single { run -> run.sourceRun.range == range(base.snapshot, 1, 2) }
                .fontInstanceKey.face,
        )
        assertTrue(line.diagnostics.any { it.code == "font.fallback.span-face-unavailable" })
        val rejected = line.diagnostics
            .mapNotNull { it.fallbackDiagnostic }
            .single { diagnostic ->
                diagnostic.faceId == base.dejaVuFace && diagnostic.reason == FontFallbackReason.MissingVisibleCoverage
            }
        assertEquals(0, rejected.candidateRank)
    }

    private fun compose(fixture: Fixture): ParagraphCompositionResult.Success =
        assertIs(ParagraphComposer.compose(fixture.request, EditableLineMaterialization.LayoutOnly))

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun source(resource: String, declaredName: String): FontSource =
        FontSource(checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }, FontSourceProvenance(declaredName))

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value

    private class Fixture(
        val snapshot: TextSnapshot,
        val request: ParagraphLayoutRequest,
        val liberationFace: FontFaceId,
        val dejaVuFace: FontFaceId,
        val amiriFace: FontFaceId,
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
                styleSpans = styleSpans,
            ),
            liberationFace = liberationFace,
            dejaVuFace = dejaVuFace,
            amiriFace = amiriFace,
        )
    }

    private fun fixture(value: String): Fixture {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "en"),
        )
        val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(snapshot, unicodeAnalysis)
        val liberation = source("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")
        val dejaVu = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val amiri = source("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val catalog: FontCatalogSnapshot = Kalligraphie.embedded(listOf(liberation, dejaVu, amiri)).successValue()
        val liberationFace = FontFaceId(liberation.id, 0)
        val dejaVuFace = FontFaceId(dejaVu.id, 0)
        val amiriFace = FontFaceId(amiri.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "style-span-face-preference-policy",
            version = "1",
            candidates = listOf(
                FontResolutionCandidate(liberationFace),
                FontResolutionCandidate(dejaVuFace),
                FontResolutionCandidate(amiriFace),
            ),
            lastResortFace = amiriFace,
        )
        val backend = HarfBuzzShapingBackend.open().successValue().also(openedBackends::add)
        val request = ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = snapshot.range,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(8_100f), LayoutUnit(2_050f)),
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
        )
        return Fixture(snapshot, request, liberationFace, dejaVuFace, amiriFace)
    }
}
