package org.graphiks.kalligraphie.font.colr

/**
 * Route-scoped artifact-key evidence derived from the M9 glyph strike key preimage rules.
 *
 * The hash is intentionally carried as a value object instead of a future renderer handle. M11 can
 * register typed artifacts from these facts later without reopening COLR/CPAL table state.
 */
data class ColorGlyphArtifactKey(
    val glyphId: Int,
    val route: String,
    val strikeKeySha256: String,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(", ")
        append(colorGlyphJsonString("route")).append(": ").append(colorGlyphJsonString(route)).append(", ")
        append(colorGlyphJsonString("strikeKeySha256")).append(": ")
        append(colorGlyphJsonString(strikeKeySha256))
        append("}")
    }
}
