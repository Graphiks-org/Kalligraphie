package org.graphiks.kalligraphie.e2e.catalog

/**
 * The corpus paths and glyph sets the registered scenes draw on.
 *
 * Kept apart from either registry so the portable scenes and the paragraph-facade ones name the
 * same corpus entries: the ratchet checks each renderer's paths against its entry's declared
 * families, and two lists of paths would defeat that check.
 */
internal const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"
internal const val AMIRI = "/fonts/amiri/Amiri-Regular.ttf"
internal const val NOTO_DEVANAGARI = "/fonts/noto-devanagari/NotoSansDevanagari-Regular.ttf"
internal const val BUNGEE_COLOR = "/fonts/bungee-color/BungeeColor-Regular.ttf"
internal const val EMOJI_TWO_COLR_V0 = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"
internal const val SKIA_EBDT_FORMAT1 = "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"
internal const val SKIA_CBDT = "/fonts/skia-cbdt/cbdt.ttf"
internal const val SKIA_SBIX = "/fonts/skia-sbix/sbix.ttf"
internal const val CFF_LIBERATION = "/fonts/cff-liberation/LiberationSans-CFF.otf"
internal const val CFF2_LIBERATION = "/fonts/cff2-liberation/LiberationSans-CFF2.otf"
internal const val KALLIGRAPHIE_VAR_VVAR = "/fonts/kalligraphie-var-vvar/KalligraphieVarVVAR.ttf"
internal const val WORK_SANS = "/fonts/worksans/WorkSans[wght].ttf"
internal const val NOTO_SANS_JP = "/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"

/** The Latin letters a colour sheet draws, without the lower case. */
internal val LATIN_LETTERS: List<Int> = (0x41..0x5A).toList()

/** Every Latin letter, digit and lower-case letter the outline sheet draws. */
internal val LATIN: List<Int> = LATIN_LETTERS + (0x61..0x7A) + (0x30..0x39)

// U+03A2 is unassigned.
internal val GREEK: List<Int> = (0x391..0x3A9).filter { codepoint -> codepoint != 0x3A2 } + (0x3B1..0x3C9)

internal val CYRILLIC: List<Int> = (0x410..0x42F).toList() + (0x430..0x44F).toList()

// Core Arabic letters only: Persian/Urdu variants (U+063B–U+063F) and tatweel (U+0640) are outside the curated set.
internal val ARABIC: List<Int> = (0x621..0x63A).toList() + (0x641..0x64A).toList()

internal val DEVANAGARI: List<Int> = (0x905..0x939).toList() + (0x966..0x96F).toList()

// U+1F602 (7 layers) and U+1F604 (10 layers) exceed the shared paint profile maxPaths=6; omitted deliberately.
internal val EMOJI: List<Int> = (0x1F600..0x1F607).filter { codepoint ->
    codepoint != 0x1F602 && codepoint != 0x1F604
}
