package io.ygdrasil.kalligraphie.font.colr

/**
 * Stable diagnostic code families used by color glyph planning and emoji dispatch evidence.
 */
object ColorGlyphDiagnosticCodes {
    const val CPALMalformed: String = "text.color.CPAL-malformed"
    const val COLRMalformed: String = "text.color.COLR-malformed"
    const val COLRV1PaintUnsupported: String = "text.color.COLRv1-paint-unsupported"
    const val COLRV1CycleDetected: String = "text.color.COLRv1-cycle-detected"
    const val COLRV1BudgetExceeded: String = "text.color.COLRv1-budget-exceeded"
    const val PNGDecodeFailed: String = "text.bitmap.PNG-decode-failed"
    const val BitmapStrikeUnavailable: String = "text.bitmap.strike-unavailable"
    const val BitmapPayloadFormatUnsupported: String = "text.bitmap.payload-format-unsupported"
    const val SVGDocumentMalformed: String = "text.SVG.document-malformed"
    const val SVGFeatureUnsupported: String = "text.SVG.feature-unsupported"
    const val SVGExternalResourceRefused: String = "text.SVG.external-resource-refused"
    const val SVGBudgetExceeded: String = "text.SVG.budget-exceeded"
    const val EmojiSequenceUnsupported: String = "text.emoji.sequence-unsupported"
    const val EmojiFallbackUnavailable: String = "text.emoji.fallback-unavailable"
    const val ColorGlyphUnavailable: String = "text.emoji.color-glyph-unavailable"
    const val EmojiRouteSelected: String = "text.emoji.route-selected"
    const val EmojiRouteLowerPreferenceSkipped: String = "text.emoji.route-lower-preference-skipped"
}

/**
 * Describes a color glyph routing decision, alternate route, or unsupported source condition.
 */
data class ColorGlyphDiagnostic @JvmOverloads constructor(
    val glyphId: Int?,
    val route: String,
    val message: String,
    val severity: String = "info",
    val code: String = ColorGlyphDiagnosticCodes.ColorGlyphUnavailable,
    val detail: String = message,
) {
    /**
     * Serializes this color diagnostic with stable field order and JSON escaping.
     *
     * @return canonical JSON object without a trailing newline.
     */
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId ?: "null").append(", ")
        append(colorGlyphJsonString("route")).append(": ").append(colorGlyphJsonString(route)).append(", ")
        append(colorGlyphJsonString("code")).append(": ").append(colorGlyphJsonString(code)).append(", ")
        append(colorGlyphJsonString("detail")).append(": ").append(colorGlyphJsonString(detail)).append(", ")
        append(colorGlyphJsonString("severity")).append(": ").append(colorGlyphJsonString(severity)).append(", ")
        append(colorGlyphJsonString("message")).append(": ").append(colorGlyphJsonString(message))
        append("}")
    }
}
