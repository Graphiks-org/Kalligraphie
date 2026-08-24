package io.ygdrasil.kalligraphie.font.colr

internal val COLOR_GLYPH_ROUTE_ORDER = listOf("colr", "bitmap", "png", "svg", "outline")
internal val COLOR_GLYPH_ROUTES = COLOR_GLYPH_ROUTE_ORDER.toSet()

internal fun StringBuilder.appendColorGlyphJsonField(name: String, value: String, comma: Boolean) {
    append("  ")
    append(colorGlyphJsonString(name))
    append(": ")
    append(colorGlyphJsonString(value))
    if (comma) append(",")
    append("\n")
}

internal fun StringBuilder.appendColorGlyphJsonField(name: String, value: Int, comma: Boolean) {
    append("  ")
    append(colorGlyphJsonString(name))
    append(": ")
    append(value)
    if (comma) append(",")
    append("\n")
}

internal fun StringBuilder.appendColorGlyphDiagnosticsJson(diagnostics: List<ColorGlyphDiagnostic>, indent: String) {
    append("[")
    if (diagnostics.isNotEmpty()) {
        append("\n")
        append(diagnostics.joinToString(",\n") { diagnostic -> "$indent  ${diagnostic.toCanonicalJson()}" })
        append("\n")
        append(indent)
    }
    append("]")
}

internal fun StringBuilder.appendColorGlyphRoutesJson(routes: List<ColorGlyphRoute>, indent: String) {
    append("[")
    if (routes.isNotEmpty()) {
        append("\n")
        append(routes.joinToString(",\n") { route -> "$indent  ${route.toCanonicalJson()}" })
        append("\n")
        append(indent)
    }
    append("]")
}

internal fun StringBuilder.appendColorGlyphLayerPlansJson(
    layers: List<COLRV0LayerPlan>,
    indent: String,
) {
    append("[")
    if (layers.isNotEmpty()) {
        append("\n")
        append(layers.joinToString(",\n") { layer -> "$indent  ${layer.toCanonicalJson()}" })
        append("\n")
        append(indent)
    }
    append("]")
}

internal fun StringBuilder.appendColorGlyphGraphNodesJson(
    nodes: List<COLRV1PaintGraphNode>,
    indent: String,
) {
    append("[")
    if (nodes.isNotEmpty()) {
        append("\n")
        append(nodes.joinToString(",\n") { node -> "$indent  ${node.toCanonicalJson()}" })
        append("\n")
        append(indent)
    }
    append("]")
}

internal fun StringBuilder.appendColorGlyphRouteOrderJson() {
    append(COLOR_GLYPH_ROUTE_ORDER.joinToString(prefix = "[", postfix = "]") { route ->
        colorGlyphJsonString(route)
    })
}
