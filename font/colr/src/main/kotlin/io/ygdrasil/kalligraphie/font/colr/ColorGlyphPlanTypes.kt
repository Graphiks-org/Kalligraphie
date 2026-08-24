package io.ygdrasil.kalligraphie.font.colr

import io.ygdrasil.kalligraphie.font.TypefaceID

/**
 * Deterministic COLRv0 plan proof produced from already-parsed color table facts.
 */
data class ColorGlyphPlan(
    val glyphId: Int,
    val typefaceId: TypefaceID,
    val routeKind: String = "colrv0",
    val artifactKey: ColorGlyphArtifactKey,
    val palette: ColorGlyphPalette,
    val layers: List<COLRV0LayerPlan>,
    val paintGraph: COLRV1PaintGraphEvidence? = null,
    val bounds: ColorGlyphBounds,
    val fallbackPolicy: String,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendColorGlyphJsonField("schema", ColorGlyphPlanSchema, comma = true)
        appendColorGlyphJsonField("glyphId", glyphId, comma = true)
        appendColorGlyphJsonField("typefaceId", typefaceId.value.toString(), comma = true)
        appendColorGlyphJsonField("routeKind", routeKind, comma = true)
        append("  ").append(colorGlyphJsonString("artifactKey")).append(": ").append(artifactKey.toCanonicalJson()).append(",\n")
        append("  ").append(colorGlyphJsonString("palette")).append(": ").append(palette.toCanonicalJson()).append(",\n")
        append("  ").append(colorGlyphJsonString("layers")).append(": ")
        appendColorGlyphLayerPlansJson(layers, indent = "  ")
        append(",\n")
        append("  ").append(colorGlyphJsonString("paintGraph")).append(": ")
        append(paintGraph?.toCanonicalJson(indent = "  ") ?: "null")
        append(",\n")
        append("  ").append(colorGlyphJsonString("bounds")).append(": ").append(bounds.toCanonicalJson()).append(",\n")
        appendColorGlyphJsonField("fallbackPolicy", fallbackPolicy, comma = true)
        append("  ").append(colorGlyphJsonString("diagnostics")).append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendColorGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }

    companion object {
        const val ColorGlyphPlanSchema: String = "org.graphiks.kanvas.font.colr.ColorGlyphPlan.v1"
    }
}

/**
 * Typed COLRv0 layer plan consumed later by artifact registration work.
 */
data class COLRV0LayerPlan(
    val layerIndex: Int,
    val glyphId: Int,
    val paletteIndex: Int,
    val resolvedColor: Int?,
    val usesForegroundColor: Boolean,
    val outlineArtifactKey: ColorGlyphArtifactKey,
    val bounds: ColorGlyphBounds,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("layerIndex")).append(": ").append(layerIndex).append(", ")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(", ")
        append(colorGlyphJsonString("paletteIndex")).append(": ").append(paletteIndex).append(", ")
        append(colorGlyphJsonString("resolvedColorArgb")).append(": ")
        append(colorGlyphNullableString(resolvedColor?.let(::colorGlyphArgbHex)))
        append(", ")
        append(colorGlyphJsonString("usesForegroundColor")).append(": ").append(usesForegroundColor).append(", ")
        append(colorGlyphJsonString("outlineArtifactKey")).append(": ").append(outlineArtifactKey.toCanonicalJson()).append(", ")
        append(colorGlyphJsonString("bounds")).append(": ").append(bounds.toCanonicalJson())
        append("}")
    }
}

/**
 * Stable palette selection facts recorded alongside a resolved COLRv0 color glyph plan.
 */
data class ColorGlyphPalette(
    val identity: String,
    val selectionIndex: Int,
    val resolvedIndex: Int,
    val overrideCount: Int,
    val colorCount: Int,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("identity")).append(": ").append(colorGlyphJsonString(identity)).append(", ")
        append(colorGlyphJsonString("selectionIndex")).append(": ").append(selectionIndex).append(", ")
        append(colorGlyphJsonString("resolvedIndex")).append(": ").append(resolvedIndex).append(", ")
        append(colorGlyphJsonString("overrideCount")).append(": ").append(overrideCount).append(", ")
        append(colorGlyphJsonString("colorCount")).append(": ").append(colorCount)
        append("}")
    }
}

/**
 * Result of attempting to convert COLRv0 metadata into a typed color glyph plan.
 */
data class COLRV0ColorGlyphPlanDecision(
    val plan: ColorGlyphPlan?,
    val selectedRoute: ColorGlyphRoute?,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
)

/**
 * Result of attempting to convert COLRv1 metadata into a typed color glyph plan.
 */
data class COLRV1ColorGlyphPlanDecision(
    val plan: ColorGlyphPlan?,
    val selectedRoute: ColorGlyphRoute?,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
)

/**
 * Describes a color glyph planning result without duplicating public GPU API plan classes.
 */
data class ColorGlyphPlanningResult(
    val routes: List<ColorGlyphRoute>,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendColorGlyphJsonField("schema", PlanningResultSchema, comma = true)
        append("  ")
        append(colorGlyphJsonString("routeOrder"))
        append(": ")
        appendColorGlyphRouteOrderJson()
        append(",\n")
        append("  ")
        append(colorGlyphJsonString("selectedRoutes"))
        append(": ")
        appendColorGlyphRoutesJson(routes, indent = "  ")
        append(",\n")
        append("  ")
        append(colorGlyphJsonString("diagnostics"))
        append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendColorGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }

    companion object {
        const val PlanningResultSchema: String = "org.graphiks.kanvas.font.colr.ColorGlyphPlanningResult.v1"
    }
}
