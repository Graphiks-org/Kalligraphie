package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.FlowChain
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowRegion
import org.graphiks.kalligraphie.api.FlowRegionIdentity
import org.graphiks.kalligraphie.api.FlowRegionResult
import org.graphiks.kalligraphie.api.InlineInterval
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutTailState
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBand
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.WritingMode

class FlowCompositionEditorJourneyTest {
    @Test
    fun earlyEditMatchesIndependentFullFlowCompositionForMaterializedCoverage() {
        val source = incrementalRealFontFixture("fi fi fi")
        val target = source.withText("ii fi fi")
        val chain = horizontalChain(4)
        val initial = success(
            JvmFlowCompositionFacade.layout(request(source, chain)),
        )
        val change = assertIs<org.graphiks.kalligraphie.api.LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(
                    TextChange(
                        source.snapshot.incrementalRange(0, 1),
                        target.snapshot.incrementalRange(0, 1),
                    ),
                ),
            ),
        ).value

        val edited = success(
            JvmFlowCompositionFacade.layout(
                request(
                    target,
                    chain,
                    previousState = initial.state,
                    delta = LayoutDelta(text = change),
                ),
            ),
        )
        val full = success(JvmFlowCompositionFacade.layout(request(target, chain)))

        assertEquals(
            listOf(
                target.snapshot.incrementalRange(0, 3),
                target.snapshot.incrementalRange(3, 6),
                target.snapshot.incrementalRange(6, 8),
            ),
            edited.fragments.map { it.laidOutRange },
        )
        assertEquals(target.snapshot.range, edited.coverage.range)
        assertEquals(LayoutTailState.MaterializedThroughDocumentEnd, edited.coverage.tailState)
        assertNull(edited.unmaterializedTail)
        assertEquals(full.fragments.map { it.laidOutRange }, edited.fragments.map { it.laidOutRange })
        assertEquals(full.lines.map(LineLayout::glyphIds), edited.lines.map(LineLayout::glyphIds))
        assertEquals(full.lines.map(LineLayout::glyphAdvances), edited.lines.map(LineLayout::glyphAdvances))
        assertEquals(full.lines.map(LineLayout::lineBox), edited.lines.map(LineLayout::lineBox))
        assertEquals(
            full.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
            edited.lines.map { line -> line.allCaretCandidates.map { it.position.index } },
        )
        assertEquals(
            listOf(100f, 2_100f, 4_100f),
            edited.lines.map { it.lineBox.top.value },
        )
        assertEquals(
            listOf(listOf(0, 1, 2, 2, 3), listOf(3, 4, 5, 5, 6), listOf(6, 7, 8)),
            edited.lines.map { line ->
                line.allCaretCandidates.map { caret ->
                    (0..8).single { ordinal ->
                        caret.position.index == target.snapshot.textIndexAtScalarBoundary(ordinal)
                    }
                }
            },
        )
    }

    @Test
    fun boundedCoveragePublishesExactTailAndDoesNotTouchLaterRegions() {
        val fixture = incrementalRealFontFixture("fi fi fi")
        val queries = MutableList(4) { 0 }
        val chain = horizontalChain(4, queries)

        val partial = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )

        assertEquals(listOf(fixture.snapshot.incrementalRange(0, 3)), partial.fragments.map { it.laidOutRange })
        assertEquals(fixture.snapshot.incrementalRange(0, 3), partial.coverage.range)
        assertEquals(
            LayoutTailState.Invalidated(fixture.snapshot.incrementalRange(3, 8)),
            partial.coverage.tailState,
        )
        val tail = assertNotNull(partial.unmaterializedTail)
        assertEquals(fixture.snapshot.incrementalRange(3, 8), tail.remainingSourceRange)
        assertEquals(1, tail.regionIndex)
        assertEquals(chain.regions[1].identity, tail.regionIdentity)
        assertEquals(0f, tail.nextBlockOffset)
        assertEquals(listOf(true, false, false, false), queries.map { it > 0 })
        assertEquals(1, partial.state.checkpoints.size)
        assertEquals(1, partial.state.checkpoints.single().resumeRegionOrdinal)
        assertEquals(chain.regions[1].identity, partial.state.checkpoints.single().resumeRegionIdentity)
        assertEquals(0f, partial.state.checkpoints.single().blockCursor)
    }

    @Test
    fun laterCoverageResumesFromTheStructuredTailAndHonorsLineOverscan() {
        val fixture = incrementalRealFontFixture("fi fi fi fi")
        val queries = MutableList(4) { 0 }
        val chain = horizontalChain(4, queries)
        val first = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )
        val firstRegionQueries = queries[0]

        val extended = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    chain,
                    requestedRange = fixture.snapshot.incrementalRange(3, 4),
                    overscan = 1,
                    previousState = first.state,
                ),
            ),
        )

        assertEquals(
            listOf(
                fixture.snapshot.incrementalRange(0, 3),
                fixture.snapshot.incrementalRange(3, 6),
                fixture.snapshot.incrementalRange(6, 9),
            ),
            extended.fragments.map { it.laidOutRange },
        )
        assertEquals(firstRegionQueries, queries[0])
        assertTrue(queries[1] > 0)
        assertTrue(queries[2] > 0)
        assertEquals(0, queries[3])
        assertEquals(fixture.snapshot.textIndexAtScalarBoundary(3), extended.diagnostics.reflowStart)
        assertEquals(false, extended.diagnostics.usedConservativeInvalidation)
        assertNotNull(extended.unmaterializedTail)

        val independent = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    horizontalChain(4),
                    requestedRange = fixture.snapshot.incrementalRange(3, 4),
                    overscan = 1,
                ),
            ),
        )
        assertEquals(independent.fragments.map { it.laidOutRange }, extended.fragments.map { it.laidOutRange })
        assertEquals(independent.lines.map(LineLayout::glyphIds), extended.lines.map(LineLayout::glyphIds))
        assertEquals(independent.lines.map(LineLayout::lineBox), extended.lines.map(LineLayout::lineBox))
    }

    @Test
    fun stateFromAnIncompatibleChainIsRejectedBeforeEitherChainIsQueried() {
        val fixture = incrementalRealFontFixture("fi fi fi")
        val originalQueries = MutableList(4) { 0 }
        val originalChain = horizontalChain(4, originalQueries)
        val partial = success(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    originalChain,
                    requestedRange = fixture.snapshot.incrementalRange(0, 1),
                ),
            ),
        )
        val replacementQueries = MutableList(4) { 0 }
        val replacementChain = horizontalChain(4, replacementQueries)
        val originalCount = originalQueries.sum()

        val rejected = assertIs<FlowCompositionResult.Failure>(
            JvmFlowCompositionFacade.layout(
                request(
                    fixture,
                    replacementChain,
                    requestedRange = fixture.snapshot.incrementalRange(3, 4),
                    previousState = partial.state,
                ),
            ),
        )

        assertIs<FlowCompositionError.IncompatibleState>(rejected.error)
        assertEquals(originalCount, originalQueries.sum())
        assertTrue(replacementQueries.all { it == 0 })
    }

    @Test
    fun verticalRegionUsesTheSameFacadeWithoutOwningAPageOrRenderer() {
        val fixture = incrementalRealFontFixture("f")
        val bounds = LayoutRect(LayoutUnit(400f), LayoutUnit(200f), LayoutUnit(3_400f), LayoutUnit(4_200f))
        val region = FixedFlowRegion(bounds, listOf(InlineInterval(0f, 4_000f)))
        val constraints = ParagraphConstraints(
            region = bounds,
            lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
            writingMode = WritingMode.VERTICAL_RL,
        )

        val composed = success(
            JvmFlowCompositionFacade.layout(
                request(fixture, FlowChain(listOf(region)), constraints = constraints),
            ),
        )

        assertEquals(fixture.snapshot.range, composed.coverage.range)
        assertEquals(bounds.right, composed.lines.single().lineBox.right)
        assertTrue(composed.lines.single().allCaretCandidates.isNotEmpty())
    }

    private fun request(
        fixture: IncrementalRealFontFixture,
        chain: FlowChain,
        requestedRange: TextRange = fixture.snapshot.range,
        constraints: ParagraphConstraints = incrementalTestConstraints(width = 1_600f, top = 100f, height = 1_200f),
        overscan: Int = 0,
        previousState: JvmFlowCompositionState? = null,
        delta: LayoutDelta? = null,
    ): JvmFlowCompositionRequest = JvmFlowCompositionRequest(
        input = LayoutInput(fixture.snapshot, fixture.typography),
        requestedRange = requestedRange,
        constraints = constraints,
        flowChain = chain,
        overscan = LineOverscan(overscan),
        previousState = previousState,
        delta = delta,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
    )

    private fun horizontalChain(count: Int, queries: MutableList<Int>? = null): FlowChain = FlowChain(
        List(count) { index ->
            FixedFlowRegion(
                bounds = LayoutRect(
                    LayoutUnit(100f),
                    LayoutUnit(100f + index * 2_000f),
                    LayoutUnit(1_700f),
                    LayoutUnit(1_300f + index * 2_000f),
                ),
                intervals = listOf(InlineInterval(0f, 1_600f)),
                onQuery = { queries?.let { it[index] += 1 } },
            )
        },
    )

    private fun success(
        result: FlowCompositionResult<JvmFlowCompositionLayout>,
    ): JvmFlowCompositionLayout = assertIs<FlowCompositionResult.Success<JvmFlowCompositionLayout>>(
        result,
        "Expected flow success, got ${(result as? FlowCompositionResult.Failure)?.error}",
    ).value

    private class FixedFlowRegion(
        override val bounds: LayoutRect,
        private val intervals: List<InlineInterval>,
        private val onQuery: () -> Unit = {},
    ) : FlowRegion {
        override val identity: FlowRegionIdentity = FlowRegionIdentity.create()

        override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
            onQuery()
            return FlowRegionResult.AvailableIntervals(intervals)
        }
    }
}
