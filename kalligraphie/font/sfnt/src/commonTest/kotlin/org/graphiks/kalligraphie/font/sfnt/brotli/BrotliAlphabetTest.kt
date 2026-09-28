@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrotliAlphabetTest {
    @Test
    fun alphabetSizesMatchTheRfc() {
        assertEquals(256, BrotliAlphabet.LITERAL_ALPHABET_SIZE)
        assertEquals(704, BrotliAlphabet.INSERT_COPY_ALPHABET_SIZE)
        assertEquals(26, BrotliAlphabet.BLOCK_COUNT_ALPHABET_SIZE)
    }

    @Test
    fun lengthAndCountTablesCoverTheirAlphabets() {
        assertEquals(24, BrotliAlphabet.INSERT_LENGTH_BASE.size)
        assertEquals(24, BrotliAlphabet.INSERT_LENGTH_EXTRA_BITS.size)
        assertEquals(24, BrotliAlphabet.COPY_LENGTH_BASE.size)
        assertEquals(24, BrotliAlphabet.COPY_LENGTH_EXTRA_BITS.size)
        assertEquals(26, BrotliAlphabet.BLOCK_COUNT_BASE.size)
        assertEquals(26, BrotliAlphabet.BLOCK_COUNT_EXTRA_BITS.size)
        assertEquals(16, BrotliAlphabet.DISTANCE_SHORT_CODE_INDEX.size)
        assertEquals(16, BrotliAlphabet.DISTANCE_SHORT_CODE_OFFSET.size)
    }

    @Test
    fun lengthTablesUseTheRfcBaseAndExtraBits() {
        assertEquals(2, BrotliAlphabet.COPY_LENGTH_BASE[0])
        assertEquals(0, BrotliAlphabet.COPY_LENGTH_EXTRA_BITS[0])
        assertEquals(2118, BrotliAlphabet.COPY_LENGTH_BASE[23])
        assertEquals(24, BrotliAlphabet.COPY_LENGTH_EXTRA_BITS[23])
        assertEquals(22594, BrotliAlphabet.INSERT_LENGTH_BASE[23])
        assertEquals(24, BrotliAlphabet.INSERT_LENGTH_EXTRA_BITS[23])
        assertEquals(1, BrotliAlphabet.BLOCK_COUNT_BASE[0])
        assertEquals(2, BrotliAlphabet.BLOCK_COUNT_EXTRA_BITS[0])
        assertEquals(16625, BrotliAlphabet.BLOCK_COUNT_BASE[25])
        assertEquals(24, BrotliAlphabet.BLOCK_COUNT_EXTRA_BITS[25])
    }

    @Test
    fun insertAndCopyCodesAreSplitAcrossTheElevenCells() {
        assertInsertCopy(0, insert = 0, copy = 0)
        assertInsertCopy(63, insert = 7, copy = 7)
        assertInsertCopy(64, insert = 0, copy = 8)
        assertInsertCopy(128, insert = 0, copy = 0)
        assertInsertCopy(384, insert = 0, copy = 16)
        assertInsertCopy(448, insert = 16, copy = 0)
        assertInsertCopy(512, insert = 8, copy = 16)
        assertInsertCopy(576, insert = 16, copy = 8)
        assertInsertCopy(640, insert = 16, copy = 16)
        assertInsertCopy(703, insert = 23, copy = 23)
    }

    @Test
    fun onlyTheFirstTwoCellsImplyDistanceZero() {
        assertTrue(BrotliAlphabet.usesImplicitDistanceZero(0))
        assertTrue(BrotliAlphabet.usesImplicitDistanceZero(127))
        assertFalse(BrotliAlphabet.usesImplicitDistanceZero(128))
        assertFalse(BrotliAlphabet.usesImplicitDistanceZero(703))
    }

    @Test
    fun distanceAlphabetGrowsWithPostfixBitsAndDirectCodes() {
        assertEquals(64, BrotliAlphabet.distanceAlphabetSize(npostfix = 0, ndirect = 0))
        assertEquals(184, BrotliAlphabet.distanceAlphabetSize(npostfix = 0, ndirect = 120))
        assertEquals(112, BrotliAlphabet.distanceAlphabetSize(npostfix = 1, ndirect = 0))
        assertEquals(520, BrotliAlphabet.distanceAlphabetSize(npostfix = 3, ndirect = 120))
    }

    private fun assertInsertCopy(code: Int, insert: Int, copy: Int) {
        assertEquals(insert, BrotliAlphabet.insertLengthCode(code), "insert code of $code")
        assertEquals(copy, BrotliAlphabet.copyLengthCode(code), "copy code of $code")
    }
}
