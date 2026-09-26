package org.graphiks.kalligraphie.unicode

/**
 * Unicode 16.0 `Script_Extensions` data.
 *
 * Used by the portable Unicode analyzer's script resolution.
 *
 * The table is derived from
 * `ScriptExtensions.txt`, SHA-256
 * `049117ce26b9769fe2749b06eef51a50a89faef4a97764dd2d81daa715980700`.
 * It contains only the scalars the source lists, and repeats each distinct set of codes
 * once: a scalar the source does not list has no extensions and falls back to its
 * `Script`, which is what an empty result from [extensionsOf] means.
 */
internal object UnicodeScriptExtensions {
    /** Pinned Unicode Character Database version that supplied this table. */
    internal const val unicodeVersion: String = "16.0"

    /** Returns the ISO 15924 codes of [scalar]'s `Script_Extensions`, empty when it declares none. */
    internal fun extensionsOf(scalar: Int): List<String> {
        val index = setIndexOf(scalar)
        if (index < 0) return emptyList()
        val from = SET_BOUNDARIES[index]
        val until = SET_BOUNDARIES[index + 1]
        return (from until until).map { position -> SET_CODES[position] }
    }

    /** Returns whether [scalar] declares any `Script_Extensions`, without building the list. */
    internal fun hasExtensions(scalar: Int): Boolean = setIndexOf(scalar) >= 0

