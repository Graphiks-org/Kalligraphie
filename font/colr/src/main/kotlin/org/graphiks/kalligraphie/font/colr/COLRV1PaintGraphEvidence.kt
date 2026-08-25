package org.graphiks.kalligraphie.font.colr

import org.graphiks.kalligraphie.font.TypefaceID

data class COLRV1GradientStopEvidence(
    val stopIndex: Int,
    val offset: Float,
    val paletteIndex: Int,
    val resolvedColorArgb: String,
    val alpha: Float,
    val varIndexBase: Long? = null,
    val appliedAlphaDelta: Float? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("stopIndex")).append(": ").append(stopIndex).append(", ")
        append(colorGlyphJsonString("offset")).append(": ").append(colorGlyphFloatToken(offset)).append(", ")
        append(colorGlyphJsonString("paletteIndex")).append(": ").append(paletteIndex).append(", ")
        append(colorGlyphJsonString("resolvedColorArgb")).append(": ").append(colorGlyphJsonString(resolvedColorArgb)).append(", ")
        append(colorGlyphJsonString("alpha")).append(": ").append(colorGlyphFloatToken(alpha)).append(", ")
        append(colorGlyphJsonString("varIndexBase")).append(": ").append(varIndexBase ?: "null").append(", ")
        append(colorGlyphJsonString("appliedAlphaDelta")).append(": ")
        append(appliedAlphaDelta?.let(::colorGlyphFloatToken) ?: "null")
        append("}")
    }
}

data class COLRV1LinearGradientGeometry(
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("x0")).append(": ").append(x0).append(", ")
        append(colorGlyphJsonString("y0")).append(": ").append(y0).append(", ")
        append(colorGlyphJsonString("x1")).append(": ").append(x1).append(", ")
        append(colorGlyphJsonString("y1")).append(": ").append(y1).append(", ")
        append(colorGlyphJsonString("x2")).append(": ").append(x2).append(", ")
        append(colorGlyphJsonString("y2")).append(": ").append(y2)
        append("}")
    }
}

data class COLRV1RadialGradientGeometry(
    val x0: Int,
    val y0: Int,
    val radius0: Int,
    val x1: Int,
    val y1: Int,
    val radius1: Int,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("x0")).append(": ").append(x0).append(", ")
        append(colorGlyphJsonString("y0")).append(": ").append(y0).append(", ")
        append(colorGlyphJsonString("radius0")).append(": ").append(radius0).append(", ")
        append(colorGlyphJsonString("x1")).append(": ").append(x1).append(", ")
        append(colorGlyphJsonString("y1")).append(": ").append(y1).append(", ")
        append(colorGlyphJsonString("radius1")).append(": ").append(radius1)
        append("}")
    }
}

data class COLRV1SweepGradientGeometry(
    val centerX: Int,
    val centerY: Int,
    val startAngle: Float,
    val endAngle: Float,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("centerX")).append(": ").append(centerX).append(", ")
        append(colorGlyphJsonString("centerY")).append(": ").append(centerY).append(", ")
        append(colorGlyphJsonString("startAngle")).append(": ").append(colorGlyphFloatToken(startAngle)).append(", ")
        append(colorGlyphJsonString("endAngle")).append(": ").append(colorGlyphFloatToken(endAngle))
        append("}")
    }
}

