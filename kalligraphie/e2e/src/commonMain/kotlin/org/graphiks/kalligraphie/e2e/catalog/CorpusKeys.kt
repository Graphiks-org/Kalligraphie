// CorpusKeys.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Canonical corpus keys, one per directory of `test-fixtures/fonts/`. */
public object CorpusKeys {
    /** Liberation Sans, static TrueType. */
    public val LIBERATION: CorpusKey = CorpusKey("liberation")
    /** Amiri Regular, static TrueType Arabic. */
    public val AMIRI: CorpusKey = CorpusKey("amiri")
    /** DejaVu Sans. */
    public val DEJAVU: CorpusKey = CorpusKey("dejavu")
    /** Noto Sans Devanagari. */
    public val NOTO_DEVANAGARI: CorpusKey = CorpusKey("noto-devanagari")
    /** Noto Sans JP vertical fixture. */
    public val NOTO_SANS_JP: CorpusKey = CorpusKey("noto-sans-jp")
    /** Bungee Color, COLR v0. */
    public val BUNGEE_COLOR: CorpusKey = CorpusKey("bungee-color")
    /** Emoji Two COLR v0. */
    public val EMOJI_TWO_COLR_V0: CorpusKey = CorpusKey("emoji-two-colr-v0")
    /** Skia COLR v1 test glyphs. */
    public val SKIA_COLR_V1: CorpusKey = CorpusKey("skia-colr-v1")
    /** Synthetic variable COLR v1. */
    public val KALLIGRAPHIE_VAR_COLR: CorpusKey = CorpusKey("kalligraphie-var-colr")
    /** Synthetic VVAR fixture. */
    public val KALLIGRAPHIE_VAR_VVAR: CorpusKey = CorpusKey("kalligraphie-var-vvar")
    /** Skia CBDT/CBLC strikes. */
    public val SKIA_CBDT: CorpusKey = CorpusKey("skia-cbdt")
    /** Skia EBDT format 1 strike. */
    public val SKIA_EBDT_FORMAT1: CorpusKey = CorpusKey("skia-ebdt-format1")
    /** Skia sbix strikes. */
    public val SKIA_SBIX: CorpusKey = CorpusKey("skia-sbix")
    /** Twemoji SVG-in-OpenType subset. */
    public val TWEMOJI_SVGINOT_GLYPH5: CorpusKey = CorpusKey("twemoji-svginot-glyph5")
    /** Liberation Sans converted to CFF 1. */
    public val CFF_LIBERATION: CorpusKey = CorpusKey("cff-liberation")
    /** Liberation Sans converted to CFF 2. */
    public val CFF2_LIBERATION: CorpusKey = CorpusKey("cff2-liberation")
    /** Synthetic variable CFF 2. */
    public val CFF2_VARIABLE: CorpusKey = CorpusKey("cff2-variable")
    /** Minimal GDEF/GPOS caret fixture. */
    public val GDEF_KERN: CorpusKey = CorpusKey("gdef-kern")
    /** Liberation + Amiri TrueType collection. */
    public val LIBERATION_AMIRI_COLLECTION: CorpusKey = CorpusKey("liberation-amiri-collection")
    /** Work Sans variable, `wght` axis 100–900. */
    public val WORK_SANS: CorpusKey = CorpusKey("worksans")
}
