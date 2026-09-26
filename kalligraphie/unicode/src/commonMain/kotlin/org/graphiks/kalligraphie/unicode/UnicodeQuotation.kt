package org.graphiks.kalligraphie.unicode

/**
 * Unicode 16.0 `General_Category` data.
 *
 * Used by the portable UAX #14 line-break analyzer (LB15a and LB15b resolve the quotation punctuation among the `QU` class by its General_Category).
 *
 * The table is derived from
 * `DerivedGeneralCategory.txt`, SHA-256
 * `7676ab755a41ef82108460238569e60ad65c191ddafe61b36c6765ec1353f293`.
 * It contains only the scalars the source assigns `Pi` or `Pf`; every other
 * scalar is not one, which is why the `@missing` default needs no range.
 */
internal object UnicodeQuotation {
    /** Pinned Unicode Character Database version that supplied this table. */
    internal const val unicodeVersion: String = "16.0"

    /** Returns whether [scalar] has General_Category `Pi`, an initial quotation mark. */
    internal fun isInitialQuotation(scalar: Int): Boolean = contains(INITIAL_BOUNDARIES, scalar)

    /** Returns whether [scalar] has General_Category `Pf`, a final quotation mark. */
    internal fun isFinalQuotation(scalar: Int): Boolean = contains(FINAL_BOUNDARIES, scalar)

    private fun contains(boundaries: IntArray, scalar: Int): Boolean {
        if (scalar !in 0..0x10FFFF) return false
        var low = 0
        var high = boundaries.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = boundaries[middle * 2]
            val end = boundaries[middle * 2 + 1]
            when {
                scalar < start -> high = middle - 1
                scalar > end -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    private val INITIAL_BOUNDARIES: IntArray = intArrayOf(
        0xAB, 0xAB, 0x2018, 0x2018, 0x201B, 0x201C, 0x201F, 0x201F,
        0x2039, 0x2039, 0x2E02, 0x2E02, 0x2E04, 0x2E04, 0x2E09, 0x2E09,
        0x2E0C, 0x2E0C, 0x2E1C, 0x2E1C, 0x2E20, 0x2E20,
    )
    private val FINAL_BOUNDARIES: IntArray = intArrayOf(
        0xBB, 0xBB, 0x2019, 0x2019, 0x201D, 0x201D, 0x203A, 0x203A,
        0x2E03, 0x2E03, 0x2E05, 0x2E05, 0x2E0A, 0x2E0A, 0x2E0D, 0x2E0D,
        0x2E1D, 0x2E1D, 0x2E21, 0x2E21,
    )
}
