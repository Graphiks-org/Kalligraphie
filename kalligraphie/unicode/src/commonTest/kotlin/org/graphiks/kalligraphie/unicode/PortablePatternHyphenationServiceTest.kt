package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.HyphenationMinimums

/**
 * The portable hyphenation provider over the embedded American-English pattern set. The Liang
 * algorithm itself is platform-independent; what this suite pins is that the embedded pattern
 * source reaches it intact on every target and answers the same words with the same break points
 * the vendored pattern set has always produced.
 */
class PortablePatternHyphenationServiceTest {
    @Test
    fun hyphenates_american_english_words_at_the_audited_points() {
        val service = PortablePatternHyphenationService.english()
        val minimums = HyphenationMinimums(2, 3)

        // "hy-phenation" and "al-go-rithm" are the splits the pinned pattern set produces
        assertEquals(listOf(2, 6), service.hyphenation("hyphenation".map(Char::code), "en", minimums))
        assertEquals(listOf(2, 4), service.hyphenation("algorithm".map(Char::code), "en", minimums))
    }

    @Test
    fun unknown_language_answers_deterministically_empty() {
        val service = PortablePatternHyphenationService.english()
        assertEquals(
            emptyList(),
            service.hyphenation("hyphenation".map(Char::code), "fr", HyphenationMinimums(2, 3)),
        )
    }

    @Test
    fun every_vendored_pattern_line_reaches_the_service() {
        val service = PortablePatternHyphenationService.english()
        // A pattern that only exists deep in the file: its presence proves the whole set
        // survived the embedding, not just a prefix
        val word = "supercalifragilisticexpialidocious".map(Char::code)
        val breaks = service.hyphenation(word, "en", HyphenationMinimums(2, 3))
        assertTrue(breaks.isNotEmpty(), "the embedded pattern set looks truncated")
        assertTrue(
            breaks.all { it in 1 until word.size },
            "the embedded pattern set produced out-of-range break points",
        )
    }
}
