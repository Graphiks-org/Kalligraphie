package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ParagraphLayoutContractsTest {
    @Test
    fun paragraphContractsSnapshotCallerCollectionsAndRejectMutableCasts() {
        val fixture = fixture("a")
        val features = mutableListOf(OpenTypeFeature("kern", 1))
        val request = fixture.request(features = features)
        val lines = mutableListOf(fixture.lineLayout())
        val layout = TestParagraphLayout(fixture.snapshot, fixture.snapshot.range, lines)

        features.clear()
        lines.clear()

        assertEquals(listOf(OpenTypeFeature("kern", 1)), request.features)
        assertEquals(1, layout.lines.size)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (request.features as MutableList<OpenTypeFeature>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (layout.lines as MutableList<LineLayout>).clear()
        }
    }

    @Test
    fun lineLayoutPublishesGlyphsAndCaretsTranslatedFromTheLocalBaseline() {
        val fixture = fixture("a")

        val line = fixture.lineLayout()

        assertEquals(LayoutPoint(LayoutUnit(10f), LayoutUnit(20f)), line.baseline)
        assertEquals(LayoutPoint(LayoutUnit(11f), LayoutUnit(19f)), line.positionedGlyphRuns.single().glyphs.single().origin)
        assertEquals(
            LayoutSegment(
                LayoutPoint(LayoutUnit(10f), LayoutUnit(18f)),
                LayoutPoint(LayoutUnit(10f), LayoutUnit(21f)),
            ),
            line.allCaretCandidates.first().geometry,
        )
        assertEquals(
            LayoutSegment(
                LayoutPoint(LayoutUnit(15f), LayoutUnit(18f)),
                LayoutPoint(LayoutUnit(15f), LayoutUnit(21f)),
            ),
            line.allCaretCandidates.last().geometry,
        )
        assertEquals(LayoutRect(LayoutUnit(10f), LayoutUnit(18f), LayoutUnit(20f), LayoutUnit(21f)), line.lineBox)
        assertEquals(
            LayoutBounds(LayoutUnit(11f), LayoutUnit(18f), LayoutUnit(16f), LayoutUnit(21f)),
            line.designInkBounds,
        )
    }

    @Test
    fun paragraphRequestsAndLayoutsRejectForeignVersionsAndOutOfRangeLines() {
        val fixture = fixture("a")
        val foreign = fixture("b")

        assertFailsWith<IllegalArgumentException> {
            fixture.request(sourceRange = foreign.snapshot.range)
        }
        assertFailsWith<IllegalArgumentException> {
            TestParagraphLayout(fixture.snapshot, fixture.snapshot.range, listOf(foreign.lineLayout()))
        }
    }

    @Test
    fun continueIsTheOnlyOverflowPolicyUntilAnotherPolicyHasEndToEndSemantics() {
        assertEquals(listOf(OverflowPolicy.CONTINUE), enumValues<OverflowPolicy>().toList())
    }

    @Test
    fun resumedRequestRejectsAContinuationFromAnotherVersionOrGeometryConfiguration() {
        val fixture = fixture("")
        val initial = fixture.request()
        val continuation = LayoutContinuation.create(initial, fixture.snapshot.range)

        val resumed = fixture.request(continuation = continuation)
        assertSame(continuation, resumed.continuation)

        assertFailsWith<IllegalArgumentException> {
            fixture.request(
                constraints = HorizontalParagraphConstraints(
                    region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(99f), LayoutUnit(60f)),
                    lineMetrics = fixture.lineMetrics,
                ),
                continuation = continuation,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            fixture("").request(continuation = continuation)
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.request(
                constraints = HorizontalParagraphConstraints(
                    region = fixture.constraints.region,
                    lineMetrics = LineVerticalMetrics(LayoutUnit(3f), LayoutUnit(1f)),
                ),
                continuation = continuation,
            )
        }
    }

    @Test
    fun paragraphConstraintsRejectEmptyPhysicalRegions() {
        assertFailsWith<IllegalArgumentException> {
            HorizontalParagraphConstraints(
                LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(10f)),
                LineVerticalMetrics(LayoutUnit(2f), LayoutUnit(1f)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HorizontalParagraphConstraints(
                LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(10f), LayoutUnit(0f)),
                LineVerticalMetrics(LayoutUnit(2f), LayoutUnit(1f)),
            )
        }
    }

    private class TestParagraphLayout(
        snapshot: TextSnapshot,
        range: TextRange,
        lines: List<LineLayout>,
    ) : ParagraphLayout(snapshot, range, lines) {
        override fun nextLogical(position: CaretPosition, direction: LogicalNavigationDirection): CaretPosition? = null

        override fun nextVisual(candidate: CaretCandidate, direction: VisualNavigationDirection): CaretCandidate? = null

        override fun caretCandidates(position: CaretPosition): List<CaretCandidate> = emptyList()

        override fun selectionGeometry(anchor: CaretPosition, focus: CaretPosition): List<LayoutRect> = emptyList()

        override fun hitTest(point: LayoutPoint): CaretCandidate = error("Not exercised by contract tests.")
    }

    private class Fixture(
        val snapshot: TextSnapshot,
        val analysis: UnicodeAnalysis,
        val lineBreakAnalysis: LineBreakAnalysis,
        val catalog: FontCatalogSnapshot,
        val policy: FontResolutionPolicySnapshot,
        val backend: ShapingBackend,
        val lineMetrics: LineVerticalMetrics,
        val constraints: HorizontalParagraphConstraints,
        private val fontKey: FontInstanceKey,
    ) {
        fun request(
            sourceRange: TextRange = snapshot.range,
            constraints: HorizontalParagraphConstraints = this.constraints,
            features: List<OpenTypeFeature> = emptyList(),
            continuation: LayoutContinuation? = null,
        ): ParagraphLayoutRequest = ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = sourceRange,
            unicodeAnalysis = analysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = constraints,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            featurePolicy = backend.identity.featurePolicy,
            features = features,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(12f)),
            shapingBackend = backend,
            materialization = EditableLineMaterialization.LayoutOnly,
            continuation = continuation,
        )

        fun lineLayout(): LineLayout {
            val localLine = editableLine()
            return LineLayout(
                line = localLine,
                baseline = LayoutPoint(LayoutUnit(10f), LayoutUnit(20f)),
                contentMetrics = LineContentMetrics(
                    ascent = LayoutUnit(2f),
                    descent = LayoutUnit(1f),
                    inlineAdvance = LayoutUnit(5f),
                ),
                lineBox = LayoutRect(LayoutUnit(10f), LayoutUnit(18f), LayoutUnit(20f), LayoutUnit(21f)),
                designInkBounds = LayoutBounds(LayoutUnit(11f), LayoutUnit(18f), LayoutUnit(16f), LayoutUnit(21f)),
            )
        }

        private fun editableLine(): EditableLine {
            if (snapshot.range.start == snapshot.range.endExclusive) {
                return EditableLine(
                    range = snapshot.range,
                    baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                    verticalMetrics = lineMetrics,
                    positionedGlyphRuns = emptyList(),
                    caretCandidates = listOf(candidate(snapshot.range.start, 0, 0f, CaretBoundaryEdge.INTERNAL)),
                )
            }
            val token = ShaperClusterToken(0)
            val cluster = ShaperCluster(
                token = token,
                sourceRange = snapshot.range,
                scalarRanges = listOf(snapshot.range),
                admissibleGraphemeBoundaries = listOf(snapshot.range.start, snapshot.range.endExclusive),
            )
            val glyph = ShapedGlyph(
                glyphId = GlyphId(7),
                xAdvance = LayoutUnit(5f),
                yAdvance = LayoutUnit(0f),
                xOffset = LayoutUnit(1f),
                yOffset = LayoutUnit(-1f),
                safetyFlags = ShapingSafetyFlags(unsafeToBreak = false, unsafeToConcat = false),
                clusterTokens = listOf(token),
            )
            val shapedRun = ShapedGlyphRun(
                range = snapshot.range,
                fontInstanceKey = fontKey,
                backendIdentity = backend.identity,
                direction = ShapingDirection.LEFT_TO_RIGHT,
                script = OpenTypeScript("Latn"),
                language = "en",
                bidiLevel = 0,
                bot = true,
                eot = true,
                featurePolicy = backend.identity.featurePolicy,
                features = emptyList(),
                graphemeClusters = listOf(snapshot.range),
                glyphs = listOf(glyph),
                clusters = listOf(cluster),
            )
            val positioned = PositionedGlyph(
                shapedGlyph = glyph,
                sourceClusters = listOf(cluster),
                origin = LayoutPoint(LayoutUnit(1f), LayoutUnit(-1f)),
                advance = LayoutVector(LayoutUnit(5f), LayoutUnit(0f)),
                renderAssetKey = null,
                materializationCertificate = null,
            )
            return EditableLine(
                range = snapshot.range,
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                verticalMetrics = lineMetrics,
                positionedGlyphRuns = listOf(PositionedGlyphRun(shapedRun, 0, null, listOf(positioned))),
                caretCandidates = listOf(
                    candidate(snapshot.range.start, 0, 0f, CaretBoundaryEdge.LOGICAL_START),
                    candidate(snapshot.range.endExclusive, 1, 5f, CaretBoundaryEdge.LOGICAL_END),
                ),
            )
        }

        private fun candidate(index: TextIndex, order: Int, x: Float, edge: CaretBoundaryEdge): CaretCandidate =
            CaretCandidate(
                position = CaretPosition(index, if (order == 0) CaretAffinity.DOWNSTREAM else CaretAffinity.UPSTREAM),
                geometry = LayoutSegment(
                    LayoutPoint(LayoutUnit(x), LayoutUnit(-2f)),
                    LayoutPoint(LayoutUnit(x), LayoutUnit(1f)),
                ),
                visualOrder = order,
                visualRunOrder = if (snapshot.range.start == snapshot.range.endExclusive) {
                    CaretCandidate.NO_POSITIONED_RUN
                } else {
                    0
                },
                bidiLevel = 0,
                direction = ShapingDirection.LEFT_TO_RIGHT,
                strength = CaretStrength.STRONG,
                edge = edge,
            )
    }

    private fun fixture(text: String): Fixture {
        val version = TextVersion.create()
        val scalars = text.map(Char::code)
        val sourceRanges = scalars.indices.map { ordinal ->
            SourceRange(
                SourceOffset(version, SourceEncoding.UTF16, ordinal),
                SourceOffset(version, SourceEncoding.UTF16, ordinal + 1),
            )
        }
        val snapshot = TextSnapshot(version, SourceEncoding.UTF16, scalars, sourceRanges)
        val unicodeData = UnicodeDataIdentity("17.0", "test", "1")
        val graphemes = if (text.isEmpty()) emptyList() else listOf(snapshot.range)
        val analysis = UnicodeAnalysis(
            range = snapshot.range,
            unicodeData = unicodeData,
            graphemeClusters = graphemes,
            scriptLanguageRuns = if (text.isEmpty()) emptyList() else listOf(ScriptLanguageRun(snapshot.range, "Latn", "en")),
            logicalBidiRuns = if (text.isEmpty()) emptyList() else listOf(BidiRun(snapshot.range, 0)),
            visualBidiRuns = if (text.isEmpty()) emptyList() else listOf(BidiRun(snapshot.range, 0)),
        )
        val lineBreaks = LineBreakAnalysis(snapshot.range, unicodeData, graphemes, emptyList())
        val faceId = FontFaceId(FontSourceId.Opaque("test", "1", "face"), 0)
        val generation = FontCatalogGeneration("catalog-1")
        val face = FontFaceRecord(
            faceId,
            FontFaceMetadata("Test", "Regular", 1_000, 10),
            FontFaceCapabilities(characterMapping = true, shaping = true, outline = true),
        )
        val catalog = object : FontCatalogSnapshot {
            override val generation: FontCatalogGeneration = generation
            override val faces: List<FontFaceRecord> = listOf(face)
            override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> = error("Not used")
            override fun resolveFace(
                faceId: FontFaceId,
                requirements: FontAccessRequirementsSnapshot,
            ): FontOperationResult<FontFace> = error("Not used")
        }
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "test-policy",
            version = "1",
            candidates = listOf(FontResolutionCandidate(faceId)),
            lastResortFace = faceId,
        )
        val featurePolicy = ShapingFeaturePolicy(
            "test-features",
            "1",
            ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
        )
        val identity = ShapingBackendIdentity(
            backendId = "test",
            nativeVersion = "1",
            nativeSourceRevision = "source",
            nativeArtifactId = "artifact",
            nativeArtifactSha256 = "0".repeat(64),
            featurePolicy = featurePolicy,
            configurationFingerprint = "config",
        )
        val backend = object : ShapingBackend {
            override val identity: ShapingBackendIdentity = identity
            override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> = error("Not used")
        }
        val descriptor = FontInstanceDescriptor(LayoutUnit(12f))
        val fontKey = FontInstanceKey(
            face = faceId,
            interpretation = FontDataInterpretationVersion("test", "1"),
            layoutSize = descriptor.layoutSize,
            geometry = descriptor.geometry,
        )
        val metrics = LineVerticalMetrics(LayoutUnit(2f), LayoutUnit(1f))
        val constraints = HorizontalParagraphConstraints(
            LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(100f), LayoutUnit(60f)),
            metrics,
        )
        return Fixture(snapshot, analysis, lineBreaks, catalog, policy, backend, metrics, constraints, fontKey)
    }
}
