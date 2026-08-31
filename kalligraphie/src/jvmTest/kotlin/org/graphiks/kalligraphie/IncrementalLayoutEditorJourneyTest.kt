package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicyDelta
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.IncrementalLayoutRequest
import org.graphiks.kalligraphie.api.IncrementalLayoutError
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LayoutTailState
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TypographyDelta
import org.graphiks.kalligraphie.api.createIncrementalLayoutRequest

class IncrementalLayoutEditorJourneyTest {
    @Test
    fun insertionDeletionAndReplacementInsideOneParagraphMatchIndependentFullLayouts() {
        val initial = incrementalRealFontFixture("fi fi")
        val inserted = initial.withText("fi fi fi")
        val deleted = inserted.withText("fi fi")
        val replaced = deleted.withText("fi \u0633\u0644\u0627\u0645")
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints)))
            assertEquivalentToFull(first, fullLayout(initial, constraints))

            val second = success(
                session.layout(
                    request(
                        inserted,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, inserted, 5, 5, 5, 8)),
                    ),
                ),
            )
            assertEquivalentToFull(second, fullLayout(inserted, constraints))

            val third = success(
                session.layout(
                    request(
                        deleted,
                        constraints = constraints,
                        previousState = second.layout.state,
                        delta = LayoutDelta(text = change(inserted, deleted, 5, 8, 5, 5)),
                    ),
                ),
            )
            assertEquivalentToFull(third, fullLayout(deleted, constraints))

            val fourth = success(
                session.layout(
                    request(
                        replaced,
                        constraints = constraints,
                        previousState = third.layout.state,
                        delta = LayoutDelta(text = change(deleted, replaced, 3, 5, 3, 7)),
                    ),
                ),
            )
            assertEquivalentToFull(fourth, fullLayout(replaced, constraints))
            assertEquals(listOf(replaced.snapshot.incrementalRange(0, 7)), fourth.layout.lines.map(LineLayout::range))
            assertEquals(listOf(3, 1, 85, 3080, 3075, 1919), fourth.layout.lines.single().glyphIds())
            assertEquals(
                listOf(900f, 292f, 452f, 446f, 245f, 568f),
                fourth.layout.lines.single().glyphAdvances(),
            )
            assertEquals(replaced.snapshot.range, fourth.layout.coveredRange)
        }
    }

    @Test
    fun editsAtVisibleBeginningMiddleAndEndPreserveAuditedLineGeometry() {
        val initial = incrementalRealFontFixture("fi\nfi\nfi")
        val atBeginning = initial.withText("ii\nfi\nfi")
        val atMiddle = atBeginning.withText("ii\nii\nfi")
        val atEnd = atMiddle.withText("ii\nii\nii")

        openSession().use { session ->
            val first = success(session.layout(request(initial)))
            val beginning = success(
                session.layout(
                    request(
                        atBeginning,
                        requestedRange = atBeginning.snapshot.incrementalRange(0, 2),
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, atBeginning, 0, 1, 0, 1)),
                    ),
                ),
            )
            assertEquivalentToFull(beginning, fullLayout(atBeginning, incrementalTestConstraints()))
            val middle = success(
                session.layout(
                    request(
                        atMiddle,
                        requestedRange = atMiddle.snapshot.incrementalRange(3, 5),
                        previousState = beginning.layout.state,
                        delta = LayoutDelta(text = change(atBeginning, atMiddle, 3, 4, 3, 4)),
                    ),
                ),
            )
            assertEquivalentToFull(middle, fullLayout(atMiddle, incrementalTestConstraints()))
            val end = success(
                session.layout(
                    request(
                        atEnd,
                        requestedRange = atEnd.snapshot.incrementalRange(6, 8),
                        previousState = middle.layout.state,
                        delta = LayoutDelta(text = change(atMiddle, atEnd, 6, 7, 6, 7)),
                    ),
                ),
            )
            assertEquivalentToFull(end, fullLayout(atEnd, incrementalTestConstraints()))

            assertEquals(listOf(atEnd.snapshot.incrementalRange(6, 8)), end.layout.lines.map(LineLayout::range))
            assertEquals(atEnd.snapshot.incrementalRange(6, 8), end.layout.coveredRange)
            assertEquals(
                LayoutRect(LayoutUnit(100f), LayoutUnit(2_450f), LayoutUnit(1_500f), LayoutUnit(3_650f)),
                end.layout.lines.single().lineBox,
            )
            assertEquals(3, end.layout.lines.single().allCaretCandidates.size)
        }
    }

    @Test
    fun insertionReflowsSeveralSoftWrappedLinesAndStillMatchesFullComposition() {
        val fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"))
        val initial = incrementalRealFontFixture("one two three", fonts)
        val target = initial.withText("one twenty two three")
        val constraints = incrementalTestConstraints(width = 3_000f, height = 8_400f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints, language = "en")))
            assertEquals(
                listOf(
                    initial.snapshot.incrementalRange(0, 4),
                    initial.snapshot.incrementalRange(4, 8),
                    initial.snapshot.incrementalRange(8, 13),
                ),
                first.layout.lines.map(LineLayout::range),
            )
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, target, 4, 4, 4, 11)),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints, language = "en"))
            assertEquals(target.snapshot.range, edited.layout.coveredRange)
            assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
            assertEquals(
                listOf(
                    target.snapshot.incrementalRange(0, 4),
                    target.snapshot.incrementalRange(4, 11),
                    target.snapshot.incrementalRange(11, 15),
                    target.snapshot.incrementalRange(15, 20),
                ),
                edited.layout.lines.map(LineLayout::range),
            )
            assertEquals(LayoutUnit(100f), edited.layout.lines.first().lineBox.left)
        }
    }

    @Test
    fun paragraphBoundaryInsertionPublishesWholeParagraphLines() {
        val initial = incrementalRealFontFixture("fi fi")
        val target = initial.withText("fi\nfi")
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints)))
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, target, 2, 3, 2, 3)),
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints))
            assertEquals(
                listOf(target.snapshot.incrementalRange(0, 3), target.snapshot.incrementalRange(3, 5)),
                edited.layout.lines.map(LineLayout::range),
            )
            assertEquals(listOf(listOf(3), listOf(3)), edited.layout.lines.map { it.glyphIds() })
            assertEquals(target.snapshot.range, edited.layout.coveredRange)
        }
    }

    @Test
    fun ligatureToCombiningMarkEditKeepsClustersCaretsAndGlyphsAuditable() {
        val fonts = listOf(
            IncrementalFontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
            IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"),
        )
        val initial = incrementalRealFontFixture("fi", fonts)
        val target = initial.withText("f\u0301")
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints, language = "en")))
            assertEquals(listOf(3), first.layout.lines.single().glyphIds())
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, target, 1, 2, 1, 2)),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints, language = "en"))
            assertEquals(listOf(73, 5923), edited.layout.lines.single().glyphIds())
            assertEquals(listOf(352.05078f, 0f), edited.layout.lines.single().glyphAdvances())
            assertEquals(
                listOf(target.snapshot.incrementalRange(0, 1), target.snapshot.incrementalRange(1, 2)),
                edited.layout.lines.single().positionedGlyphRuns.flatMap { it.sourceRun.clusters }.map { it.sourceRange }.distinct(),
            )
            assertEquals(2, edited.layout.lines.single().allCaretCandidates.size)
        }
    }

    @Test
    fun bidiDirectionalContextEditMatchesFullVisualRuns() {
        val fonts = listOf(IncrementalFontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular"))
        val initial = incrementalRealFontFixture("abc \u05D0\u05D1\u05D2   \u05E9\u05DC\u05D5\u05DD", fonts)
        val target = initial.withText("\u05D0abc \u05D0\u05D1\u05D2   \u05E9\u05DC\u05D5\u05DD")
        val constraints = incrementalTestConstraints(width = 4_500f, height = 3_600f)

        openSession().use { session ->
            val first = success(session.layout(request(initial, constraints = constraints, language = "he")))
            val edited = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, target, 0, 0, 0, 1)),
                        baseDirection = BaseDirection.RIGHT_TO_LEFT,
                        language = "he",
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, constraints, BaseDirection.RIGHT_TO_LEFT, "he"))
            assertEquals(target.snapshot.range, edited.layout.coveredRange)
            assertEquals(listOf(1, 2, 1), edited.layout.lines.first().positionedGlyphRuns.map { it.sourceRun.bidiLevel })
            assertEquals(listOf(0, 1, 2), edited.layout.lines.first().positionedGlyphRuns.map { it.visualOrder })
        }
    }

    @Test
    fun fallbackPolicyAndTypographyChangeForceDocumentStartReflow() {
        val initial = incrementalRealFontFixture("fi \u0633\u0644\u0627\u0645")
        val reversedPolicy = FontResolutionPolicySnapshot(
            generation = initial.catalog.generation,
            policyId = "incremental-session-fixture-reversed",
            version = "2",
            candidates = initial.faces.reversed().map(::FontResolutionCandidate),
            lastResortFace = initial.faces.first(),
        )
        val target = initial.withTypography(
            policy = reversedPolicy,
            fontInstanceDescriptor = org.graphiks.kalligraphie.api.FontInstanceDescriptor(LayoutUnit(1_200f)),
            features = listOf(OpenTypeFeature("liga", 0)),
        )
        val typographyDelta = TypographyDelta(
            sourceVersion = initial.typography.version,
            targetVersion = target.typography.version,
            fontResolutionPolicy = FontResolutionPolicyDelta(initial.policy, reversedPolicy),
        )

        openSession().use { session ->
            val first = success(session.layout(request(initial)))
            val edited = success(
                session.layout(
                    request(
                        target,
                        previousState = first.layout.state,
                        delta = LayoutDelta(typography = typographyDelta),
                    ),
                ),
            )
            assertEquivalentToFull(edited, fullLayout(target, incrementalTestConstraints()))
            assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
            assertTrue(edited.diagnostics.usedConservativeInvalidation)
            assertEquals(target.snapshot.range, edited.layout.coveredRange)
            assertEquals(
                setOf(LayoutUnit(1_200f)),
                edited.layout.lines.flatMap { it.positionedGlyphRuns }.map { it.fontInstanceKey.layoutSize }.toSet(),
            )
            assertEquals(
                setOf(OpenTypeFeature("liga", 0)),
                edited.layout.lines.flatMap { it.positionedGlyphRuns }.flatMap { it.sourceRun.features }.toSet(),
            )
        }
    }

    @Test
    fun visibleRangeWithOneLineOverscanPublishesExactWholeLineCoverage() {
        val fixture = incrementalRealFontFixture("fi\nfi\nfi\nfi")
        val requested = fixture.snapshot.incrementalRange(3, 5)

        openSession().use { session ->
            val result = success(session.layout(request(fixture, requestedRange = requested, overscan = 1)))
            assertEquivalentToFull(result, fullLayout(fixture, incrementalTestConstraints()))
            assertEquals(
                listOf(
                    fixture.snapshot.incrementalRange(0, 3),
                    fixture.snapshot.incrementalRange(3, 6),
                    fixture.snapshot.incrementalRange(6, 9),
                ),
                result.layout.lines.map(LineLayout::range),
            )
            assertEquals(fixture.snapshot.incrementalRange(0, 9), result.layout.coveredRange)
            assertEquals(
                fixture.snapshot.incrementalRange(9, 11),
                assertIs<LayoutTailState.Invalidated>(result.layout.coverage.tailState).range,
            )
        }
    }

    @Test
    fun malformedVersionsOverlapsAndCrossSpaceRangesAreRejectedWithTypedErrors() {
        val source = incrementalRealFontFixture("abcd")
        val target = source.withText("aXd")
        val foreign = source.withText("aYd")
        val overlap = TextChangeSet.create(
            source.snapshot,
            target.snapshot,
            listOf(
                TextChange(source.snapshot.incrementalRange(0, 2), target.snapshot.incrementalRange(0, 1)),
                TextChange(source.snapshot.incrementalRange(1, 3), target.snapshot.incrementalRange(1, 2)),
            ),
        )
        assertIs<IncrementalLayoutError.OverlappingRanges>(assertIs<LayoutContractResult.Failure>(overlap).error)

        val crossSpace = TextChangeSet.create(
            source.snapshot,
            target.snapshot,
            listOf(TextChange(foreign.snapshot.incrementalRange(1, 3), target.snapshot.incrementalRange(1, 2))),
        )
        assertIs<IncrementalLayoutError.VersionMismatch>(assertIs<LayoutContractResult.Failure>(crossSpace).error)

        openSession().use { session ->
            val previous = success(session.layout(request(source)))
            val wrongTargetDelta = change(source, foreign, 1, 3, 1, 2)
            val requestResult = createIncrementalLayoutRequest(
                LayoutInput(target.snapshot, target.typography),
                target.snapshot.range,
                incrementalTestConstraints(),
                LineOverscan(0),
                previous.layout.state,
                LayoutDelta(text = wrongTargetDelta),
                CancellationToken.none,
            )
            assertIs<IncrementalLayoutError.VersionMismatch>(assertIs<LayoutContractResult.Failure>(requestResult).error)
            assertEquals(source.snapshot.version, session.currentLayout()?.layout?.inputIdentity?.textVersion)
        }
    }

    @Test
    fun incompatibleConstraintsUseConservativeFullReflowAndMatchFullLayout() {
        val fixture = incrementalRealFontFixture("fi \u0633\u0644\u0627\u0645")
        val changedConstraints = incrementalTestConstraints(width = 10_000f, top = 500f)

        openSession().use { session ->
            val previous = success(session.layout(request(fixture)))
            val result = success(session.layout(request(fixture, constraints = changedConstraints, previousState = previous.layout.state)))
            assertEquivalentToFull(result, fullLayout(fixture, changedConstraints))
            assertEquals(fixture.snapshot.range.start, result.diagnostics.reflowStart)
            assertTrue(result.diagnostics.usedConservativeInvalidation)
            assertEquals(LayoutUnit(500f), result.layout.lines.single().lineBox.top)
            assertEquals(fixture.snapshot.range, result.layout.coveredRange)
        }
    }

    @Test
    fun staleCompletionCannotReplaceNewerAuditedPublication() {
        val firstFixture = incrementalRealFontFixture("fi \u0633\u0644\u0627\u0645")
        val newerFixture = firstFixture.withText("fi fi")
        val constraints = incrementalTestConstraints(width = 10_000f)

        openSession().use { session ->
            val first = success(session.layout(request(firstFixture, constraints = constraints)))
            val newer = success(session.layout(request(newerFixture, constraints = constraints)))
            assertEquivalentToFull(newer, fullLayout(newerFixture, constraints))
            assertIs<IncrementalLayoutResult.Obsolete>(session.publishForTesting(first, generation = 1L))
            assertEquals(newerFixture.snapshot.version, session.currentLayout()?.layout?.inputIdentity?.textVersion)
            assertEquals(listOf(3, 1, 3), session.currentLayout()?.layout?.lines?.single()?.glyphIds())
            assertEquals(newerFixture.snapshot.range, session.currentLayout()?.layout?.coveredRange)
        }
    }

    @Test
    fun cancellationKeepsThePreviousFullEquivalentPublication() {
        val initial = incrementalRealFontFixture("fi \u0633\u0644\u0627\u0645")
        val target = initial.withText("fi \u0633\u0644\u0627\u0645 fi \u0633\u0644\u0627\u0645")

        openSession().use { session ->
            val previous = success(session.layout(request(initial)))
            assertEquivalentToFull(previous, fullLayout(initial, incrementalTestConstraints()))
            val token = CancelAfterChecks(5)
            val cancelled = session.layout(
                request(
                    target,
                    previousState = previous.layout.state,
                    delta = LayoutDelta(text = change(initial, target, 7, 7, 7, 15)),
                    cancellationToken = token,
                ),
            )
            assertIs<IncrementalLayoutResult.Cancelled>(cancelled)
            assertTrue(token.checks > 5)
            assertEquals(previous.layout.inputIdentity, session.currentLayout()?.layout?.inputIdentity)
            assertEquals(listOf(listOf(3, 1), listOf(85, 3080, 3075, 1919)), session.currentLayout()?.layout?.lines?.map { it.glyphIds() })
            assertEquals(initial.snapshot.range, session.currentLayout()?.layout?.coveredRange)
        }
    }

    @Test
    fun globalFeatureChangeReflowsFromDocumentStartForLateVisibleCoverage() {
        val fonts = listOf(IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"))
        val initial = incrementalRealFontFixture("office-office", fonts)
        val target = initial.withTypography(features = listOf(OpenTypeFeature("liga", 0)))
        val constraints = incrementalTestConstraints(width = 3_200f)
        val typographyDelta = TypographyDelta(initial.typography.version, target.typography.version)

        openSession().use { session ->
            val previous = success(session.layout(request(initial, constraints = constraints, language = "en")))
            val result = success(
                session.layout(
                    request(
                        target,
                        constraints = constraints,
                        requestedRange = target.snapshot.incrementalRange(7, 13),
                        previousState = previous.layout.state,
                        delta = LayoutDelta(typography = typographyDelta),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(result, fullLayout(target, constraints, language = "en"))
            assertEquals(target.snapshot.range.start, result.diagnostics.reflowStart)
            assertTrue(result.diagnostics.usedConservativeInvalidation)
            assertEquals(target.snapshot.incrementalRange(7, 13), result.layout.coveredRange)
            assertEquals(listOf(82, 73, 73, 76, 70, 72), result.layout.lines.single().glyphIds())
            assertEquals(listOf(OpenTypeFeature("liga", 0)), result.layout.lines.single().positionedGlyphRuns.single().sourceRun.features)
        }
    }

    @Test
    fun realisticEditorSequenceMatchesIndependentFullLayoutAtEveryRevision() {
        val fonts = listOf(
            IncrementalFontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            IncrementalFontFixture("amiri/Amiri-Regular.ttf", "Amiri Regular"),
        )
        val initial = incrementalRealFontFixture("office cafe\nabc \u0633\u0644\u0627\u0645", fonts)
        val inserted = initial.withText("oXffice cafe\nabc \u0633\u0644\u0627\u0645")
        val replaced = inserted.withText("oXffice \uD83D\uDE00\nabc \u0633\u0644\u0627\u0645")
        val constraints = incrementalTestConstraints(width = 4_000f, height = 6_000f)

        openSession().use { session ->
            val first = success(
                session.layout(
                    request(
                        initial,
                        constraints = constraints,
                        requestedRange = initial.snapshot.incrementalRange(0, 6),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(first, fullLayout(initial, constraints, language = "en"))
            val second = success(
                session.layout(
                    request(
                        inserted,
                        constraints = constraints,
                        requestedRange = inserted.snapshot.incrementalRange(0, 8),
                        previousState = first.layout.state,
                        delta = LayoutDelta(text = change(initial, inserted, 1, 1, 1, 2)),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(second, fullLayout(inserted, constraints, language = "en"))
            val third = success(
                session.layout(
                    request(
                        replaced,
                        constraints = constraints,
                        requestedRange = replaced.snapshot.incrementalRange(0, 9),
                        previousState = second.layout.state,
                        delta = LayoutDelta(text = change(inserted, replaced, 8, 12, 8, 9)),
                        language = "en",
                    ),
                ),
            )
            assertEquivalentToFull(third, fullLayout(replaced, constraints, language = "en"))

            assertEquals(replaced.snapshot.incrementalRange(0, 10), third.layout.coveredRange)
            assertEquals(
                listOf(replaced.snapshot.incrementalRange(0, 8), replaced.snapshot.incrementalRange(8, 10)),
                third.layout.lines.map(LineLayout::range),
            )
            assertTrue(
                third.layout.lines[1].positionedGlyphRuns
                    .flatMap { it.sourceRun.clusters }
                    .any { it.sourceRange == replaced.snapshot.incrementalRange(8, 9) },
            )
            assertEquals(
                listOf(8, 9, 10),
                third.layout.lines[1].allCaretCandidates.map { candidate ->
                    listOf(8, 9, 10).single { ordinal -> candidate.position.index == replaced.snapshot.textIndexAtScalarBoundary(ordinal) }
                },
            )
        }
    }

    private fun openSession(): JvmIncrementalParagraphLayoutSession =
        assertIs<FontOperationResult.Success<JvmIncrementalParagraphLayoutSession>>(
            JvmIncrementalParagraphLayoutSession.open(),
        ).value

    private fun request(
        fixture: IncrementalRealFontFixture,
        constraints: HorizontalParagraphConstraints = incrementalTestConstraints(),
        requestedRange: TextRange = fixture.snapshot.range,
        overscan: Int = 0,
        previousState: LayoutStateHandle? = null,
        delta: LayoutDelta? = null,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "ar",
        cancellationToken: CancellationToken = CancellationToken.none,
    ): JvmIncrementalParagraphLayoutRequest {
        val portable = assertIs<LayoutContractResult.Success<IncrementalLayoutRequest>>(
            createIncrementalLayoutRequest(
                input = LayoutInput(fixture.snapshot, fixture.typography),
                requestedRange = requestedRange,
                constraints = constraints,
                overscan = LineOverscan(overscan),
                previousState = previousState,
                delta = delta,
                cancellationToken = cancellationToken,
            ),
        ).value
        return JvmIncrementalParagraphLayoutRequest(
            request = portable,
            baseDirection = baseDirection,
            language = language,
            materialization = EditableLineMaterialization.LayoutOnly,
        )
    }

    private fun fullLayout(
        fixture: IncrementalRealFontFixture,
        constraints: HorizontalParagraphConstraints,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "ar",
    ): List<LineLayout> = assertIs<ParagraphLayoutResult.Success>(
        JvmEditableParagraphFacade.layout(
            JvmEditableParagraphFacadeRequest(
                snapshot = fixture.snapshot,
                constraints = constraints,
                baseDirection = baseDirection,
                language = language,
                fontCatalog = fixture.catalog,
                resolutionPolicy = fixture.policy,
                fontInstanceDescriptor = fixture.typography.fontInstanceDescriptor,
                features = fixture.typography.features,
            ),
        ),
    ).layout.lines

    private fun change(
        source: IncrementalRealFontFixture,
        target: IncrementalRealFontFixture,
        sourceStart: Int,
        sourceEnd: Int,
        targetStart: Int,
        targetEnd: Int,
    ): TextChangeSet = assertIs<LayoutContractResult.Success<TextChangeSet>>(
        TextChangeSet.create(
            source.snapshot,
            target.snapshot,
            listOf(
                TextChange(
                    source.snapshot.incrementalRange(sourceStart, sourceEnd),
                    target.snapshot.incrementalRange(targetStart, targetEnd),
                ),
            ),
        ),
    ).value

    private fun success(result: IncrementalLayoutResult): IncrementalLayoutResult.Success = assertIs(result)

    private fun assertEquivalentToFull(
        incremental: IncrementalLayoutResult.Success,
        full: List<LineLayout>,
    ) {
        val expected = incremental.layout.lines.map { line ->
            full.single { candidate -> candidate.range == line.range }
        }
        assertEquals(expected.map(::lineFingerprint), incremental.layout.lines.map(::lineFingerprint))
    }

    private fun lineFingerprint(line: LineLayout): List<Any> = listOf(
        line.range,
        line.baseline,
        line.contentMetrics,
        line.lineBox,
        line.designInkBounds,
        line.positionedGlyphRuns.map { run ->
            listOf(
                run.sourceRun.range,
                run.sourceRun.fontInstanceKey,
                run.sourceRun.backendIdentity,
                run.sourceRun.direction,
                run.sourceRun.script,
                run.sourceRun.language,
                run.sourceRun.bidiLevel,
                run.sourceRun.bot,
                run.sourceRun.eot,
                run.sourceRun.featurePolicy,
                run.sourceRun.features,
                run.sourceRun.graphemeClusters,
                run.visualOrder,
                run.glyphs.map { glyph ->
                    listOf(
                        glyph.shapedGlyph.glyphId,
                        glyph.shapedGlyph.xAdvance,
                        glyph.shapedGlyph.yAdvance,
                        glyph.shapedGlyph.xOffset,
                        glyph.shapedGlyph.yOffset,
                        glyph.shapedGlyph.safetyFlags,
                        glyph.shapedGlyph.clusterTokens,
                        glyph.advance,
                        glyph.origin,
                        glyph.sourceClusters.map { cluster ->
                            listOf(
                                cluster.sourceRange,
                                cluster.scalarRanges,
                                cluster.admissibleGraphemeBoundaries,
                            )
                        },
                    )
                },
            )
        },
        line.allCaretCandidates.map { candidate -> candidate.position to candidate.geometry },
    )

    private class CancelAfterChecks(private val allowedChecks: Int) : CancellationToken {
        var checks: Int = 0
            private set

        override fun isCancellationRequested(): Boolean {
            checks += 1
            return checks > allowedChecks
        }
    }
}
