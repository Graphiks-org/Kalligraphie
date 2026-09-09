package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

class UnicodeGraphemeBreakConformanceTest {
    @Test
    fun unicode_16_grapheme_break_corpus_matches_the_public_analysis_contract() {
        val cases = unicode16GraphemeBreakCases().toList()
        cases.forEach { case ->
            val snapshot = snapshotOf(case.text)
            val actual = analyzer.analyze(
                snapshot,
                UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
            ).graphemeClusters
            val expected = case.boundaries.zipWithNext { start, endExclusive ->
                TextRange(
                    snapshot.textIndexAtScalarBoundary(start),
                    snapshot.textIndexAtScalarBoundary(endExclusive),
                )
            }

            assertEquals(expected, actual, "Unicode 16 GraphemeBreakTest line ${case.lineNumber}: ${case.source}")
        }
        println("Unicode 16.0 GraphemeBreakTest cases executed: ${cases.size}")
    }

    private fun unicode16GraphemeBreakCases(): Sequence<GraphemeBreakCase> = sequence {
        val corpus = checkNotNull(javaClass.getResourceAsStream("/unicode/16.0.0/GraphemeBreakTest.txt")) {
            "The checked-in Unicode 16 GraphemeBreakTest corpus is missing."
        }
        corpus.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val source = rawLine.substringBefore('#').trim()
                if (source.isNotEmpty()) yield(parseCase(index + 1, source))
            }
        }
    }

    private fun parseCase(lineNumber: Int, source: String): GraphemeBreakCase {
        val text = StringBuilder()
        val boundaries = mutableListOf<Int>()
        var scalarCount = 0
        source.split(WHITESPACE).forEach { token ->
            when (token) {
                BREAK -> boundaries += scalarCount
                NO_BREAK -> Unit
                else -> {
                    text.appendCodePoint(token.toInt(radix = 16))
                    scalarCount += 1
                }
            }
        }
        return GraphemeBreakCase(lineNumber, source, text.toString(), boundaries)
    }

    private fun snapshotOf(text: String): TextSnapshot =
        TextSnapshots.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot

    private data class GraphemeBreakCase(
        val lineNumber: Int,
        val source: String,
        val text: String,
        val boundaries: List<Int>,
    )

    private companion object {
        val analyzer: UnicodeAnalyzer = JvmUnicodeAnalyzer.create()
        val WHITESPACE: Regex = Regex("\\s+")
        const val BREAK: String = "÷"
        const val NO_BREAK: String = "×"
    }
}
