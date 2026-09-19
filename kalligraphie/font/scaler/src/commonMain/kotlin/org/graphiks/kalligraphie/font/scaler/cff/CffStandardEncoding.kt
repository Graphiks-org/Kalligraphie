@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

/**
 * Adobe StandardEncoding code-to-SID table.
 *
 * The deprecated CFF `seac` form (an `endchar` with four operands) names the
 * base and accent glyphs by StandardEncoding character code, not by glyph index.
 * This table maps those codes to Standard String IDs, which a charset then maps
 * to glyph indices. Codes without a standard glyph map to SID 0 (`.notdef`).
 */
internal object CffStandardEncoding {
    private val codeToSid: IntArray = IntArray(256).also { map ->
        val pairs = intArrayOf(
            32, 1, 33, 2, 34, 3, 35, 4, 36, 5, 37, 6, 38, 7, 39, 8, 40, 9, 41, 10,
            42, 11, 43, 12, 44, 13, 45, 14, 46, 15, 47, 16,
            48, 17, 49, 18, 50, 19, 51, 20, 52, 21, 53, 22, 54, 23, 55, 24, 56, 25, 57, 26,
            58, 27, 59, 28, 60, 29, 61, 30, 62, 31, 63, 32, 64, 33,
            65, 34, 66, 35, 67, 36, 68, 37, 69, 38, 70, 39, 71, 40, 72, 41, 73, 42, 74, 43, 75, 44,
            76, 45, 77, 46, 78, 47, 79, 48, 80, 49, 81, 50, 82, 51, 83, 52, 84, 53, 85, 54, 86, 55,
            87, 56, 88, 57, 89, 58, 90, 59, 91, 60, 92, 61, 93, 62, 94, 63, 95, 64, 96, 65,
            97, 66, 98, 67, 99, 68, 100, 69, 101, 70, 102, 71, 103, 72, 104, 73, 105, 74, 106, 75,
            107, 76, 108, 77, 109, 78, 110, 79, 111, 80, 112, 81, 113, 82, 114, 83, 115, 84, 116, 85,
            117, 86, 118, 87, 119, 88, 120, 89, 121, 90, 122, 91, 123, 92, 124, 93, 125, 94, 126, 95,
            161, 96, 162, 97, 163, 98, 164, 99, 165, 100, 166, 101, 167, 102, 168, 103, 169, 104,
            170, 105, 171, 106, 172, 107, 173, 108, 174, 109, 175, 110, 177, 111, 178, 112, 179, 113,
            180, 114, 182, 115, 183, 116, 184, 117, 185, 118, 186, 119, 187, 120, 188, 121, 189, 122,
            191, 123, 193, 124, 194, 125, 195, 126, 196, 127, 197, 128, 198, 129, 199, 130, 200, 131,
            202, 132, 203, 133, 205, 134, 206, 135, 207, 136, 208, 137, 225, 138, 227, 139, 232, 140,
            233, 141, 234, 142, 235, 143, 241, 144, 245, 145, 248, 146, 249, 147, 250, 148, 251, 149,
        )
        var index = 0
        while (index < pairs.size) {
            map[pairs[index]] = pairs[index + 1]
            index += 2
        }
    }

    /** Standard String ID for an 8-bit character [code], or `0` when unassigned. */
    fun sidForCode(code: Int): Int = if (code in 0..255) codeToSid[code] else 0
}
