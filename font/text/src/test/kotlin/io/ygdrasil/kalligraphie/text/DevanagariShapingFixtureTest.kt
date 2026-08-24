package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.font.sfnt.CMapTable
import io.ygdrasil.kalligraphie.font.sfnt.DefaultOpenTypeFaceParser
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGdefTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposPairTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposSingleTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubTable
import io.ygdrasil.kalligraphie.text.shaping.BasicOpenTypeShapingEngine
import io.ygdrasil.kalligraphie.text.shaping.CMapGlyphMapper
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataSetResources
import io.ygdrasil.kalligraphie.text.shaping.ScriptExtensionsItemizer
import io.ygdrasil.kalligraphie.text.shaping.ScriptItemizer
import io.ygdrasil.kalligraphie.text.shaping.ScriptRun
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest

class DevanagariShapingFixtureTest {
    @Test
    fun pinnedScriptItemizerClassifiesVendoredPrebaseMatraCaseAsDeva() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440711",
            relativePath = "font/fixtures/fonts/fallback/NotoSansDevanagari-Regular.ttf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0915\u093F",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(1, result.glyphRuns.size)
        assertEquals("Deva", result.glyphRuns.single().script)
    }

    @Test
    fun basicOpenTypeShapingEngineShapesVendoredDevanagariConsonantCluster() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440712",
            relativePath = "font/fixtures/fonts/fallback/NotoSansDevanagari-Regular.ttf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0915\u094D\u0937\u093E",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(1, result.glyphRuns.size)
        assertEquals("Deva", result.glyphRuns.single().script)
        assertEquals(2, result.glyphRuns.single().glyphIds.size)
        assertEquals(1, result.glyphRuns.single().clusters.size)
        assertEquals(0..3, result.glyphRuns.single().clusters.single().textRange)
    }

    @Test
    fun basicOpenTypeShapingEngineShapesVendoredDevanagariRephLikeCase() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440713",
            relativePath = "font/fixtures/fonts/fallback/NotoSansDevanagari-Regular.ttf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0930\u094D\u0915",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(1, result.glyphRuns.size)
        assertEquals("Deva", result.glyphRuns.single().script)
        assertTrue(
            result.glyphRuns.single().clusters.any { it.offsetX != 0f || it.advanceX == 0f },
            "Reph-like case should expose a reordered or zero-advance cluster on the vendored font.",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineShapesVendoredDevanagariMarkPlacementCase() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440714",
            relativePath = "font/fixtures/fonts/fallback/NotoSansDevanagari-Regular.ttf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0915\u0902",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(1, result.glyphRuns.size)
        assertEquals("Deva", result.glyphRuns.single().script)
        assertTrue(
            result.glyphRuns.single().clusters.any { it.offsetX != 0f || it.offsetY != 0f || it.advanceX == 0f },
            "Devanagari mark case should expose a positioned or zero-advance mark cluster on the vendored font.",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineShapesFixtureConsonantCluster() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440731",
            relativePath = "font/fixtures/fonts/shaping/devanagari-consonant-cluster.otf",
            allowDiagnostics = true,
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0915\u094D\u0937\u093E",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals("Deva", result.glyphRuns.single().script)
    }

    @Test
    fun basicOpenTypeShapingEngineShapesFixtureReph() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440732",
            relativePath = "font/fixtures/fonts/shaping/devanagari-reph.otf",
            allowDiagnostics = true,
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0930\u094D\u0915",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals("Deva", result.glyphRuns.single().script)
    }

    @Test
    fun basicOpenTypeShapingEngineShapesFixturePrebaseMatra() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440733",
            relativePath = "font/fixtures/fonts/shaping/devanagari-prebase-matra.otf",
            allowDiagnostics = true,
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0915\u093F",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals("Deva", result.glyphRuns.single().script)
    }

    @Test
    fun basicOpenTypeShapingEngineShapesFixtureBelowBase() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440734",
            relativePath = "font/fixtures/fonts/shaping/devanagari-below-base.otf",
            allowDiagnostics = true,
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0915\u0941",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals("Deva", result.glyphRuns.single().script)
    }

    @Test
    fun basicOpenTypeShapingEngineShapesFixtureMarkPlacement() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440735",
            relativePath = "font/fixtures/fonts/shaping/devanagari-mark-placement.otf",
            allowDiagnostics = true,
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0915\u0902",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals("Deva", result.glyphRuns.single().script)
    }

    @Test
    fun basicOpenTypeShapingEngineShapesFixtureUnsupportedSyllable() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440736",
            relativePath = "font/fixtures/fonts/shaping/devanagari-unsupported-syllable.otf",
            allowDiagnostics = true,
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0915\u094D\u0937\u094D\u0923",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals("Deva", result.glyphRuns.single().script)
    }

    @Test
    fun devanagariShapedGlyphRunGoldenExistsAndTracksFixtureWave() {
        val golden = readProjectFile("font/fixtures/expected/shaping/devanagari-shaped-glyph-run.json")
        assertContains(golden, """"dumpId": "devanagari-shaped-glyph-run"""")
        assertContains(golden, """"ownerTickets": ["KFONT-M6-008"]""")
        assertContains(golden, """"caseId": "fixture-consonant-cluster"""")
        assertContains(golden, """"caseId": "fixture-reph"""")
        assertContains(golden, """"caseId": "fixture-prebase-matra"""")
        assertContains(golden, """"caseId": "fixture-below-base"""")
        assertContains(golden, """"caseId": "fixture-mark-placement"""")
        assertContains(golden, """"caseId": "fixture-unsupported-syllable"""")
    }

    @Test
    fun devanagariShapingPlanGoldenExistsAndTracksFixtureWave() {
        val golden = readProjectFile("font/fixtures/expected/shaping/devanagari-shaping-plan.json")
        assertContains(golden, """"dumpId": "devanagari-shaping-plan"""")
        assertContains(golden, """"ownerTickets": ["KFONT-M6-008"]""")
    }

    @Test
    fun devanagariGsubTraceGoldenExistsAndTracksFixtureWave() {
        val golden = readProjectFile("font/fixtures/expected/shaping/devanagari-gsub-trace.json")
        assertContains(golden, """"dumpId": "devanagari-gsub-trace"""")
        assertContains(golden, """"ownerTickets": ["KFONT-M6-008"]""")
    }

    @Test
    fun devanagariGposTraceGoldenExistsAndTracksFixtureWave() {
        val golden = readProjectFile("font/fixtures/expected/shaping/devanagari-gpos-trace.json")
        assertContains(golden, """"dumpId": "devanagari-gpos-trace"""")
        assertContains(golden, """"ownerTickets": ["KFONT-M6-008"]""")
    }

    @Test
    fun devanagariShapingReportGoldenExistsAndTracksFixtureWave() {
        val report = readProjectFile("font/fixtures/expected/shaping/devanagari-shaping-report.json")

        assertContains(report, """"dumpId": "devanagari-shaping-report"""")
        assertContains(report, """"ownerTickets": ["KFONT-M6-008"]""")
        assertContains(report, """"fixtureId": "single-ttf-noto-sans-devanagari"""")
        assertContains(report, """"caseId": "prebase-matra-script-selection"""")
        assertContains(report, """"caseId": "consonant-cluster"""")
        assertContains(report, """"caseId": "reph-like"""")
        assertContains(report, """"caseId": "mark-placement"""")
        assertContains(report, """"remainingGateId": "indic-syllable-plan-and-phase-evidence"""")
        assertContains(report, """"no-devanagari-shaping-support-claim"""")
        assertContains(report, """"no-native-shaper-oracle-claim"""")
    }

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun engineFor(face: ParsedFixtureFace): BasicOpenTypeShapingEngine =
        BasicOpenTypeShapingEngine(
            glyphMapper = CMapGlyphMapper(cmapsByTypefaceId = mapOf(face.typefaceId to face.cmap)),
            gsubTablesByTypefaceId = face.gsub?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            gdefTablesByTypefaceId = face.gdef?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            gposTablesByTypefaceId = face.gpos?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            gposSingleTablesByTypefaceId = face.gposSingles?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            gposPairTablesByTypefaceId = face.gposPairs?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            kernUnitsPerEmByTypefaceId = mapOf(face.typefaceId to face.unitsPerEm),
            scriptItemizer = pinnedScriptItemizer(),
        )

    private fun pinnedScriptItemizer(): ScriptItemizer {
        val delegate = ScriptExtensionsItemizer(PinnedUnicodeDataSetResources.load())
        return object : ScriptItemizer {
            override fun itemize(request: ShapingRequest): List<ScriptRun> {
                val requestedTextRange = codePointSafeTextRange(request.text, request.textRange) ?: return emptyList()
                val scopedText = request.text.substring(requestedTextRange.first, requestedTextRange.last + 1)
                return delegate.itemize(scopedText).runs.map { run ->
                    ScriptRun(
                        textRange = (run.utf16Range.first + requestedTextRange.first)..(run.utf16Range.last + requestedTextRange.first),
                        script = run.selectedScript,
                    )
                }
            }
        }
    }

    private fun parsedFixtureFace(
        uuid: String,
        relativePath: String,
        allowDiagnostics: Boolean = false,
    ): ParsedFixtureFace {
        val typefaceId = TypefaceID(Uuid.parse(uuid))
        val path = projectRoot().resolve(relativePath)
        val source = FontSource(
            id = FontSourceID(Uuid.parse(uuid.replaceRange(uuid.length - 1, uuid.length, "0"))),
            kind = FontSourceKind.FILE,
            displayName = path.fileName.toString(),
            bytes = Files.readAllBytes(path),
        )
        val parsed = DefaultOpenTypeFaceParser().parse(source)
        if (!allowDiagnostics) {
            assertEquals(emptyList(), parsed.diagnostics, relativePath)
        }
        return ParsedFixtureFace(
            typefaceId = typefaceId,
            cmap = parsed.cmap,
            unitsPerEm = requireNotNull(parsed.metrics.unitsPerEm),
            gsub = parsed.layout.gsub,
            gdef = parsed.layout.gdef,
            gpos = parsed.layout.gpos,
            gposSingles = parsed.layout.gposSingles,
            gposPairs = parsed.layout.gposPairs,
        )
    }

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun codePointSafeTextRange(text: String, requestedRange: IntRange): IntRange? {
        val normalizedRange = normalizedTextRange(text, requestedRange) ?: return null
        var first = normalizedRange.first
        var last = normalizedRange.last
        if (first > 0 && text[first].isLowSurrogate() && text[first - 1].isHighSurrogate()) {
            first -= 1
        }
        if (last < text.lastIndex && text[last].isHighSurrogate() && text[last + 1].isLowSurrogate()) {
            last += 1
        }
        return first..last
    }

    private fun normalizedTextRange(text: String, requestedRange: IntRange): IntRange? {
        if (text.isEmpty()) return null
        val first = requestedRange.first.coerceAtLeast(0)
        val last = requestedRange.last.coerceAtMost(text.lastIndex)
        return if (first <= last) first..last else null
    }

    private data class ParsedFixtureFace(
        val typefaceId: TypefaceID,
        val cmap: CMapTable,
        val unitsPerEm: Int,
        val gsub: OpenTypeGsubTable?,
        val gdef: OpenTypeGdefTable?,
        val gpos: OpenTypeGposTable?,
        val gposSingles: OpenTypeGposSingleTable?,
        val gposPairs: OpenTypeGposPairTable?,
    )
}
