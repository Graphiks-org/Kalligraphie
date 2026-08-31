package org.graphiks.kalligraphie.layout

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontCatalogGeneration
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
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalogEntry
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots

class EditableParagraphCompositionTest {
    private val openedBackends = mutableListOf<ShapingBackend>()

    @AfterTest
    fun closeOpenedBackends() {
        openedBackends.asReversed().forEach { backend ->
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        }
    }

    @Test
    fun latinSpacesChooseTheLastLegalBreakThatFits() {
        val fixture = fixture("one two three", width = 3_000f, height = 4_000f)

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 8), range(fixture.snapshot, 8, 13)),
            result.lines.map { it.line.range },
        )
        assertEquals(listOf(850f, 1_850f, 2_850f), result.lines.map { it.baseline.y.value })
        assertEquals(3, result.lines.size)
        result.lines.forEach { placed -> assertCompleteClusterCoverage(fixture.snapshot, placed.line.range, placed.line) }
    }

    @Test
    fun mandatoryBreakEndsTheCurrentLineEvenWhenMoreTextFits() {
        val fixture = fixture("a\nb", width = 20_000f, height = 3_000f)

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 2), range(fixture.snapshot, 2, 3)),
            result.lines.map { it.line.range },
        )
        assertEquals(listOf(850f, 1_850f), result.lines.map { it.baseline.y.value })
        result.lines.forEach { placed -> assertCompleteClusterCoverage(fixture.snapshot, placed.line.range, placed.line) }
    }

    @Test
    fun anUnbreakableOverwideUnitIsPublishedWholeAndAdvances() {
        val fixture = fixture("Supercalifragilistic", width = 100f, height = 2_000f)

        val result = compose(fixture)

        assertEquals(listOf(fixture.snapshot.range), result.lines.map { it.line.range })
        assertEquals(1, result.lines.size)
        assertTrue(result.lines.single().inlineAdvance.value > fixture.request.constraints.width.value)
        assertCompleteClusterCoverage(fixture.snapshot, fixture.snapshot.range, result.lines.single().line)
    }

    @Test
    fun emptyParagraphPublishesOnePhysicalEmptyLine() {
        val fixture = fixture("", width = 2_000f, height = 1_000f)

        val result = compose(fixture)

        assertEquals(1, result.lines.size)
        assertEquals(fixture.snapshot.range, result.lines.single().line.range)
        assertTrue(result.lines.single().line.positionedGlyphRuns.isEmpty())
        assertEquals(LayoutPoint(LayoutUnit(100f), LayoutUnit(850f)), result.lines.single().baseline)
    }

    @Test
    fun mandatoryTerminationAtParagraphEndPublishesATrailingEmptyLine() {
        val fixture = fixture("a\n", width = 2_000f, height = 3_000f)
        val end = fixture.snapshot.range.endExclusive

        val result = compose(fixture)

        assertEquals(
            listOf(fixture.snapshot.range, TextRange(end, end)),
            result.lines.map { it.line.range },
        )
        assertEquals(listOf(850f, 1_850f), result.lines.map { it.baseline.y.value })
        assertTrue(result.lines.last().line.positionedGlyphRuns.isEmpty())
    }

    @Test
    fun unsafeBoundaryReshapingKeepsTheFrozenLigatureAndSeparateLineAdvances() {
        val fixture = fixture("office-office", width = 3_200f, height = 2_000f)

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 7), range(fixture.snapshot, 7, 13)),
            result.lines.map { it.line.range },
        )
        assertEquals(
            listOf(listOf(82, 5044, 70, 72, 16), listOf(82, 5044, 70, 72)),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } } },
        )
        assertEquals(
            listOf(
                listOf(611.8164f, 966.7969f, 549.8047f, 615.2344f, 360.83984f),
                listOf(611.8164f, 966.7969f, 549.8047f, 615.2344f),
            ),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.advance.x.value } } },
        )
        assertTrue(result.lines.all { placed -> placed.line.positionedGlyphRuns.first().sourceRun.bot })
        assertTrue(result.lines.all { placed -> placed.line.positionedGlyphRuns.last().sourceRun.eot })
        // Frozen external oracle: whole-span HarfBuzz 14.3.0 marks the glyph following the
        // legal hyphen boundary unsafe-to-break/unsafe-to-concat. Separately shaping `office-`
        // and `office` with DejaVu Sans at 1000, monotone characters, BOT/EOT, and the OT shaper
        // produces the literal glyph IDs/advances above; the provisional cross-boundary advance
        // of the hyphen is deliberately not published.
    }

    @Test
    fun combiningVariationAndEmojiZwJUnitsRemainWholeAcrossNarrowLines() {
        val fixture = fixture(
            "f\u0301 \u2764\uFE0F\u200D\u2764\uFE0F x",
            width = 100f,
            height = 4_000f,
            fontResources = listOf(
                FontFixture("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
                FontFixture("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans"),
            ),
        )

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 3), range(fixture.snapshot, 3, 9), range(fixture.snapshot, 9, 10)),
            result.lines.map { it.line.range },
        )
        assertEquals(
            listOf(listOf(73, 5923, 3), listOf(6, 6, 3), listOf(91)),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } } },
        )
        assertEquals(
            listOf(
                listOf(352.05078f, 0f, 317.8711f),
                listOf(900f, 900f, 317.8711f),
                listOf(591.7969f),
            ),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.advance.x.value } } },
        )
        result.lines.forEach { placed -> assertCompleteClusterCoverage(fixture.snapshot, placed.line.range, placed.line) }
        // Frozen external oracles: Unicode 16 LineBreakTest LB8a/LB9 and HarfBuzz 14.3.0
        // over the audited GDEF/DejaVu fixtures. No expectation is computed by the composer.
    }

    @Test
    fun multilineBidiResetsTrailingSpacesAndReordersEachSelectedLine() {
        val fixture = fixture(
            "abc \u05D0\u05D1\u05D2   \u05E9\u05DC\u05D5\u05DD",
            width = 4_500f,
            height = 3_000f,
            language = "he",
            fontResources = listOf(FontFixture("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")),
        )

        val result = compose(fixture)

        assertEquals(
            listOf(range(fixture.snapshot, 0, 10), range(fixture.snapshot, 10, 14)),
            result.lines.map { it.line.range },
        )
        assertEquals(
            listOf(0, 1, 0),
            result.lines.first().line.positionedGlyphRuns.map { it.sourceRun.bidiLevel },
        )
        assertEquals(
            listOf(range(fixture.snapshot, 0, 4), range(fixture.snapshot, 4, 7), range(fixture.snapshot, 7, 10)),
            result.lines.first().line.positionedGlyphRuns.map { it.sourceRun.range },
        )
        assertEquals(
            listOf(
                listOf(68, 69, 70, 3, 1282, 1281, 1280, 3, 3, 3),
                listOf(1293, 1285, 1292, 1305),
            ),
            result.lines.map { placed -> placed.line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.shapedGlyph.glyphId.value } } },
        )
        assertEquals(listOf(0, 1, 2), result.lines.first().line.positionedGlyphRuns.map { it.visualOrder })
        // Frozen external oracle: UAX #9 L1/L2 for the selected 0..<10 line, plus
        // HarfBuzz 14.3.0 Liberation Sans glyph order for Latn/Hebr runs.
    }

    @Test
    fun verticalCompositionStopsBeforeAPartialLineBox() {
        val fixture = fixture("one two three", width = 3_000f, height = 1_999f)

        val result = compose(fixture)

        assertEquals(listOf(range(fixture.snapshot, 0, 4)), result.lines.map { it.line.range })
        assertEquals(range(fixture.snapshot, 4, 13), result.remainingSourceRange)
        assertNull(result.lines.single().line.positionedGlyphRuns.firstOrNull { run -> run.sourceRun.range.start >= result.remainingSourceRange!!.start })
    }

    @Test
    fun compositionResultRejectsMutationThroughAJvmMutableCast() {
        val fixture = fixture("one two three", width = 3_000f, height = 4_000f)
        val result = compose(fixture)

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.lines as MutableList<ComposedParagraphLine>).clear()
        }
        assertEquals(3, result.lines.size)
    }

    private fun compose(fixture: Fixture): ParagraphCompositionResult.Success =
        assertIs(
            ParagraphComposer.compose(fixture.request, EditableLineMaterialization.LayoutOnly),
        )

    private fun fixture(
        value: String,
        width: Float,
        height: Float,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        language: String = "en",
        fontResources: List<FontFixture> = listOf(FontFixture("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")),
    ): Fixture {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(value.toCharArray())),
        ).snapshot
        val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(
            snapshot,
            UnicodeAnalysisRequest(baseDirection, language),
        )
        val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(snapshot, unicodeAnalysis)
        val sources = fontResources.map { font -> source(font.resource, font.declaredName) }
        val generation = FontCatalogGeneration("paragraph-composition-${fontResources.joinToString("-") { it.declaredName }}-v1")
        val catalog = EmbeddedFontCatalog(
            generation,
            sources.map { source -> EmbeddedFontCatalogEntry(source, SfntReader.readMetadata(source).successValue()) },
        )
        val faces = sources.map { source -> FontFaceId(source.id, 0) }
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "paragraph-composition-test-policy",
            version = "1",
            candidates = faces.map(::FontResolutionCandidate),
            lastResortFace = faces.last(),
        )
        val backend = JvmHarfBuzzShapingBackend.open().successValue().also(openedBackends::add)
        val metrics = LineVerticalMetrics(LayoutUnit(800f), LayoutUnit(200f))
        val request = ParagraphLayoutRequest(
            snapshot = snapshot,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(100f + width), LayoutUnit(50f + height)),
                lineMetrics = metrics,
            ),
            baseDirection = baseDirection,
            language = language,
            featurePolicy = backend.identity.featurePolicy,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            shapingBackend = backend,
            materializationIdentity = ParagraphMaterializationIdentity.LayoutOnly,
        )
        return Fixture(snapshot, request)
    }

    private fun assertCompleteClusterCoverage(
        snapshot: TextSnapshot,
        range: TextRange,
        line: org.graphiks.kalligraphie.api.EditableLine,
    ) {
        val published = line.positionedGlyphRuns
            .flatMap { run -> run.sourceRun.clusters }
            .flatMap { cluster -> cluster.scalarRanges }
        assertEquals(snapshot.scalarRanges(range), published)
    }

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))

    private fun source(resource: String, declaredName: String): FontSource =
        FontSource(checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }, FontSourceProvenance(declaredName))

    private fun <T> FontOperationResult<T>.successValue(): T = assertIs<FontOperationResult.Success<T>>(this).value

    private data class Fixture(
        val snapshot: TextSnapshot,
        val request: ParagraphLayoutRequest,
    )

    private data class FontFixture(val resource: String, val declaredName: String)
}
