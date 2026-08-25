package org.graphiks.kalligraphie.font.colr

/**
 * Describes one COLR version 1 base glyph paint record.
 *
 * @property glyphId base glyph identifier whose color representation is described by [paint].
 * @property paint parsed root paint for [glyphId].
 */
data class COLRV1BaseGlyphPaintRecord(
    val glyphId: Int,
    val paint: COLRV1Paint,
)

/**
 * Describes a COLR version 1 clip box in font design units.
 *
 * @property xMin minimum x coordinate.
 * @property yMin minimum y coordinate.
 * @property xMax maximum x coordinate.
 * @property yMax maximum y coordinate.
 */
data class COLRV1ClipBox(
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
)

/**
 * Describes one parsed COLR version 1 ClipList range.
 *
 * @property startGlyphId first glyph identifier covered by [box].
 * @property endGlyphId last glyph identifier covered by [box].
 * @property box parsed clip box for this inclusive glyph range.
 */
data class COLRV1ClipRange(
    val startGlyphId: Int,
    val endGlyphId: Int,
    val box: COLRV1ClipBox,
)

/**
 * Stores the parsed COLR version 1 paint data supported by the pure Kotlin font stack.
 *
 * This is a renderer-neutral metadata model. It proves that a bounded COLRv1 paint graph can be
 * parsed deterministically, but it does not claim complete COLRv1 rendering support. Unsupported
 * paint formats, malformed offsets, excessive nesting, and count expansions are rejected by
 * [COLRV1Parser.parse] with `null`.
 *
 * @property baseGlyphPaintRecords COLRv1 base glyph paint records in source order.
 * @property layerPaints parsed LayerList paints in source order.
 * @property clipRanges parsed ClipList ranges in source order.
 */
data class COLRV1Table(
    val baseGlyphPaintRecords: List<COLRV1BaseGlyphPaintRecord>,
    val layerPaints: List<COLRV1Paint> = emptyList(),
    val clipRanges: List<COLRV1ClipRange> = emptyList(),
) {
    /**
     * Resolves the COLRv1 root paint for [glyphId].
     *
     * @param glyphId base glyph identifier to look up.
     * @return parsed root paint, or null when [glyphId] has no COLRv1 paint record.
     */
    fun paintForGlyph(glyphId: Int): COLRV1Paint? =
        baseGlyphPaintRecords.firstOrNull { record -> record.glyphId == glyphId }?.paint

    /**
     * Resolves the first ClipList box whose range contains [glyphId].
     *
     * @param glyphId glyph identifier to look up.
     * @return parsed clip box, or null when no parsed range covers [glyphId].
     */
    fun clipBoxForGlyph(glyphId: Int): COLRV1ClipBox? =
        clipRanges.firstOrNull { range -> glyphId in range.startGlyphId..range.endGlyphId }?.box

    /**
     * Builds a deterministic generic paint graph for [glyphId].
     *
     * The returned graph flattens the parsed COLRv1 paint tree into stable pre-order node IDs. It
     * preserves palette and glyph references needed by route diagnostics, while transform values
     * remain available on the typed [COLRV1Paint] model returned by [paintForGlyph].
     *
     * @param glyphId base glyph identifier to convert.
     * @return flattened graph, or null when [glyphId] has no COLRv1 paint record.
     */
    fun paintGraphForGlyph(glyphId: Int): COLRPaintGraph? {
        val paint = paintForGlyph(glyphId) ?: return null
        val nodes = ArrayList<COLRPaintNode>()
        val root = COLRPaintNode(
            id = 0,
            kind = "colr-v1-glyph",
            glyphId = glyphId,
        )
        nodes += root
        val childId = appendCOLRV1PaintNode(paint = paint, nodes = nodes)
        nodes[0] = root.copy(children = listOf(childId))
        return COLRPaintGraph(
            root = nodes[0],
            nodes = nodes.toList(),
        )
    }

    /**
     * Detects a PaintColrGlyph cycle reachable from [glyphId] and reports it as stable evidence.
     *
     * This traversal follows only already-parsed COLRv1 paint data. It does not expand a renderer
     * graph, compute bounds, resolve palettes, or claim complete COLRv1 rendering support.
     *
     * @param glyphId base glyph identifier to inspect for recursive PaintColrGlyph references.
     * @return cycle diagnostic, or null when no cycle is found through parsed PaintColrGlyph links.
     */
    fun paintColrGlyphCycleDiagnostic(glyphId: Int): ColorGlyphDiagnostic? {
        val glyphPath = ArrayList<Int>()

        fun cycleDiagnostic(cyclePath: List<Int>): ColorGlyphDiagnostic {
            val pathText = cyclePath.joinToString(">")
            val cycleLength = cyclePath.size - 1
            return ColorGlyphDiagnostic(
                glyphId = glyphId,
                route = "colr",
                code = ColorGlyphDiagnosticCodes.COLRV1CycleDetected,
                severity = "warning",
                detail = "glyphId=$glyphId;tableFamily=COLR;version=1;" +
                    "cyclePath=$pathText;cycleLength=$cycleLength",
                message = "COLRv1 PaintColrGlyph cycle detected for glyph $glyphId: $pathText.",
            )
        }

        fun visitPaint(paint: COLRV1Paint): ColorGlyphDiagnostic? {
            when (paint) {
                is COLRV1Paint.Solid,
                is COLRV1Paint.LinearGradient,
                is COLRV1Paint.RadialGradient,
                is COLRV1Paint.SweepGradient -> return null
                is COLRV1Paint.Glyph -> return visitPaint(paint.paint)
                is COLRV1Paint.Layers -> {
                    paint.paints.forEach { child ->
                        visitPaint(child)?.let { diagnostic -> return diagnostic }
                    }
                    return null
                }
                is COLRV1Paint.Composite -> {
                    visitPaint(paint.source)?.let { diagnostic -> return diagnostic }
                    return visitPaint(paint.backdrop)
                }
                is COLRV1Paint.ColrGlyph -> {
                    val cycleStart = glyphPath.indexOf(paint.glyphId)
                    if (cycleStart >= 0) {
                        return cycleDiagnostic(glyphPath.drop(cycleStart) + paint.glyphId)
                    }

                    val referencedPaint = paintForGlyph(paint.glyphId) ?: return null
                    glyphPath += paint.glyphId
                    val diagnostic = visitPaint(referencedPaint)
                    glyphPath.removeAt(glyphPath.lastIndex)
                    return diagnostic
                }
                is COLRV1Paint.Translate -> return visitPaint(paint.paint)
                is COLRV1Paint.Transform -> return visitPaint(paint.paint)
                is COLRV1Paint.Scale -> return visitPaint(paint.paint)
                is COLRV1Paint.ScaleAroundCenter -> return visitPaint(paint.paint)
                is COLRV1Paint.ScaleUniform -> return visitPaint(paint.paint)
                is COLRV1Paint.ScaleUniformAroundCenter -> return visitPaint(paint.paint)
                is COLRV1Paint.Rotate -> return visitPaint(paint.paint)
                is COLRV1Paint.RotateAroundCenter -> return visitPaint(paint.paint)
                is COLRV1Paint.Skew -> return visitPaint(paint.paint)
                is COLRV1Paint.SkewAroundCenter -> return visitPaint(paint.paint)
            }
        }

        val paint = paintForGlyph(glyphId) ?: return null
        glyphPath += glyphId
        val diagnostic = visitPaint(paint)
        glyphPath.removeAt(glyphPath.lastIndex)
        return diagnostic
    }
}

