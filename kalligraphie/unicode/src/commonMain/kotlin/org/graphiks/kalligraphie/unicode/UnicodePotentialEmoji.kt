package org.graphiks.kalligraphie.unicode

/**
 * Unicode 16.0 `Extended_Pictographic and General_Category` data.
 *
 * Used by the portable UAX #14 line-break analyzer (LB30b admits an `EM` after a potential emoji base: a scalar that is both `Extended_Pictographic` and `Cn`).
 *
 * The table is derived from
 * `emoji-data.txt`, SHA-256
 * `f1365a5173eee18e1f98b240cdc492e84a25f1ce7e0c9d1094eb29c41a22696a`.
 * It contains only the scalars that are both, which is why the `@missing`
 * default needs no range. The pinned `General_Category` source supplies the
 * unassigned half of the intersection.
 */
internal object UnicodePotentialEmoji {
    /** Pinned Unicode Character Database version that supplied this table. */
    internal const val unicodeVersion: String = "16.0"

    /** Returns whether [scalar] is an `Extended_Pictographic` that is still `Cn`. */
    internal fun isPotentialEmojiBase(scalar: Int): Boolean {
        if (scalar !in 0..0x10FFFF) return false
        var low = 0
        var high = RANGE_BOUNDARIES.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = RANGE_BOUNDARIES[middle * 2]
            val end = RANGE_BOUNDARIES[middle * 2 + 1]
            when {
                scalar < start -> high = middle - 1
                scalar > end -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    private val RANGE_BOUNDARIES: IntArray = intArrayOf(
        0x1F02C, 0x1F02F, 0x1F094, 0x1F09F, 0x1F0AF, 0x1F0B0, 0x1F0C0, 0x1F0C0,
        0x1F0D0, 0x1F0D0, 0x1F0F6, 0x1F0FF, 0x1F1AE, 0x1F1E5, 0x1F203, 0x1F20F,
        0x1F23C, 0x1F23F, 0x1F249, 0x1F24F, 0x1F252, 0x1F25F, 0x1F266, 0x1F2FF,
        0x1F6D8, 0x1F6DB, 0x1F6ED, 0x1F6EF, 0x1F6FD, 0x1F6FF, 0x1F777, 0x1F77A,
        0x1F7DA, 0x1F7DF, 0x1F7EC, 0x1F7EF, 0x1F7F1, 0x1F7FF, 0x1F80C, 0x1F80F,
        0x1F848, 0x1F84F, 0x1F85A, 0x1F85F, 0x1F888, 0x1F88F, 0x1F8AE, 0x1F8AF,
        0x1F8BC, 0x1F8BF, 0x1F8C2, 0x1F8FF, 0x1FA54, 0x1FA5F, 0x1FA6E, 0x1FA6F,
        0x1FA7D, 0x1FA7F, 0x1FA8A, 0x1FA8E, 0x1FAC7, 0x1FACD, 0x1FADD, 0x1FADE,
        0x1FAEA, 0x1FAEF, 0x1FAF9, 0x1FAFF, 0x1FC00, 0x1FFFD,
    )
}
