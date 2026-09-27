package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.Kalligraphie
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FlowChain
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionInputIdentity
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowParagraphLayouter
import org.graphiks.kalligraphie.api.FlowRegion
import org.graphiks.kalligraphie.api.FlowRegionIdentity
import org.graphiks.kalligraphie.api.FlowRegionResult
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.IncrementalFlowLayoutRequest
import org.graphiks.kalligraphie.api.InlineInterval
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBand
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ParagraphStyleSnapshot
import org.graphiks.kalligraphie.api.ParagraphStyleSpan
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TypographySnapshot
import org.graphiks.kalligraphie.api.TypographyVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.api.createIncrementalFlowLayoutRequest
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots

/**
 * Pins the v1 composition boundary for per-span paragraph styles.
 *
 * The paragraph trunk must reject structurally invalid style snapshots with
 * [ParagraphLayoutError.InvalidInput] before any resolution, while every flow/line entry point
 * rejects a styled request with a typed failure before copying, querying a region, or reusing state.
 */
class StyleSpanValidationTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun aSpanBoundaryThatCutsAGraphemeClusterIsInvalidInput() {
        val fixture = fixture("e\u0301")
        val styles = ParagraphStyleSnapshot(
            listOf(ParagraphStyleSpan(range(fixture.snapshot, 0, 1), face = fixture.faceId)),
        )

        val failure = assertIs<ParagraphLayoutResult.Failure>(
            ParagraphComposer.layout(fixture.withStyles(styles), EditableLineMaterialization.LayoutOnly),
        )