data class COLRV1GradientEvidence(
    val extendMode: String,
    val stops: List<COLRV1GradientStopEvidence>,
    val variationCoordinates: Map<String, Float> = emptyMap(),
    val linearGeometry: COLRV1LinearGradientGeometry? = null,
    val radialGeometry: COLRV1RadialGradientGeometry? = null,
    val sweepGeometry: COLRV1SweepGradientGeometry? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("extendMode")).append(": ").append(colorGlyphJsonString(extendMode)).append(", ")
        append(colorGlyphJsonString("stops")).append(": ")
        append(stops.joinToString(prefix = "[", postfix = "]") { stop -> stop.toCanonicalJson() })
        append(", ")
        append(colorGlyphJsonString("variationCoordinates")).append(": ")
        append(colorGlyphFloatMapJson(variationCoordinates))
        append(", ")
        append(colorGlyphJsonString("linearGeometry")).append(": ")
        append(linearGeometry?.toCanonicalJson() ?: "null")
        append(", ")
        append(colorGlyphJsonString("radialGeometry")).append(": ")
        append(radialGeometry?.toCanonicalJson() ?: "null")
        append(", ")
        append(colorGlyphJsonString("sweepGeometry")).append(": ")
        append(sweepGeometry?.toCanonicalJson() ?: "null")
        append("}")
    }
}

data class COLRV1TransformEvidence(
    val transformKind: String,
    val xx: Float,
    val yx: Float,
    val xy: Float,
    val yy: Float,
    val dx: Float,
    val dy: Float,
    val determinant: Float,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("transformKind")).append(": ").append(colorGlyphJsonString(transformKind)).append(", ")
        append(colorGlyphJsonString("xx")).append(": ").append(colorGlyphFloatToken(xx)).append(", ")
        append(colorGlyphJsonString("yx")).append(": ").append(colorGlyphFloatToken(yx)).append(", ")
        append(colorGlyphJsonString("xy")).append(": ").append(colorGlyphFloatToken(xy)).append(", ")
        append(colorGlyphJsonString("yy")).append(": ").append(colorGlyphFloatToken(yy)).append(", ")
        append(colorGlyphJsonString("dx")).append(": ").append(colorGlyphFloatToken(dx)).append(", ")
        append(colorGlyphJsonString("dy")).append(": ").append(colorGlyphFloatToken(dy)).append(", ")
        append(colorGlyphJsonString("determinant")).append(": ").append(colorGlyphFloatToken(determinant))
        append("}")
    }
}

data class COLRV1CompositeEvidence(
    val mode: String,
    val sourceNodeId: Int,
    val backdropNodeId: Int,
    val destinationReadClass: String,
    val requiresLayerIsolation: Boolean,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("mode")).append(": ").append(colorGlyphJsonString(mode)).append(", ")
        append(colorGlyphJsonString("sourceNodeId")).append(": ").append(sourceNodeId).append(", ")
        append(colorGlyphJsonString("backdropNodeId")).append(": ").append(backdropNodeId).append(", ")
        append(colorGlyphJsonString("destinationReadClass")).append(": ")
        append(colorGlyphJsonString(destinationReadClass)).append(", ")
        append(colorGlyphJsonString("requiresLayerIsolation")).append(": ").append(requiresLayerIsolation)
        append("}")
    }
}

data class COLRV1ClipEvidence(
    val clipBounds: ColorGlyphBounds,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("clipBounds")).append(": ").append(clipBounds.toCanonicalJson())
        append("}")
    }
}

