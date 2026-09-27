package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.Kalligraphie
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.CoverageStatus
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationLimitKind
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutContinuation
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.MaterializationResourceProfile
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ParagraphStyleSnapshot
import org.graphiks.kalligraphie.api.ParagraphStyleSpan
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots

/**
 * Cross-cutting hardening for per-span paragraph styling at the public paragraph facade.
 *
 * The earlier per-span suites pin validation, face preference, variation projection, run
 * grouping, and the ellipsis anchor instance in isolation. These tests instead exercise the
 * Review Focus scenarios end to end with real fonts: ligatures and Arabic joins that cross a
 * style boundary, mixed-script visual reordering with a span, successful and rejected
 * continuation replay, finite complete-operation budgets, and the render-asset budget when two
 * instances of the same face are live at once.
 *
 * Every expectation is a frozen real-font oracle (HarfBuzz 14.3.0 over the repository font
 * fixtures), and composition is reached only through the public `ParagraphComposer.layout`
 * entry point.
 */
class StyleSpanHardeningTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun aLatinLigatureSplitByAStyleChangePublishesTwoIntactRuns() {
        val fixture = fixture("ffi", fonts = listOf(WORK_SANS))
        val split = fixture.request(
            styleSpans = fixture.styles(ParagraphStyleSpan(fixture.range(0, 1), variation = wght(900f))),
        )

        val uninterrupted = layout(fixture.request())
        val styled = layout(split)

        // Frozen oracle: the whole word forms the single `ffi` ligature (glyph 619, advance 1052)
        // when no style boundary interrupts it.
        assertEquals(1, uninterrupted.layout.lines.single().positionedGlyphRuns.size)
        assertEquals(listOf(619), glyphIds(uninterrupted))
        assertEquals(1_052f, uninterrupted.layout.lines.single().contentMetrics.inlineAdvance.value)

