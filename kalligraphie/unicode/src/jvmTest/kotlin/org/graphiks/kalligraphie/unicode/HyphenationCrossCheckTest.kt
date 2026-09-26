package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.api.HyphenationMinimums

/**
 * The JVM cross-check between the two hyphenation providers: the JVM provider reading its
 * resource at runtime and the portable provider reading the generated constant. Both build the
 * same Liang service over the same pinned pattern set, so the same words must produce the same
 * break points. A disagreement means the embedded source drifted from the vendored resource.
 */
class HyphenationCrossCheckTest {
    @Test
    fun both_providers_hyphenate_the_same_words_identically() {
        val jvm = JvmPatternHyphenationService.english()
        val portable = PortablePatternHyphenationService.english()
        val minimums = HyphenationMinimums(2, 3)

        val words = listOf(
            "hyphenation", "algorithm", "supercalifragilisticexpialidocious", "internationalization",
            "line", "breaking", "typography", "extraordinary", "calendar", "wonderful",
            "repository", "celebration", "documentation", "understanding", "kalligraphie",
            "expression", "resolution", "parameter", "measurable", "accountability",
        )
        words.forEach { word ->
            assertEquals(
                jvm.hyphenation(word.map(Char::code), "en", minimums),
                portable.hyphenation(word.map(Char::code), "en", minimums),
                "hyphenation of '$word' differs between the providers.",
            )
        }
    }
}
