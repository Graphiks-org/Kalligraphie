@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * Alphabet sizes and base/extra-bit tables of the Brotli format (RFC 7932 §4, §5 and §6).
 *
 * A symbol from an insert-and-copy prefix code is not itself a length; it indexes two parallel
 * alphabets, the insert length code and the copy length code, whose base value and extra-bit count
 * come from [INSERT_LENGTH_BASE]/[INSERT_LENGTH_EXTRA_BITS] and [COPY_LENGTH_BASE]/
 * [COPY_LENGTH_EXTRA_BITS]. Distance symbols 0..15 are not offsets but references into the small
 * ring buffer of the last four distances; [DISTANCE_SHORT_CODE_INDEX] and
 * [DISTANCE_SHORT_CODE_OFFSET] resolve them. Block-switch counts use [BLOCK_COUNT_BASE] and
 * [BLOCK_COUNT_EXTRA_BITS].
 */
@KalligraphieInternalApi
internal object BrotliAlphabet {
    /** Number of literal symbols (RFC 7932 §3.3). */
    const val LITERAL_ALPHABET_SIZE: Int = 256

    /** Number of insert-and-copy length symbols (RFC 7932 §3.3). */
    const val INSERT_COPY_ALPHABET_SIZE: Int = 704

    /** Number of block count symbols (RFC 7932 §3.3). */
    const val BLOCK_COUNT_ALPHABET_SIZE: Int = 26

    /** Base insert length for each insert length code (RFC 7932 §5). */
    val INSERT_LENGTH_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194, 322, 578, 1090, 2114,
        6210, 22594,
    )

    /** Extra bits read after each insert length code (RFC 7932 §5). */
    val INSERT_LENGTH_EXTRA_BITS = intArrayOf(
        0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24,
    )

    /** Base copy length for each copy length code (RFC 7932 §5). */
    val COPY_LENGTH_BASE = intArrayOf(
        2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134, 198, 326, 582, 1094,
        2118,
    )

    /** Extra bits read after each copy length code (RFC 7932 §5). */
    val COPY_LENGTH_EXTRA_BITS = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24,
    )

    /** Base block count for each block count code (RFC 7932 §6). */
    val BLOCK_COUNT_BASE = intArrayOf(
        1, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97, 113, 145, 177, 209, 241, 305, 369, 497, 753,
        1265, 2289, 4337, 8433, 16625,
    )

    /** Extra bits read after each block count code (RFC 7932 §6). */
    val BLOCK_COUNT_EXTRA_BITS = intArrayOf(
        2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24,
    )

    /**
     * Ring-buffer index each distance symbol 0..15 refers to, where 3 is the most recent distance
     * (RFC 7932 §4). Symbols 4..9 reuse the last distance, 10..15 the second-to-last.
     */
    val DISTANCE_SHORT_CODE_INDEX = intArrayOf(
        3, 2, 1, 0, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2,
    )

    /** Signed offset applied to the referenced distance for each symbol 0..15 (RFC 7932 §4). */
    val DISTANCE_SHORT_CODE_OFFSET = intArrayOf(
        0, 0, 0, 0, -1, 1, -2, 2, -3, 3, -1, 1, -2, 2, -3, 3,
    )

    /** The distance prefix-code alphabet size for the given [npostfix] and [ndirect] (RFC 7932 §4). */
    fun distanceAlphabetSize(npostfix: Int, ndirect: Int): Int =
        16 + ndirect + (48 shl npostfix)

    /**
     * Insert length code selected by an insert-and-copy length symbol (RFC 7932 §5).
     *
     * The 704 symbols are laid out in eleven 64-value cells; the insert code is one of eight values
     * within a cell (bits 3..5) and the copy code the matching value at the same position (bits 0..2).
     */
    fun insertLengthCode(insertCopyCode: Int): Int {
        require(insertCopyCode in 0 until INSERT_COPY_ALPHABET_SIZE) {
            "Brotli insert-and-copy code $insertCopyCode is out of range."
        }
        return INSERT_CODE_BASE_BY_CELL[insertCopyCode ushr 6] + ((insertCopyCode ushr 3) and 7)
    }

    /** Copy length code selected by an insert-and-copy length symbol (RFC 7932 §5). */
    fun copyLengthCode(insertCopyCode: Int): Int {
        require(insertCopyCode in 0 until INSERT_COPY_ALPHABET_SIZE) {
            "Brotli insert-and-copy code $insertCopyCode is out of range."
        }
        return COPY_CODE_BASE_BY_CELL[insertCopyCode ushr 6] + (insertCopyCode and 7)
    }

    /**
     * Whether an insert-and-copy symbol implies distance symbol 0 (RFC 7932 §5).
     *
     * Symbols 0..127 reuse the last distance and carry no distance code in the stream.
     */
    fun usesImplicitDistanceZero(insertCopyCode: Int): Boolean =
        insertCopyCode in 0..127

    /** Insert code base for each of the eleven 64-value insert-and-copy cells (RFC 7932 §5). */
    private val INSERT_CODE_BASE_BY_CELL = intArrayOf(0, 0, 0, 0, 8, 8, 0, 16, 8, 16, 16)

    /** Copy code base for each of the eleven 64-value insert-and-copy cells (RFC 7932 §5). */
    private val COPY_CODE_BASE_BY_CELL = intArrayOf(0, 8, 0, 8, 0, 8, 16, 0, 16, 8, 16)
}