        val error = assertIs<ParagraphLayoutError.InvalidInput>(failure.error)
        assertTrue(error.message.contains("grapheme boundaries"), error.message)
    }

    @Test
    fun aSpanStartFromAForeignTextVersionIsInvalidInput() {
        val fixture = fixture("ab")
        val foreign = fixture("cd")
        val foreignRange = TextRange(
            foreign.snapshot.textIndexAtScalarBoundary(0),
            foreign.snapshot.textIndexAtScalarBoundary(1),
        )
        val styles = ParagraphStyleSnapshot(
            listOf(ParagraphStyleSpan(foreignRange, face = fixture.faceId)),
        )

        val failure = assertIs<ParagraphLayoutResult.Failure>(
            ParagraphComposer.layout(fixture.withStyles(styles), EditableLineMaterialization.LayoutOnly),
        )

        val error = assertIs<ParagraphLayoutError.InvalidInput>(failure.error)
        assertTrue(error.message.contains("snapshot version"), error.message)
    }

    @Test
    fun aSpanFaceAbsentFromTheResolutionPolicyIsInvalidInput() {
        val fixture = fixture("ab")
        val foreignFace = FontFaceId(FontSourceId.Opaque("foreign-provider", "foreign-generation", "foreign-face"), 0)
        val styles = ParagraphStyleSnapshot(
            listOf(ParagraphStyleSpan(range(fixture.snapshot, 0, 1), face = foreignFace)),
        )

        val failure = assertIs<ParagraphLayoutResult.Failure>(
            ParagraphComposer.layout(fixture.withStyles(styles), EditableLineMaterialization.LayoutOnly),
        )

        val error = assertIs<ParagraphLayoutError.InvalidInput>(failure.error)
        assertTrue(error.message.contains("resolution policy candidate"), error.message)
    }

    @Test
    fun aSpanVariationCombinedWithParagraphNormalizedAxesIsInvalidInput() {
        val fixture = fixture(
            "ab",
            fontInstanceDescriptor = FontInstanceDescriptor(
                LayoutUnit(1_000f),
                FontGeometryParameters(normalizedAxes = listOf(FontAxisCoordinate("wght", 1f))),
            ),
        )
        val styles = ParagraphStyleSnapshot(
            listOf(
                ParagraphStyleSpan(
                    range(fixture.snapshot, 0, 1),
                    variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", 700f))),
                ),
            ),
        )

        val failure = assertIs<ParagraphLayoutResult.Failure>(
            ParagraphComposer.layout(fixture.withStyles(styles), EditableLineMaterialization.LayoutOnly),
        )

        val error = assertIs<ParagraphLayoutError.InvalidInput>(failure.error)
        assertTrue(error.message.contains("normalized axes"), error.message)
    }

    @Test
    fun aWellFormedSpanIsAcceptedByTheParagraphTrunk() {
        val fixture = fixture("ab")
        val styles = ParagraphStyleSnapshot(
            listOf(ParagraphStyleSpan(range(fixture.snapshot, 0, 1), face = fixture.faceId)),
        )

        assertIs<ParagraphLayoutResult.Success>(
            ParagraphComposer.layout(fixture.withStyles(styles), EditableLineMaterialization.LayoutOnly),
        )
    }

    @Test
    fun flowLayoutLineRejectsPerSpanStylesWithATypedParagraphFailure() {
        val fixture = fixture("ab")
        val region = FixedRegion(fixture.request.constraints.region)
        val styled = fixture.withStyles(styles(fixture))

        val failure = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutLine(styled, EditableLineMaterialization.LayoutOnly, region),
        )
        val viaFacade = assertIs<FlowCompositionResult.Failure>(
            (FlowParagraphComposer as FlowParagraphLayouter).layoutLine(
                styled,
                EditableLineMaterialization.LayoutOnly,
                region,
            ),
        )

        val error = assertIs<FlowCompositionError.ParagraphFailure>(failure.error)
        val facadeError = assertIs<FlowCompositionError.ParagraphFailure>(viaFacade.error)
        assertIs<ParagraphLayoutError.InvalidInput>(error.paragraphError)
        assertIs<ParagraphLayoutError.InvalidInput>(facadeError.paragraphError)
    }

    @Test
    fun flowLayoutFragmentRejectsPerSpanStylesWithoutQueryingARegion() {
        val fixture = fixture("ab")
        var queries = 0
        val region: FlowRegion = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                queries += 1
                return FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 4_000f)))
            }
        }

        val failure = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutFragment(
                fixture.withStyles(styles(fixture)),
                EditableLineMaterialization.LayoutOnly,
                FlowChain(listOf(region)),
                FlowCompositionInputIdentity(fixture.snapshot.version, TypographyVersion.create()),
            ),
        )

        val error = assertIs<FlowCompositionError.ParagraphFailure>(failure.error)
        assertIs<ParagraphLayoutError.InvalidInput>(error.paragraphError)
        assertTrue(queries == 0, "A styled request must be rejected before any region query.")
    }

    @Test
    fun incrementalFlowLayoutRejectsPerSpanStylesBeforeValidatingThePreparedParagraph() {
        val fixture = fixture("ab")
        val chain = FlowChain(listOf(FixedRegion(fixture.request.constraints.region)))
        val incremental = assertIs<FlowCompositionResult.Success<IncrementalFlowLayoutRequest>>(
            createIncrementalFlowLayoutRequest(
                input = LayoutInput(fixture.snapshot, fixture.typography),
                requestedRange = fixture.snapshot.range,
                constraints = fixture.request.constraints,
                flowChain = chain,
                overscan = LineOverscan(0),
            ),
        ).value

        val failure = assertIs<FlowCompositionResult.Failure>(
            IncrementalFlowLayoutEngine.layout(
                incremental,
                fixture.withStyles(styles(fixture)),
                EditableLineMaterialization.LayoutOnly,
            ),
        )

        assertIs<FlowCompositionError.IncompatibleState>(failure.error)
    }

    private fun styles(fixture: Fixture): ParagraphStyleSnapshot = ParagraphStyleSnapshot(
        listOf(ParagraphStyleSpan(range(fixture.snapshot, 0, 1), face = fixture.faceId)),
    )

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun fixture(
        value: String,
        fontInstanceDescriptor: FontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
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
        val source = FontSource(
            checkNotNull(javaClass.getResourceAsStream("/fonts/dejavu/DejaVuSans.ttf")).use { it.readBytes() },
            FontSourceProvenance("DejaVu Sans"),
        )
        val catalog: FontCatalogSnapshot = Kalligraphie.embedded(listOf(source)).successValue()
        val faceId = FontFaceId(source.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "style-span-validation-policy",
            version = "1",
            candidates = listOf(FontResolutionCandidate(faceId)),
            lastResortFace = faceId,
        )
        val backend = HarfBuzzShapingBackend.open().successValue().also(openedBackends::add)
        val constraints = HorizontalParagraphConstraints(
            region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(4_100f), LayoutUnit(2_050f)),
            lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
        )
        val request = ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = snapshot.range,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = constraints,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            featurePolicy = backend.identity.semantic.featurePolicy,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = fontInstanceDescriptor,
            shapingBackend = backend,
            materializationIdentity = ParagraphMaterializationIdentity.LayoutOnly,
            operationProfile = EditorOperationProfile.unbounded,
        )
        val typography = TypographySnapshot(
            version = TypographyVersion.create(),
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = fontInstanceDescriptor,
        )
        return Fixture(snapshot, request, typography, faceId)
    }

    private class Fixture(
        val snapshot: TextSnapshot,
        val request: ParagraphLayoutRequest,
        val typography: TypographySnapshot,
        val faceId: FontFaceId,
    ) {
        fun withStyles(styleSpans: ParagraphStyleSnapshot): ParagraphLayoutRequest = ParagraphLayoutRequest(
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
        )
    }

    private class FixedRegion(
        override val bounds: LayoutRect,
    ) : FlowRegion {
        override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
        override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
            FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 4_000f)))
    }

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value
}
