package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Unicode 16 `GraphemeBreakTest.txt` corpus, run against the portable segmenter.
 *
 * This is the authority for the segmentation rules: the corpus states, for every case it carries,
 * exactly where a cluster boundary is allowed to fall, and the segmenter must agree on every one of
 * them. The corpus is the one already committed beside this module's conformance data — the ICU
 * analyzer is checked against it too, which is why a disagreement here is a defect in the portable
 * rules rather than a question about the expectations.
 */
class PortableGraphemeBreakConformanceTest {
    @Test
    fun unicode_16_grapheme_break_corpus_matches_the_portable_segmenter() {
        val cases = graphemeBreakCases().toList()
        assertTrue(cases.size > 1_000, "the corpus looks truncated: ${cases.size} cases")
        cases.forEach { case ->
            assertEquals(
                case.boundaries,
                UnicodeGraphemeSegmenter.boundaries(case.scalars).toList(),
                "Unicode 16 GraphemeBreakTest line ${case.lineNumber}: ${case.source}",
            )
        }
    }

    private fun graphemeBreakCases(): Sequence<Case> = sequence {
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

    private fun parseCase(lineNumber: Int, source: String): Case {
        val scalars = ArrayList<Int>()
        val boundaries = ArrayList<Int>()
        source.split(WHITESPACE).forEach { token ->
            when (token) {
                BREAK -> boundaries.add(scalars.size)
                NO_BREAK -> Unit
                else -> scalars.add(token.toInt(radix = 16))
            }
        }
        return Case(lineNumber, source, scalars, boundaries)
    }

    private data class Case(
        val lineNumber: Int,
        val source: String,
        val scalars: List<Int>,
        val boundaries: List<Int>,
    )

    private companion object {
        val WHITESPACE: Regex = Regex("\\s+")
        const val BREAK: String = "÷"
        const val NO_BREAK: String = "×"
    }
}