internal fun appendCOLRV1PaintNode(paint: COLRV1Paint, nodes: MutableList<COLRPaintNode>): Int {
    val id = nodes.size
    val baseNode = when (paint) {
        is COLRV1Paint.Solid -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-solid",
            paletteIndex = paint.paletteIndex,
        )
        is COLRV1Paint.Glyph -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-glyph",
            glyphId = paint.glyphId,
        )
        is COLRV1Paint.Layers -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-layers",
        )
        is COLRV1Paint.LinearGradient -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-linear-gradient",
        )
        is COLRV1Paint.RadialGradient -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-radial-gradient",
        )
        is COLRV1Paint.SweepGradient -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-sweep-gradient",
        )
        is COLRV1Paint.Composite -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-composite-${paint.mode.graphSuffix}",
        )
        is COLRV1Paint.ColrGlyph -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-colr-glyph",
            glyphId = paint.glyphId,
        )
        is COLRV1Paint.Translate -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-translate",
        )
        is COLRV1Paint.Transform -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-transform",
        )
        is COLRV1Paint.Scale -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-scale",
        )
        is COLRV1Paint.ScaleAroundCenter -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-scale-around-center",
        )
        is COLRV1Paint.ScaleUniform -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-scale-uniform",
        )
        is COLRV1Paint.ScaleUniformAroundCenter -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-scale-uniform-around-center",
        )
        is COLRV1Paint.Rotate -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-rotate",
        )
        is COLRV1Paint.RotateAroundCenter -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-rotate-around-center",
        )
        is COLRV1Paint.Skew -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-skew",
        )
        is COLRV1Paint.SkewAroundCenter -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-skew-around-center",
        )
    }
    nodes += baseNode

    val childIds = when (paint) {
        is COLRV1Paint.Solid -> emptyList()
        is COLRV1Paint.Glyph -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.Layers -> paint.paints.map { child -> appendCOLRV1PaintNode(paint = child, nodes = nodes) }
        is COLRV1Paint.LinearGradient -> emptyList()
        is COLRV1Paint.RadialGradient -> emptyList()
        is COLRV1Paint.SweepGradient -> emptyList()
        is COLRV1Paint.Composite -> listOf(
            appendCOLRV1PaintNode(paint = paint.source, nodes = nodes),
            appendCOLRV1PaintNode(paint = paint.backdrop, nodes = nodes),
        )
        is COLRV1Paint.ColrGlyph -> emptyList()
        is COLRV1Paint.Translate -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.Transform -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.Scale -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.ScaleAroundCenter -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.ScaleUniform -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.ScaleUniformAroundCenter -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.Rotate -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.RotateAroundCenter -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.Skew -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.SkewAroundCenter -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
    }
    nodes[id] = baseNode.copy(children = childIds)
    return id
}
