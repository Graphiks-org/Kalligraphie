package org.graphiks.kalligraphie

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.CoverageStatus
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LogicalNavigationDirection
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.VisualNavigationDirection
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend

class JvmEditableParagraphFacadeTest {
    @Test
    fun mainArtifactSnapshotsAnOrderedMultiFaceCatalogFromRealFonts() {
        val latin = fontSource("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val arabic = fontSource("amiri/Amiri-Regular.ttf", "Amiri Regular")
        val mutableSources = mutableListOf(latin, arabic)

        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(mutableSources),
        ).value
        mutableSources.clear()

        assertEquals(listOf(latin.id, arabic.id), catalog.faces.map { face -> face.id.source })
        // Both artifacts are checked-in, licensed TrueType fonts. Their order is the public
        // fallback order input; no internal SFNT reader or embedded provider is used here.
    }

    @Test
    fun publicFacadeCanonicalizesBcp47LanguageForPopulatedSnapshot() {
        val populatedFixture = multiFaceFixture("fi")
        val populated = assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layout(
                request(
                    fixture = populatedFixture,
                    constraints = constraints(width = 1_400f, top = 50f, height = 1_200f),
                    language = "EN-us",
                ),
            ),
        )
        assertEquals(
            setOf("en-US"),
            populated.layout.lines
                .flatMap(LineLayout::positionedGlyphRuns)
                .map { run -> run.sourceRun.language }
                .toSet(),
        )
    }

    @Test
    fun emptyFacadeSuppliesCanonicalLanguageToParagraphLayoutRequest() {
        val emptyFixture = multiFaceFixture("")
        val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        var suppliedRequest: ParagraphLayoutRequest? = null

        val result = JvmEditableParagraphFacade.layout(
            request = request(
                fixture = emptyFixture,
                constraints = constraints(width = 1_400f, top = 50f, height = 1_200f),
                language = "EN-us",
            ),
            backend = backend,
            paragraphLayout = { paragraphRequest, _ ->
                suppliedRequest = paragraphRequest
                ParagraphLayoutResult.Cancelled()
            },
        )

        assertIs<ParagraphLayoutResult.Cancelled>(result)
        assertEquals("en-US", assertNotNull(suppliedRequest).language)
    }

    @Test
    fun facadeRequestFeaturesAreAnImmutableDefensiveSnapshot() {
        val fixture = multiFaceFixture("fi")
        val supplied = mutableListOf(OpenTypeFeature("kern", 1), OpenTypeFeature("liga", 0))

        val facadeRequest = request(
            fixture = fixture,
            constraints = constraints(width = 1_400f, top = 50f, height = 1_200f),
            features = supplied,
        )
        supplied.clear()

        assertEquals(listOf(OpenTypeFeature("kern", 1), OpenTypeFeature("liga", 0)), facadeRequest.features)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (facadeRequest.features as MutableList<OpenTypeFeature>).clear()
        }
        assertEquals(listOf(OpenTypeFeature("kern", 1), OpenTypeFeature("liga", 0)), facadeRequest.features)
    }

    @Test
    fun publicFacadePublishesMixedScriptFallbackGeometryAndMultilineEditing() {
        val fixture = multiFaceFixture("fi \u0633\u0644\u0627\u0645")

        val result = layout(fixture, constraints(width = 1_400f, top = 50f, height = 2_400f))
        val paragraph = result.layout
        val first = paragraph.lines[0]
        val second = paragraph.lines[1]

        assertEquals(CoverageStatus.COMPLETE, result.coverageStatus)
        assertNull(result.continuation)
        assertEquals(
            listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 7)),
            paragraph.lines.map(LineLayout::range),
        )
        assertEquals(
            listOf(fixture.latinFace, fixture.arabicFace),
            paragraph.lines.flatMap(LineLayout::positionedGlyphRuns)
                .map { run -> run.fontInstanceKey.face }
                .distinct(),
        )
        val latinRun = paragraph.lines.flatMap(LineLayout::positionedGlyphRuns)
            .single { run -> run.fontInstanceKey.face == fixture.latinFace }
        val arabicRun = paragraph.lines.flatMap(LineLayout::positionedGlyphRuns)
            .single { run -> run.fontInstanceKey.face == fixture.arabicFace && run.sourceRun.range == second.range }
        assertEquals(listOf(3), latinRun.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value })
        assertEquals(listOf(900f), latinRun.glyphs.map { glyph -> glyph.advance.x.value })
        assertEquals(listOf(85, 3080, 3075, 1919), arabicRun.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value })
        assertEquals(listOf(452f, 446f, 245f, 568f), arabicRun.glyphs.map { glyph -> glyph.advance.x.value })
        // Frozen external HarfBuzz 14.3/14.4 oracles are documented with both checked-in fonts.

        assertEquals(
            listOf(
                LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(1_500f), LayoutUnit(1_250f)),
                LayoutRect(LayoutUnit(100f), LayoutUnit(1_250f), LayoutUnit(1_500f), LayoutUnit(2_450f)),
            ),
            paragraph.lines.map(LineLayout::lineBox),
        )
        assertEquals(
            listOf(LayoutUnit(950f), LayoutUnit(2_150f)),
            paragraph.lines.map { line -> line.baseline.y },
        )
        assertTrue(paragraph.lines.all { line -> line.designInkBounds.minX < line.designInkBounds.maxX })
        assertTrue(paragraph.lines.all { line -> line.contentMetrics.inlineAdvance.value > 0f })
        assertTrue(paragraph.lines.any { line ->
            line.contentMetrics.ascent != line.verticalMetrics.ascent ||
                line.contentMetrics.descent != line.verticalMetrics.descent
        })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (paragraph.lines as MutableList<LineLayout>).clear()
        }

        val firstEnd = first.allCaretCandidates.single { candidate -> candidate.position.index == first.range.endExclusive }
        assertEquals(
            fixture.snapshot.textIndexAtScalarBoundary(4),
            assertNotNull(paragraph.nextLogical(firstEnd.position, LogicalNavigationDirection.FORWARD)).index,
        )
        assertSame(second.allCaretCandidates.first(), paragraph.nextVisual(firstEnd, VisualNavigationDirection.FORWARD))

        val selection = paragraph.selectionGeometry(
            first.allCaretCandidates.single { candidate -> candidate.position.index == first.range.start }.position,
            second.allCaretCandidates.single { candidate -> candidate.position.index == second.range.endExclusive }.position,
        )
        assertTrue(selection.isNotEmpty())
        assertEquals(setOf(first.lineBox.top, second.lineBox.top), selection.map { rectangle -> rectangle.top }.toSet())
        assertTrue(selection.none { rectangle ->
            rectangle.top < first.lineBox.bottom && rectangle.bottom > second.lineBox.top
        })

        val firstStart = first.allCaretCandidates.first()
        val secondInterior = second.allCaretCandidates.single { candidate ->
            candidate.position.index == fixture.snapshot.textIndexAtScalarBoundary(5)
        }
        val secondMidlineY = LayoutUnit((second.lineBox.top.value + second.lineBox.bottom.value) / 2f)
        val secondEnd = second.allCaretCandidates.last()
        assertSame(firstStart, paragraph.hitTest(LayoutPoint(firstStart.geometry.start.x, LayoutUnit(-500f))))
        assertSame(
            firstStart,
            paragraph.hitTest(LayoutPoint(firstStart.geometry.start.x, first.lineBox.bottom)),
        )
        assertSame(
            secondInterior,
            paragraph.hitTest(LayoutPoint(secondInterior.geometry.start.x, secondMidlineY)),
        )
        assertSame(secondEnd, paragraph.hitTest(LayoutPoint(secondEnd.geometry.start.x, LayoutUnit(3_000f))))
    }

    @Test
    fun publicFacadeChoosesTheLastLegalBreakThatFits() {
        val fixture = fontFixture(
            value = "one two three",
            fonts = listOf(FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = layout(
            fixture,
            constraints(width = 3_000f, top = 50f, height = 3_600f),
            language = "en",
        )

        assertEquals(CoverageStatus.COMPLETE, result.coverageStatus)
        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 8), range(fixture.snapshot, 8, 13)),
            result.layout.lines.map(LineLayout::range),
        )
    }

    @Test
    fun publicFacadePublishesAnOverwideIndivisibleUnitWhole() {
        val fixture = fontFixture(
            value = "Supercalifragilistic",
            fonts = listOf(FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = layout(
            fixture,
            constraints(width = 100f, top = 50f, height = 1_200f),
            language = "en",
        )
        val line = result.layout.lines.single()

        assertEquals(CoverageStatus.COMPLETE, result.coverageStatus)
        assertEquals(fixture.snapshot.range, line.range)
        assertTrue(line.contentMetrics.inlineAdvance > LayoutUnit(100f))
        assertTrue(line.positionedGlyphRuns.flatMap { run -> run.sourceRun.clusters }.isNotEmpty())
    }

    @Test
    fun publicFacadeKeepsCombiningVariationAndEmojiZwJUnitsWholeOnNarrowLines() {
        val fixture = fontFixture(
            value = "f\u0301 \u2764\uFE0F\u200D\u2764\uFE0F x",
            fonts = listOf(
                FontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
                FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            ),
        )

        val result = layout(
            fixture,
            constraints(width = 100f, top = 50f, height = 3_600f),
            language = "en",
        )

        assertEquals(
            listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 9), range(fixture.snapshot, 9, 10)),
            result.layout.lines.map(LineLayout::range),
        )
        assertEquals(
            listOf(listOf(73, 5923, 3), listOf(6, 6, 3), listOf(91)),
            result.layout.lines.map { line -> line.glyphIds() },
        )
        assertEquals(
            listOf(
                listOf(352.05078f, 0f, 317.8711f),
                listOf(900f, 900f, 317.8711f),
                listOf(591.7969f),
            ),
            result.layout.lines.map { line -> line.glyphAdvances() },
        )
        // Frozen Unicode 16 UAX #14 and HarfBuzz oracle over the checked-in real GDEF/DejaVu
        // fixtures; only public paragraph lines are observed here.
    }

    @Test
    fun publicFacadeReshapesAnUnsafeBreakWithFinalBotAndEotGlyphs() {
        val fixture = fontFixture(
            value = "office-office",
            fonts = listOf(FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = layout(
            fixture,
            constraints(width = 3_200f, top = 50f, height = 2_400f),
            language = "en",
        )

        assertEquals(
            listOf(range(fixture.snapshot, 0, 7), range(fixture.snapshot, 7, 13)),
            result.layout.lines.map(LineLayout::range),
        )
        assertEquals(
            listOf(listOf(82, 5044, 70, 72, 16), listOf(82, 5044, 70, 72)),
            result.layout.lines.map { line -> line.glyphIds() },
        )
        assertEquals(
            listOf(
                listOf(611.8164f, 966.7969f, 549.8047f, 615.2344f, 360.83984f),
                listOf(611.8164f, 966.7969f, 549.8047f, 615.2344f),
            ),
            result.layout.lines.map { line -> line.glyphAdvances() },
        )
        assertTrue(result.layout.lines.all { line ->
            line.positionedGlyphRuns.first().sourceRun.bot && line.positionedGlyphRuns.last().sourceRun.eot
        })
        // Frozen HarfBuzz 14.3.0 oracle for separate BOT/EOT shaping of the selected lines.
    }

    @Test
    fun publicFacadeBacktracksFromAFinalEotAdvanceThatWouldOverflow() {
        val fixture = fontFixture(
            value = "A-V-AV",
            fonts = listOf(FontFixture("dejavu/DejaVuSans.ttf", "DejaVu Sans")),
        )

        val result = layout(
            fixture,
            constraints(width = 1_940f, top = 50f, height = 3_600f),
            language = "en",
        )

        assertEquals(
            listOf(range(fixture.snapshot, 0, 2), range(fixture.snapshot, 2, 4), range(fixture.snapshot, 4, 6)),
            result.layout.lines.map(LineLayout::range),
        )
        assertEquals(
            listOf(1_022.9492f, 986.3281f, 1_304.1992f),
            result.layout.lines.map { line -> line.contentMetrics.inlineAdvance.value },
        )
        assertTrue(result.layout.lines.all { line -> line.contentMetrics.inlineAdvance <= LayoutUnit(1_940f) })
        // Frozen HarfBuzz 14.3.0 oracle: final EOT shaping makes `A-V-` too wide, so the
        // published first line must backtrack to the preceding legal boundary `A-`.
    }

    @Test
    fun publicFacadeAppliesTheExactPerLineBidiOracle() {
        val fixture = fontFixture(
            value = "abc \u05D0\u05D1\u05D2   \u05E9\u05DC\u05D5\u05DD",
            fonts = listOf(FontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")),
        )

        val result = layout(
            fixture,
            constraints(width = 4_500f, top = 50f, height = 2_400f),
            language = "he",
        )
        val first = result.layout.lines.first()

        assertEquals(
            listOf(range(fixture.snapshot, 0, 10), range(fixture.snapshot, 10, 14)),
            result.layout.lines.map(LineLayout::range),
        )
        assertEquals(listOf(0, 1, 0), first.positionedGlyphRuns.map { run -> run.sourceRun.bidiLevel })
        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 7), range(fixture.snapshot, 7, 10)),
            first.positionedGlyphRuns.map { run -> run.sourceRun.range },
        )
        assertEquals(
            listOf(
                listOf(68, 69, 70, 3, 1282, 1281, 1280, 3, 3, 3),
                listOf(1293, 1285, 1292, 1305),
            ),
            result.layout.lines.map { line -> line.glyphIds() },
        )
        assertEquals(
            listOf(
                listOf(556.15234f, 556.15234f, 500f, 277.83203f, 422.85156f, 598.14453f, 627.9297f, 277.83203f, 277.83203f, 277.83203f),
                listOf(678.22266f, 259.76562f, 529.78516f, 729.98047f),
            ),
            result.layout.lines.map { line -> line.glyphAdvances() },
        )
        assertEquals(listOf(0, 1, 2), first.positionedGlyphRuns.map { run -> run.visualOrder })
        // Frozen UAX #9 L1/L2 and HarfBuzz 14.3.0 oracle, asserted only through public lines.
    }

    @Test
    fun publicFacadeContinuationReplaysAsTheSameTallComposition() {
        val fixture = multiFaceFixture("fi \u0633\u0644\u0627\u0645")
        val partial = layout(fixture, constraints(width = 1_400f, top = 50f, height = 1_200f))
        val continuation = assertNotNull(partial.continuation)
        val resumed = assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layout(
                request(
                    fixture = fixture,
                    constraints = constraints(width = 1_400f, top = 1_250f, height = 1_200f),
                    sourceRange = continuation.remainingSourceRange,
                    continuation = continuation,
                ),
            ),
        )
        val full = layout(fixture, constraints(width = 1_400f, top = 50f, height = 2_400f))

        assertEquals(CoverageStatus.PARTIAL, partial.coverageStatus)
        assertEquals(range(fixture.snapshot, 3, 7), continuation.remainingSourceRange)
        assertEquals(CoverageStatus.COMPLETE, resumed.coverageStatus)
        assertEquals(
            full.layout.lines.map(::lineFingerprint),
            (partial.layout.lines + resumed.layout.lines).map(::lineFingerprint),
        )

        val incompatible = JvmEditableParagraphFacade.layout(
            request(
                fixture = fixture,
                constraints = constraints(width = 1_399f, top = 1_250f, height = 1_200f),
                sourceRange = continuation.remainingSourceRange,
                continuation = continuation,
            ),
        )
        assertIs<ParagraphLayoutError.InvalidInput>(
            assertIs<ParagraphLayoutResult.Failure>(incompatible).error,
        )
    }

    @Test
    fun publicFacadeReturnsTypedCancellationAndInvalidClusterRange() {
        val fixture = multiFaceFixture("f\u0301")
        val cancelled = JvmEditableParagraphFacade.layout(
            request(
                fixture,
                constraints(width = 1_400f, top = 50f, height = 1_200f),
                cancellationToken = CancellationToken.cancelled,
            ),
        )
        assertIs<ParagraphLayoutResult.Cancelled>(cancelled)

        val splitCluster = JvmEditableParagraphFacade.layout(
            request(
                fixture,
                constraints(width = 1_400f, top = 50f, height = 1_200f),
                sourceRange = range(fixture.snapshot, 1, 2),
            ),
        )
        assertIs<ParagraphLayoutError.InvalidInput>(
            assertIs<ParagraphLayoutResult.Failure>(splitCluster).error,
        )
    }

    @Test
    fun publicFacadePublishesEmptyAndMandatoryTrailingEmptyLines() {
        val emptyFixture = multiFaceFixture("")
        val empty = layout(emptyFixture, constraints(width = 1_400f, top = 50f, height = 1_200f))
        assertEquals(listOf(emptyFixture.snapshot.range), empty.layout.lines.map(LineLayout::range))
        assertTrue(empty.layout.lines.single().positionedGlyphRuns.isEmpty())

        val terminatedFixture = multiFaceFixture("fi\n")
        val terminated = layout(terminatedFixture, constraints(width = 1_400f, top = 50f, height = 2_400f))
        val end = terminatedFixture.snapshot.range.endExclusive
        assertEquals(
            listOf(terminatedFixture.snapshot.range, TextRange(end, end)),
            terminated.layout.lines.map(LineLayout::range),
        )
        assertTrue(terminated.layout.lines.last().positionedGlyphRuns.isEmpty())
    }

    @Test
    fun ownedBackendCloseFailureCannotPublishAParagraphSuccess() {
        val fixture = multiFaceFixture("fi")
        val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(
            JvmHarfBuzzShapingBackend.open(),
        ).value
        try {
            val result = JvmEditableParagraphFacade.layout(
                request(fixture, constraints(width = 1_400f, top = 50f, height = 1_200f)),
                CloseFailingBackend(backend),
            )

            val failure = assertIs<ParagraphLayoutResult.Failure>(result)
            val fontFailure = assertIs<ParagraphLayoutError.FontFailure>(failure.error)
            assertEquals("font.test-close-failure", fontFailure.fontError.code)
            assertEquals("font.test-close-failure", failure.diagnostics.single().code)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    private fun layout(
        fixture: ParagraphFixture,
        constraints: HorizontalParagraphConstraints,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "ar",
    ): ParagraphLayoutResult.Success = assertIs(
        JvmEditableParagraphFacade.layout(request(fixture, constraints, baseDirection = baseDirection, language = language)),
    )

    private fun request(
        fixture: ParagraphFixture,
        constraints: HorizontalParagraphConstraints,
        sourceRange: TextRange = fixture.snapshot.range,
        continuation: org.graphiks.kalligraphie.api.LayoutContinuation? = null,
        cancellationToken: CancellationToken = CancellationToken.none,
        language: String = "ar",
        features: List<OpenTypeFeature> = emptyList(),
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
    ): JvmEditableParagraphFacadeRequest = JvmEditableParagraphFacadeRequest(
        snapshot = fixture.snapshot,
        sourceRange = sourceRange,
        constraints = constraints,
        baseDirection = baseDirection,
        language = language,
        fontCatalog = fixture.catalog,
        resolutionPolicy = fixture.policy,
        fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        features = features,
        materialization = EditableLineMaterialization.LayoutOnly,
        continuation = continuation,
        cancellationToken = cancellationToken,
    )

    private fun multiFaceFixture(value: String): ParagraphFixture = fontFixture(
        value = value,
        fonts = listOf(
            FontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
            FontFixture("amiri/Amiri-Regular.ttf", "Amiri Regular"),
        ),
        policyId = "public-multiscript-fixture",
    )

    private fun fontFixture(
        value: String,
        fonts: List<FontFixture>,
        policyId: String = "public-paragraph-fixture",
    ): ParagraphFixture {
        val sources = fonts.map { font -> fontSource(font.relativePath, font.declaredName) }
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(sources),
        ).value
        val faces = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = policyId,
            version = "1",
            candidates = faces.map(::FontResolutionCandidate),
            lastResortFace = faces.last(),
        )
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        return ParagraphFixture(snapshot, catalog, policy, faces.first(), faces.last())
    }

    private fun constraints(
        width: Float,
        top: Float,
        height: Float,
    ): HorizontalParagraphConstraints = HorizontalParagraphConstraints(
        region = LayoutRect(LayoutUnit(100f), LayoutUnit(top), LayoutUnit(100f + width), LayoutUnit(top + height)),
        lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
    )

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange = TextRange(
        snapshot.textIndexAtScalarBoundary(start),
        snapshot.textIndexAtScalarBoundary(endExclusive),
    )

    private fun lineFingerprint(line: LineLayout): List<Any> = listOf(
        line.range,
        line.baseline,
        line.contentMetrics,
        line.lineBox,
        line.designInkBounds,
        line.positionedGlyphRuns.flatMap { run ->
            run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId to glyph.origin }
        },
        line.allCaretCandidates.map { candidate -> candidate.position to candidate.geometry },
    )

    private fun LineLayout.glyphIds(): List<Int> = positionedGlyphRuns.flatMap { run ->
        run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value }
    }

    private fun LineLayout.glyphAdvances(): List<Float> = positionedGlyphRuns.flatMap { run ->
        run.glyphs.map { glyph -> glyph.advance.x.value }
    }

    private fun fontSource(relativePath: String, declaredName: String): FontSource = FontSource(
        sourceBytes = fixtureBytes(relativePath),
        provenance = FontSourceProvenance(declaredName),
    )

    private fun fixtureBytes(relativePath: String): ByteArray {
        val classpathPath = "/fonts/$relativePath"
        javaClass.getResourceAsStream(classpathPath)?.use { stream -> return stream.readBytes() }
        val sourceCandidates = listOf(
            Path.of("shaping", "src", "jvmTest", "resources", "fonts", relativePath),
            Path.of("kalligraphie", "shaping", "src", "jvmTest", "resources", "fonts", relativePath),
        )
        val source = sourceCandidates.firstOrNull(Files::isRegularFile)
        return Files.readAllBytes(checkNotNull(source) { "fixture font is missing: $relativePath" })
    }

    private data class ParagraphFixture(
        val snapshot: TextSnapshot,
        val catalog: FontCatalogSnapshot,
        val policy: FontResolutionPolicySnapshot,
        val latinFace: FontFaceId,
        val arabicFace: FontFaceId,
    )

    private data class FontFixture(
        val relativePath: String,
        val declaredName: String,
    )

    private class CloseFailingBackend(
        private val delegate: ShapingBackend,
    ) : ShapingBackend {
        override val identity = delegate.identity

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> = delegate.shape(request)

        override fun close(): FontOperationResult<Unit> = FontOperationResult.Failure(
            FontError.FontDataFailure(
                code = "font.test-close-failure",
                message = "The test backend could not close.",
                location = FontDiagnosticLocation.Source,
            ),
        )
    }
}
