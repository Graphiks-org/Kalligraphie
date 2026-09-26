package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.unicode.corpus.UnicodeTestEnvironment

/**
 * The Unicode 16 `LineBreakTest.txt` corpus, run against the portable engine.
 *
 * The corpus states every case as `÷` and `×` markers between scalars, with the rule that decided
 * each marker cited in its comment; it already includes the LB9 folding of combining marks, so it
 * pins the engine's raw decisions — `÷` is a break, `×` is not, and the mandatory breaks of LB4
 * and LB5 are breaks like any other. The authority for the rules is the corpus, not the
 * implementation: a disagreement is a defect in the engine.
 *
 * This test lives on the class-path side of the corpus seam because the file is three megabytes,
 * deliberately not embedded in the iOS test binary — the same decision the BiDi corpora document.
 * The engine it exercises is integer arithmetic over this module's own tables, whose behaviour on
 * that target the grapheme corpus and the table spot checks establish.
 */
class UcdLineBreakConformanceTest {
    @Test
    fun every_unicode_16_line_break_corpus_case_matches_the_portable_engine() {
        var executed = 0
        lineBreakTestCases().forEach { case ->
            val decisions = UnicodeLineBreakEngine.decisions(
                case.scalars,
                UnicodeAnalysisProfile.unbounded,
                CancellationToken.none,
            )
            assertEquals(
                case.scalars.size + 1,
                decisions.size,
                "Unicode 16.0 LineBreakTest line ${case.lineNumber}: decision count differs",
            )
            for (boundary in case.breaks.indices) {
                assertEquals(
                    case.breaks[boundary],
                    decisions[boundary] != UnicodeLineBreakEngine.Decision.NO_BREAK,
                    "Unicode 16.0 LineBreakTest line ${case.lineNumber}, boundary $boundary: ${case.source}",
                )
            }
            executed += 1
        }
        assertTrue(executed > 10_000, "LineBreakTest looks truncated: $executed cases")
    }

    private fun lineBreakTestCases(): Sequence<LineBreakCase> = sequence {
        val corpus = UnicodeTestEnvironment.corpus.text("/unicode/16.0.0/LineBreakTest.txt")
        corpus.lineSequence().forEachIndexed { index, rawLine ->
            val source = rawLine.substringBefore('#').trim()
            if (source.isEmpty()) return@forEachIndexed
            val tokens = source.split(WHITESPACE)
            val scalars = mutableListOf<Int>()
            val breaks = mutableListOf<Boolean>()
            var expectMarker = true
            tokens.forEach { token ->
                when {
                    token == BREAK_MARKER && expectMarker -> {
                        breaks += true
                        expectMarker = false
                    }
                    token == NO_BREAK_MARKER && expectMarker -> {
                        breaks += false
                        expectMarker = false
                    }
                    else -> {
                        scalars += token.toInt(radix = 16)
                        expectMarker = true
                    }
                }
            }
            check(!expectMarker && breaks.size == scalars.size + 1) {
                "Malformed LineBreakTest line ${index + 1}: $source"
            }
            yield(LineBreakCase(index + 1, source, scalars, breaks))
        }
    }

    private data class LineBreakCase(
        val lineNumber: Int,
        val source: String,
        val scalars: List<Int>,
        /** One entry per boundary: `true` where the corpus marks a break, `false` where it does not. */
        val breaks: List<Boolean>,
    )

    private companion object {
        val WHITESPACE: Regex = Regex("\\s+")
        const val BREAK_MARKER: String = "÷"
        const val NO_BREAK_MARKER: String = "×"
    }
}