data class COLRV1PaintGraphNode(
    val nodeId: Int,
    val kind: String,
    val childNodeIds: List<Int> = emptyList(),
    val glyphId: Int? = null,
    val referencedColrGlyphId: Int? = null,
    val paletteIndex: Int? = null,
    val resolvedColorArgb: String? = null,
    val alpha: Float? = null,
    val varIndexBase: Long? = null,
    val outlineArtifactKey: ColorGlyphArtifactKey? = null,
    val bounds: ColorGlyphBounds? = null,
    val gradient: COLRV1GradientEvidence? = null,
    val transform: COLRV1TransformEvidence? = null,
    val composite: COLRV1CompositeEvidence? = null,
    val clip: COLRV1ClipEvidence? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("nodeId")).append(": ").append(nodeId).append(", ")
        append(colorGlyphJsonString("kind")).append(": ").append(colorGlyphJsonString(kind)).append(", ")
        append(colorGlyphJsonString("childNodeIds")).append(": ")
        append(childNodeIds.joinToString(prefix = "[", postfix = "]"))
        append(", ")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId ?: "null").append(", ")
        append(colorGlyphJsonString("referencedColrGlyphId")).append(": ").append(referencedColrGlyphId ?: "null").append(", ")
        append(colorGlyphJsonString("paletteIndex")).append(": ").append(paletteIndex ?: "null").append(", ")
        append(colorGlyphJsonString("resolvedColorArgb")).append(": ").append(colorGlyphNullableString(resolvedColorArgb)).append(", ")
        append(colorGlyphJsonString("alpha")).append(": ").append(alpha?.let(::colorGlyphFloatToken) ?: "null").append(", ")
        append(colorGlyphJsonString("varIndexBase")).append(": ").append(varIndexBase ?: "null").append(", ")
        append(colorGlyphJsonString("outlineArtifactKey")).append(": ")
        append(outlineArtifactKey?.toCanonicalJson() ?: "null")
        append(", ")
        append(colorGlyphJsonString("bounds")).append(": ").append(bounds?.toCanonicalJson() ?: "null")
        append(", ")
        append(colorGlyphJsonString("gradient")).append(": ").append(gradient?.toCanonicalJson() ?: "null")
        if (transform != null) {
            append(", ")
            append(colorGlyphJsonString("transform")).append(": ").append(transform.toCanonicalJson())
        }
        if (composite != null) {
            append(", ")
            append(colorGlyphJsonString("composite")).append(": ").append(composite.toCanonicalJson())
        }
        if (clip != null) {
            append(", ")
            append(colorGlyphJsonString("clip")).append(": ").append(clip.toCanonicalJson())
        }
        append("}")
    }
}

data class COLRV1PaintGraphEvidence(
    val glyphId: Int,
    val typefaceId: TypefaceID,
    val paletteIdentity: String,
    val rootNodeId: Int,
    val supportedOperationGroup: String,
    val nodes: List<COLRV1PaintGraphNode>,
    val bounds: ColorGlyphBounds,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false, indent = "").toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true, indent = "")

    internal fun toCanonicalJson(indent: String): String = canonicalJson(includeDumpSha256 = true, indent = indent)

    private fun canonicalJson(includeDumpSha256: Boolean, indent: String): String = buildString {
        val fieldIndent = "$indent  "
        append("{\n")
        append(fieldIndent).append(colorGlyphJsonString("schema")).append(": ")
        append(colorGlyphJsonString(COLRV1PaintGraphSchema)).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("typefaceId")).append(": ")
        append(colorGlyphJsonString(typefaceId.value.toString())).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("paletteIdentity")).append(": ")
        append(colorGlyphJsonString(paletteIdentity)).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("rootNodeId")).append(": ").append(rootNodeId).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("supportedOperationGroup")).append(": ")
        append(colorGlyphJsonString(supportedOperationGroup)).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("nodes")).append(": ")
        appendColorGlyphGraphNodesJson(nodes, indent = fieldIndent)
        append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("bounds")).append(": ").append(bounds.toCanonicalJson()).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("diagnostics")).append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = fieldIndent)
        if (includeDumpSha256) {
            append(",\n")
            append(fieldIndent).append(colorGlyphJsonString("dumpSha256")).append(": ")
            append(colorGlyphJsonString(dumpSha256)).append("\n")
        } else {
            append("\n")
        }
        append(indent).append("}")
    }

    fun toCompositePlan(): ColorGlyphCompositePlan? {
        val operations = nodes
            .filter { node -> node.transform != null || node.composite != null || node.clip != null }
            .map { node ->
                ColorGlyphCompositeOperation(
                    nodeId = node.nodeId,
                    kind = node.kind,
                    childNodeIds = node.childNodeIds,
                    bounds = node.bounds,
                    transform = node.transform,
                    composite = node.composite,
                    clip = node.clip,
                )
            }
        if (operations.isEmpty()) return null
        return ColorGlyphCompositePlan(
            glyphId = glyphId,
            typefaceId = typefaceId,
            paletteIdentity = paletteIdentity,
            supportedOperationGroup = supportedOperationGroup,
            operations = operations,
            diagnostics = diagnostics,
        )
    }

    companion object {
        const val COLRV1PaintGraphSchema: String = "org.graphiks.kanvas.font.colr.COLRV1PaintGraph.v1"
    }
}

