package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The portable segmenter's own contract, independent of the corpus: the shape of its result and the
 * handful of rules that are cheapest to state by hand.
 *
 * The boundary rules themselves are checked against the Unicode conformance corpus; these cases
 * exist so a change that broke the result's shape, or one obvious rule, fails on every target
 * without needing the corpus at all.
 */
class UnicodeGraphemeSegmenterTest {
    private fun boundaries(vararg scalars: Int): List<Int> =
        UnicodeGraphemeSegmenter.boundaries(scalars.toList()).toList()

    @Test
    fun anEmptyInput_has_no_boundaries() {
        assertContentEquals(IntArray(0), UnicodeGraphemeSegmenter.boundaries(emptyList()))
    }

    @Test
    fun aSingleScalar_is_oneCluster() {
        assertEquals(listOf(0, 1), boundaries(0x41))
    }

    @Test
    fun latinLetters_areOneClusterEach() {
        assertEquals(listOf(0, 1, 2, 3), boundaries(0x41, 0x42, 0x43))
    }

    @Test
    fun aCombiningMark_joinsTheClusterBeforeIt() {
        // GB9: × Extend
        assertEquals(listOf(0, 2), boundaries(0x41, 0x0300))
    }

    @Test
    fun carriageReturnLineFeed_isOneCluster() {
        // GB3: CR × LF
        assertEquals(listOf(0, 2), boundaries(0x0D, 0x0A))
    }

    @Test
    fun aControl_breaksOnBothSides() {
        // GB4 and GB5
        assertEquals(listOf(0, 1, 2, 3), boundaries(0x41, 0x0A, 0x42))
    }

    @Test
    fun aHangulSyllable_isAssembledFromItsJamo() {
        // GB6, GB7, GB8: L × V × T
        assertEquals(listOf(0, 3), boundaries(0x1100, 0x1160, 0x11A8))
    }

    @Test
    fun regionalIndicators_pairUp() {
        // GB12, GB13: an odd run pairs with the next one, so four flags are two clusters.
        val flags = listOf(0x1F1E6, 0x1F1E7, 0x1F1E8, 0x1F1E9)
        assertEquals(listOf(0, 2, 4), UnicodeGraphemeSegmenter.boundaries(flags).toList())
    }

    @Test
    fun aZeroWidthJoinerKeepsAnEmojiSequenceWhole() {
        // GB11: Extended_Pictographic Extend* ZWJ × Extended_Pictographic
        assertEquals(listOf(0, 3), boundaries(0x1F468, 0x200D, 0x1F469))
        // A whole family: two joiners chain, and those marks extend the run rather than break it.
        assertEquals(
            listOf(0, 5),
            boundaries(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F466),
        )
    }

    @Test
    fun aDevanagariConjunct_isOneCluster() {
        // GB9c: Consonant [Extend Linker]* Linker [Extend Linker]* × Consonant
        // KA, VIRAMA, SSA — the virama links the two consonants.
        assertEquals(listOf(0, 3), boundaries(0x0915, 0x094D, 0x0937))
    }

    @Test
    fun aViramaWithoutASecondConsonant_doesNotExtendPastTheMark() {
        // The same virama, followed by a Latin letter: the conjunct rule cannot apply.
        assertEquals(listOf(0, 2, 3), boundaries(0x0915, 0x094D, 0x41))
    }
}
