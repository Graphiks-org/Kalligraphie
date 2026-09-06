package org.graphiks.kalligraphie.layout

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowRegion
import org.graphiks.kalligraphie.api.FlowRegionIdentity
import org.graphiks.kalligraphie.api.FlowRegionResult
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.InlineInterval
import org.graphiks.kalligraphie.api.InlineObjectAlignment
import org.graphiks.kalligraphie.api.InlineObjectDefinition
import org.graphiks.kalligraphie.api.InlineObjectEntry
import org.graphiks.kalligraphie.api.InlineObjectId
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBand
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.NoProgressReason
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalogEntry
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots

class FlowParagraphCompositionTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun exclusionProjectsOneLogicalLatinLineIntoTwoSourceExactFragments() {
        val fixture = fixture("abcd")
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(0f, 1_300f), InlineInterval(2_200f, 4_000f)),
        )

        val fragment = success(FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region))
        val line = fragment.lines.single()

        assertEquals(fixture.snapshot.range, fragment.laidOutRange)
        assertEquals(fixture.snapshot.range, line.range)
        assertEquals(
            listOf(InlineInterval(0f, 1_300f), InlineInterval(2_200f, 4_000f)),
            line.fragments.map { it.availableInterval },
        )
        assertEquals(
            listOf(range(fixture.snapshot, 0, 2), range(fixture.snapshot, 2, 4)),
            line.fragments.map { part ->
                val glyphs = part.positionedGlyphRuns.flatMap { it.glyphs }
                val starts = glyphs.map { it.mappedSourceRange.start }.sortedWith { left, right -> left.compareTo(right) }
                val ends = glyphs.map { it.mappedSourceRange.endExclusive }.sortedWith { left, right -> left.compareTo(right) }
                TextRange(starts.first(), ends.last())
            },
        )
        assertEquals(
            listOf(100f, 712.79297f, 2_300f, 2_849.8047f),
            line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.origin.x.value } },
        )
        // Frozen HarfBuzz 14.3.0 DejaVu Sans advances at size 1000. The first two glyphs
        // occupy 1247.5586 units; the third begins at the second logical interval, not in the gap.
    }

    @Test
    fun bidiVisualOrderAndCaretOrderCrossTheFragmentBoundaryOnlyOnce() {
        val fixture = fixture(
            "ab \u05D0\u05D1",
            language = "he",
            fontResource = "/fonts/liberation/LiberationSans-Regular.ttf",
            fontName = "Liberation Sans Regular",
        )
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(0f, 1_500f), InlineInterval(2_200f, 4_000f)),
        )

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()

        assertEquals(listOf(0, 1), line.positionedGlyphRuns.map { it.visualOrder }.distinct())
        assertEquals(listOf(0, 1), line.positionedGlyphRuns.map { it.sourceRun.bidiLevel }.distinct())
        assertEquals(
            listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 5)),
            line.positionedGlyphRuns.map { it.sourceRun.range },
        )
        assertEquals(line.allCaretCandidates.indices.toList(), line.allCaretCandidates.map { it.visualOrder })
        assertTrue(line.fragments.first().caretCandidates.last().visualOrder < line.fragments.last().caretCandidates.first().visualOrder)
        assertEquals(
            listOf(68, 69, 3, 1281, 1280),
            line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } },
        )
        // Frozen UAX #9 L2 order plus HarfBuzz 14.3.0 Liberation Sans glyph IDs. The region
        // boundary only translates the final visual stream and cannot create another run order.
    }

    @Test
    fun combiningClusterAndInlineObjectMoveWholeToTheNextInterval() {
        val objectDefinition = InlineObjectDefinition(
            id = InlineObjectId.create("flow-object"),
            width = LayoutUnit(900f),
            height = LayoutUnit(600f),
            baselineOffset = LayoutUnit(500f),
            alignment = InlineObjectAlignment.BASELINE,
        )
        val fixture = fixture("f\u0301\uFFFCx") { snapshot ->
            InlineObjectSnapshot(listOf(InlineObjectEntry(snapshot.textIndexAtScalarBoundary(2), objectDefinition)))
        }
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(0f, 100f), InlineInterval(1_000f, 3_000f)),
        )

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()
        val combining = range(fixture.snapshot, 0, 2)
        val objectRange = range(fixture.snapshot, 2, 3)

        assertTrue(line.fragments.first().positionedGlyphRuns.flatMap { it.glyphs }.none { glyph ->
            glyph.mappedSourceRange.start < combining.endExclusive && combining.start < glyph.mappedSourceRange.endExclusive
        })
        assertEquals(2, line.fragments.last().positionedGlyphRuns.flatMap { it.glyphs }.count { glyph ->
            glyph.mappedSourceRange.start < combining.endExclusive && combining.start < glyph.mappedSourceRange.endExclusive
        })
        assertEquals(listOf(objectRange), line.fragments.last().positionedInlineObjects.map { it.sourceRange })
        assertTrue(line.fragments.last().positionedInlineObjects.single().rect.left.value >= 1_000f + 100f)
    }

    @Test
    fun noProgressNamesTheIndivisibleObjectThatFitsNoInterval() {
        val objectDefinition = InlineObjectDefinition(
            id = InlineObjectId.create("too-wide"),
            width = LayoutUnit(900f),
            height = LayoutUnit(600f),
        )
        val fixture = fixture("\uFFFC") { snapshot ->
            InlineObjectSnapshot(listOf(InlineObjectEntry(snapshot.range.start, objectDefinition)))
        }
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(0f, 500f), InlineInterval(1_000f, 1_600f)),
        )

        val failure = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        )
        val error = assertIs<FlowCompositionError.NoProgress>(failure.error)

        assertEquals(fixture.snapshot.range, error.offendingRange)
        assertEquals(NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT, error.reason)
    }

    @Test
    fun noProgressNamesTheCompleteCombiningClusterThatFitsNoInterval() {
        val fixture = fixture("f\u0301")
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(0f, 100f), InlineInterval(1_000f, 1_200f)),
        )

        val failure = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        )
        val error = assertIs<FlowCompositionError.NoProgress>(failure.error)

        assertEquals(fixture.snapshot.range, error.offendingRange)
        assertEquals(NoProgressReason.CLUSTER_DOES_NOT_FIT, error.reason)
    }

    @Test
    fun tallInlineObjectRefinesTheSameBlockOriginWithOnlyGrowingBands() {
        val objectDefinition = InlineObjectDefinition(
            id = InlineObjectId.create("tall"),
            width = LayoutUnit(900f),
            height = LayoutUnit(1_400f),
            baselineOffset = LayoutUnit(1_200f),
            alignment = InlineObjectAlignment.BASELINE,
        )
        val fixture = fixture("\uFFFC") { snapshot ->
            InlineObjectSnapshot(listOf(InlineObjectEntry(snapshot.range.start, objectDefinition)))
        }
        val queries = mutableListOf<LineBand>()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                queries += lineBand
                return FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 4_000f)))
            }
        }

        success(FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region))

        assertEquals(listOf(0f, 0f, 0f, 0f), queries.map { it.blockStart })
        assertEquals(listOf(1_000f, 1_000f, 1_400f, 1_400f), queries.map { it.blockExtent })
    }

    @Test
    fun refinedBandCannotRegainPreviouslyExcludedInlineSpace() {
        val fixture = fixtureWithTallObject()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                FlowRegionResult.AvailableIntervals(
                    listOf(InlineInterval(0f, if (lineBand.blockExtent == 1_000f) 3_000f else 3_200f)),
                )
        }

        val failure = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        )

        assertIs<FlowCompositionError.NonConvergentFlowRegion>(failure.error)
    }

    @Test
    fun regionRefinementLimitFailsBeforeAnApproximateTallLineCanEscape() {
        val fixture = fixtureWithTallObject()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override val maximumRefinements: Int = 1
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 4_000f)))
        }

        val failure = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        )

        assertIs<FlowCompositionError.NonConvergentFlowRegion>(failure.error)
    }

    @Test
    fun malformedRegionOutputRemainsItsPublicTypedErrorThroughTheComposer() {
        val fixture = fixture("ab")
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(2_000f, 3_000f), InlineInterval(0f, 1_000f)),
        )

        val failure = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        )

        assertIs<FlowCompositionError.NonCanonicalIntervals>(failure.error)
    }

    @Test
    fun extremeFiniteFragmentProjectionReturnsFlowGeometryOverflow() {
        val bounds = LayoutRect(LayoutUnit(-3.4e38f), LayoutUnit(0f), LayoutUnit(-3.1e38f), LayoutUnit(2.0e38f))
        val fixture = fixture("j", bounds = bounds, fontSize = 1.0e38f)
        val region = FixedRegion(bounds, listOf(InlineInterval(0f, 2.9e37f)))

        val failure = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        )

        assertIs<FlowCompositionError.GeometryOverflow>(failure.error)
    }

    @Test
    fun gapHitTestingUsesTheNearestCaretTieBreakAndSelectionNeverFillsTheGap() {
        val fixture = fixture("abcd")
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(0f, 1_300f), InlineInterval(2_200f, 4_000f)),
        )
        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()
        val left = line.fragments.first().caretCandidates.last()
        val right = line.fragments.last().caretCandidates.first()
        val midpoint = (left.geometry.start.x.value + right.geometry.start.x.value) / 2f

        assertSame(left, line.hitTest(LayoutPoint(LayoutUnit(midpoint), line.baseline.y)))
        val rectangles = line.selectionGeometry(
            line.allCaretCandidates.first().position,
            line.allCaretCandidates.last().position,
        )
        assertEquals(2, rectangles.size)
        assertTrue(rectangles.none { rectangle -> rectangle.left.value < 2_300f && rectangle.right.value > 1_400f })
    }

    @Test
    fun validEmptyResponseAdvancesTheLogicalBlockStartBeforeComposition() {
        val fixture = fixture("ab")
        val queries = mutableListOf<LineBand>()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                queries += lineBand
                return if (lineBand.blockStart == 0f) {
                    FlowRegionResult.Empty(1_200f)
                } else {
                    FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 4_000f)))
                }
            }
        }

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()

        assertEquals(1_250f, line.lineBox.top.value)
        assertEquals(listOf(0f, 0f, 1_200f, 1_200f), queries.map { it.blockStart })
    }

    @Test
    fun unstableIdenticalRegionInputFailsWithoutPublishingApproximateGeometry() {
        val fixture = fixture("ab")
        var call = 0
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                if (call++ % 2 == 0) {
                    FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 2_000f)))
                } else {
                    FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 2_100f)))
                }
        }

        val result = FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region)

        assertIs<FlowCompositionError.NonConvergentFlowRegion>(
            assertIs<FlowCompositionResult.Failure>(result).error,
        )
    }

    @Test
    fun verticalRightToLeftUsesLogicalIntervalsAndPhysicalBlockProjection() {
        val bounds = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(3_100f), LayoutUnit(4_050f))
        val fixture = fixture("ab", writingMode = WritingMode.VERTICAL_RL, bounds = bounds)
        val region = FixedRegion(bounds, listOf(InlineInterval(0f, 1_100f), InlineInterval(2_200f, 4_000f)))

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()

        assertEquals(listOf(0f, 2_200f), line.fragments.map { it.availableInterval.start })
        assertEquals(LayoutRect(LayoutUnit(2_100f), LayoutUnit(50f), LayoutUnit(3_100f), LayoutUnit(4_050f)), line.lineBox)
        assertEquals(2_900f, line.baseline.x.value)
        val inlineOrigins = line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.origin.y.value - bounds.top.value } }
        assertTrue(inlineOrigins[0] in 0f..1_100f)
        assertTrue(inlineOrigins[1] in 2_200f..4_000f)
    }

    private fun success(result: FlowCompositionResult<org.graphiks.kalligraphie.api.ParagraphFragment>) =
        assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.ParagraphFragment>>(result).value

    private fun fixture(
        value: String,
        language: String = "en",
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        fontResource: String = "/fonts/dejavu/DejaVuSans.ttf",
        fontName: String = "DejaVu Sans",
        writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
        bounds: LayoutRect = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(4_100f), LayoutUnit(3_050f)),
        fontSize: Float = 1_000f,
        inlineObjects: (TextSnapshot) -> InlineObjectSnapshot? = { null },
    ): Fixture {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        val unicode = JvmUnicodeAnalyzer.create().analyze(snapshot, UnicodeAnalysisRequest(baseDirection, language))
        val breaks = JvmLineBreakAnalyzer.create().analyze(snapshot, unicode)
        val source = FontSource(
            checkNotNull(javaClass.getResourceAsStream(fontResource)).use { it.readBytes() },
            FontSourceProvenance(fontName),
        )
        val generation = FontCatalogGeneration("flow-composition-$fontName-v1")
        val catalog: FontCatalogSnapshot = EmbeddedFontCatalog(
            generation,
            listOf(EmbeddedFontCatalogEntry(source, SfntReader.readMetadata(source).successValue())),
        )
        val face = FontFaceId(source.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "flow-composition-policy",
            version = "1",
            candidates = listOf(FontResolutionCandidate(face)),
            lastResortFace = face,
        )
        val backend = JvmHarfBuzzShapingBackend.open().successValue().also(openedBackends::add)
        val metrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f))
        val constraints = if (writingMode == WritingMode.HORIZONTAL_TB) {
            HorizontalParagraphConstraints(bounds, metrics)
        } else {
            ParagraphConstraints(bounds, metrics, writingMode)
        }
        val request = ParagraphLayoutRequest(
            snapshot = snapshot,
            unicodeAnalysis = unicode,
            lineBreakAnalysis = breaks,
            constraints = constraints,
            baseDirection = baseDirection,
            language = language,
            featurePolicy = backend.identity.featurePolicy,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(fontSize)),
            shapingBackend = backend,
            materializationIdentity = ParagraphMaterializationIdentity.LayoutOnly,
            inlineObjects = inlineObjects(snapshot),
        )
        return Fixture(snapshot, request)
    }

    private fun fixtureWithTallObject(): Fixture {
        val definition = InlineObjectDefinition(
            id = InlineObjectId.create("refinement-tall"),
            width = LayoutUnit(900f),
            height = LayoutUnit(1_400f),
            baselineOffset = LayoutUnit(1_200f),
            alignment = InlineObjectAlignment.BASELINE,
        )
        return fixture("\uFFFC") { snapshot ->
            InlineObjectSnapshot(listOf(InlineObjectEntry(snapshot.range.start, definition)))
        }
    }

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value

    private data class Fixture(val snapshot: TextSnapshot, val request: ParagraphLayoutRequest)

    private class FixedRegion(
        override val bounds: LayoutRect,
        private val intervals: List<InlineInterval>,
    ) : FlowRegion {
        override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
        override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
            FlowRegionResult.AvailableIntervals(intervals)
    }
}
