package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

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
        val inputIdentity = FlowCompositionInputIdentity(text.version, TypographyVersion.create())
        val continuation = firstChain.createContinuation(
            inputIdentity = inputIdentity,
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
            inputIdentity = inputIdentity,
            continuation = continuation,
        )

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.ForeignContinuation>(failure.error)
    }

    @Test
    fun flowChainRejectsAContinuationAfterTheTextIdentityChanges() {
        val chain = FlowChain(listOf(region(FlowRegionResult.EndOfRegion)))
        val original = text("abc")
        val changed = text("abc")
        val typographyVersion = TypographyVersion.create()
        val continuation = chain.createContinuation(
            inputIdentity = FlowCompositionInputIdentity(original.version, typographyVersion),
            paragraphRange = original.range,
            remainingSourceRange = original.range,
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            nextBlockOffset = 0f,
        )

        val result = chain.query(
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            lineBand = LineBand(0f, 12f),
            inputIdentity = FlowCompositionInputIdentity(changed.version, typographyVersion),
            continuation = continuation,
        )

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.TextIdentityMismatch>(failure.error)
    }

    @Test
    fun flowChainRejectsAContinuationAfterTheTypographyIdentityChanges() {
        val chain = FlowChain(listOf(region(FlowRegionResult.EndOfRegion)))
        val text = text("abc")
        val originalIdentity = FlowCompositionInputIdentity(text.version, TypographyVersion.create())
        val continuation = chain.createContinuation(
            inputIdentity = originalIdentity,
            paragraphRange = text.range,
            remainingSourceRange = text.range,
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            nextBlockOffset = 0f,
        )

        val result = chain.query(
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            lineBand = LineBand(0f, 12f),
            inputIdentity = FlowCompositionInputIdentity(text.version, TypographyVersion.create()),
            continuation = continuation,
        )

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.TypographyIdentityMismatch>(failure.error)
    }

    @Test
    fun flowChainRejectsContinuationReuseWithoutCurrentInputIdentityProof() {
        val chain = FlowChain(listOf(region(FlowRegionResult.EndOfRegion)))
        val text = text("abc")
        val continuation = chain.createContinuation(
            inputIdentity = FlowCompositionInputIdentity(text.version, TypographyVersion.create()),
            paragraphRange = text.range,
            remainingSourceRange = text.range,
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            nextBlockOffset = 0f,
        )

        val result = chain.query(
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            lineBand = LineBand(0f, 12f),
            continuation = continuation,
        )

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.UnprovenInputIdentity>(failure.error)
    }

    @Test
    fun flowChainPreservesThePositionalContinuationParameterOrder() {
        val chain = FlowChain(listOf(region(FlowRegionResult.EndOfRegion)))
        val text = text("abc")
        val inputIdentity = FlowCompositionInputIdentity(text.version, TypographyVersion.create())
        val continuation = chain.createContinuation(
            inputIdentity = inputIdentity,
            paragraphRange = text.range,
            remainingSourceRange = text.range,
            regionIndex = 0,
            writingMode = WritingMode.HORIZONTAL_TB,
            nextBlockOffset = 0f,
        )

        val result = chain.query(
            0,
            WritingMode.HORIZONTAL_TB,
            LineBand(0f, 12f),
            continuation,
            inputIdentity,
        )

        assertIs<FlowCompositionResult.Success<FlowRegionResult>>(result)
    }

    @Test
    fun invalidRegionRefinementLimitIsAttributedToRegionNonConvergence() {
        val region = region(FlowRegionResult.EndOfRegion, maximumRefinements = 0)

        val result = queryFlowRegion(region, WritingMode.HORIZONTAL_TB, LineBand(0f, 12f))

        val failure = assertIs<FlowCompositionResult.Failure>(result)
        assertIs<FlowCompositionError.NonConvergentFlowRegion>(failure.error)
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

    @Test
    fun lineLayoutRejectsFragmentsThatEraseItsLogicalLineContent() {
        val fixture = fragmentedLineFixture()

        assertFailsWith<IllegalArgumentException> {
            fixture.lineLayout(
                listOf(
                    LineFragment(
                        availableInterval = InlineInterval(0f, 40f),
                        positionedGlyphRuns = emptyList(),
                        caretCandidates = emptyList(),
                        positionedInlineObjects = emptyList(),
                    ),
                ),
            )
        }
    }

    @Test
    fun lineLayoutFlattensTwoGeometricFragmentsForEditingOperations() {
        val fixture = fragmentedLineFixture()
        val firstRun = fixture.projectedRun(fixture.localLine.positionedGlyphRuns[0], x = 10f)
        val secondRun = fixture.projectedRun(fixture.localLine.positionedGlyphRuns[1], x = 30f)
        val firstStart = fixture.projectedCaret(fixture.localLine.allCaretCandidates[0], x = 10f)
        val firstEnd = fixture.projectedCaret(fixture.localLine.allCaretCandidates[1], x = 20f)
        val secondStart = fixture.projectedCaret(fixture.localLine.allCaretCandidates[2], x = 30f)
        val secondEnd = fixture.projectedCaret(fixture.localLine.allCaretCandidates[3], x = 40f)
        val projectedObject = PositionedInlineObject(
            sourceRange = fixture.localLine.positionedInlineObjects.single().sourceRange,
            definition = fixture.localLine.positionedInlineObjects.single().definition,
            rect = LayoutRect(LayoutUnit(30f), LayoutUnit(18f), LayoutUnit(40f), LayoutUnit(21f)),
        )
        val line = fixture.lineLayout(
            listOf(
                LineFragment(
                    availableInterval = InlineInterval(0f, 10f),
                    positionedGlyphRuns = listOf(firstRun),
                    caretCandidates = listOf(firstStart, firstEnd),
                ),
                LineFragment(
                    availableInterval = InlineInterval(20f, 30f),
                    positionedGlyphRuns = listOf(secondRun),
                    caretCandidates = listOf(secondStart, secondEnd),
                    positionedInlineObjects = listOf(projectedObject),
                ),
            ),
        )
        val paragraph = TestParagraphLayout(
            snapshot = fixture.snapshot,
            lineBreakAnalysis = fixture.lineBreakAnalysis,
            line = line,
        )

        assertEquals(listOf(firstRun, secondRun), line.positionedGlyphRuns)
        assertEquals(listOf(firstStart, firstEnd, secondStart, secondEnd), line.allCaretCandidates)
        assertEquals(listOf(projectedObject), line.positionedInlineObjects)
        assertEquals(
            listOf(
                LayoutRect(LayoutUnit(10f), LayoutUnit(18f), LayoutUnit(20f), LayoutUnit(21f)),
                LayoutRect(LayoutUnit(30f), LayoutUnit(18f), LayoutUnit(40f), LayoutUnit(21f)),
                LayoutRect(LayoutUnit(30f), LayoutUnit(18f), LayoutUnit(40f), LayoutUnit(21f)),
            ),
            paragraph.selectionGeometry(firstStart.position, secondEnd.position),
        )
        assertSame(firstEnd, paragraph.hitTest(LayoutPoint(LayoutUnit(25f), LayoutUnit(20f))))
    }

    private fun region(
        result: FlowRegionResult,
        maximumRefinements: Int = 8,
        onQuery: () -> Unit = {},
    ): FlowRegion = object : FlowRegion {
        override val identity: FlowRegionIdentity = FlowRegionIdentity.create()
        override val bounds: LayoutRect = LayoutRect(
            left = LayoutUnit(0f),
            top = LayoutUnit(0f),
            right = LayoutUnit(100f),
            bottom = LayoutUnit(100f),
        )
        override val maximumRefinements: Int = maximumRefinements

        override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult {
            onQuery()
            return result
        }
    }

    private class TestParagraphLayout(
        snapshot: TextSnapshot,
        lineBreakAnalysis: LineBreakAnalysis,
        line: LineLayout,
    ) : ParagraphLayout(snapshot, lineBreakAnalysis, snapshot.range, listOf(line)) {
        override fun nextLogical(position: CaretPosition, direction: LogicalNavigationDirection): CaretPosition? = null

        override fun nextVisual(candidate: CaretCandidate, direction: VisualNavigationDirection): CaretCandidate? = null

        override fun caretCandidates(position: CaretPosition): List<CaretCandidate> =
            lines.single().allCaretCandidates.filter { candidate -> candidate.position == position }
    }

    private class FragmentedLineFixture(
        val snapshot: TextSnapshot,
        val lineBreakAnalysis: LineBreakAnalysis,
        val localLine: EditableLine,
    ) {
        fun lineLayout(fragments: List<LineFragment>): LineLayout = LineLayout(
            line = localLine,
            baseline = LayoutPoint(LayoutUnit(10f), LayoutUnit(20f)),
            contentMetrics = LineContentMetrics(LayoutUnit(2f), LayoutUnit(1f), LayoutUnit(20f)),
            lineBox = LayoutRect(LayoutUnit(10f), LayoutUnit(18f), LayoutUnit(50f), LayoutUnit(21f)),
            designInkBounds = LayoutBounds(LayoutUnit(10f), LayoutUnit(18f), LayoutUnit(40f), LayoutUnit(21f)),
            fragments = fragments,
        )

        fun projectedRun(run: PositionedGlyphRun, x: Float): PositionedGlyphRun {
            val original = run.glyphs.single()
            return PositionedGlyphRun(
                sourceRun = run.sourceRun,
                visualOrder = run.visualOrder,
                renderAssetKey = run.renderAssetKey,
                glyphs = listOf(
                    PositionedGlyph(
                        shapedGlyph = original.shapedGlyph,
                        sourceClusters = original.sourceClusters,
                        origin = LayoutPoint(LayoutUnit(x), LayoutUnit(19f)),
                        advance = original.advance,
                        transform = original.transform,
                        renderAssetKey = original.renderAssetKey,
                        materializationCertificate = original.materializationCertificate,
                        provenance = original.provenance,
                    ),
                ),
            )
        }

        fun projectedCaret(candidate: CaretCandidate, x: Float): CaretCandidate = CaretCandidate(
            position = candidate.position,
            geometry = LayoutSegment(
                LayoutPoint(LayoutUnit(x), LayoutUnit(18f)),
                LayoutPoint(LayoutUnit(x), LayoutUnit(21f)),
            ),
            visualOrder = candidate.visualOrder,
            visualRunOrder = candidate.visualRunOrder,
            bidiLevel = candidate.bidiLevel,
            direction = candidate.direction,
            strength = candidate.strength,
            edge = candidate.edge,
        )
    }

    private fun fragmentedLineFixture(): FragmentedLineFixture {
        val snapshot = text("a\uFFFC")
        val firstRange = TextRange(snapshot.range.start, snapshot.textIndexAtScalarBoundary(1))
        val secondRange = TextRange(snapshot.textIndexAtScalarBoundary(1), snapshot.range.endExclusive)
        val backendIdentity = ShapingBackendIdentity(
            backendId = "flow-test",
            nativeVersion = "1",
            nativeSourceRevision = "source",
            nativeArtifactId = "artifact",
            nativeArtifactSha256 = "0".repeat(64),
            featurePolicy = ShapingFeaturePolicy(
                policyId = "flow-test-features",
                version = "1",
                application = ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
            ),
            configurationFingerprint = "flow-test-config",
        )
        val descriptor = FontInstanceDescriptor(LayoutUnit(12f))
        val fontKey = FontInstanceKey(
            face = FontFaceId(FontSourceId.Opaque("flow-test", "1", "face"), 0),
            interpretation = FontDataInterpretationVersion("flow-test", "1"),
            layoutSize = descriptor.layoutSize,
            geometry = descriptor.geometry,
        )
        val runs = listOf(firstRange, secondRange).mapIndexed { index, range ->
            val token = ShaperClusterToken(index)
            val cluster = ShaperCluster(
                token = token,
                sourceRange = range,
                scalarRanges = listOf(range),
                admissibleGraphemeBoundaries = listOf(range.start, range.endExclusive),
            )
            val glyph = ShapedGlyph(
                glyphId = GlyphId(7 + index),
                xAdvance = LayoutUnit(10f),
                yAdvance = LayoutUnit(0f),
                xOffset = LayoutUnit(0f),
                yOffset = LayoutUnit(-1f),
                safetyFlags = ShapingSafetyFlags(unsafeToBreak = false, unsafeToConcat = false),
                clusterTokens = listOf(token),
            )
            val shaped = ShapedGlyphRun(
                range = range,
                fontInstanceKey = fontKey,
                backendIdentity = backendIdentity,
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                bot = index == 0,
                eot = index == 1,
                featurePolicy = backendIdentity.featurePolicy,
                features = emptyList(),
                graphemeClusters = listOf(range),
                glyphs = listOf(glyph),
                clusters = listOf(cluster),
            )
            PositionedGlyphRun(
                sourceRun = shaped,
                visualOrder = index,
                renderAssetKey = null,
                glyphs = listOf(
                    PositionedGlyph(
                        shapedGlyph = glyph,
                        sourceClusters = listOf(cluster),
                        origin = LayoutPoint(LayoutUnit(index * 10f), LayoutUnit(-1f)),
                        advance = LayoutVector(LayoutUnit(10f), LayoutUnit(0f)),
                        renderAssetKey = null,
                        materializationCertificate = null,
                    ),
                ),
            )
        }
        val metrics = LineVerticalMetrics(LayoutUnit(2f), LayoutUnit(1f))
        fun caret(
            index: TextIndex,
            affinity: CaretAffinity,
            x: Float,
            order: Int,
            runOrder: Int,
            edge: CaretBoundaryEdge,
        ): CaretCandidate = CaretCandidate(
            position = CaretPosition(index, affinity),
            geometry = LayoutSegment(
                LayoutPoint(LayoutUnit(x), LayoutUnit(-2f)),
                LayoutPoint(LayoutUnit(x), LayoutUnit(1f)),
            ),
            visualOrder = order,
            visualRunOrder = runOrder,
            bidiLevel = 0,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            strength = CaretStrength.STRONG,
            edge = edge,
        )
        val boundary = firstRange.endExclusive
        val localLine = EditableLine(
            range = snapshot.range,
            baseDirection = ShapingDirection.LEFT_TO_RIGHT,
            verticalMetrics = metrics,
            positionedGlyphRuns = runs,
            caretCandidates = listOf(
                caret(snapshot.range.start, CaretAffinity.DOWNSTREAM, 0f, 0, 0, CaretBoundaryEdge.LOGICAL_START),
                caret(boundary, CaretAffinity.UPSTREAM, 10f, 1, 0, CaretBoundaryEdge.LOGICAL_END),
                caret(boundary, CaretAffinity.DOWNSTREAM, 10f, 2, 1, CaretBoundaryEdge.LOGICAL_START),
                caret(snapshot.range.endExclusive, CaretAffinity.UPSTREAM, 20f, 3, 1, CaretBoundaryEdge.LOGICAL_END),
            ),
            inlineObjects = listOf(
                PositionedInlineObject(
                    sourceRange = secondRange,
                    definition = InlineObjectDefinition(
                        id = InlineObjectId.create("flow-test-object"),
                        width = LayoutUnit(10f),
                        height = LayoutUnit(3f),
                    ),
                    rect = LayoutRect(LayoutUnit(10f), LayoutUnit(-2f), LayoutUnit(20f), LayoutUnit(1f)),
                ),
            ),
        )
        val unicodeData = UnicodeDataIdentity("17.0", "flow-test", "1")
        return FragmentedLineFixture(
            snapshot = snapshot,
            lineBreakAnalysis = LineBreakAnalysis(
                range = snapshot.range,
                unicodeData = unicodeData,
                graphemeClusters = listOf(firstRange, secondRange),
                opportunities = emptyList(),
            ),
            localLine = localLine,
        )
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
