package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.text.shaping.BasicTextSegmenter
import io.ygdrasil.kalligraphie.text.shaping.GraphemeCluster
import io.ygdrasil.kalligraphie.text.shaping.GraphemeClusterer
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataGenerator
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_UNICODE_DATA_VERSION_MISMATCH_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_UNICODE_CLUSTER_BOUNDARY_INVALID_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_UNICODE_GRAPHEME_RULE_UNSUPPORTED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_UNICODE_INVALID_SCALAR_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.UcdInputFile
import io.ygdrasil.kalligraphie.text.shaping.validateGraphemeClusterInvariants

class GraphemeSegmentationTest {
    @Test
    fun pinnedGraphemeClustererSegmentsRequiredFixtureMatrix() {
        val clusterer = GraphemeClusterer(loadUnicodeDataSet())

        assertEquals(listOf(0..0, 1..1, 2..2), clusterer.segment("abc").clusters.map { it.utf16Range })
        assertEquals(listOf(0..2, 3..4, 5..5), clusterer.segment("\u1100\u1161\u11A8\uAC00\u11A8\uAC01").clusters.map { it.utf16Range })

        val latin = clusterer.segment(readFixture("grapheme-latin-combining.txt"))
        assertEquals(listOf(0..1, 2..2), latin.clusters.map { it.utf16Range })
        assertTrue(latin.boundaries.any { it.ruleId == "GB9" && !it.breakAllowed })

        val emojiZwj = clusterer.segment(readFixture("grapheme-emoji-zwj.txt"))
        assertEquals(listOf(0..6), emojiZwj.clusters.map { it.utf16Range })
        assertTrue(emojiZwj.boundaries.any { it.ruleId == "GB11" && !it.breakAllowed })

        val regionalIndicators = clusterer.segment(readFixture("grapheme-regional-indicators.txt"))
        assertEquals(listOf(0..3, 4..5), regionalIndicators.clusters.map { it.utf16Range })
        assertTrue(regionalIndicators.boundaries.any { it.ruleId == "GB12/13" && !it.breakAllowed })

        val devanagari = clusterer.segment(readFixture("grapheme-devanagari-virama.txt"))
        assertEquals(listOf(0..3), devanagari.clusters.map { it.utf16Range })
        assertTrue(devanagari.boundaries.any { it.ruleId == "GB9c" && !it.breakAllowed })

        val variationSelector = clusterer.segment(readFixture("grapheme-variation-selector.txt"))
        assertEquals(listOf(0..2), variationSelector.clusters.map { it.utf16Range })
        assertTrue(variationSelector.boundaries.any { it.ruleId == "GB9" && !it.breakAllowed })

        val supplementaryVariationSelector = clusterer.segment("\u4E00\uDB40\uDD00")
        assertEquals(listOf(0..2), supplementaryVariationSelector.clusters.map { it.utf16Range })
        assertTrue(supplementaryVariationSelector.boundaries.any { it.ruleId == "GB9" && !it.breakAllowed })

        val mixedCjkSupplementaryVariationSelector = clusterer.segment("\u4E00\uDB40\uDD00\u30A2")
        assertEquals(listOf(0..2, 3..3), mixedCjkSupplementaryVariationSelector.clusters.map { it.utf16Range })

        val crlfControl = clusterer.segment(readEscapedFixture("grapheme-crlf-control.txt"))
        assertEquals(listOf(0..1, 2..2, 3..3), crlfControl.clusters.map { it.utf16Range })
        assertTrue(crlfControl.boundaries.any { it.ruleId == "GB3" && !it.breakAllowed })
        assertTrue(crlfControl.boundaries.any { it.ruleId == "GB4" && it.breakAllowed })

        val prepend = clusterer.segment(readEscapedFixture("grapheme-prepend.txt"))
        assertEquals(listOf(0..1), prepend.clusters.map { it.utf16Range })
        assertTrue(prepend.boundaries.any { it.ruleId == "GB9b" && !it.breakAllowed })
    }

    @Test
    fun basicTextSegmenterUsesPinnedGraphemeClustersByDefault() {
        val segmenter = BasicTextSegmenter()

        assertEquals(listOf(0..6), segmenter.segment(readFixture("grapheme-emoji-zwj.txt")))
        assertEquals(listOf(0..3), segmenter.segment(readFixture("grapheme-devanagari-virama.txt")))
        assertEquals(listOf(0..1, 2..2), segmenter.segment(readFixture("grapheme-latin-combining.txt")))
    }

