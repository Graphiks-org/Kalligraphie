package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FlowCompositionContractsTest {
    @Test
    fun flowQueryRejectsIntervalsThatAreNotInCanonicalLogicalOrder() {
        val region = region(
            FlowRegionResult.AvailableIntervals(
                listOf(
                    InlineInterval(40f, 70f),
                    InlineInterval(10f, 30f),
                ),
            ),
        )

        val result = queryFlowRegion(region, WritingMode.HORIZONTAL_TB, LineBand(0f, 12f))

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.NonCanonicalIntervals>(failure.error)
        assertEquals(1, failure.error.intervalIndex)
    }

    @Test
    fun flowQueryRejectsANonFiniteIntervalCoordinate() {
        var callCount = 0
        val region = region(
            FlowRegionResult.AvailableIntervals(listOf(InlineInterval(0f, Float.POSITIVE_INFINITY))),
        ) { callCount += 1 }

        val result = queryFlowRegion(region, WritingMode.HORIZONTAL_TB, LineBand(0f, 12f))

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.NonFiniteCoordinate>(failure.error)
        assertEquals(FlowCoordinate.INTERVAL_END, failure.error.coordinate)
        assertEquals(1, callCount)
    }

    @Test
    fun flowQueryRejectsEmptyWithoutStrictBlockProgress() {
        val region = region(FlowRegionResult.Empty(nextBlockOffset = 16f))

        val result = queryFlowRegion(region, WritingMode.HORIZONTAL_TB, LineBand(16f, 12f))

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.NonProgressingEmpty>(failure.error)
    }

    @Test
    fun flowChainRejectsAContinuationFromAnotherComposition() {
        val firstChain = FlowChain(listOf(region(FlowRegionResult.EndOfRegion)))
        val secondChain = FlowChain(listOf(region(FlowRegionResult.EndOfRegion)))
        val text = text("abc")
        val continuation = firstChain.createContinuation(
            textVersion = text.version,
            paragraphRange = text.range,
            remainingSourceRange = text.range,
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            nextBlockOffset = 0f,
        )

        val result = secondChain.query(
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            lineBand = LineBand(0f, 12f),
            continuation = continuation,
        )

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.ForeignContinuation>(failure.error)
    }

    @Test
    fun lineFragmentSnapshotsItsPositionedItemCollections() {
        val boundary = text("").range.start
        val candidate = CaretCandidate(
            position = CaretPosition(boundary, CaretAffinity.DOWNSTREAM),
            geometry = LayoutSegment(
                LayoutPoint(LayoutUnit(10f), LayoutUnit(0f)),
                LayoutPoint(LayoutUnit(10f), LayoutUnit(12f)),
            ),
            visualOrder = 0,
            visualRunOrder = CaretCandidate.NO_POSITIONED_RUN,
            bidiLevel = 0,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            strength = CaretStrength.STRONG,
            edge = CaretBoundaryEdge.INTERNAL,
        )
        val runs = mutableListOf<PositionedGlyphRun>()
        val carets = mutableListOf(candidate)
        val objects = mutableListOf<PositionedInlineObject>()
        val fragment = LineFragment(
            availableInterval = InlineInterval(10f, 30f),
            positionedGlyphRuns = runs,
            caretCandidates = carets,
            positionedInlineObjects = objects,
        )

        runs.clear()
        carets.clear()
        objects.clear()

        assertEquals(emptyList(), fragment.positionedGlyphRuns)
        assertEquals(listOf(candidate), fragment.caretCandidates)
        assertEquals(emptyList(), fragment.positionedInlineObjects)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (fragment.caretCandidates as MutableList<CaretCandidate>).clear()
        }
    }

    @Test
    fun finalParagraphFragmentCannotSilentlyLeaveSourceUncovered() {
        val text = text("abc")
        val emptyPrefix = TextRange(text.range.start, text.range.start)

        assertFailsWith<IllegalArgumentException> {
            ParagraphFragment(
                paragraphRange = text.range,
                laidOutRange = emptyPrefix,
                isFirstFragment = true,
                isLastFragment = true,
                lines = emptyList(),
            )
        }
    }

    private fun region(
        result: FlowRegionResult,
        onQuery: () -> Unit = {},
    ): FlowRegion = object : FlowRegion {
        override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
        override val bounds: LayoutRect = LayoutRect(
            left = LayoutUnit(0f),
            top = LayoutUnit(0f),
            right = LayoutUnit(100f),
            bottom = LayoutUnit(100f),
        )

        override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
            onQuery()
            return result
        }
    }

    private fun text(value: String): TextSnapshot {
        val version = TextVersion.create()
        return TextSnapshot(
            version = version,
            sourceEncoding = SourceEncoding.UTF16,
            scalars = value.map(Char::code),
            sourceRanges = value.indices.map { offset ->
                SourceRange(
                    SourceOffset(version, SourceEncoding.UTF16, offset),
                    SourceOffset(version, SourceEncoding.UTF16, offset + 1),
                )
            },
        )
    }
}
