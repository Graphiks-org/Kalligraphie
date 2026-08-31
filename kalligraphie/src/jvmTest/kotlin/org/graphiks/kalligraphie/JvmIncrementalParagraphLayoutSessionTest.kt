package org.graphiks.kalligraphie

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.IncrementalLayoutRequest
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutTailState
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TypographySnapshot
import org.graphiks.kalligraphie.api.TypographyVersion
import org.graphiks.kalligraphie.api.createIncrementalLayoutRequest
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend

class JvmIncrementalParagraphLayoutSessionTest {
    @Test
    fun realFontLayoutPublishesLiteralGlyphsAdvancesRangesAndCoverage() {
        val fixture = fixture("fi \u0633\u0644\u0627\u0645")
        val session = openSession()

        session.use { open ->
            val success = assertIs<IncrementalLayoutResult.Success>(open.layout(request(fixture)))

            assertEquals(
                listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 7)),
                success.layout.lines.map(LineLayout::range),
            )
            assertEquals(fixture.snapshot.range, success.layout.coveredRange)
            assertEquals(
                listOf(listOf(3, 1), listOf(85, 3080, 3075, 1919)),
                success.layout.lines.map { line -> line.glyphIds() },
            )
            assertEquals(
                listOf(listOf(900f, 292f), listOf(452f, 446f, 245f, 568f)),
                success.layout.lines.map { line -> line.glyphAdvances() },
            )
            assertEquals(fixture.snapshot.version, open.currentLayout()?.layout?.coverage?.textVersion)
        }
    }

    @Test
    fun staleCompletionCannotReplaceANewerPublishedLayout() {
        val firstFixture = fixture("fi \u0633\u0644\u0627\u0645")
        val newerFixture = fixture("fi fi")
        val session = openSession()

        session.use { open ->
            val first = assertIs<IncrementalLayoutResult.Success>(open.layout(request(firstFixture)))
            val newer = assertIs<IncrementalLayoutResult.Success>(open.layout(request(newerFixture)))

            val stale = open.publishForTesting(first, generation = 1L)

            assertIs<IncrementalLayoutResult.Obsolete>(stale)
            assertEquals(newerFixture.snapshot.version, open.currentLayout()?.layout?.coverage?.textVersion)
            assertEquals(
                newer.layout.lines.map { line -> line.glyphIds() },
                open.currentLayout()?.layout?.lines?.map { line -> line.glyphIds() },
            )
        }
    }

    @Test
    fun cancellationPublishesNothingAndKeepsTheLastCompleteLayout() {
        val fixture = fixture("fi \u0633\u0644\u0627\u0645")
        val session = openSession()

        session.use { open ->
            val published = assertIs<IncrementalLayoutResult.Success>(open.layout(request(fixture)))
            val token = CancelsAfterChecks(8)
            val cancelledFixture = fixture("fi \u0633\u0644\u0627\u0645 fi \u0633\u0644\u0627\u0645 fi \u0633\u0644\u0627\u0645")

            val cancelled = open.layout(request(cancelledFixture, cancellationToken = token))

            assertIs<IncrementalLayoutResult.Cancelled>(cancelled)
            assertTrue(token.checks > 8)
            assertEquals(published.layout.coveredRange, open.currentLayout()?.layout?.coveredRange)
            assertEquals(
                listOf(listOf(3, 1), listOf(85, 3080, 3075, 1919)),
                open.currentLayout()?.layout?.lines?.map { line -> line.glyphIds() },
            )
        }
    }

    @Test
    fun alreadyCancelledRequestDoesNotPublishAnInitialLayout() {
        val fixture = fixture("fi")
        val session = openSession()

        session.use { open ->
            assertIs<IncrementalLayoutResult.Cancelled>(
                open.layout(request(fixture, cancellationToken = CancellationToken.cancelled)),
            )
            assertNull(open.currentLayout())
        }
    }

    @Test
    fun completionAttemptAfterCloseIsObsoleteAndCannotRepublish() {
        val fixture = fixture("fi")
        val session = openSession()
        val published = assertIs<IncrementalLayoutResult.Success>(session.layout(request(fixture)))

        session.close()
        val afterClose = session.publishForTesting(published, generation = 2L)

        assertIs<IncrementalLayoutResult.Obsolete>(afterClose)
        assertEquals(fixture.snapshot.version, session.currentLayout()?.layout?.inputIdentity?.textVersion)
        assertEquals(fixture.typography.version, session.currentLayout()?.layout?.inputIdentity?.typographyVersion)
    }

    @Test
    fun documentEndCaretPublishesTheCanonicalTrailingEmptyLine() {
        val fixture = fixture("fi\n")
        val end = fixture.snapshot.range.endExclusive
        val session = openSession()

        session.use { open ->
            val success = assertIs<IncrementalLayoutResult.Success>(
                open.layout(request(fixture, requestedRange = TextRange(end, end))),
            )

            assertEquals(listOf(TextRange(end, end)), success.layout.lines.map(LineLayout::range))
            assertEquals(TextRange(end, end), success.layout.coveredRange)
            assertTrue(success.layout.lines.single().positionedGlyphRuns.isEmpty())
        }
    }

    @Test
    fun fullRangeEndingInNewlinePublishesContentAndTheCanonicalTerminalEmptyLine() {
        val fixture = fixture("fi\n")
        val end = fixture.snapshot.range.endExclusive
        val session = openSession()

        session.use { open ->
            val success = assertIs<IncrementalLayoutResult.Success>(open.layout(request(fixture)))

            assertEquals(
                listOf(range(fixture.snapshot, 0, 3), TextRange(end, end)),
                success.layout.lines.map(LineLayout::range),
            )
            assertEquals(listOf(listOf(3), emptyList()), success.layout.lines.map { line -> line.glyphIds() })
            assertEquals(fixture.snapshot.range, success.layout.coveredRange)
            assertEquals(LayoutTailState.MaterializedThroughDocumentEnd, success.layout.coverage.tailState)
        }
    }

    @Test
    fun negativeCacheBudgetIsRejectedBeforeOpeningABackend() {
        var backendOpenCalls = 0

        assertFailsWith<IllegalArgumentException> {
            JvmIncrementalParagraphLayoutSession.openWithBackendFactory(cacheBudgetBytes = -1) {
                backendOpenCalls += 1
                error("Backend opening must not be attempted for an invalid cache budget.")
            }
        }

        assertEquals(0, backendOpenCalls)
    }

    @Test
    fun oneBackendServesSuccessiveLayoutsAndSessionCloseIsIdempotent() {
        val delegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val backend = TrackingBackend(delegate)
        val session = JvmIncrementalParagraphLayoutSession.openOwnedBackend(backend)
        val fixture = fixture("fi \u0633\u0644\u0627\u0645")

        try {
            assertIs<IncrementalLayoutResult.Success>(session.layout(request(fixture)))
            val shapesAfterFirst = backend.shapeCalls
            assertIs<IncrementalLayoutResult.Success>(session.layout(request(fixture)))

            assertTrue(shapesAfterFirst > 0)
            assertTrue(backend.shapeCalls > shapesAfterFirst)
        } finally {
            session.close()
            session.close()
        }

        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun middleEditStartsRealShapingAtTheEngineReflowBoundaryAndReturnsOnlyTheTarget() {
        val source = fixture("fi \u0633\u0644\u0627\u0645")
        val target = source.withText("fi \u0633\u0644\u0645")
        val changeSet = assertIs<LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(TextChange(range(source.snapshot, 5, 6), range(target.snapshot, 5, 5))),
            ),
        ).value
        val delegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val backend = TrackingBackend(delegate)
        val session = JvmIncrementalParagraphLayoutSession.openOwnedBackend(backend)

        try {
            val initial = assertIs<IncrementalLayoutResult.Success>(session.layout(request(source)))
            backend.clearObservedRanges()

            val edited = assertIs<IncrementalLayoutResult.Success>(
                session.layout(
                    request(
                        fixture = target,
                        requestedRange = range(target.snapshot, 3, 6),
                        previousState = initial.layout.state,
                        delta = LayoutDelta(text = changeSet),
                    ),
                ),
            )

            assertEquals(target.snapshot.textIndexAtScalarBoundary(3), edited.diagnostics.reflowStart)
            assertEquals(listOf(range(target.snapshot, 3, 6)), edited.layout.lines.map(LineLayout::range))
            assertEquals(range(target.snapshot, 3, 6), edited.layout.coveredRange)
            assertTrue(backend.observedRangeStarts.isNotEmpty())
            assertTrue(backend.observedRangeStarts.all { start ->
                start >= target.snapshot.textIndexAtScalarBoundary(3)
            })
        } finally {
            session.close()
        }
    }

    @Test
    fun softWrappedTextWithoutMandatoryBreakSearchesThroughDocumentEndAndMatchesFullJ4() {
        val fixture = fixture("fi ".repeat(24) + "\u0633\u0644\u0627\u0645")
        val documentEnd = fixture.snapshot.range.endExclusive
        val reference = assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layout(
                JvmEditableParagraphFacadeRequest(
                    snapshot = fixture.snapshot,
                    constraints = constraints(height = 1_200f),
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "ar",
                    fontCatalog = fixture.catalog,
                    resolutionPolicy = fixture.policy,
                    fontInstanceDescriptor = fixture.typography.fontInstanceDescriptor,
                ),
            ),
        ).layout.lines.single()
        val delegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val backend = TrackingBackend(delegate)
        val session = JvmIncrementalParagraphLayoutSession.openOwnedBackend(backend)

        try {
            val actual = assertIs<IncrementalLayoutResult.Success>(
                session.layout(
                    request(
                        fixture,
                        requestedRange = range(fixture.snapshot, 0, 1),
                    ),
                ),
            ).layout.lines.single()

            assertEquals(range(fixture.snapshot, 0, 3), actual.range)
            assertEquals(listOf(3, 1), actual.glyphIds())
            assertEquals(listOf(900f, 292f), actual.glyphAdvances())
            assertEquals(reference.range, actual.range)
            assertEquals(reference.glyphIds(), actual.glyphIds())
            assertEquals(reference.glyphAdvances(), actual.glyphAdvances())
            assertEquals(actual.range, session.currentLayout()?.layout?.coveredRange)
            assertTrue(backend.observedRanges.isNotEmpty())
            assertTrue(backend.observedRanges.any { shaped -> shaped.endExclusive == documentEnd })
            assertTrue(backend.observedRanges.all { shaped -> shaped.start >= fixture.snapshot.range.start })
        } finally {
            session.close()
        }
    }

    @Test
    fun firstMandatoryBreakBoundsShapingBeforeALongFollowingSuffixAndMatchesFullJ4() {
        val fixture = fixture("fi\n" + "fi ".repeat(24) + "\u0633\u0644\u0627\u0645")
        val mandatoryEnd = fixture.snapshot.textIndexAtScalarBoundary(3)
        val reference = assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layout(
                JvmEditableParagraphFacadeRequest(
                    snapshot = fixture.snapshot,
                    constraints = constraints(height = 1_200f),
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "ar",
                    fontCatalog = fixture.catalog,
                    resolutionPolicy = fixture.policy,
                    fontInstanceDescriptor = fixture.typography.fontInstanceDescriptor,
                ),
            ),
        ).layout.lines.single()
        val delegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val backend = TrackingBackend(delegate)
        val session = JvmIncrementalParagraphLayoutSession.openOwnedBackend(backend)

        try {
            val actual = assertIs<IncrementalLayoutResult.Success>(
                session.layout(request(fixture, requestedRange = range(fixture.snapshot, 0, 1))),
            ).layout.lines.single()

            assertEquals(range(fixture.snapshot, 0, 3), actual.range)
            assertEquals(listOf(3), actual.glyphIds())
            assertEquals(listOf(900f), actual.glyphAdvances())
            assertEquals(reference.range, actual.range)
            assertEquals(reference.glyphIds(), actual.glyphIds())
            assertEquals(reference.glyphAdvances(), actual.glyphAdvances())
            assertEquals(reference.range, session.currentLayout()?.layout?.coveredRange)
            assertTrue(backend.observedRanges.isNotEmpty())
            assertTrue(backend.observedRanges.all { shaped -> shaped.endExclusive <= mandatoryEnd })
        } finally {
            session.close()
        }
    }

    @Test
    fun signedAdvancesRequireTheCompleteCandidateSearchSegment() {
        val fixture = fixture("fi ".repeat(4))
        val signedSuffixStart = fixture.snapshot.textIndexAtScalarBoundary(8)
        val referenceDelegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val referenceBackend = ThresholdSignedAdvanceBackend(referenceDelegate, signedSuffixStart)
        val sessionDelegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val session = JvmIncrementalParagraphLayoutSession.openOwnedBackend(
            ThresholdSignedAdvanceBackend(sessionDelegate, signedSuffixStart),
        )

        try {
            val reference = assertIs<ParagraphLayoutResult.Success>(
                JvmEditableParagraphFacade.layoutBorrowing(
                    request = JvmEditableParagraphFacadeRequest(
                        snapshot = fixture.snapshot,
                        constraints = constraints(height = 1_200f),
                        baseDirection = BaseDirection.LEFT_TO_RIGHT,
                        language = "en",
                        fontCatalog = fixture.catalog,
                        resolutionPolicy = fixture.policy,
                        fontInstanceDescriptor = fixture.typography.fontInstanceDescriptor,
                    ),
                    backend = referenceBackend,
                ),
            ).layout.lines.single()

            val actual = assertIs<IncrementalLayoutResult.Success>(
                session.layout(
                    request(
                        fixture,
                        requestedRange = range(fixture.snapshot, 0, 1),
                        language = "en",
                    ),
                ),
            ).layout.lines.single()

            assertEquals(fixture.snapshot.range, reference.range)
            assertEquals(reference.range, actual.range)
            assertEquals(reference.glyphIds(), actual.glyphIds())
            assertEquals(reference.glyphAdvances(), actual.glyphAdvances())
        } finally {
            referenceBackend.close()
            session.close()
        }
    }

    @Test
    fun cancellationAfterFirstFallbackFragmentStartsNoSecondShapeAndKeepsPublication() {
        val token = SwitchableCancellationToken()
        val delegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val backend = CancellingAfterFirstShapeBackend(delegate, token)
        val session = JvmIncrementalParagraphLayoutSession.openOwnedBackend(backend)
        val publishedFixture = fixture("fi")
        val cancelledFixture = fixture("fi \u0633\u0644\u0627\u0645")

        try {
            val published = assertIs<IncrementalLayoutResult.Success>(session.layout(request(publishedFixture)))
            backend.arm()

            val cancelled = session.layout(request(cancelledFixture, cancellationToken = token))

            assertIs<IncrementalLayoutResult.Cancelled>(cancelled)
            assertEquals(1, backend.shapeCallsAfterArming)
            assertEquals(published.layout.inputIdentity, session.currentLayout()?.layout?.inputIdentity)
            assertEquals(published.layout.lines.map { it.glyphIds() }, session.currentLayout()?.layout?.lines?.map { it.glyphIds() })
        } finally {
            session.close()
        }
    }

    @Test
    fun changedJvmCompositionConfigurationForcesConservativeDocumentStartReflow() {
        val source = fixture("fi \u0633\u0644\u0627\u0645")
        val target = source.withText("fi \u0633\u0644\u0645")
        val changeSet = assertIs<LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(TextChange(range(source.snapshot, 5, 6), range(target.snapshot, 5, 5))),
            ),
        ).value
        val delegate = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        val backend = TrackingBackend(delegate)
        val session = JvmIncrementalParagraphLayoutSession.openOwnedBackend(backend)

        try {
            val initial = assertIs<IncrementalLayoutResult.Success>(
                session.layout(request(source, language = "ar")),
            )
            backend.clearObservedRanges()

            val edited = assertIs<IncrementalLayoutResult.Success>(
                session.layout(
                    request(
                        fixture = target,
                        requestedRange = range(target.snapshot, 3, 6),
                        previousState = initial.layout.state,
                        delta = LayoutDelta(text = changeSet),
                        language = "en",
                    ),
                ),
            )

            assertEquals(target.snapshot.range.start, edited.diagnostics.reflowStart)
            assertTrue(edited.diagnostics.usedConservativeInvalidation)
            assertNull(edited.diagnostics.stabilizedAt)
            assertTrue(backend.observedRangeStarts.any { start -> start == target.snapshot.range.start })
        } finally {
            session.close()
        }
    }

    @Test
    fun collidingStateIdentityFromAnotherSessionCannotReuseLocalCheckpointGeometry() {
        val source = fixture("fi \u0633\u0644\u0627\u0645")
        val target = source.withText("fi \u0633\u0644\u0645")
        val changeSet = assertIs<LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(TextChange(range(source.snapshot, 5, 6), range(target.snapshot, 5, 5))),
            ),
        ).value
        val firstConstraints = constraints(top = 50f)
        val foreignConstraints = constraints(top = 500f)
        val firstSession = openSession()
        val foreignSession = openSession()

        try {
            val local = assertIs<IncrementalLayoutResult.Success>(
                firstSession.layout(request(source, constraints = firstConstraints)),
            )
            val foreign = assertIs<IncrementalLayoutResult.Success>(
                foreignSession.layout(request(source, constraints = foreignConstraints)),
            )
            assertEquals(local.layout.state.identity, foreign.layout.state.identity)

            val result = assertIs<IncrementalLayoutResult.Success>(
                firstSession.layout(
                    request(
                        fixture = target,
                        requestedRange = range(target.snapshot, 3, 6),
                        previousState = foreign.layout.state,
                        delta = LayoutDelta(text = changeSet),
                        constraints = foreignConstraints,
                    ),
                ),
            )

            assertEquals(target.snapshot.range.start, result.diagnostics.reflowStart)
            assertTrue(result.diagnostics.usedConservativeInvalidation)
            assertEquals(LayoutUnit(1_700f), result.layout.lines.single().lineBox.top)
        } finally {
            firstSession.close()
            foreignSession.close()
        }
    }

    @Test
    fun structurallyValidForeignStateFallsBackToSuccessfulFullReflow() {
        val source = fixture("fi \u0633\u0644\u0627\u0645")
        val target = source.withText("fi \u0633\u0644\u0645")
        val changeSet = assertIs<LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(TextChange(range(source.snapshot, 5, 6), range(target.snapshot, 5, 5))),
            ),
        ).value
        val session = openSession()

        session.use { open ->
            val local = assertIs<IncrementalLayoutResult.Success>(open.layout(request(source)))
            val localState = local.layout.state
            val foreign = org.graphiks.kalligraphie.api.LayoutStateHandle(
                identity = "foreign-state",
                checkpoint = localState.checkpoint,
                coverage = localState.coverage,
                configuration = localState.configuration,
                continuation = localState.continuation,
                lineCheckpoints = localState.lineCheckpoints,
            )

            val result = assertIs<IncrementalLayoutResult.Success>(
                open.layout(
                    request(
                        fixture = target,
                        requestedRange = range(target.snapshot, 3, 6),
                        previousState = foreign,
                        delta = LayoutDelta(text = changeSet),
                    ),
                ),
            )

            assertEquals(target.snapshot.range.start, result.diagnostics.reflowStart)
            assertTrue(result.diagnostics.usedConservativeInvalidation)
        }
    }

    private fun openSession(): JvmIncrementalParagraphLayoutSession =
        assertIs<FontOperationResult.Success<JvmIncrementalParagraphLayoutSession>>(
            JvmIncrementalParagraphLayoutSession.open(),
        ).value

    private fun request(
        fixture: Fixture,
        cancellationToken: CancellationToken = CancellationToken.none,
        requestedRange: TextRange = fixture.snapshot.range,
        previousState: org.graphiks.kalligraphie.api.LayoutStateHandle? = null,
        delta: LayoutDelta? = null,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "ar",
        constraints: HorizontalParagraphConstraints = constraints(),
    ): JvmIncrementalParagraphLayoutRequest = JvmIncrementalParagraphLayoutRequest(
        request = incrementalRequest(fixture, cancellationToken, requestedRange, previousState, delta, constraints),
        baseDirection = baseDirection,
        language = language,
        materialization = EditableLineMaterialization.LayoutOnly,
    )

    private fun incrementalRequest(
        fixture: Fixture,
        cancellationToken: CancellationToken,
        requestedRange: TextRange,
        previousState: org.graphiks.kalligraphie.api.LayoutStateHandle?,
        delta: LayoutDelta?,
        constraints: HorizontalParagraphConstraints,
    ): IncrementalLayoutRequest = assertIs<LayoutContractResult.Success<IncrementalLayoutRequest>>(
        createIncrementalLayoutRequest(
            input = LayoutInput(
                text = fixture.snapshot,
                typography = fixture.typography,
            ),
            requestedRange = requestedRange,
            constraints = constraints,
            overscan = LineOverscan(0),
            previousState = previousState,
            delta = delta,
            cancellationToken = cancellationToken,
        ),
    ).value

    private fun fixture(value: String): Fixture {
        val sources = listOf(
            fontSource("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
            fontSource("amiri/Amiri-Regular.ttf", "Amiri Regular"),
        )
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(sources),
        ).value
        val faces = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "incremental-session-fixture",
            version = "1",
            candidates = faces.map(::FontResolutionCandidate),
            lastResortFace = faces.last(),
        )
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        return Fixture(
            snapshot,
            catalog,
            policy,
            TypographySnapshot(
                version = TypographyVersion.create(),
                fontCatalog = catalog,
                resolutionPolicy = policy,
                fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            ),
        )
    }

    private fun constraints(
        top: Float = 50f,
        height: Float = 3_600f,
    ): HorizontalParagraphConstraints = HorizontalParagraphConstraints(
        region = LayoutRect(LayoutUnit(100f), LayoutUnit(top), LayoutUnit(1_500f), LayoutUnit(top + height)),
        lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
    )

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange = TextRange(
        snapshot.textIndexAtScalarBoundary(start),
        snapshot.textIndexAtScalarBoundary(endExclusive),
    )

    private fun fontSource(relativePath: String, declaredName: String): FontSource = FontSource(
        sourceBytes = fixtureBytes(relativePath),
        provenance = FontSourceProvenance(declaredName),
    )

    private fun fixtureBytes(relativePath: String): ByteArray {
        javaClass.getResourceAsStream("/fonts/$relativePath")?.use { stream -> return stream.readBytes() }
        val candidates = listOf(
            Path.of("shaping", "src", "jvmTest", "resources", "fonts", relativePath),
            Path.of("kalligraphie", "shaping", "src", "jvmTest", "resources", "fonts", relativePath),
        )
        return Files.readAllBytes(checkNotNull(candidates.firstOrNull(Files::isRegularFile)))
    }

    private fun LineLayout.glyphIds(): List<Int> = positionedGlyphRuns.flatMap { run ->
        run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value }
    }

    private fun LineLayout.glyphAdvances(): List<Float> = positionedGlyphRuns.flatMap { run ->
        run.glyphs.map { glyph -> glyph.advance.x.value }
    }

    private data class Fixture(
        val snapshot: TextSnapshot,
        val catalog: FontCatalogSnapshot,
        val policy: FontResolutionPolicySnapshot,
        val typography: TypographySnapshot,
    ) {
        fun withText(value: String): Fixture = copy(
            snapshot = Kalligraphie.decodeUtf16(
                TextVersion.create(),
                listOf(TextSlice.Utf16(value.toCharArray())),
            ).snapshot,
        )
    }

    private class CancelsAfterChecks(private val allowedChecks: Int) : CancellationToken {
        var checks: Int = 0
            private set

        override fun isCancellationRequested(): Boolean {
            checks += 1
            return checks > allowedChecks
        }
    }

    private class TrackingBackend(
        private val delegate: ShapingBackend,
    ) : ShapingBackend {
        override val identity = delegate.identity
        var shapeCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        val observedRangeStarts: MutableList<TextIndex> = mutableListOf()
        val observedRanges: MutableList<TextRange> = mutableListOf()

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
            shapeCalls += 1
            observedRangeStarts += request.range.start
            observedRanges += request.range
            return delegate.shape(request)
        }

        fun clearObservedRanges() {
            observedRangeStarts.clear()
            observedRanges.clear()
        }

        override fun close(): FontOperationResult<Unit> {
            closeCalls += 1
            return delegate.close()
        }
    }

    private class SwitchableCancellationToken : CancellationToken {
        private var cancelled: Boolean = false

        fun cancel() {
            cancelled = true
        }

        override fun isCancellationRequested(): Boolean = cancelled
    }

    private class CancellingAfterFirstShapeBackend(
        private val delegate: ShapingBackend,
        private val token: SwitchableCancellationToken,
    ) : ShapingBackend {
        override val identity = delegate.identity
        var shapeCallsAfterArming: Int = 0
            private set
        private var armed: Boolean = false

        fun arm() {
            armed = true
        }

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
            if (armed) shapeCallsAfterArming += 1
            val result = delegate.shape(request)
            if (armed && shapeCallsAfterArming == 1) token.cancel()
            return result
        }

        override fun close(): FontOperationResult<Unit> = delegate.close()
    }

    private class ThresholdSignedAdvanceBackend(
        private val delegate: ShapingBackend,
        private val signedSuffixStart: TextIndex,
    ) : ShapingBackend {
        override val identity = delegate.identity

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> =
            when (val result = delegate.shape(request)) {
                is FontOperationResult.Success -> result.copy(
                    value = result.value.withSignedSuffixAdvances(signedSuffixStart),
                )
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }

        override fun close(): FontOperationResult<Unit> = delegate.close()

        private fun ShapedGlyphRun.withSignedSuffixAdvances(signedSuffixStart: TextIndex): ShapedGlyphRun = ShapedGlyphRun(
            range = range,
            fontInstanceKey = fontInstanceKey,
            backendIdentity = backendIdentity,
            direction = direction,
            script = script,
            language = language,
            bidiLevel = bidiLevel,
            bot = bot,
            eot = eot,
            featurePolicy = featurePolicy,
            features = features,
            graphemeClusters = graphemeClusters,
            glyphs = glyphs.map { glyph ->
                val beginsInSignedSuffix = glyph.clusterTokens
                    .map { token -> clusters.single { cluster -> cluster.token == token }.sourceRange.start }
                    .minWith { left, right -> left.compareTo(right) } >= signedSuffixStart
                ShapedGlyph(
                    glyphId = glyph.glyphId,
                    xAdvance = LayoutUnit(if (beginsInSignedSuffix) -1_500f else 1_000f),
                    yAdvance = glyph.yAdvance,
                    xOffset = glyph.xOffset,
                    yOffset = glyph.yOffset,
                    safetyFlags = glyph.safetyFlags,
                    clusterTokens = glyph.clusterTokens,
                )
            },
            clusters = clusters,
            ligatureCaretFacts = ligatureCaretFacts,
        )
    }
}