    @Test
    fun graphemeDiagnosticsAreStableForMismatchInvalidScalarAndInvariantFailures() {
        val unicode = loadUnicodeDataSet()
        val mismatch = GraphemeClusterer(unicode, expectedUnicodeVersion = "15.1.0")
            .segment(readFixture("grapheme-latin-combining.txt"))
        assertEquals(
            listOf(TEXT_SHAPING_UNICODE_DATA_VERSION_MISMATCH_DIAGNOSTIC_CODE),
            mismatch.diagnostics.map { it.code },
        )

        val malformed = GraphemeClusterer(unicode).segment(readEscapedFixture("grapheme-isolated-surrogate.txt"))
        assertEquals(
            listOf(TEXT_UNICODE_INVALID_SCALAR_DIAGNOSTIC_CODE, TEXT_UNICODE_CLUSTER_BOUNDARY_INVALID_DIAGNOSTIC_CODE),
            malformed.diagnostics.map { it.code },
        )
        assertEquals(listOf(1..1), malformed.clusters.map { it.utf16Range })

        val unsupported = GraphemeClusterer(
            unicode.copy(
                graphemeBreak = unicode.graphemeBreak.copy(
                    ranges = unicode.graphemeBreak.ranges.filterNot { range -> range.value == "LVT" },
                ),
            ),
        ).segment("abc")
        assertEquals(
            listOf(TEXT_UNICODE_GRAPHEME_RULE_UNSUPPORTED_DIAGNOSTIC_CODE),
            unsupported.diagnostics.map { it.code },
        )
        assertEquals(emptyList(), unsupported.clusters)

        val invariantDiagnostics = validateGraphemeClusterInvariants(
            text = "ab",
            clusters = listOf(
                GraphemeCluster(
                    clusterIndex = 0,
                    utf16Range = 0..1,
                    codePointRange = 0..1,
                    clusterLevel = 0,
                    sourceTextHash = "fixture",
                    unicodeVersion = "16.0.0",
                    breakBeforeRuleId = "GB1",
                ),
                GraphemeCluster(
                    clusterIndex = 1,
                    utf16Range = 1..1,
                    codePointRange = 1..1,
                    clusterLevel = 0,
                    sourceTextHash = "fixture",
                    unicodeVersion = "16.0.0",
                    breakBeforeRuleId = "GB999",
                ),
            ),
        )
        assertEquals(
            listOf(TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE),
            invariantDiagnostics.map { it.code },
        )
    }

    @Test
    fun unicodeSegmentsDumpIsByteIdenticalToCheckedEvidence() {
        val clusterer = GraphemeClusterer(loadUnicodeDataSet())
        val dump = clusterer.dumpFixtures(
            listOf(
                "grapheme-latin-combining.txt" to readFixture("grapheme-latin-combining.txt"),
                "grapheme-emoji-zwj.txt" to readFixture("grapheme-emoji-zwj.txt"),
                "grapheme-regional-indicators.txt" to readFixture("grapheme-regional-indicators.txt"),
                "grapheme-devanagari-virama.txt" to readFixture("grapheme-devanagari-virama.txt"),
                "grapheme-variation-selector.txt" to readFixture("grapheme-variation-selector.txt"),
                "grapheme-crlf-control.txt" to readEscapedFixture("grapheme-crlf-control.txt"),
                "grapheme-prepend.txt" to readEscapedFixture("grapheme-prepend.txt"),
                "grapheme-isolated-surrogate.txt" to readEscapedFixture("grapheme-isolated-surrogate.txt"),
            ),
        )

        assertEquals(dump, clusterer.dumpFixtures(dump.inputs.map { it.fixtureName to it.sourceText }))
        assertEquals(readProjectFile("font/fixtures/expected/unicode/unicode-segments.json"), dump.toCanonicalJson())
    }

    private fun loadUnicodeDataSet() =
        PinnedUnicodeDataGenerator.generate(
            seedInputFileNames.map { fileName ->
                UcdInputFile(
                    fileName = fileName,
                    unicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
                    content = readProjectFile("font/fixtures/expected/unicode/source-extracts/16.0.0/$fileName"),
                )
            },
        )

    private fun readFixture(fileName: String): String =
        readProjectFile("font/fixtures/expected/unicode/$fileName").trimEnd('\n')

    private fun readEscapedFixture(fileName: String): String =
        readFixture(fileName).replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private companion object {
        val seedInputFileNames = listOf(
            "DerivedCoreProperties.txt",
            "GraphemeBreakProperty.txt",
            "LineBreak.txt",
            "PropList.txt",
            "ScriptExtensions.txt",
            "Scripts.txt",
            "UnicodeData.txt",
            "emoji/emoji-data.txt",
        )
    }
}