        // Splitting the ligature at the first `f` yields exactly two runs; the second run still
        // forms the `fi` ligature (glyph 621), and the whole-word `ffi` glyph never reappears.
        val line = styled.layout.lines.single()
        assertEquals(2, line.positionedGlyphRuns.size)
        assertEquals(
            listOf(fixture.range(0, 1), fixture.range(1, 3)),
            line.positionedGlyphRuns.map { run -> run.sourceRun.range },
        )
        assertEquals(listOf(384, 621), glyphIds(styled))
        assertEquals(
            listOf(listOf(430f), listOf(659f)),
            line.positionedGlyphRuns.map { run -> run.glyphs.map { glyph -> glyph.advance.x.value } },
        )
        assertTrue(glyphIds(styled).none { it == 619 }, "The ligature must not survive a style boundary inside it.")
        assertEquals(2, line.positionedGlyphRuns.map { run -> run.fontInstanceKey }.toSet().size)
        assertCompleteClusterCoverage(fixture, line)
    }

    @Test
    fun anArabicWordSplitIntoTwoIdenticallyResolvedSpansKeepsOneJoinedRun() {
        val fixture = fixture(
            "\u0633\u0644\u0627\u0645",
            fonts = listOf(AMIRI),
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
            language = "ar",
        )
        val styled = fixture.request(
            styleSpans = fixture.styles(
                ParagraphStyleSpan(fixture.range(0, 2), face = fixture.faceIds.single()),
                ParagraphStyleSpan(fixture.range(2, 4), face = fixture.faceIds.single()),
            ),
        )

        val uninterrupted = layout(fixture.request())
        val spanned = layout(styled)

        val plainLine = uninterrupted.layout.lines.single()
        val styledLine = spanned.layout.lines.single()

        // A span boundary that resolves to the identical instance is coalesced: the joined word is
        // still one RTL run, and shaping is byte-for-byte the uninterrupted result.
        assertEquals(1, styledLine.positionedGlyphRuns.size)
        assertEquals(1, plainLine.positionedGlyphRuns.size)
        assertEquals(glyphIds(uninterrupted), glyphIds(spanned))
        assertEquals(listOf(85, 3080, 3075, 1919), glyphIds(spanned))
        assertEquals(
            plainLine.positionedGlyphRuns.single().glyphs.map { it.advance.x.value },
            styledLine.positionedGlyphRuns.single().glyphs.map { it.advance.x.value },
        )
        assertEquals(1, styledLine.positionedGlyphRuns.single().sourceRun.bidiLevel)
        assertEquals(fixture.range(0, 4), styledLine.positionedGlyphRuns.single().sourceRun.range)
        assertCompleteClusterCoverage(fixture, styledLine)
    }

    @Test
    fun aSpanInsideAMixedScriptRtlParagraphKeepsVisualRunOrder() {
        val fixture = fixture(
            "abc \u05D0\u05D1\u05D2",
            fonts = listOf(LIBERATION, DEJAVU, AMIRI),
            baseDirection = BaseDirection.RIGHT_TO_LEFT,
            language = "he",
        )
        val styled = fixture.request(
            styleSpans = fixture.styles(ParagraphStyleSpan(fixture.range(4, 7), face = fixture.faceIds[1])),
        )

        val line = layout(styled).layout.lines.single()
        val runs = line.positionedGlyphRuns

        // The span moves the Hebrew range to DejaVu Sans; the published runs stay in visual order,
        // so the RTL Hebrew range comes first, then the neutral space, then the embedded LTR Latin.
        assertEquals(listOf(0, 1, 2), runs.map { run -> run.visualOrder })
        assertEquals(
            listOf(fixture.range(4, 7), fixture.range(3, 4), fixture.range(0, 3)),
            runs.map { run -> run.sourceRun.range },
        )
        assertEquals(listOf(1, 1, 2), runs.map { run -> run.sourceRun.bidiLevel })
        assertEquals(listOf(fixture.faceIds[1], fixture.faceIds[0], fixture.faceIds[0]), runs.map { run -> run.fontInstanceKey.face })
        assertEquals(listOf(listOf(1321, 1320, 1319), listOf(3), listOf(68, 69, 70)), runs.map { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } })
        assertCompleteClusterCoverage(fixture, line)
    }

    @Test
    fun anIdenticalSpanSnapshotResumesToTheUninterruptedComposition() {
        val fixture = fixture("ffi fi ffi", fonts = listOf(WORK_SANS))
        val styles = fixture.styles(ParagraphStyleSpan(fixture.range(0, 3), variation = wght(900f)))

        val partial = layout(fixture.request(styleSpans = styles, width = 700f, height = 1_000f))
        val covered = checkNotNull(partial.continuation)
        assertEquals(CoverageStatus.PARTIAL, partial.coverageStatus)
        assertEquals(listOf(fixture.range(0, 4)), partial.layout.lines.map { line -> line.range })

        // The continuation is rebuilt through the public factory, then replayed with the identical
        // span snapshot.
        val rebuilt = LayoutContinuation.create(
            request = fixture.request(styleSpans = styles, width = 700f, height = 1_000f),
            remainingSourceRange = covered.remainingSourceRange,
            resumptionRegionTop = covered.resumptionRegionTop,
        )
        assertEquals(styles, rebuilt.styleSpans)

        val resumed = layout(
            fixture.request(
                styleSpans = styles,
                width = 700f,
                top = rebuilt.resumptionRegionTop.value,
                height = 3_000f,
                sourceRange = rebuilt.remainingSourceRange,
                continuation = rebuilt,
            ),
        )
        val full = layout(fixture.request(styleSpans = styles, width = 700f, height = 3_000f))

        assertEquals(CoverageStatus.COMPLETE, resumed.coverageStatus)
        assertEquals(
            listOf(fixture.range(0, 4), fixture.range(4, 7), fixture.range(7, 10)),
            full.layout.lines.map { line -> line.range },
        )
        assertEquals(
            full.layout.lines.map(::lineFingerprint),
            (partial.layout.lines + resumed.layout.lines).map(::lineFingerprint),
        )
    }

    @Test
    fun changingOneSpanMakesTheContinuationIncompatibleAndNamesStyleSpans() {
        val fixture = fixture("ffi fi ffi", fonts = listOf(WORK_SANS))
        val styles = fixture.styles(ParagraphStyleSpan(fixture.range(0, 3), variation = wght(900f)))

        val partial = layout(fixture.request(styleSpans = styles, width = 700f, height = 1_000f))
        val continuation = checkNotNull(partial.continuation)

        val identical = fixture.request(
            styleSpans = styles,
            width = 700f,
            top = continuation.resumptionRegionTop.value,
            height = 3_000f,
            sourceRange = continuation.remainingSourceRange,
        )
        assertTrue(continuation.isCompatibleWith(identical))
        assertEquals(styles, identical.styleSpans)

        val changed = fixture.styles(ParagraphStyleSpan(fixture.range(0, 3), variation = wght(400f)))
        val changedRequest = fixture.request(
            styleSpans = changed,
            width = 700f,
            top = continuation.resumptionRegionTop.value,
            height = 3_000f,
            sourceRange = continuation.remainingSourceRange,
        )
        assertTrue(!continuation.isCompatibleWith(changedRequest))

        val rejection = assertFailsWith<IllegalArgumentException> {
            fixture.request(
                styleSpans = changed,
                width = 700f,
                top = continuation.resumptionRegionTop.value,
                height = 3_000f,
                sourceRange = continuation.remainingSourceRange,
                continuation = continuation,
            )
        }
        assertTrue(rejection.message.orEmpty().contains("style spans"), rejection.message)
    }

    @Test
    fun aFiniteOperationBudgetAcceptsSpansAndRejectsATooSmallGlyphBudget() {
        val fixture = fixture("ffi", fonts = listOf(WORK_SANS))
        val styles = fixture.styles(
            ParagraphStyleSpan(fixture.range(0, 1), variation = wght(100f)),
            ParagraphStyleSpan(fixture.range(1, 3), variation = wght(900f)),
        )

        val accepted = layout(fixture.request(styleSpans = styles, operationProfile = EditorOperationProfile(maxTotalGlyphs = 10)))
        assertEquals(2, accepted.layout.lines.single().positionedGlyphRuns.size)

        val limited = layoutResult(fixture.request(styleSpans = styles, operationProfile = EditorOperationProfile(maxTotalGlyphs = 2)))
        val exceeded = assertIs<ParagraphLayoutError.OperationLimitExceeded>(
            assertIs<ParagraphLayoutResult.Failure>(limited).error,
        ).limit
        assertEquals(EditorOperationLimitKind.TOTAL_GLYPHS, exceeded.kind)
        assertEquals(2L, exceeded.maximum)
        assertEquals(3L, exceeded.observed)
    }

    @Test
    fun aCancelledRequestWithSpansPublishesNoPartialLines() {
        val fixture = fixture("ffi", fonts = listOf(WORK_SANS))
        val styled = fixture.request(
            styleSpans = fixture.styles(ParagraphStyleSpan(fixture.range(0, 1), variation = wght(900f))),
            cancellationToken = CancellationToken { true },
        )

        assertIs<ParagraphLayoutResult.Cancelled>(layoutResult(styled))
    }

    @Test
    fun aMaterializationFromAForeignGenerationIsRejectedWithSpans() {
        val fixture = fixture("ffi", fonts = listOf(WORK_SANS))
        val foreign = fixture("ffi", fonts = listOf(DEJAVU))
        val profile = outlineProfile()
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            foreign.catalog.openAssetResolver(),
        ).value
        val materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantKey.default, profile)
        try {
            val result = layoutResult(
                fixture.request(
                    styleSpans = fixture.styles(ParagraphStyleSpan(fixture.range(0, 1), variation = wght(900f))),
                    materializationIdentity = ParagraphMaterializationIdentity.Renderable(FontRenderVariantKey.default, profile),
                ),
                materialization = materialization,
            )

            val error = assertIs<ParagraphLayoutError.InvalidInput>(assertIs<ParagraphLayoutResult.Failure>(result).error)
            assertTrue(error.message.contains("generation"), error.message)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }

    @Test
    fun twoInstancesOfTheSameFaceAreOwnedAndBoundedByTheAssetBudget() {
        val fixture = fixture("ffi", fonts = listOf(WORK_SANS))
        val styles = fixture.styles(
            ParagraphStyleSpan(fixture.range(0, 1), variation = wght(100f)),
            ParagraphStyleSpan(fixture.range(1, 3), variation = wght(900f)),
        )
        val profile = outlineProfile()
        val identity = ParagraphMaterializationIdentity.Renderable(FontRenderVariantKey.default, profile)
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            fixture.catalog.openAssetResolver(),
        ).value
        val materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantKey.default, profile)
        try {
            val limited = layoutResult(
                fixture.request(
                    styleSpans = styles,
                    materializationIdentity = identity,
                    operationProfile = EditorOperationProfile(
                        materializationResourceProfile = MaterializationResourceProfile(maxLiveAssets = 1),
                    ),
                ),
                materialization = materialization,
            )
            val exceeded = assertIs<ParagraphLayoutError.OperationLimitExceeded>(
                assertIs<ParagraphLayoutResult.Failure>(limited).error,
            ).limit

            // Two same-face instances need two live assets; the smaller budget rejects the second.
            assertEquals(EditorOperationLimitKind.MATERIALIZATION_ASSETS, exceeded.kind)
            assertEquals(1L, exceeded.maximum)
            assertEquals(2L, exceeded.observed)

            // The failed operation unwinds without disturbing the resolver, so the same two assets
            // can be acquired, certified, and closed by the next complete operation.
            val accepted = layout(
                fixture.request(
                    styleSpans = styles,
                    materializationIdentity = identity,
                    operationProfile = EditorOperationProfile(
                        materializationResourceProfile = MaterializationResourceProfile(maxLiveAssets = 2),
                    ),
                ),
                materialization = materialization,
            )
            val runs = accepted.layout.lines.single().positionedGlyphRuns
            assertEquals(2, runs.size)
            assertEquals(2, runs.map { run -> run.fontInstanceKey }.toSet().size)
            val glyphs = runs.flatMap { run -> run.glyphs }
            assertTrue(glyphs.isNotEmpty())
            assertTrue(glyphs.all { glyph -> glyph.materializationCertificate != null })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }

    @Test
    fun manyShortAlternatingSpansProduceOneRunPerSpan() {
        val value = "abcdefghijkl"
        val fixture = fixture(value, fonts = listOf(WORK_SANS))
        val styles = fixture.styles(
            *(0 until value.length).map { index ->
                ParagraphStyleSpan(
                    fixture.range(index, index + 1),
                    variation = wght(if (index % 2 == 0) 100f else 900f),
                )
            }.toTypedArray(),
        )

        val line = layout(fixture.request(styleSpans = styles)).layout.lines.single()
        val runs = line.positionedGlyphRuns

        assertEquals(value.length, runs.size)
        assertEquals(
            value.indices.map { index -> fixture.range(index, index + 1) },
            runs.map { run -> run.sourceRun.range },
        )
        // Adjacent spans use different instances, so nothing coalesces and the instances alternate.
        assertEquals(2, runs.map { run -> run.fontInstanceKey }.toSet().size)
        assertTrue(
            runs.zipWithNext().all { (left, right) -> left.fontInstanceKey != right.fontInstanceKey },
        )
        assertCompleteClusterCoverage(fixture, line)
    }

    private fun layoutResult(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    ): ParagraphLayoutResult = ParagraphComposer.layout(request, materialization)

    private fun layout(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    ): ParagraphLayoutResult.Success = assertIs(layoutResult(request, materialization))

    private fun glyphIds(result: ParagraphLayoutResult.Success): List<Int> =
        result.layout.lines
            .flatMap { line -> line.positionedGlyphRuns }
            .flatMap { run -> run.glyphs }
            .map { glyph -> glyph.shapedGlyph.glyphId.value }

    private fun assertCompleteClusterCoverage(fixture: Fixture, line: LineLayout) {
        val published = line.positionedGlyphRuns
            .flatMap { run -> run.sourceRun.clusters }
            .flatMap { cluster -> cluster.scalarRanges }
            .toList()
        // Published clusters are in visual order, so coverage is pinned as the exact scalar
        // partition of the line range rather than as a positional sequence.
        val position = { range: TextRange -> fixture.boundaries.indexOf(range.start) }
        assertEquals(
            fixture.snapshot.scalarRanges(line.range).toList().sortedBy(position),
            published.sortedBy(position),
        )
    }

    private fun lineFingerprint(line: LineLayout): List<Any> = listOf(
        line.range,
        line.baseline,
        line.contentMetrics,
        line.lineBox,
        line.designInkBounds,
        line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId to glyph.origin } },
        line.allCaretCandidates.map { candidate -> candidate.position to candidate.geometry },
    )

    private fun wght(value: Float): FontVariationCoordinates =
        FontVariationCoordinates(listOf(FontVariationCoordinate("wght", value)))

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_024,
        maxContours = 16,
        maxPoints = 64,
        maxCompositeDepth = 4,
        maxCompositeComponents = 8,
    )

    private fun source(resource: String, declaredName: String): FontSource =
        FontSource(checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }, FontSourceProvenance(declaredName))

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value

    private class FontFixture(val resource: String, val declaredName: String)

    private class Fixture(
        val snapshot: TextSnapshot,
        val catalog: FontCatalogSnapshot,
        val faceIds: List<FontFaceId>,
        val boundaries: List<TextIndex>,
        private val baseDirection: BaseDirection,
        private val language: String,
        private val backend: ShapingBackend,
        private val resolutionPolicy: FontResolutionPolicySnapshot,
    ) {
        fun range(start: Int, endExclusive: Int): TextRange = TextRange(boundaries[start], boundaries[endExclusive])

        fun styles(vararg spans: ParagraphStyleSpan): ParagraphStyleSnapshot = ParagraphStyleSnapshot(spans.toList())

        fun request(
            styleSpans: ParagraphStyleSnapshot? = null,
            width: Float = 10_000f,
            top: Float = 50f,
            height: Float = 2_050f,
            sourceRange: TextRange = snapshot.range,
            operationProfile: EditorOperationProfile = EditorOperationProfile.unbounded,
            materializationIdentity: ParagraphMaterializationIdentity = ParagraphMaterializationIdentity.LayoutOnly,
            cancellationToken: CancellationToken = CancellationToken.none,
            continuation: LayoutContinuation? = null,
        ): ParagraphLayoutRequest {
            val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(
                snapshot,
                UnicodeAnalysisRequest(baseDirection, language),
            )
            val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(snapshot, unicodeAnalysis)
            return ParagraphLayoutRequest(
                snapshot = snapshot,
                sourceRange = sourceRange,
                unicodeAnalysis = unicodeAnalysis,
                lineBreakAnalysis = lineBreakAnalysis,
                constraints = HorizontalParagraphConstraints(
                    region = LayoutRect(LayoutUnit(100f), LayoutUnit(top), LayoutUnit(100f + width), LayoutUnit(top + height)),
                    lineMetrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f)),
                ),
                baseDirection = baseDirection,
                language = language,
                featurePolicy = backend.identity.semantic.featurePolicy,
                fontCatalog = catalog,
                resolutionPolicy = resolutionPolicy,
                fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
                shapingBackend = backend,
                materializationIdentity = materializationIdentity,
                operationProfile = operationProfile,
                cancellationToken = cancellationToken,
                styleSpans = styleSpans,
                continuation = continuation,
            )
        }
    }

    private fun fixture(
        value: String,
        fonts: List<FontFixture>,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "en",
    ): Fixture {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        val sources = fonts.map { font -> source(font.resource, font.declaredName) }
        val catalog = Kalligraphie.embedded(sources).successValue()
        val faceIds = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "style-span-hardening-policy",
            version = "1",
            candidates = faceIds.map(::FontResolutionCandidate),
            lastResortFace = faceIds.last(),
        )
        val backend = HarfBuzzShapingBackend.open().successValue().also(openedBackends::add)
        val boundaries = (0..snapshot.scalars.size).map { index -> snapshot.textIndexAtScalarBoundary(index) }
        return Fixture(snapshot, catalog, faceIds, boundaries, baseDirection, language, backend, policy)
    }

    private companion object {
        val DEJAVU = FontFixture("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val LIBERATION = FontFixture("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")
        val AMIRI = FontFixture("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val WORK_SANS = FontFixture("/fonts/worksans/WorkSans[wght].ttf", "Work Sans")
    }
}
