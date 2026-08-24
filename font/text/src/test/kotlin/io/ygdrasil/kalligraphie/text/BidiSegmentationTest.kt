package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.text.shaping.BidiFixtureDumpInput
import io.ygdrasil.kalligraphie.text.shaping.BidiRunsDump
import io.ygdrasil.kalligraphie.text.shaping.BasicOpenTypeShapingEngine
import io.ygdrasil.kalligraphie.text.shaping.DefaultBidiResolver
import io.ygdrasil.kalligraphie.text.shaping.GlyphCluster
import io.ygdrasil.kalligraphie.text.shaping.GlyphMapper
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataGenerator
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataSetResources
import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_PARAGRAPH_BIDI_REQUIRED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_UNICODE_BIDI_CONTROL_UNBALANCED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_UNICODE_INVALID_SCALAR_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_UNICODE_DATA_VERSION_MISMATCH_DIAGNOSTIC_CODE

class BidiSegmentationTest {
    private val resolver = DefaultBidiResolver(PinnedUnicodeDataSetResources.load())

    @Test
    fun resolvesPinnedBidiRunsForMixedHebrewArabicNumbersNeutralsAndIsolates() {
        val hebrewLatin = resolver.resolveDetailed(ShapingRequest("abc \u05D0\u05D1!"))
        val arabicNumberNeutral = resolver.resolveDetailed(ShapingRequest("\u0627\u0661\u0662, abc"))
        val isolateControls = resolver.resolveDetailed(ShapingRequest("abc \u2067\u05D0\u05D1\u2069 xyz"))

        assertEquals("LeftToRight", hebrewLatin.paragraphDirection)
        assertEquals(listOf("0..3:0:L", "4..6:1:R"), hebrewLatin.runLabels())
        assertTrue(hebrewLatin.trace.any { it.rule == "W7/ON-neutral-resolution" })

        assertEquals("RightToLeft", arabicNumberNeutral.paragraphDirection)
        assertEquals(listOf("0..4:1:R", "5..7:2:L"), arabicNumberNeutral.runLabels())
        assertTrue(arabicNumberNeutral.trace.any { it.rule == "W2/AN-inside-rtl-run" })

        assertEquals(listOf("RLI", "PDI"), isolateControls.sourceControls.map { it.kind })
        assertEquals(listOf("0..3:0:L", "5..6:1:R", "8..11:0:L"), isolateControls.runLabels())
        assertTrue(isolateControls.runs.all { run -> isolateControls.clusters.any { it.utf16Range.first == run.logicalUtf16Range.first } })
        assertTrue(isolateControls.runs.all { run -> isolateControls.clusters.any { it.utf16Range.last == run.logicalUtf16Range.last } })
    }

    @Test
    fun diagnosesUnbalancedControlsParagraphBidiAndVersionMismatch() {
        val unbalanced = resolver.resolveDetailed(ShapingRequest("abc \u2067\u05D0\u05D1"))
        val singleRun = resolver.resolveDetailed(
            ShapingRequest("invoice \u05D0\u05D1 42", paragraphDirection = 1),
            requireParagraphOrdering = false,
        )
        val mismatch = DefaultBidiResolver(
            unicodeDataSet = PinnedUnicodeDataSetResources.load().copy(version = PinnedUnicodeDataGenerator.PinnedUnicodeVersion.toVersion()),
            expectedUnicodeVersion = "99.0.0",
        ).resolveDetailed(ShapingRequest("abc"))

        assertTrue(unbalanced.diagnostics.any { it.code == TEXT_UNICODE_BIDI_CONTROL_UNBALANCED_DIAGNOSTIC_CODE })
        assertTrue(singleRun.diagnostics.any { it.code == TEXT_SHAPING_PARAGRAPH_BIDI_REQUIRED_DIAGNOSTIC_CODE })
        assertTrue(mismatch.diagnostics.any { it.code == TEXT_SHAPING_UNICODE_DATA_VERSION_MISMATCH_DIAGNOSTIC_CODE })
    }

    @Test
    fun basicShapingPathPropagatesParagraphBidiRequiredDiagnostic() {
        val result = BasicOpenTypeShapingEngine(glyphMapper = MissingGlyphMapper)
            .shape(ShapingRequest("abc \u05D0\u05D1", paragraphDirection = 1))

        assertEquals(
            listOf(
                ShapedGlyphRun(
                    glyphIds = listOf(0, 0, 0, 0),
                    clusters = listOf(
                        GlyphCluster(textRange = 0..0, glyphRange = 0..0, advanceX = 12f),
                        GlyphCluster(textRange = 1..1, glyphRange = 1..1, advanceX = 12f),
                        GlyphCluster(textRange = 2..2, glyphRange = 2..2, advanceX = 12f),
                        GlyphCluster(textRange = 3..3, glyphRange = 3..3, advanceX = 12f),
                    ),
                    advanceX = 48f,
                    script = "Latn",
                    bidiLevel = 0,
                ),
                ShapedGlyphRun(
                    glyphIds = listOf(0, 0),
                    clusters = listOf(
                        GlyphCluster(textRange = 5..5, glyphRange = 0..0, advanceX = 12f),
                        GlyphCluster(textRange = 4..4, glyphRange = 1..1, advanceX = 12f),
                    ),
                    advanceX = 24f,
                    script = "Hebr",
                    bidiLevel = 1,
                ),
            ),
            result.glyphRuns,
        )
        assertTrue(result.diagnostics.any { it.code == TEXT_SHAPING_PARAGRAPH_BIDI_REQUIRED_DIAGNOSTIC_CODE })
    }