data class ColorGlyphCompositeOperation(
    val nodeId: Int,
    val kind: String,
    val childNodeIds: List<Int>,
    val bounds: ColorGlyphBounds?,
    val transform: COLRV1TransformEvidence? = null,
    val composite: COLRV1CompositeEvidence? = null,
    val clip: COLRV1ClipEvidence? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("nodeId")).append(": ").append(nodeId).append(", ")
        append(colorGlyphJsonString("kind")).append(": ").append(colorGlyphJsonString(kind)).append(", ")
        append(colorGlyphJsonString("childNodeIds")).append(": ")
        append(childNodeIds.joinToString(prefix = "[", postfix = "]"))
        append(", ")
        append(colorGlyphJsonString("bounds")).append(": ").append(bounds?.toCanonicalJson() ?: "null")
        if (transform != null) {
            append(", ")
            append(colorGlyphJsonString("transform")).append(": ").append(transform.toCanonicalJson())
        }
        if (composite != null) {
            append(", ")
            append(colorGlyphJsonString("composite")).append(": ").append(composite.toCanonicalJson())
        }
        if (clip != null) {
            append(", ")
            append(colorGlyphJsonString("clip")).append(": ").append(clip.toCanonicalJson())
        }
        append("}")
    }
}

data class ColorGlyphCompositePlan(
    val glyphId: Int,
    val typefaceId: TypefaceID,
    val paletteIdentity: String,
    val supportedOperationGroup: String,
    val operations: List<ColorGlyphCompositeOperation>,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        append("  ").append(colorGlyphJsonString("schema")).append(": ")
        append(colorGlyphJsonString(ColorGlyphCompositePlanSchema)).append(",\n")
        append("  ").append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(",\n")
        append("  ").append(colorGlyphJsonString("typefaceId")).append(": ")
        append(colorGlyphJsonString(typefaceId.value.toString())).append(",\n")
        append("  ").append(colorGlyphJsonString("paletteIdentity")).append(": ")
        append(colorGlyphJsonString(paletteIdentity)).append(",\n")
        append("  ").append(colorGlyphJsonString("supportedOperationGroup")).append(": ")
        append(colorGlyphJsonString(supportedOperationGroup)).append(",\n")
        append("  ").append(colorGlyphJsonString("operations")).append(": ")
        append(
            operations.joinToString(
                prefix = "[\n",
                postfix = "\n  ]",
                separator = ",\n",
            ) { operation -> "    ${operation.toCanonicalJson()}" },
        )
        append(",\n")
        append("  ").append(colorGlyphJsonString("diagnostics")).append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            append("  ").append(colorGlyphJsonString("dumpSha256")).append(": ")
            append(colorGlyphJsonString(dumpSha256)).append("\n")
        } else {
            append("\n")
        }
        append("}")
    }

    companion object {
        const val ColorGlyphCompositePlanSchema: String = "org.graphiks.kanvas.font.colr.ColorGlyphCompositePlan.v1"
    }
}

/**
 * Describes a renderer-neutral COLR paint graph.
 */
data class COLRPaintGraph(
    val root: COLRPaintNode,
    val nodes: List<COLRPaintNode>,
)

/**
 * Represents one node in a COLR paint graph.
 */
data class COLRPaintNode(
    val id: Int,
    val kind: String,
    val children: List<Int> = emptyList(),
    val paletteIndex: Int? = null,
    val glyphId: Int? = null,
)
