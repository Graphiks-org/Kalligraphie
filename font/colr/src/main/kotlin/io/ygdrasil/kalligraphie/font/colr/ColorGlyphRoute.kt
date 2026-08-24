package io.ygdrasil.kalligraphie.font.colr

/**
 * Metadata-only color glyph route selected for one glyph.
 */
data class ColorGlyphRoute(
    val glyphId: Int,
    val route: String,
) {
    init {
        require(route in COLOR_GLYPH_ROUTES) {
            "Unsupported color glyph route: $route."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(", ")
        append(colorGlyphJsonString("route")).append(": ").append(colorGlyphJsonString(route)).append(", ")
        append(colorGlyphJsonString("outlineFallback")).append(": ").append(route == "outline").append(", ")
        append(colorGlyphJsonString("outlineFacts")).append(": null")
        append("}")
    }
}
