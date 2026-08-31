package org.graphiks.kalligraphie.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretBoundaryEdge
import org.graphiks.kalligraphie.api.CaretCandidate
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.CaretStrength
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDataInterpretationVersion
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceCapabilities
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontFaceRecord
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.IncrementalLayout
import org.graphiks.kalligraphie.api.IncrementalLayoutDiagnostics
import org.graphiks.kalligraphie.api.IncrementalLayoutRequest
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutBounds
import org.graphiks.kalligraphie.api.LayoutCheckpoint
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutCoverage
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutSegment
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LayoutVector
import org.graphiks.kalligraphie.api.LineContentMetrics
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.PositionedGlyph
import org.graphiks.kalligraphie.api.PositionedGlyphRun
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackendIdentity
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingFeaturePolicyApplication
import org.graphiks.kalligraphie.api.ShapingSafetyFlags
import org.graphiks.kalligraphie.api.SourceEncoding
import org.graphiks.kalligraphie.api.SourceOffset
import org.graphiks.kalligraphie.api.SourceRange
import org.graphiks.kalligraphie.api.TextChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TypographySnapshot
import org.graphiks.kalligraphie.api.TypographyVersion
import org.graphiks.kalligraphie.api.createIncrementalLayoutRequest

class IncrementalParagraphLayoutEngineTest {
    @Test
    fun requestedMiddleRangePublishesOnlyWholeLinesAndReportsExactCoverage() {
        val fixture = fixture("abcdefghijklm", listOf(0 to 4, 4 to 8, 8 to 13))

        val result = fixture.engine.layout(fixture.request(4, 5, beforeAndAfter = 1), fixture.computer())

        val success = assertIs<IncrementalLayoutResult.Success>(result)
        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 8), range(fixture.snapshot, 8, 13)),
            success.layout.lines.map(LineLayout::range),
        )
        assertEquals(range(fixture.snapshot, 0, 13), success.layout.coveredRange)
        assertNull(success.layout.coverage.invalidatedSuffix)
    }

    @Test
    fun incompatiblePriorStateCausesConservativeReflowFromDocumentStart() {
        val fixture = fixture("abcdefgh", listOf(0 to 4, 4 to 8))
        val foreign = LayoutStateHandle(
            identity = "foreign",
            checkpoint = LayoutCheckpoint(fixture.snapshot.version, fixture.typography.version),
            coverage = coverage(fixture.snapshot, fixture.snapshot.range, invalidatedSuffix = null),
        )

        val result = fixture.engine.layout(fixture.request(4, 5, previousState = foreign), fixture.computer())

        val success = assertIs<IncrementalLayoutResult.Success>(result)
        assertEquals(fixture.snapshot.range.start, success.diagnostics.reflowStart)
        assertTrue(success.diagnostics.usedConservativeInvalidation)
    }

    @Test
    fun requestedRangeInsideOneGraphemePublishesItsCompleteContainingLine() {
        val fixture = fixture("a\u0301bcdef", listOf(0 to 3, 3 to 7))

        val result = fixture.engine.layout(fixture.request(1, 2), fixture.computer())

        val success = assertIs<IncrementalLayoutResult.Success>(result)
        assertEquals(listOf(range(fixture.snapshot, 0, 3)), success.layout.lines.map(LineLayout::range))
        assertEquals(range(fixture.snapshot, 0, 3), success.layout.coveredRange)
        assertEquals(range(fixture.snapshot, 3, 7), success.layout.coverage.invalidatedSuffix)
    }

    @Test
    fun overscanAtDocumentStartIsClampedToAvailableWholeLines() {
        val fixture = fixture("abcdefghijklm", listOf(0 to 4, 4 to 8, 8 to 13))

        val result = fixture.engine.layout(fixture.request(0, 1, beforeAndAfter = 2), fixture.computer())

        val success = assertIs<IncrementalLayoutResult.Success>(result)
        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 8), range(fixture.snapshot, 8, 13)),
            success.layout.lines.map(LineLayout::range),
        )
        assertEquals(range(fixture.snapshot, 0, 13), success.layout.coveredRange)
    }

    @Test
    fun validCheckpointMovesReflowBackToMaterializeRequestedPreviousOverscan() {
        val engine = IncrementalParagraphLayoutEngine(cacheBudgetBytes = 64 * 1024)
        val fixture = fixture("abcdefghijklm", listOf(0 to 4, 4 to 8, 8 to 13), engine = engine)
        val initial = assertIs<IncrementalLayoutResult.Success>(
            engine.layout(fixture.request(0, 13), fixture.computer()),
        )

        val result = engine.layout(
            fixture.request(8, 9, beforeAndAfter = 1, previousState = initial.layout.state),
            fixture.computer(),
        )

        val success = assertIs<IncrementalLayoutResult.Success>(result)
        assertEquals(
            listOf(range(fixture.snapshot, 4, 8), range(fixture.snapshot, 8, 13)),
            success.layout.lines.map(LineLayout::range),
        )
        assertEquals(fixture.snapshot.textIndexAtScalarBoundary(4), success.diagnostics.reflowStart)
        assertFalse(success.diagnostics.usedConservativeInvalidation)
    }

    @Test
    fun validTextDeltaMapsTheCheckpointBeforeTheTargetRange() {
        val engine = IncrementalParagraphLayoutEngine(cacheBudgetBytes = 64 * 1024)
        val source = fixture("abcdefgh", listOf(0 to 4, 4 to 8), engine = engine)
        val initial = assertIs<IncrementalLayoutResult.Success>(
            engine.layout(source.request(0, 8), source.computer()),
        )
        val target = fixture("abcdefXgh", listOf(0 to 4, 4 to 7, 7 to 9), engine = engine, typography = source.typography)
        val textDelta = assertIs<LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(TextChange(range(source.snapshot, 6, 6), range(target.snapshot, 6, 7))),
            ),
        ).value

        val result = engine.layout(
            target.request(4, 5, previousState = initial.layout.state, delta = LayoutDelta(text = textDelta)),
            target.computer(),
        )

        val success = assertIs<IncrementalLayoutResult.Success>(result)
        assertEquals(range(target.snapshot, 4, 7), success.layout.coveredRange)
        assertEquals(target.snapshot.textIndexAtScalarBoundary(4), success.diagnostics.reflowStart)
        assertFalse(success.diagnostics.usedConservativeInvalidation)
    }

    @Test
    fun semanticCheckpointMismatchPreventsStabilization() {
        val engine = IncrementalParagraphLayoutEngine(cacheBudgetBytes = 64 * 1024)
        val source = fixture("abcdefghijkl", listOf(0 to 4, 4 to 8, 8 to 12), engine = engine)
        val initial = assertIs<IncrementalLayoutResult.Success>(
            engine.layout(source.request(0, 12), source.computer()),
        )
        val target = fixture("axcdefghijkl", listOf(0 to 4, 4 to 8, 8 to 12), engine = engine, typography = source.typography)
        val textDelta = assertIs<LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source.snapshot,
                target.snapshot,
                listOf(TextChange(range(source.snapshot, 1, 2), range(target.snapshot, 1, 2))),
            ),
        ).value

        val result = engine.layout(
            target.request(4, 5, previousState = initial.layout.state, delta = LayoutDelta(text = textDelta)),
            target.computer(glyphIds = listOf(7, 99, 7)),
        )

        val success = assertIs<IncrementalLayoutResult.Success>(result)
        assertNull(success.diagnostics.stabilizedAt)
        assertEquals(range(target.snapshot, 8, 12), success.layout.coverage.invalidatedSuffix)
    }

    private data class Fixture(
        val snapshot: TextSnapshot,
        val typography: TypographySnapshot,
        val lineBoundaries: List<Pair<Int, Int>>,
        val engine: IncrementalParagraphLayoutEngine,
    ) {
        fun request(
            start: Int,
            endExclusive: Int,
            beforeAndAfter: Int = 0,
            previousState: LayoutStateHandle? = null,
            delta: LayoutDelta? = null,
        ): IncrementalLayoutRequest = assertIs<LayoutContractResult.Success<IncrementalLayoutRequest>>(
            createIncrementalLayoutRequest(
                input = LayoutInput(snapshot, typography),
                requestedRange = range(snapshot, start, endExclusive),
                constraints = constraints,
                overscan = LineOverscan(beforeAndAfter),
                previousState = previousState,
                delta = delta,
                cancellationToken = CancellationToken.none,
            ),
        ).value

        fun computer(glyphIds: List<Int> = lineBoundaries.map { 7 }): IncrementalParagraphComputer =
            IncrementalParagraphComputer { from, request ->
                val lines = lineBoundaries.zip(glyphIds)
                    .filter { (bounds, _) -> bounds.first >= scalarOrdinal(snapshot, from.start) }
                    .mapIndexed { index, (bounds, glyphId) ->
                        line(snapshot, bounds.first, bounds.second, baselineY = 8f + index * 10f, glyphId = glyphId)
                    }
                val composedRange = from
                val outputCoverage = coverage(snapshot, composedRange, invalidatedSuffix = null)
                val state = LayoutStateHandle(
                    identity = "fixture",
                    checkpoint = LayoutCheckpoint(snapshot.version, typography.version),
                    coverage = outputCoverage,
                )
                IncrementalLayoutResult.Success(
                    layout = FixtureLayout(LayoutInput(snapshot, typography), outputCoverage, lines, state),
                    diagnostics = IncrementalLayoutDiagnostics(from.start, usedConservativeInvalidation = true),
                )
            }
    }

    private class FixtureLayout(
        override val input: LayoutInput,
        override val coverage: LayoutCoverage,
        override val lines: List<LineLayout>,
        override val state: LayoutStateHandle,
    ) : IncrementalLayout

    private companion object {
        val constraints = HorizontalParagraphConstraints(
            region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(100f), LayoutUnit(100f)),
            lineMetrics = LineVerticalMetrics(LayoutUnit(8f), LayoutUnit(2f)),
        )
        val featurePolicy = ShapingFeaturePolicy(
            policyId = "tests",
            version = "1",
            application = ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
        )
        val backendIdentity = ShapingBackendIdentity(
            backendId = "fixture",
            nativeVersion = "1",
            nativeSourceRevision = "fixture",
            nativeArtifactId = "fixture",
            nativeArtifactSha256 = "0".repeat(64),
            featurePolicy = featurePolicy,
            configurationFingerprint = "fixture-v1",
        )
        val faceId = FontFaceId(FontSourceId.Opaque("tests", "fixture", "face"), 0)
        val fontKey = FontInstanceKey(
            face = faceId,
            interpretation = FontDataInterpretationVersion("tests", "1"),
            layoutSize = LayoutUnit(12f),
        )

        fun fixture(
            text: String,
            lines: List<Pair<Int, Int>>,
            engine: IncrementalParagraphLayoutEngine = IncrementalParagraphLayoutEngine(64 * 1024),
            typography: TypographySnapshot? = null,
        ): Fixture {
            val snapshot = decode(text)
            return Fixture(snapshot, typography ?: typography(), lines, engine)
        }

        fun decode(value: String): TextSnapshot {
            val version = TextVersion.create()
            val scalars = value.map(Char::code)
            val sourceRanges = scalars.indices.map { ordinal ->
                SourceRange(
                    SourceOffset(version, SourceEncoding.UTF16, ordinal),
                    SourceOffset(version, SourceEncoding.UTF16, ordinal + 1),
                )
            }
            return TextSnapshot(version, SourceEncoding.UTF16, scalars, sourceRanges)
        }

        fun typography(): TypographySnapshot {
            val generation = FontCatalogGeneration("fixture")
            val record = FontFaceRecord(
                faceId,
                FontFaceMetadata("Fixture", "Regular", 1_000, 256),
                FontFaceCapabilities(characterMapping = true, shaping = true, outline = true),
            )
            val catalog = object : FontCatalogSnapshot {
                override val generation: FontCatalogGeneration = generation
                override val faces: List<FontFaceRecord> = listOf(record)
                override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> = error("Not used")
                override fun resolveFace(
                    faceId: FontFaceId,
                    requirements: FontAccessRequirementsSnapshot,
                ): FontOperationResult<FontFace> = error("Not used")
            }
            return TypographySnapshot(
                version = TypographyVersion.create(),
                fontCatalog = catalog,
                resolutionPolicy = FontResolutionPolicySnapshot(
                    generation = generation,
                    policyId = "fixture",
                    version = "1",
                    candidates = listOf(FontResolutionCandidate(faceId)),
                    lastResortFace = faceId,
                ),
                fontInstanceDescriptor = FontInstanceDescriptor(),
                shapingConfigurationIdentity = "fixture-v1",
            )
        }

        fun line(
            snapshot: TextSnapshot,
            start: Int,
            endExclusive: Int,
            baselineY: Float,
            glyphId: Int,
        ): LineLayout {
            val sourceRange = range(snapshot, start, endExclusive)
            val token = ShaperClusterToken(0)
            val cluster = ShaperCluster(
                token = token,
                sourceRange = sourceRange,
                scalarRanges = snapshot.scalarRanges(sourceRange),
                admissibleGraphemeBoundaries = listOf(sourceRange.start, sourceRange.endExclusive),
            )
            val shapedGlyph = ShapedGlyph(
                glyphId = GlyphId(glyphId),
                xAdvance = LayoutUnit(10f),
                yAdvance = LayoutUnit(0f),
                xOffset = LayoutUnit(0f),
                yOffset = LayoutUnit(0f),
                safetyFlags = ShapingSafetyFlags(unsafeToBreak = false, unsafeToConcat = false),
                clusterTokens = listOf(token),
            )
            val shapedRun = ShapedGlyphRun(
                range = sourceRange,
                fontInstanceKey = fontKey,
                backendIdentity = backendIdentity,
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = org.graphiks.kalligraphie.api.OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                bot = true,
                eot = true,
                featurePolicy = featurePolicy,
                features = emptyList(),
                graphemeClusters = listOf(sourceRange),
                glyphs = listOf(shapedGlyph),
                clusters = listOf(cluster),
            )
            val positionedGlyph = PositionedGlyph(
                shapedGlyph = shapedGlyph,
                sourceClusters = listOf(cluster),
                origin = LayoutPoint(LayoutUnit(0f), LayoutUnit(0f)),
                advance = LayoutVector(LayoutUnit(10f), LayoutUnit(0f)),
                renderAssetKey = null,
                materializationCertificate = null,
            )
            val editable = EditableLine(
                range = sourceRange,
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                verticalMetrics = constraints.lineMetrics,
                positionedGlyphRuns = listOf(PositionedGlyphRun(shapedRun, 0, null, listOf(positionedGlyph))),
                caretCandidates = listOf(
                    caret(sourceRange.start, 0, 0f, CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.LOGICAL_START),
                    caret(sourceRange.endExclusive, 1, 10f, CaretAffinity.UPSTREAM, CaretBoundaryEdge.LOGICAL_END),
                ),
            )
            val baseline = LayoutPoint(LayoutUnit(0f), LayoutUnit(baselineY))
            return LineLayout(
                line = editable,
                baseline = baseline,
                contentMetrics = LineContentMetrics(LayoutUnit(8f), LayoutUnit(2f), LayoutUnit(10f)),
                lineBox = LayoutRect(LayoutUnit(0f), LayoutUnit(baselineY - 8f), LayoutUnit(100f), LayoutUnit(baselineY + 2f)),
                designInkBounds = LayoutBounds(LayoutUnit(0f), LayoutUnit(baselineY - 8f), LayoutUnit(10f), LayoutUnit(baselineY + 2f)),
            )
        }

        fun caret(
            index: org.graphiks.kalligraphie.api.TextIndex,
            order: Int,
            x: Float,
            affinity: CaretAffinity,
            edge: CaretBoundaryEdge,
        ): CaretCandidate = CaretCandidate(
            position = CaretPosition(index, affinity),
            geometry = LayoutSegment(
                LayoutPoint(LayoutUnit(x), LayoutUnit(-8f)),
                LayoutPoint(LayoutUnit(x), LayoutUnit(2f)),
            ),
            visualOrder = order,
            visualRunOrder = 0,
            bidiLevel = 0,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            strength = CaretStrength.STRONG,
            edge = edge,
        )

        fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
            TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

        fun scalarOrdinal(snapshot: TextSnapshot, index: org.graphiks.kalligraphie.api.TextIndex): Int =
            (0..snapshot.scalars.size).single { snapshot.textIndexAtScalarBoundary(it) == index }

        fun coverage(snapshot: TextSnapshot, range: TextRange, invalidatedSuffix: TextRange?): LayoutCoverage =
            assertIs<LayoutContractResult.Success<LayoutCoverage>>(
                LayoutCoverage.create(snapshot.version, range, isComplete = true, invalidatedSuffix = invalidatedSuffix),
            ).value
    }
}