    @Test
    fun resolvesExplicitEmbeddingAndOverrideControlsInBoundedFixtures() {
        val embeddingControls = resolver.resolveDetailed(ShapingRequest("abc \u202B12\u202C \u202D\u05D0\u05D1\u202C"))

        assertEquals(listOf("RLE", "PDF", "LRO", "PDF"), embeddingControls.sourceControls.map { it.kind })
        assertEquals(listOf("0..3:0:L", "5..6:1:R", "8..11:0:L"), embeddingControls.runLabels())
        assertTrue(embeddingControls.trace.any { it.rule == "X2/RLE-embedding" })
        assertTrue(embeddingControls.trace.any { it.rule == "X4/LRO-override" })
    }

    @Test
    fun malformedUtf16AndSplitSurrogateRangesReturnStableDiagnosticsWithoutThrowing() {
        val isolatedHighSurrogate = resolver.resolveDetailed(ShapingRequest("\uD800abc"))
        val splitSurrogateRange = resolver.resolveDetailed(ShapingRequest("a\uD83D\uDE00b", textRange = 2..2))

        assertTrue(isolatedHighSurrogate.diagnostics.any { it.code == TEXT_UNICODE_INVALID_SCALAR_DIAGNOSTIC_CODE })
        assertTrue(splitSurrogateRange.diagnostics.any { it.code == TEXT_UNICODE_INVALID_SCALAR_DIAGNOSTIC_CODE })
        assertTrue(splitSurrogateRange.diagnostics.any { it.textRange == 2..2 })
        assertEquals(emptyList(), isolatedHighSurrogate.runs)
        assertEquals(emptyList(), splitSurrogateRange.runs)
    }

    @Test
    fun mismatchedEmbeddingAndIsolateClosersRemainUnbalanced() {
        val isolateClosedByPdf = resolver.resolveDetailed(ShapingRequest("abc \u2067\u05D0\u202C xyz"))
        val embeddingClosedByPdi = resolver.resolveDetailed(ShapingRequest("abc \u202B\u05D0\u2069 xyz"))

        assertEquals(listOf("RLI:false", "PDF:false"), isolateClosedByPdf.controlLabels())
        assertEquals(listOf("RLE:false", "PDI:false"), embeddingClosedByPdi.controlLabels())
        assertTrue(isolateClosedByPdf.diagnostics.any { it.code == TEXT_UNICODE_BIDI_CONTROL_UNBALANCED_DIAGNOSTIC_CODE })
        assertTrue(embeddingClosedByPdi.diagnostics.any { it.code == TEXT_UNICODE_BIDI_CONTROL_UNBALANCED_DIAGNOSTIC_CODE })
    }

    @Test
    fun serializesBidiRunsDumpForTicketFixtures() {
        val fixtures = listOf(
            "bidi-hebrew-latin.txt",
            "bidi-arabic-number-neutral.txt",
            "bidi-isolate-controls.txt",
            "bidi-embedding-override-controls.txt",
            "bidi-unbalanced-controls.txt",
            "bidi-single-run-needs-paragraph.txt",
        ).map { fixtureName ->
            fixtureName to readProjectFile("font/fixtures/expected/unicode/$fixtureName").trimEnd('\n', '\r')
        }
        val dump = BidiRunsDump(
            unicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
            inputs = fixtures.map { (fixtureName, text) ->
                BidiFixtureDumpInput(
                    fixtureName = fixtureName,
                    sourceText = text,
                    result = resolver.resolveDetailed(ShapingRequest(text), requireParagraphOrdering = false),
                )
            },
        )

        val json = dump.toCanonicalJson()

        assertEquals(readProjectFile("font/fixtures/expected/unicode/bidi-runs.json"), json)
        assertTrue(json.contains("\"dumpId\": \"bidi-runs\""))
        assertTrue(json.contains("\"ownerTickets\": [\"KFONT-M5-003\"]"))
        assertTrue(json.contains("\"fixtureName\": \"bidi-hebrew-latin.txt\""))
        assertTrue(json.contains("\"sourceControls\""))
        assertTrue(json.contains("\"trace\""))
        assertTrue(json.contains("\"diagnostics\""))
        assertTrue(json.contains("\"text.shaping.paragraph-bidi-required\""))
        assertTrue(json.contains("\"text.unicode.bidi-control-unbalanced\""))
        assertTrue(json.contains("\"no-paired-bracket-resolution-claim\""))
    }

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun String.toVersion() = io.ygdrasil.kalligraphie.text.shaping.UnicodeVersion(this)

    private object MissingGlyphMapper : GlyphMapper {
        override fun glyphIdFor(typefaceId: io.ygdrasil.kalligraphie.font.TypefaceID?, codePoint: Int): Int? = null
    }
}

private fun io.ygdrasil.kalligraphie.text.shaping.BidiResolution.runLabels(): List<String> =
    runs.map { run -> "${run.logicalUtf16Range}:${run.embeddingLevel}:${run.direction}" }

private fun io.ygdrasil.kalligraphie.text.shaping.BidiResolution.controlLabels(): List<String> =
    sourceControls.map { control -> "${control.kind}:${control.balanced}" }