    private fun setIndexOf(scalar: Int): Int {
        if (scalar !in 0..0x10FFFF) return -1
        var low = 0
        var high = RANGE_STARTS.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                scalar < RANGE_STARTS[middle] -> high = middle - 1
                middle + 1 < RANGE_STARTS.size && scalar >= RANGE_STARTS[middle + 1] -> low = middle + 1
                scalar > RANGE_ENDS[middle] -> return -1
                else -> return RANGE_SETS[middle].toInt()
            }
        }
        return -1
    }

    private val RANGE_STARTS: IntArray = intArrayOf(
        0xB7, 0x2BC, 0x2C7, 0x2C9, 0x2CD, 0x2D7, 0x2D9, 0x300, 0x301, 0x302,
        0x303, 0x304, 0x305, 0x306, 0x307, 0x308, 0x309, 0x30A, 0x30B, 0x30C,
        0x30D, 0x30E, 0x310, 0x311, 0x313, 0x320, 0x323, 0x324, 0x325, 0x32D,
        0x32E, 0x330, 0x331, 0x342, 0x345, 0x358, 0x35E, 0x363, 0x374, 0x375,
        0x483, 0x484, 0x485, 0x487, 0x589, 0x60C, 0x61B, 0x61C, 0x61F, 0x640,
        0x64B, 0x660, 0x670, 0x6D4, 0x951, 0x952, 0x964, 0x965, 0x966, 0x9E6,
        0xA66, 0xAE6, 0xBE6, 0xBF0, 0xBF3, 0xCE6, 0x1040, 0x10FB, 0x16EB,
        0x1735, 0x1802, 0x1805, 0x1CD0, 0x1CD1, 0x1CD2, 0x1CD3, 0x1CD4, 0x1CD5,
        0x1CD7, 0x1CD8, 0x1CD9, 0x1CDA, 0x1CDB, 0x1CDC, 0x1CDE, 0x1CE0, 0x1CE1,
        0x1CE2, 0x1CE9, 0x1CEA, 0x1CEB, 0x1CED, 0x1CEE, 0x1CF2, 0x1CF3, 0x1CF4,
        0x1CF5, 0x1CF7, 0x1CF8, 0x1CFA, 0x1DC0, 0x1DF8, 0x1DFA, 0x202F, 0x204F,
        0x205A, 0x205D, 0x20F0, 0x2E17, 0x2E30, 0x2E31, 0x2E3C, 0x2E41, 0x2E43,
        0x2FF0, 0x3001, 0x3002, 0x3003, 0x3006, 0x3008, 0x3009, 0x300A, 0x300B,
        0x300C, 0x300D, 0x300E, 0x300F, 0x3010, 0x3011, 0x3013, 0x3014, 0x3015,
        0x3016, 0x3017, 0x3018, 0x3019, 0x301A, 0x301B, 0x301C, 0x301D, 0x301E,
        0x302A, 0x3030, 0x3031, 0x3037, 0x303C, 0x303D, 0x303E, 0x3099, 0x309B,
        0x30A0, 0x30FB, 0x30FC, 0x3190, 0x3192, 0x3196, 0x31C0, 0x31EF, 0x3220,
        0x322A, 0x3280, 0x328A, 0x32C0, 0x32FF, 0x3358, 0x337B, 0x33E0, 0xA66F,
        0xA700, 0xA830, 0xA833, 0xA836, 0xA838, 0xA839, 0xA8F1, 0xA8F3, 0xA92E,
        0xA9CF, 0xFD3E, 0xFD3F, 0xFDF2, 0xFDFD, 0xFE45, 0xFF61, 0xFF62, 0xFF63,
        0xFF64, 0xFF70, 0xFF9E, 0x10100, 0x10102, 0x10107, 0x10137, 0x102E0,
        0x102E1, 0x10AF2, 0x11301, 0x11303, 0x1133B, 0x11FD0, 0x11FD3, 0x1BCA0,
        0x1D360, 0x1F250,
    )
    private val RANGE_ENDS: IntArray = intArrayOf(
        0xB7, 0x2BC, 0x2C7, 0x2CB, 0x2CD, 0x2D7, 0x2D9, 0x300, 0x301, 0x302,
        0x303, 0x304, 0x305, 0x306, 0x307, 0x308, 0x309, 0x30A, 0x30B, 0x30C,
        0x30D, 0x30E, 0x310, 0x311, 0x313, 0x320, 0x323, 0x324, 0x325, 0x32D,
        0x32E, 0x330, 0x331, 0x342, 0x345, 0x358, 0x35E, 0x36F, 0x374, 0x375,
        0x483, 0x484, 0x486, 0x487, 0x589, 0x60C, 0x61B, 0x61C, 0x61F, 0x640,
        0x655, 0x669, 0x670, 0x6D4, 0x951, 0x952, 0x964, 0x965, 0x96F, 0x9EF,
        0xA6F, 0xAEF, 0xBEF, 0xBF2, 0xBF3, 0xCEF, 0x1049, 0x10FB, 0x16ED,
        0x1736, 0x1803, 0x1805, 0x1CD0, 0x1CD1, 0x1CD2, 0x1CD3, 0x1CD4, 0x1CD6,
        0x1CD7, 0x1CD8, 0x1CD9, 0x1CDA, 0x1CDB, 0x1CDD, 0x1CDF, 0x1CE0, 0x1CE1,
        0x1CE8, 0x1CE9, 0x1CEA, 0x1CEC, 0x1CED, 0x1CF1, 0x1CF2, 0x1CF3, 0x1CF4,
        0x1CF6, 0x1CF7, 0x1CF9, 0x1CFA, 0x1DC1, 0x1DF8, 0x1DFA, 0x202F, 0x204F,
        0x205A, 0x205D, 0x20F0, 0x2E17, 0x2E30, 0x2E31, 0x2E3C, 0x2E41, 0x2E43,
        0x2FFF, 0x3001, 0x3002, 0x3003, 0x3006, 0x3008, 0x3009, 0x300A, 0x300B,
        0x300C, 0x300D, 0x300E, 0x300F, 0x3010, 0x3011, 0x3013, 0x3014, 0x3015,
        0x3016, 0x3017, 0x3018, 0x3019, 0x301A, 0x301B, 0x301C, 0x301D, 0x301F,
        0x302D, 0x3030, 0x3035, 0x3037, 0x303C, 0x303D, 0x303F, 0x309A, 0x309C,
        0x30A0, 0x30FB, 0x30FC, 0x3191, 0x3195, 0x319F, 0x31E5, 0x31EF, 0x3229,
        0x3247, 0x3289, 0x32B0, 0x32CB, 0x32FF, 0x3370, 0x337F, 0x33FE, 0xA66F,
        0xA707, 0xA832, 0xA835, 0xA837, 0xA838, 0xA839, 0xA8F1, 0xA8F3, 0xA92E,
        0xA9CF, 0xFD3E, 0xFD3F, 0xFDF2, 0xFDFD, 0xFE46, 0xFF61, 0xFF62, 0xFF63,
        0xFF65, 0xFF70, 0xFF9F, 0x10101, 0x10102, 0x10133, 0x1013F, 0x102E0,
        0x102FB, 0x10AF2, 0x11301, 0x11303, 0x1133C, 0x11FD1, 0x11FD3, 0x1BCA3,
        0x1D371, 0x1F251,
    )
    private val RANGE_SETS: ShortArray = shortArrayOf(
        0, 1, 2, 2, 3, 4, 2, 5, 6, 7,
        8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
        18, 19, 18, 20, 21, 22, 23, 24, 22, 25,
        22, 26, 27, 28, 28, 29, 30, 31, 32, 32,
        33, 34, 35, 34, 36, 37, 37, 38, 39, 40,
        41, 42, 41, 43, 44, 45, 46, 47, 48, 49,
        50, 51, 52, 52, 52, 53, 54, 55, 56, 57,
        58, 58, 59, 60, 59, 61, 60, 62, 63, 62,
        63, 64, 60, 63, 60, 63, 62, 60, 65, 62,
        60, 62, 60, 66, 67, 68, 62, 69, 67, 70,
        28, 71, 72, 73, 74, 75, 76, 77, 78, 79,
        80, 81, 82, 34, 83, 84, 85, 86, 87, 88,
        88, 89, 89, 90, 90, 90, 90, 90, 90, 86,
        90, 90, 90, 90, 90, 90, 90, 90, 86, 86,
        86, 91, 86, 92, 86, 93, 93, 87, 92, 92,
        92, 90, 92, 87, 87, 87, 87, 83, 87, 87,
        87, 87, 87, 87, 87, 87, 87, 34, 94, 95,
        96, 97, 98, 97, 99, 100, 101, 102, 103, 103,
        104, 104, 86, 90, 90, 90, 90, 92, 92, 105,
        106, 107, 106, 108, 108, 109, 52, 52, 52, 52,
        52, 81, 87, 87,
    )
    private val SET_BOUNDARIES: IntArray = intArrayOf(
        0x0, 0x10, 0x17, 0x19, 0x1B, 0x1D, 0x25, 0x2D, 0x31, 0x36,
        0x41, 0x47, 0x4B, 0x54, 0x5E, 0x60, 0x63, 0x67, 0x6A, 0x6C,
        0x6E, 0x71, 0x75, 0x77, 0x7C, 0x80, 0x83, 0x86, 0x8C, 0x8D,
        0x8F, 0x92, 0x93, 0x95, 0x97, 0x99, 0x9B, 0x9E, 0xA5, 0xA8,
        0xB0, 0xB9, 0xBB, 0xBE, 0xC0, 0xCD, 0xD9, 0xEE, 0x105, 0x109,
        0x10C, 0x10E, 0x110, 0x112, 0x115, 0x118, 0x11B, 0x11C, 0x120, 0x122,
        0x126, 0x127, 0x12A, 0x12C, 0x12E, 0x134, 0x136, 0x141, 0x143, 0x147,
        0x148, 0x149, 0x14C, 0x14D, 0x150, 0x152, 0x158, 0x15C, 0x15F, 0x161,
        0x163, 0x16A, 0x16B, 0x16E, 0x170, 0x177, 0x17F, 0x184, 0x185, 0x18D,
        0x196, 0x19C, 0x19E, 0x1A0, 0x1A3, 0x1A5, 0x1B5, 0x1C4, 0x1CF, 0x1DB,
        0x1DE, 0x1E0, 0x1E3, 0x1E5, 0x1E7, 0x1E9, 0x1EC, 0x1EE, 0x1F1, 0x1F3,
        0x1F5,
    )
    private val SET_CODES: Array<String> = arrayOf(
        "Avst", "Cari", "Copt", "Dupl", "Elba", "Geor", "Glag", "Gong", "Goth",
        "Grek", "Hani", "Latn", "Lydi", "Mahj", "Perm", "Shaw", "Beng", "Cyrl",
        "Deva", "Latn", "Lisu", "Thai", "Toto", "Bopo", "Latn", "Latn", "Lisu",
        "Latn", "Thai", "Cher", "Copt", "Cyrl", "Grek", "Latn", "Perm", "Sunu",
        "Tale", "Cher", "Cyrl", "Grek", "Latn", "Osge", "Sunu", "Tale", "Todr",
        "Cher", "Cyrl", "Latn", "Tfng", "Glag", "Latn", "Sunu", "Syrc", "Thai",
        "Aghb", "Cher", "Copt", "Cyrl", "Goth", "Grek", "Latn", "Osge", "Syrc",
        "Tfng", "Todr", "Copt", "Elba", "Glag", "Goth", "Kana", "Latn", "Cyrl",
        "Grek", "Latn", "Perm", "Copt", "Dupl", "Hebr", "Latn", "Perm", "Syrc",
        "Tale", "Tfng", "Todr", "Armn", "Cyrl", "Dupl", "Goth", "Grek", "Hebr",
        "Latn", "Perm", "Syrc", "Tale", "Latn", "Tfng", "Dupl", "Latn", "Syrc",
        "Cher", "Cyrl", "Latn", "Osge", "Cher", "Latn", "Tale", "Latn", "Sunu",
        "Ethi", "Latn", "Cyrl", "Latn", "Todr", "Grek", "Latn", "Perm", "Todr",
        "Latn", "Syrc", "Cher", "Dupl", "Kana", "Latn", "Syrc", "Cher", "Dupl",
        "Latn", "Syrc", "Latn", "Sunu", "Syrc", "Cher", "Latn", "Syrc", "Aghb",
        "Cher", "Goth", "Latn", "Sunu", "Thai", "Grek", "Latn", "Osge", "Aghb",
        "Latn", "Todr", "Latn", "Copt", "Grek", "Cyrl", "Perm", "Cyrl", "Glag",
        "Cyrl", "Latn", "Armn", "Geor", "Glag", "Arab", "Gara", "Nkoo", "Rohg",
        "Syrc", "Thaa", "Yezi", "Arab", "Syrc", "Thaa", "Adlm", "Arab", "Gara",
        "Nkoo", "Rohg", "Syrc", "Thaa", "Yezi", "Adlm", "Arab", "Mand", "Mani",
        "Ougr", "Phlp", "Rohg", "Sogd", "Syrc", "Arab", "Syrc", "Arab", "Thaa",
        "Yezi", "Arab", "Rohg", "Beng", "Deva", "Gran", "Gujr", "Guru", "Knda",
        "Latn", "Mlym", "Orya", "Shrd", "Taml", "Telu", "Tirh", "Beng", "Deva",
        "Gran", "Gujr", "Guru", "Knda", "Latn", "Mlym", "Orya", "Taml", "Telu",
        "Tirh", "Beng", "Deva", "Dogr", "Gong", "Gonm", "Gran", "Gujr", "Guru",
        "Knda", "Mahj", "Mlym", "Nand", "Onao", "Orya", "Sind", "Sinh", "Sylo",
        "Takr", "Taml", "Telu", "Tirh", "Beng", "Deva", "Dogr", "Gong", "Gonm",
        "Gran", "Gujr", "Gukh", "Guru", "Knda", "Limb", "Mahj", "Mlym", "Nand",
        "Onao", "Orya", "Sind", "Sinh", "Sylo", "Takr", "Taml", "Telu", "Tirh",
        "Deva", "Dogr", "Kthi", "Mahj", "Beng", "Cakm", "Sylo", "Guru", "Mult",
        "Gujr", "Khoj", "Gran", "Taml", "Knda", "Nand", "Tutg", "Cakm", "Mymr",
        "Tale", "Geor", "Glag", "Latn", "Runr", "Buhd", "Hano", "Tagb", "Tglg",
        "Mong", "Phag", "Beng", "Deva", "Gran", "Knda", "Deva", "Deva", "Gran",
        "Knda", "Beng", "Deva", "Deva", "Shrd", "Deva", "Knda", "Mlym", "Orya",
        "Taml", "Telu", "Deva", "Nand", "Beng", "Deva", "Gran", "Knda", "Mlym",
        "Nand", "Orya", "Sinh", "Telu", "Tirh", "Tutg", "Deva", "Gran", "Deva",
        "Gran", "Knda", "Tutg", "Beng", "Nand", "Cyrl", "Latn", "Syrc", "Syrc",
        "Latn", "Mong", "Phag", "Adlm", "Arab", "Cari", "Geor", "Glag", "Hung",
        "Lyci", "Orkh", "Cari", "Grek", "Hung", "Mero", "Deva", "Gran", "Latn",
        "Copt", "Latn", "Avst", "Orkh", "Avst", "Cari", "Geor", "Hung", "Kthi",
        "Lydi", "Samr", "Dupl", "Adlm", "Arab", "Hung", "Hani", "Tang", "Bopo",
        "Hang", "Hani", "Hira", "Kana", "Mong", "Yiii", "Bopo", "Hang", "Hani",
        "Hira", "Kana", "Mong", "Phag", "Yiii", "Bopo", "Hang", "Hani", "Hira",
        "Kana", "Hani", "Bopo", "Hang", "Hani", "Hira", "Kana", "Mong", "Tibt",
        "Yiii", "Bopo", "Hang", "Hani", "Hira", "Kana", "Lisu", "Mong", "Tibt",
        "Yiii", "Bopo", "Hang", "Hani", "Hira", "Kana", "Yiii", "Bopo", "Hani",
        "Hira", "Kana", "Hani", "Hira", "Kana", "Hani", "Latn", "Deva", "Dogr",
        "Gujr", "Guru", "Khoj", "Knda", "Kthi", "Mahj", "Mlym", "Modi", "Nand",
        "Shrd", "Sind", "Takr", "Tirh", "Tutg", "Deva", "Dogr", "Gujr", "Guru",
        "Khoj", "Knda", "Kthi", "Mahj", "Modi", "Nand", "Shrd", "Sind", "Takr",
        "Tirh", "Tutg", "Deva", "Dogr", "Gujr", "Guru", "Khoj", "Kthi", "Mahj",
        "Modi", "Sind", "Takr", "Tirh", "Deva", "Dogr", "Gujr", "Guru", "Khoj",
        "Kthi", "Mahj", "Modi", "Shrd", "Sind", "Takr", "Tirh", "Beng", "Deva",
        "Tutg", "Deva", "Taml", "Kali", "Latn", "Mymr", "Bugi", "Java", "Arab",
        "Nkoo", "Arab", "Thaa", "Cpmn", "Cprt", "Linb", "Cprt", "Linb", "Cprt",
        "Lina", "Linb", "Arab", "Copt", "Mani", "Ougr",
    )
}
