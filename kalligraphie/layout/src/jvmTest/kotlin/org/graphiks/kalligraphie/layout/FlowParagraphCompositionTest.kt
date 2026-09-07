package org.graphiks.kalligraphie.layout

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EllipsisSide
import org.graphiks.kalligraphie.api.FlowChain
import org.graphiks.kalligraphie.api.FlowCompositionDiagnostic
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionInputIdentity
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowContinuation
import org.graphiks.kalligraphie.api.FlowRegion
import org.graphiks.kalligraphie.api.FlowRegionIdentity
import org.graphiks.kalligraphie.api.FlowRegionResult
import org.graphiks.kalligraphie.api.FragmentationConstraintKind
import org.graphiks.kalligraphie.api.FragmentationConstraints
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
import org.graphiks.kalligraphie.api.LineBreakAnalysis
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.NoProgressReason
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TypographyVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysis
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
    fun oneRtlVisualRunCrossesTheFragmentBoundaryWithoutRestartingBidi() {
        val fixture = fixture(
            "ab \u05D0\u05D1",
            language = "he",
            fontResource = "/fonts/liberation/LiberationSans-Regular.ttf",
            fontName = "Liberation Sans Regular",
        )
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(0f, 2_100f), InlineInterval(2_600f, 4_000f)),
        )

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()

        assertEquals(listOf(0, 1, 1), line.positionedGlyphRuns.map { it.visualOrder })
        assertEquals(listOf(0, 1, 1), line.positionedGlyphRuns.map { it.sourceRun.bidiLevel })
        assertEquals(
            listOf(
                range(fixture.snapshot, 0, 3),
                range(fixture.snapshot, 4, 5),
                range(fixture.snapshot, 3, 4),
            ),
            line.positionedGlyphRuns.map { it.sourceRun.range },
        )
        assertEquals(
            listOf(100f, 656.15234f, 1_212.3047f, 1_490.1367f, 2_700f),
            line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.origin.x.value } },
        )
        assertEquals(line.allCaretCandidates.indices.toList(), line.allCaretCandidates.map { it.visualOrder })
        assertEquals(
            listOf(
                fixture.snapshot.textIndexAtScalarBoundary(0),
                fixture.snapshot.textIndexAtScalarBoundary(1),
                fixture.snapshot.textIndexAtScalarBoundary(2),
                fixture.snapshot.textIndexAtScalarBoundary(3),
                fixture.snapshot.textIndexAtScalarBoundary(5),
                fixture.snapshot.textIndexAtScalarBoundary(4),
                fixture.snapshot.textIndexAtScalarBoundary(3),
            ),
            line.allCaretCandidates.map { it.position.index },
        )
        assertTrue(line.fragments.first().positionedGlyphRuns.any { it.visualOrder == 1 })
        assertTrue(line.fragments.last().positionedGlyphRuns.any { it.visualOrder == 1 })
        assertTrue(line.fragments.first().caretCandidates.last().visualOrder < line.fragments.last().caretCandidates.first().visualOrder)
        assertEquals(
            listOf(68, 69, 3, 1281, 1280),
            line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } },
        )
        // Frozen UAX #9 L2 order, source boundaries, HarfBuzz 14.3.0 Liberation Sans glyph IDs,
        // and font-unit positions. Both fragments retain visual run 1, so resolving either
        // fragment independently would reverse or duplicate one of these literal sequences.
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
    fun refinedTallObjectOccupiesTheExactAcceptedBandAroundItsBaseline() {
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

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()
        val objectItem = line.positionedInlineObjects.single()
        val selection = line.selectionGeometry(
            line.allCaretCandidates.first().position,
            line.allCaretCandidates.last().position,
        )

        assertEquals(listOf(0f, 0f, 0f, 0f), queries.map { it.blockStart })
        assertEquals(listOf(1_000f, 1_000f, 1_400f, 1_400f), queries.map { it.blockExtent })
        assertEquals(1_250f, line.baseline.y.value)
        assertEquals(
            LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(4_100f), LayoutUnit(1_450f)),
            line.lineBox,
        )
        assertEquals(
            LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(1_000f), LayoutUnit(1_450f)),
            objectItem.rect,
        )
        assertTrue(line.designInkBounds.minY >= line.lineBox.top)
        assertTrue(line.designInkBounds.maxY <= line.lineBox.bottom)
        assertTrue(selection.contains(objectItem.rect))
        assertTrue(selection.all { rectangle ->
            rectangle.top >= line.lineBox.top && rectangle.bottom <= line.lineBox.bottom
        })
    }

    @Test
    fun refinementBudgetResetsAfterEachProgressingEmptyBand() {
        val fixture = fixture("ab")
        val queries = mutableListOf<LineBand>()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override val maximumRefinements: Int = 1
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                queries += lineBand
                return when (lineBand.blockStart) {
                    0f -> FlowRegionResult.Empty(500f)
                    500f -> FlowRegionResult.Empty(1_000f)
                    else -> FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 4_000f)))
                }
            }
        }

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()

        assertEquals(listOf(0f, 0f, 500f, 500f, 1_000f, 1_000f), queries.map { it.blockStart })
        assertEquals(1_050f, line.lineBox.top.value)
    }

    @Test
    fun adjacentIntervalsMayMergeWithoutRegainingLogicalSpace() {
        val fixture = fixtureWithTallObject()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                FlowRegionResult.AvailableIntervals(
                    if (lineBand.blockExtent == 1_000f) {
                        listOf(InlineInterval(0f, 1_500f), InlineInterval(1_500f, 4_000f))
                    } else {
                        listOf(InlineInterval(0f, 4_000f))
                    },
                )
        }

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()

        assertEquals(listOf(InlineInterval(0f, 4_000f)), line.fragments.map { it.availableInterval })
        assertEquals(1_400f, line.lineBox.bottom.value - line.lineBox.top.value)
    }

    @Test
    fun canonicalFractionalFloatMetricsPublishTheirExactAcceptedBand() {
        val metrics = LineVerticalMetrics(LayoutUnit(1_000.11f), LayoutUnit(304.07f))
        val bounds = LayoutRect(LayoutUnit(100f), LayoutUnit(0f), LayoutUnit(4_100f), LayoutUnit(3_000f))
        val fixture = fixture("a", bounds = bounds, lineMetrics = metrics)
        val queries = mutableListOf<LineBand>()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = bounds
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                queries += lineBand
                return FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 4_000f)))
            }
        }

        val line = success(
            FlowParagraphComposer.layoutLine(fixture.request, EditableLineMaterialization.LayoutOnly, region),
        ).lines.single()

        assertEquals(listOf(1_304.1799f, 1_304.1799f), queries.map { it.blockExtent })
        assertEquals(metrics, line.verticalMetrics)
        assertEquals(1_000.11f, line.baseline.y.value)
        assertEquals(
            LayoutRect(LayoutUnit(100f), LayoutUnit(0f), LayoutUnit(4_100f), LayoutUnit(1_304.1799f)),
            line.lineBox,
        )
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
        assertEquals(LayoutRect(LayoutUnit(1_857.6172f), LayoutUnit(50f), LayoutUnit(3_100f), LayoutUnit(4_050f)), line.lineBox)
        assertEquals(2_657.6172f, line.baseline.x.value)
        val inlineOrigins = line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.origin.y.value - bounds.top.value } }
        assertTrue(inlineOrigins[0] in 0f..1_100f)
        assertTrue(inlineOrigins[1] in 2_200f..4_000f)
    }

    @Test
    fun chainEmptyAdvancesStrictlyAndLaterRegionRestartsAtItsLocalOrigin() {
        val fixture = fixture("ab ab")
        val firstQueries = mutableListOf<LineBand>()
        val secondQueries = mutableListOf<LineBand>()
        val firstBounds = LayoutRect(LayoutUnit(10f), LayoutUnit(20f), LayoutUnit(1_610f), LayoutUnit(1_020f))
        val secondBounds = LayoutRect(LayoutUnit(70f), LayoutUnit(90f), LayoutUnit(1_670f), LayoutUnit(1_090f))
        val first = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = firstBounds
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                firstQueries += lineBand
                return when (lineBand.blockStart) {
                    0f -> FlowRegionResult.Empty(1_000f)
                    else -> FlowRegionResult.EndOfRegion
                }
            }
        }
        val second = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = secondBounds
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                secondQueries += lineBand
                return FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 1_600f)))
            }
        }

        val result = FlowParagraphComposer.layoutFragment(
            fixture.request,
            EditableLineMaterialization.LayoutOnly,
            FlowChain(listOf(first, second)),
            flowIdentity(fixture),
        )
        val fragment = success(result)

        assertEquals(listOf(0f, 0f), firstQueries.map(LineBand::blockStart))
        assertTrue(secondQueries.isNotEmpty())
        assertTrue(secondQueries.all { it.blockStart == 0f })
        assertEquals(secondBounds.top, fragment.lines.single().lineBox.top)
    }

    @Test
    fun twoRegionFragmentsAndContinuationPartitionTheParagraphExactly() {
        val fixture = fixture("ab ab")
        val regionOne = FixedRegion(
            LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_600f), LayoutUnit(1_000f)),
            listOf(InlineInterval(0f, 1_600f)),
        )
        val regionTwo = FixedRegion(
            LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_600f), LayoutUnit(1_000f)),
            listOf(InlineInterval(0f, 1_600f)),
        )
        val chain = FlowChain(listOf(regionOne, regionTwo))
        val identity = flowIdentity(fixture)

        val first = success(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                chain,
                identity,
            ),
        )
        val continuation = checkNotNull(first.continuation)
        val second = success(
            FlowParagraphComposer.layoutFragment(
                fixture.request.withFlowSourceRange(continuation.remainingSourceRange),
                EditableLineMaterialization.LayoutOnly,
                chain,
                identity,
                continuation,
            ),
        )

        assertEquals(fixture.snapshot.range, first.paragraphRange)
        assertEquals(first.laidOutRange.endExclusive, continuation.remainingSourceRange.start)
        assertEquals(continuation.remainingSourceRange, second.laidOutRange)
        assertEquals(fixture.snapshot.range.endExclusive, second.laidOutRange.endExclusive)
        assertTrue(first.isFirstFragment)
        assertTrue(!first.isLastFragment)
        assertTrue(!second.isFirstFragment)
        assertTrue(second.isLastFragment)
        assertEquals(null, second.continuation)
        assertEquals(1, continuation.regionIndex)
        assertEquals(0f, continuation.nextBlockOffset)
    }

    @Test
    fun aPlacedInlineObjectDoesNotPreventTheExactSuffixFromContinuing() {
        val definition = InlineObjectDefinition(
            id = InlineObjectId.create("continued-flow-object"),
            width = LayoutUnit(500f),
            height = LayoutUnit(600f),
        )
        val fixture = fixture("\uFFFC\nab") { snapshot ->
            InlineObjectSnapshot(listOf(InlineObjectEntry(snapshot.range.start, definition)))
        }
        val chain = FlowChain(
            listOf(
                FixedRegion(
                    LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_000f), LayoutUnit(1_000f)),
                    listOf(InlineInterval(0f, 1_000f)),
                ),
                FixedRegion(
                    LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(4_000f), LayoutUnit(2_000f)),
                    listOf(InlineInterval(0f, 4_000f)),
                ),
            ),
        )

        val first = success(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                chain,
                flowIdentity(fixture),
            ),
        )

        val continuation = checkNotNull(first.continuation)
        val resumedRequest = fixture.request.withFlowSourceRange(continuation.remainingSourceRange)

        assertEquals(listOf(range(fixture.snapshot, 0, 1)), first.lines.single().positionedInlineObjects.map { it.sourceRange })
        assertEquals(first.laidOutRange.endExclusive, continuation.remainingSourceRange.start)
        assertEquals(fixture.snapshot.range.endExclusive, continuation.remainingSourceRange.endExclusive)
        assertIs<FlowCompositionResult.Success<Unit>>(
            chain.validateContinuation(continuation, resumedRequest, continuation.inputIdentity),
        )
    }

    @Test
    fun impossibleFragmentationRulesRelaxInDocumentedOrderWithoutDroppingSource() {
        val fixture = fixture("ab ab ab")
        val constraints = FragmentationConstraints(
            minLinesAtStart = 2,
            minLinesAtEnd = 3,
            keepTogether = true,
            keepWithNext = true,
        )
        val chain = FlowChain(
            listOf(
                FixedRegion(
                    LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_600f), LayoutUnit(1_000f)),
                    listOf(InlineInterval(0f, 1_600f)),
                ),
                FixedRegion(
                    LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_600f), LayoutUnit(1_000f)),
                    listOf(InlineInterval(0f, 1_600f)),
                ),
            ),
            constraints,
        )

        val outcome = assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.ParagraphFragment>>(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                chain,
                flowIdentity(fixture),
            ),
        )
        val continuation = checkNotNull(outcome.value.continuation)

        assertEquals(
            listOf(
                FragmentationConstraintKind.KEEP_WITH_NEXT,
                FragmentationConstraintKind.KEEP_TOGETHER,
                FragmentationConstraintKind.MIN_LINES_AT_END,
                FragmentationConstraintKind.MIN_LINES_AT_START,
            ),
            outcome.diagnostics.map { diagnostic ->
                assertIs<FlowCompositionDiagnostic.FragmentationRelaxed>(diagnostic).constraint
            },
        )
        assertEquals(outcome.value.laidOutRange.endExclusive, continuation.remainingSourceRange.start)
        assertEquals(fixture.snapshot.range.endExclusive, continuation.remainingSourceRange.endExclusive)

        val resumed = assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.ParagraphFragment>>(
            FlowParagraphComposer.layoutFragment(
                fixture.request.withFlowSourceRange(continuation.remainingSourceRange),
                EditableLineMaterialization.LayoutOnly,
                chain,
                continuation.inputIdentity,
                continuation,
            ),
        )
        assertEquals(emptyList(), resumed.diagnostics)
        assertEquals(
            listOf(
                FragmentationConstraintKind.KEEP_WITH_NEXT,
                FragmentationConstraintKind.KEEP_TOGETHER,
                FragmentationConstraintKind.MIN_LINES_AT_END,
                FragmentationConstraintKind.MIN_LINES_AT_START,
            ),
            checkNotNull(resumed.value.continuation).relaxedConstraints,
        )
        assertEquals(continuation.remainingSourceRange.start, resumed.value.laidOutRange.start)
    }

    @Test
    fun eachRelaxationReevaluatesLaterCandidatesBeforeRelaxingTheNextRule() {
        val fixture = fixture("ab ab ab")
        val first = FixedRegion(
            LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_600f), LayoutUnit(1_000f)),
            listOf(InlineInterval(0f, 1_600f)),
        )
        val secondBounds =
            LayoutRect(LayoutUnit(40f), LayoutUnit(60f), LayoutUnit(1_640f), LayoutUnit(2_060f))
        val second = FixedRegion(secondBounds, listOf(InlineInterval(0f, 1_600f)))
        val result = assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.ParagraphFragment>>(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                FlowChain(
                    listOf(first, second),
                    FragmentationConstraints(
                        minLinesAtEnd = 2,
                        keepTogether = true,
                        keepWithNext = true,
                    ),
                ),
                flowIdentity(fixture),
            ),
        )

        assertEquals(secondBounds.top, result.value.lines.first().lineBox.top)
        assertEquals(2, result.value.lines.size)
        assertEquals(
            listOf(
                FragmentationConstraintKind.KEEP_WITH_NEXT,
                FragmentationConstraintKind.KEEP_TOGETHER,
            ),
            result.diagnostics.map { diagnostic ->
                assertIs<FlowCompositionDiagnostic.FragmentationRelaxed>(diagnostic).constraint
            },
        )
        assertEquals(
            listOf(
                FragmentationConstraintKind.KEEP_WITH_NEXT,
                FragmentationConstraintKind.KEEP_TOGETHER,
            ),
            checkNotNull(result.value.continuation).relaxedConstraints,
        )
    }

    @Test
    fun keepWithNextRelaxesBeforeEvenACompleteParagraphIsFitted() {
        val fixture = fixture("ab")
        val result = assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.ParagraphFragment>>(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                FlowChain(
                    listOf(
                        FixedRegion(
                            fixture.request.constraints.region,
                            listOf(InlineInterval(0f, 4_000f)),
                        ),
                    ),
                    FragmentationConstraints(keepWithNext = true),
                ),
                flowIdentity(fixture),
            ),
        )

        assertEquals(fixture.snapshot.range, result.value.laidOutRange)
        assertEquals(null, result.value.continuation)
        assertEquals(
            listOf(FragmentationConstraintKind.KEEP_WITH_NEXT),
            result.diagnostics.map { diagnostic ->
                assertIs<FlowCompositionDiagnostic.FragmentationRelaxed>(diagnostic).constraint
            },
        )
    }

    @Test
    fun keepTogetherMovesTheCompleteParagraphToTheNextRegionWhenItFitsThere() {
        val fixture = fixture("ab ab")
        val first = FixedRegion(
            LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_600f), LayoutUnit(1_000f)),
            listOf(InlineInterval(0f, 1_600f)),
        )
        val secondBounds =
            LayoutRect(LayoutUnit(40f), LayoutUnit(60f), LayoutUnit(1_640f), LayoutUnit(2_060f))
        val second = FixedRegion(secondBounds, listOf(InlineInterval(0f, 1_600f)))
        val result = assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.ParagraphFragment>>(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                FlowChain(listOf(first, second), FragmentationConstraints(keepTogether = true)),
                flowIdentity(fixture),
            ),
        )

        assertEquals(fixture.snapshot.range, result.value.laidOutRange)
        assertEquals(2, result.value.lines.size)
        assertEquals(secondBounds.top, result.value.lines.first().lineBox.top)
        assertEquals(emptyList(), result.diagnostics)
        assertEquals(null, result.value.continuation)
    }

    @Test
    fun emptyParagraphStillPublishesItsCompletePhysicalLine() {
        val fixture = fixture("")
        val region = FixedRegion(
            fixture.request.constraints.region,
            listOf(InlineInterval(0f, 4_000f)),
        )

        val fragment = success(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                FlowChain(listOf(region)),
                flowIdentity(fixture),
            ),
        )

        assertEquals(fixture.snapshot.range, fragment.laidOutRange)
        assertEquals(listOf(fixture.snapshot.range), fragment.lines.map { it.range })
        assertTrue(fragment.isFirstFragment)
        assertTrue(fragment.isLastFragment)
    }

    @Test
    fun mandatoryTerminalEmptyLineContinuesWithoutDroppingItsPhysicalLine() {
        val fixture = fixture("ab\n")
        val regions = List(2) {
            FixedRegion(
                LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(4_000f), LayoutUnit(1_000f)),
                listOf(InlineInterval(0f, 4_000f)),
            )
        }
        val chain = FlowChain(regions)
        val identity = flowIdentity(fixture)

        val first = success(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                chain,
                identity,
            ),
        )
        val continuation = checkNotNull(first.continuation)
        val second = success(
            FlowParagraphComposer.layoutFragment(
                fixture.request.withFlowSourceRange(continuation.remainingSourceRange),
                EditableLineMaterialization.LayoutOnly,
                chain,
                identity,
                continuation,
            ),
        )

        val terminal = TextRange(fixture.snapshot.range.endExclusive, fixture.snapshot.range.endExclusive)
        assertEquals(listOf(fixture.snapshot.range), first.lines.map { it.range })
        assertEquals(terminal, continuation.remainingSourceRange)
        assertTrue(!first.isLastFragment)
        assertEquals(listOf(terminal), second.lines.map { it.range })
        assertTrue(second.isLastFragment)
        assertEquals(null, second.continuation)
    }

    @Test
    fun flowChainRejectsEllipsisBeforeQueryingARegion() {
        val fixture = fixture("ab ab")
        var queries = 0
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
            override val bounds: LayoutRect = fixture.request.constraints.region
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                queries += 1
                return FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 1_600f)))
            }
        }

        val result = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutFragment(
                fixture.request.withFlowSourceRange(
                    fixture.snapshot.range,
                    overflowPolicy = OverflowPolicy.Ellipsis(EllipsisSide.INLINE_END),
                ),
                EditableLineMaterialization.LayoutOnly,
                FlowChain(listOf(region)),
                flowIdentity(fixture),
            ),
        )

        assertIs<FlowCompositionError.UnsupportedOverflowPolicy>(result.error)
        assertEquals(0, queries)
    }

    @Test
    fun chainFailuresPublishNoFragmentForEveryInvalidRegionProtocol() {
        val ordinary = fixture("ab")
        val tall = fixtureWithTallObject()

        fun assertChainFailure(
            fixture: Fixture,
            region: FlowRegion,
            expected: (FlowCompositionError) -> Boolean,
        ) {
            val result = FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                FlowChain(listOf(region)),
                flowIdentity(fixture),
            )
            val error = assertIs<FlowCompositionResult.Failure>(result).error
            assertTrue(expected(error), "Unexpected flow error: $error")
        }

        assertChainFailure(
            ordinary,
            FixedRegion(
                ordinary.request.constraints.region,
                listOf(InlineInterval(2_000f, 3_000f), InlineInterval(0f, 1_000f)),
            ),
        ) { it is FlowCompositionError.NonCanonicalIntervals }

        var unstableCall = 0
        assertChainFailure(
            ordinary,
            object : FlowRegion {
                override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
                override val bounds: LayoutRect = ordinary.request.constraints.region
                override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                    FlowRegionResult.AvailableIntervals(
                        listOf(InlineInterval(0f, if (unstableCall++ % 2 == 0) 2_000f else 2_100f)),
                    )
            },
        ) { it is FlowCompositionError.NonConvergentFlowRegion }

        assertChainFailure(
            ordinary,
            object : FlowRegion {
                override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
                override val bounds: LayoutRect = ordinary.request.constraints.region
                override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                    FlowRegionResult.Empty(lineBand.blockStart)
            },
        ) { it is FlowCompositionError.NonProgressingEmpty }

        assertChainFailure(
            tall,
            object : FlowRegion {
                override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
                override val bounds: LayoutRect = tall.request.constraints.region
                override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                    FlowRegionResult.AvailableIntervals(
                        listOf(InlineInterval(0f, if (lineBand.blockExtent == 1_000f) 3_000f else 3_200f)),
                    )
            },
        ) { it is FlowCompositionError.NonConvergentFlowRegion }

        assertChainFailure(
            tall,
            object : FlowRegion {
                override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
                override val bounds: LayoutRect = tall.request.constraints.region
                override val maximumRefinements: Int = 1
                override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                    FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 4_000f)))
            },
        ) { it is FlowCompositionError.NonConvergentFlowRegion }

        assertChainFailure(
            ordinary,
            object : FlowRegion {
                override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
                override val bounds: LayoutRect = ordinary.request.constraints.region
                override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                    FlowRegionResult.Empty(lineBand.blockStart + 1f)
            },
        ) { it is FlowCompositionError.NonConvergentFlowRegion }
    }

    @Test
    fun chainResumeRejectsForeignAndChangedSemanticInputsBeforePublishingLayout() {
        val fixture = fixture("ab ab")
        val firstRegionIdentity = FlowRegionIdentity.create()
        val region = object : FlowRegion {
            override val identity: FlowRegionIdentity = firstRegionIdentity
            override val bounds: LayoutRect =
                LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_600f), LayoutUnit(1_000f))
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 1_600f)))
        }
        var continuationRegionIdentity = FlowRegionIdentity.create()
        val continuationRegion = object : FlowRegion {
            override val identity: FlowRegionIdentity
                get() = continuationRegionIdentity
            override val bounds: LayoutRect = region.bounds
            override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
                FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 1_600f)))
        }
        val chain = FlowChain(listOf(region, continuationRegion))
        val inputIdentity = flowIdentity(fixture)
        val first = success(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                chain,
                inputIdentity,
            ),
        )
        val continuation = checkNotNull(first.continuation)
        val resumed = fixture.request.withFlowSourceRange(continuation.remainingSourceRange)

        fun error(
            request: ParagraphLayoutRequest = resumed,
            selectedChain: FlowChain = chain,
            identity: FlowCompositionInputIdentity = inputIdentity,
            selectedContinuation: FlowContinuation = continuation,
        ): FlowCompositionError = assertIs<FlowCompositionResult.Failure>(
            FlowParagraphComposer.layoutFragment(
                request,
                EditableLineMaterialization.LayoutOnly,
                selectedChain,
                identity,
                selectedContinuation,
            ),
        ).error

        assertIs<FlowCompositionError.TextIdentityMismatch>(
            error(identity = FlowCompositionInputIdentity(TextVersion.create(), inputIdentity.typographyVersion)),
        )
        assertIs<FlowCompositionError.TypographyIdentityMismatch>(
            error(identity = FlowCompositionInputIdentity(inputIdentity.textVersion, TypographyVersion.create())),
        )
        assertIs<FlowCompositionError.ForeignContinuation>(
            error(selectedChain = FlowChain(chain.regions, chain.fragmentationConstraints)),
        )
        assertIs<FlowCompositionError.IncompatibleContinuation>(
            error(
                request = resumed.withFlowSourceRange(
                    resumed.sourceRange,
                    lineMetrics = LineVerticalMetrics(LayoutUnit(700f), LayoutUnit(300f)),
                ),
            ),
        )
        assertIs<FlowCompositionError.IncompatibleContinuation>(
            error(
                request = resumed.withFlowSourceRange(
                    resumed.sourceRange,
                    fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(900f)),
                ),
            ),
        )
        val unproven = chain.createContinuation(
            inputIdentity = inputIdentity,
            paragraphRange = fixture.snapshot.range,
            remainingSourceRange = continuation.remainingSourceRange,
            regionIndex = continuation.regionIndex,
            writingMode = continuation.writingMode,
            nextBlockOffset = continuation.nextBlockOffset,
        )
        assertIs<FlowCompositionError.UnprovenInputIdentity>(error(selectedContinuation = unproven))
        continuationRegionIdentity = FlowRegionIdentity.create()
        assertIs<FlowCompositionError.ForeignContinuation>(error())
    }

    @Test
    fun chainResumeRejectsContradictoryAnalysesSharingUnicodeDataBeforeQueryingARegion() {
        val fixture = fixture("ab ab")
        var queries = 0
        val regions = List(2) {
            object : FlowRegion {
                override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
                override val bounds: LayoutRect =
                    LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(1_600f), LayoutUnit(1_000f))
                override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
                    queries += 1
                    return FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, 1_600f)))
                }
            }
        }
        val chain = FlowChain(regions)
        val identity = flowIdentity(fixture)
        val first = success(
            FlowParagraphComposer.layoutFragment(
                fixture.request,
                EditableLineMaterialization.LayoutOnly,
                chain,
                identity,
            ),
        )
        val continuation = checkNotNull(first.continuation)
        val queriesBeforeResume = queries
        val originalUnicode = fixture.request.unicodeAnalysis
        val contradictoryUnicode = UnicodeAnalysis(
            range = originalUnicode.range,
            unicodeData = originalUnicode.unicodeData,
            graphemeClusters = originalUnicode.graphemeClusters,
            scriptLanguageRuns = originalUnicode.scriptLanguageRuns.map { run ->
                run.copy(script = if (run.script == "Latn") "Arab" else "Latn")
            },
            logicalBidiRuns = originalUnicode.logicalBidiRuns,
            visualBidiRuns = originalUnicode.visualBidiRuns,
        )
        val originalBreaks = fixture.request.lineBreakAnalysis
        val contradictoryBreaks = LineBreakAnalysis(
            range = originalBreaks.range,
            unicodeData = originalBreaks.unicodeData,
            graphemeClusters = originalBreaks.graphemeClusters,
            opportunities = originalBreaks.opportunities.drop(1),
        )

        fun rejected(request: ParagraphLayoutRequest) {
            val result = assertIs<FlowCompositionResult.Failure>(
                FlowParagraphComposer.layoutFragment(
                    request,
                    EditableLineMaterialization.LayoutOnly,
                    chain,
                    identity,
                    continuation,
                ),
            )
            assertIs<FlowCompositionError.IncompatibleContinuation>(result.error)
            assertEquals(queriesBeforeResume, queries)
        }

        rejected(
            fixture.request.withFlowSourceRange(
                continuation.remainingSourceRange,
                unicodeAnalysis = contradictoryUnicode,
            ),
        )
        rejected(
            fixture.request.withFlowSourceRange(
                continuation.remainingSourceRange,
                lineBreakAnalysis = contradictoryBreaks,
            ),
        )
    }

    private fun success(result: FlowCompositionResult<org.graphiks.kalligraphie.api.ParagraphFragment>) =
        assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.ParagraphFragment>>(
            result,
            "Expected flow success, got ${(result as? FlowCompositionResult.Failure)?.error}",
        ).value

    private fun flowIdentity(fixture: Fixture): FlowCompositionInputIdentity =
        FlowCompositionInputIdentity(fixture.snapshot.version, TypographyVersion.create())

    private fun ParagraphLayoutRequest.withFlowSourceRange(
        sourceRange: TextRange,
        lineMetrics: LineVerticalMetrics = constraints.lineMetrics,
        fontInstanceDescriptor: FontInstanceDescriptor = this.fontInstanceDescriptor,
        unicodeAnalysis: UnicodeAnalysis = this.unicodeAnalysis,
        lineBreakAnalysis: LineBreakAnalysis = this.lineBreakAnalysis,
        overflowPolicy: OverflowPolicy = this.overflowPolicy,
    ): ParagraphLayoutRequest =
        ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = sourceRange,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = ParagraphConstraints(constraints.region, lineMetrics, constraints.writingMode),
            baseDirection = baseDirection,
            language = language,
            featurePolicy = featurePolicy,
            features = features,
            fontCatalog = fontCatalog,
            resolutionPolicy = resolutionPolicy,
            fontInstanceDescriptor = fontInstanceDescriptor,
            shapingBackend = shapingBackend,
            materializationIdentity = materializationIdentity,
            overflowPolicy = overflowPolicy,
            positioning = positioning,
            hyphenationMode = hyphenationMode,
            hyphenationService = hyphenationService,
            inlineObjects = inlineObjects?.let { snapshot ->
                InlineObjectSnapshot(snapshot.entries.filter { entry ->
                    entry.index >= sourceRange.start && entry.index < sourceRange.endExclusive
                })
            },
            textOrientation = textOrientation,
            verticalMetricsPolicy = verticalMetricsPolicy,
            cancellationToken = cancellationToken,
        )

    private fun fixture(
        value: String,
        language: String = "en",
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        fontResource: String = "/fonts/dejavu/DejaVuSans.ttf",
        fontName: String = "DejaVu Sans",
        writingMode: WritingMode = WritingMode.HORIZONTAL_TB,
        bounds: LayoutRect = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(4_100f), LayoutUnit(3_050f)),
        fontSize: Float = 1_000f,
        lineMetrics: LineVerticalMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
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
        val constraints = if (writingMode == WritingMode.HORIZONTAL_TB) {
            HorizontalParagraphConstraints(bounds, lineMetrics)
        } else {
            ParagraphConstraints(bounds, lineMetrics, writingMode)
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
