package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretBoundaryEdge
import org.graphiks.kalligraphie.api.CaretCandidate
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineDiagnosticSeverity
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineLayouter
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.GlyphMaterializationCertificate
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutSegment
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LayoutVector
import org.graphiks.kalligraphie.api.PositionedGlyph
import org.graphiks.kalligraphie.api.PositionedGlyphRun
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange

/**
 * Pure portable implementation of [EditableLineLayouter] for one horizontal, non-wrapped line.
 *
 * It keeps shaped runs relative until final placement, accepts only explicit BiDi directions,
 * and exposes no renderer or platform type. Renderable mode borrows the supplied resolver only
 * during [layout], closes every acquired asset before returning, and never retains a handle in
 * the published line.
 */
public object ExactEditableLineLayouter : EditableLineLayouter {
    override fun layout(request: EditableLineRequest): EditableLineResult {
        val diagnostics = mutableListOf<EditableLineDiagnostic>()
        val placements = try {
            positionRuns(request)
        } catch (overflow: GeometryOverflowException) {
            return EditableLineResult.Failure(
                EditableLineError.GeometryOverflow(overflow.message ?: "Editable line geometry overflowed."),
                diagnostics,
            )
        }

        placements.forEach { placement ->
            placement.caretPositions.putAll(resolveInternalLigatureCarets(request, placement, diagnostics))
        }
        val certificates = when (val result = certifyFinalGlyphs(request, placements)) {
            is CertificationResult.Success -> result.certificates
            is CertificationResult.Failure -> return EditableLineResult.Failure(result.error, diagnostics + result.diagnostics)
            is CertificationResult.Cancelled -> return EditableLineResult.Cancelled(diagnostics + result.diagnostics)
        }

        val positionedRuns = placements.map { placement ->
            PositionedGlyphRun(
                sourceRun = placement.sourceRun,
                visualOrder = placement.visualOrder,
                glyphs = placement.glyphs.mapIndexed { glyphIndex, glyph ->
                    PositionedGlyph(
                        shapedGlyph = glyph.shapedGlyph,
                        sourceClusters = glyph.sourceClusters,
                        origin = glyph.origin,
                        advance = glyph.advance,
                        materializationCertificate = certificates[GlyphPosition(placement.visualOrder, glyphIndex)],
                    )
                },
            )
        }
        val candidates = candidates(request, placements)
        return EditableLineResult.Success(
            EditableLine(
                range = request.unicodeAnalysis.range,
                verticalMetrics = request.verticalMetrics,
                positionedGlyphRuns = positionedRuns,
                caretCandidates = candidates,
                diagnostics = diagnostics,
            ),
        )
    }

    private fun positionRuns(request: EditableLineRequest): List<RunPlacement> {
        val visualRuns = visualRuns(request)
        var pen = 0.0
        return visualRuns.mapIndexed { visualOrder, sourceRun ->
            val glyphs = sourceRun.glyphs.map { glyph ->
                val penStart = finiteUnit(pen, "glyph pen")
                val origin = LayoutPoint(
                    x = finiteUnit(pen + glyph.xOffset.value.toDouble(), "glyph horizontal origin"),
                    y = glyph.yOffset,
                )
                val advance = LayoutVector(glyph.xAdvance, glyph.yAdvance)
                pen += glyph.xAdvance.value.toDouble()
                val penEnd = finiteUnit(pen, "glyph end pen")
                GlyphPlacement(
                    shapedGlyph = glyph,
                    sourceClusters = glyph.clusterTokens.map(sourceRun::clusterFor),
                    origin = origin,
                    advance = advance,
                    penStart = penStart,
                    penEnd = penEnd,
                )
            }
            val xStart = glyphs.minOfOrNull { glyph -> minOf(glyph.penStart.value, glyph.penEnd.value) }
                ?.let { value -> LayoutUnit(value) }
                ?: finiteUnit(pen, "empty run origin")
            val xEnd = glyphs.maxOfOrNull { glyph -> maxOf(glyph.penStart.value, glyph.penEnd.value) }
                ?.let { value -> LayoutUnit(value) }
                ?: xStart
            RunPlacement(
                sourceRun = sourceRun,
                visualOrder = visualOrder,
                glyphs = glyphs,
                xStart = xStart,
                xEnd = xEnd,
                caretPositions = endpointCarets(sourceRun, xStart, xEnd),
            )
        }
    }

    private fun visualRuns(request: EditableLineRequest): List<ShapedGlyphRun> {
        val ordered = request.unicodeAnalysis.visualBidiRuns.flatMap { visualBidi ->
            val contained = request.shapedGlyphRuns.filter { run -> containedBy(visualBidi.range, run.range) }
            require(contained.isNotEmpty()) { "Every visual BiDi run must contain a shaped run." }
            if (visualBidi.level % 2 == 0) contained else contained.asReversed()
        }
        require(ordered.size == request.shapedGlyphRuns.size && ordered.toSet() == request.shapedGlyphRuns.toSet()) {
            "Visual BiDi runs must reorder every shaped run exactly once."
        }
        return ordered
    }

    private fun endpointCarets(
        run: ShapedGlyphRun,
        xStart: LayoutUnit,
        xEnd: LayoutUnit,
    ): MutableMap<TextIndex, CaretLocation> = mutableMapOf<TextIndex, CaretLocation>().apply {
        when (run.direction) {
            org.graphiks.kalligraphie.api.ShapingDirection.LEFT_TO_RIGHT -> {
                put(run.range.start, CaretLocation(xStart, CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.LOGICAL_START))
                put(run.range.endExclusive, CaretLocation(xEnd, CaretAffinity.UPSTREAM, CaretBoundaryEdge.LOGICAL_END))
            }

            org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT -> {
                put(run.range.start, CaretLocation(xEnd, CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.LOGICAL_START))
                put(run.range.endExclusive, CaretLocation(xStart, CaretAffinity.UPSTREAM, CaretBoundaryEdge.LOGICAL_END))
            }
        }
    }

    private fun resolveInternalLigatureCarets(
        request: EditableLineRequest,
        placement: RunPlacement,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): Map<TextIndex, CaretLocation> {
        val values = mutableMapOf<TextIndex, CaretLocation>()
        val analysisBoundaries = request.unicodeAnalysis.graphemeClusters
            .flatMap { cluster -> listOf(cluster.start, cluster.endExclusive) }
            .toSet()
        placement.sourceRun.ligatureCaretFacts.forEach { fact ->
            val glyph = placement.glyphs[fact.glyphIndex]
            val boundaries = fact.logicalSourceBoundaries.filter(analysisBoundaries::contains)
            if (boundaries.isEmpty()) return@forEach
            val supplied = fact.takeIf { it.state == GdefLigatureCaretState.AVAILABLE }
                ?.let { availableGdefCarets(placement.sourceRun, glyph, it) }
            if (supplied != null) {
                supplied.forEach { (boundary, x) ->
                    if (boundary in analysisBoundaries) {
                        values[boundary] = CaretLocation(x, CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.INTERNAL)
                    }
                }
            } else {
                if (fact.state != GdefLigatureCaretState.ABSENT) {
                    diagnostics += EditableLineDiagnostic(
                        code = "layout.invalid-ligature-caret-data",
                        severity = EditableLineDiagnosticSeverity.WARNING,
                        message = "Font-provided ligature caret data was inconsistent with the final glyph advance; deterministic interpolation was used.",
                        sourceRange = glyph.sourceClusters.first().sourceRange,
                        glyphId = glyph.shapedGlyph.glyphId,
                    )
                }
                fallbackCarets(placement.sourceRun, listOf(glyph), boundaries).forEach { (boundary, x) ->
                    values[boundary] = CaretLocation(x, CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.INTERNAL)
                }
            }
        }
        placement.sourceRun.clusters.forEach { cluster ->
            val internal = cluster.admissibleGraphemeBoundaries.filter { boundary ->
                boundary in analysisBoundaries &&
                    boundary > cluster.sourceRange.start &&
                    boundary < cluster.sourceRange.endExclusive
            }
            val unresolved = internal.filter { it !in values }
            if (unresolved.isEmpty()) return@forEach
            val relatedGlyphs = placement.glyphs.filter { cluster.token in it.shapedGlyph.clusterTokens }
            fallbackCarets(placement.sourceRun, relatedGlyphs, unresolved).forEach { (boundary, x) ->
                values[boundary] = CaretLocation(x, CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.INTERNAL)
            }
        }
        addClusterBoundaryCarets(placement, analysisBoundaries, values)
        return values
    }

    private fun availableGdefCarets(
        run: ShapedGlyphRun,
        glyph: GlyphPlacement,
        fact: org.graphiks.kalligraphie.api.GdefLigatureCaretFact,
    ): Map<TextIndex, LayoutUnit>? {
        if (fact.positions.size != fact.logicalSourceBoundaries.size) return null
        val advance = glyph.shapedGlyph.xAdvance.value
        if (advance <= 0f) return null
        val strictOrder = fact.positions.zipWithNext().all { (left, right) ->
            when (run.direction) {
                org.graphiks.kalligraphie.api.ShapingDirection.LEFT_TO_RIGHT -> left < right
                org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT -> left > right
            }
        }
        if (!strictOrder || fact.positions.any { it.value <= 0f || it.value >= advance }) return null
        return fact.logicalSourceBoundaries.zip(fact.positions).associate { (boundary, position) ->
            boundary to finiteUnit(glyph.origin.x.value.toDouble() + position.value.toDouble(), "GDEF caret")
        }
    }

    private fun fallbackCarets(
        run: ShapedGlyphRun,
        relatedGlyphs: List<GlyphPlacement>,
        boundaries: List<TextIndex>,
    ): List<Pair<TextIndex, LayoutUnit>> {
        val left = relatedGlyphs.minOfOrNull { glyph -> minOf(glyph.penStart.value, glyph.penEnd.value) } ?: return emptyList()
        val right = relatedGlyphs.maxOfOrNull { glyph -> maxOf(glyph.penStart.value, glyph.penEnd.value) } ?: return emptyList()
        val width = right.toDouble() - left.toDouble()
        return boundaries.mapIndexed { index, boundary ->
            val fraction = (index + 1).toDouble() / (boundaries.size + 1).toDouble()
            val coordinate = when (run.direction) {
                org.graphiks.kalligraphie.api.ShapingDirection.LEFT_TO_RIGHT -> left.toDouble() + width * fraction
                org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT -> right.toDouble() - width * fraction
            }
            boundary to finiteUnit(coordinate, "interpolated ligature caret")
        }
    }

    private fun addClusterBoundaryCarets(
        placement: RunPlacement,
        analysisBoundaries: Set<TextIndex>,
        values: MutableMap<TextIndex, CaretLocation>,
    ) {
        val clusters = placement.sourceRun.clusters
        clusters.zipWithNext().forEach { (before, after) ->
            val boundary = before.sourceRange.endExclusive
            if (boundary != after.sourceRange.start || boundary !in analysisBoundaries || boundary in values) return@forEach
            val beforeGlyphs = placement.glyphs.filter { before.token in it.shapedGlyph.clusterTokens }
            val afterGlyphs = placement.glyphs.filter { after.token in it.shapedGlyph.clusterTokens }
            val coordinate = when (placement.sourceRun.direction) {
                org.graphiks.kalligraphie.api.ShapingDirection.LEFT_TO_RIGHT -> beforeGlyphs.maxOfOrNull { it.penEnd.value }
                    ?: afterGlyphs.minOfOrNull { it.penStart.value }

                org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT -> beforeGlyphs.minOfOrNull { it.penStart.value }
                    ?: afterGlyphs.maxOfOrNull { it.penEnd.value }
            } ?: return@forEach
            values[boundary] = CaretLocation(LayoutUnit(coordinate), CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.INTERNAL)
        }
    }

    private fun certifyFinalGlyphs(
        request: EditableLineRequest,
        placements: List<RunPlacement>,
    ): CertificationResult {
        return when (val materialization = request.materialization) {
            EditableLineMaterialization.LayoutOnly -> CertificationResult.Success(emptyMap())
            is EditableLineMaterialization.Renderable -> {
                if (request.cancellationToken.isCancellationRequested()) return CertificationResult.Cancelled(emptyList())
            when (
                val acquired = request.font.acquireRenderAsset(
                    resolver = materialization.resolver,
                    variant = materialization.variant,
                    requirements = FontAccessRequirementsSnapshot.renderable(materialization.outlineProfile),
                )
            ) {
                is FontOperationResult.Success -> certifyWithAsset(request, placements, acquired.value, materialization)
                is FontOperationResult.Failure -> CertificationResult.Failure(
                    EditableLineError.FontMaterializationFailure(acquired.error),
                    acquired.diagnostics.map(::fontDiagnostic),
                )

                is FontOperationResult.Cancelled -> CertificationResult.Cancelled(acquired.diagnostics.map(::fontDiagnostic))
            }
            }
        }
    }

    private fun certifyWithAsset(
        request: EditableLineRequest,
        placements: List<RunPlacement>,
        asset: FontRenderAssetHandle,
        materialization: EditableLineMaterialization.Renderable,
    ): CertificationResult {
        var result: CertificationResult = CertificationResult.Success(emptyMap())
        try {
            val certificates = mutableMapOf<GlyphPosition, GlyphMaterializationCertificate>()
            placements.forEach { placement ->
                placement.glyphs.forEachIndexed { glyphIndex, glyph ->
                    when (val representation = asset.resolveGlyph(org.graphiks.kalligraphie.api.FontGlyphRequest(glyph.shapedGlyph.glyphId), request.cancellationToken)) {
                        is FontOperationResult.Success -> {
                            val route = when (representation.value) {
                                GlyphRepresentation.Empty -> GlyphMaterializationRoute.EMPTY
                                is GlyphRepresentation.Outline -> GlyphMaterializationRoute.OUTLINE
                            }
                            certificates[GlyphPosition(placement.visualOrder, glyphIndex)] = GlyphMaterializationCertificate(
                                fontInstanceKey = request.font.key,
                                glyphId = glyph.shapedGlyph.glyphId,
                                variant = materialization.variant,
                                outlineProfile = materialization.outlineProfile,
                                route = route,
                            )
                        }

                        is FontOperationResult.Failure -> {
                            result = CertificationResult.Failure(
                                EditableLineError.FontMaterializationFailure(representation.error),
                                representation.diagnostics.map(::fontDiagnostic),
                            )
                            return@forEachIndexed
                        }

                        is FontOperationResult.Cancelled -> {
                            result = CertificationResult.Cancelled(representation.diagnostics.map(::fontDiagnostic))
                            return@forEachIndexed
                        }
                    }
                }
                if (result !is CertificationResult.Success) return@forEach
            }
            if (result is CertificationResult.Success) result = CertificationResult.Success(certificates)
        } finally {
            when (val close = asset.close()) {
                is FontOperationResult.Failure -> if (result is CertificationResult.Success) {
                    result = CertificationResult.Failure(
                        EditableLineError.FontMaterializationFailure(close.error),
                        close.diagnostics.map(::fontDiagnostic),
                    )
                }

                is FontOperationResult.Cancelled -> if (result is CertificationResult.Success) {
                    result = CertificationResult.Cancelled(close.diagnostics.map(::fontDiagnostic))
                }

                is FontOperationResult.Success -> Unit
            }
        }
        return result
    }

    private fun candidates(request: EditableLineRequest, placements: List<RunPlacement>): List<CaretCandidate> {
        val top = LayoutUnit(-request.verticalMetrics.ascent.value)
        val bottom = request.verticalMetrics.descent
        val drafts = if (placements.isEmpty()) {
            listOf(
                CandidateDraft(
                    index = request.unicodeAnalysis.range.start,
                    affinity = CaretAffinity.DOWNSTREAM,
                    x = LayoutUnit(0f),
                    visualRunOrder = 0,
                    bidiLevel = checkNotNull(request.emptyLineBidiLevel),
                    direction = checkNotNull(request.emptyLineDirection),
                    edge = CaretBoundaryEdge.LOGICAL_START,
                ),
                CandidateDraft(
                    index = request.unicodeAnalysis.range.endExclusive,
                    affinity = CaretAffinity.UPSTREAM,
                    x = LayoutUnit(0f),
                    visualRunOrder = 0,
                    bidiLevel = checkNotNull(request.emptyLineBidiLevel),
                    direction = checkNotNull(request.emptyLineDirection),
                    edge = CaretBoundaryEdge.LOGICAL_END,
                ),
            )
        } else {
            placements.flatMap { placement ->
                placement.caretPositions.map { (index, location) ->
                    CandidateDraft(
                        index = index,
                        affinity = location.affinity,
                        x = location.x,
                        visualRunOrder = placement.visualOrder,
                        bidiLevel = placement.sourceRun.bidiLevel,
                        direction = placement.sourceRun.direction,
                        edge = location.edge,
                    )
                }
            }
        }
        return drafts
            .sortedWith(
                compareBy<CandidateDraft> { it.x.value }
                    .thenBy { it.visualRunOrder }
                    .thenComparator { left, right -> left.index.compareTo(right.index) }
                    .thenBy { if (it.affinity == CaretAffinity.DOWNSTREAM) 0 else 1 },
            )
            .mapIndexed { visualOrder, draft ->
                CaretCandidate(
                    position = CaretPosition(draft.index, draft.affinity),
                    geometry = LayoutSegment(LayoutPoint(draft.x, top), LayoutPoint(draft.x, bottom)),
                    visualOrder = visualOrder,
                    visualRunOrder = draft.visualRunOrder,
                    bidiLevel = draft.bidiLevel,
                    direction = draft.direction,
                    edge = draft.edge,
                )
            }
    }
}

private sealed interface CertificationResult {
    data class Success(val certificates: Map<GlyphPosition, GlyphMaterializationCertificate>) : CertificationResult
    data class Failure(val error: EditableLineError, val diagnostics: List<EditableLineDiagnostic>) : CertificationResult
    data class Cancelled(val diagnostics: List<EditableLineDiagnostic>) : CertificationResult
}

private data class GlyphPosition(
    val visualRunOrder: Int,
    val glyphIndex: Int,
)

private class RunPlacement(
    val sourceRun: ShapedGlyphRun,
    val visualOrder: Int,
    val glyphs: List<GlyphPlacement>,
    val xStart: LayoutUnit,
    val xEnd: LayoutUnit,
    val caretPositions: MutableMap<TextIndex, CaretLocation>,
)

private data class GlyphPlacement(
    val shapedGlyph: ShapedGlyph,
    val sourceClusters: List<ShaperCluster>,
    val origin: LayoutPoint,
    val advance: LayoutVector,
    val penStart: LayoutUnit,
    val penEnd: LayoutUnit,
)

private data class CaretLocation(
    val x: LayoutUnit,
    val affinity: CaretAffinity,
    val edge: CaretBoundaryEdge,
)

private data class CandidateDraft(
    val index: TextIndex,
    val affinity: CaretAffinity,
    val x: LayoutUnit,
    val visualRunOrder: Int,
    val bidiLevel: Int,
    val direction: org.graphiks.kalligraphie.api.ShapingDirection,
    val edge: CaretBoundaryEdge,
)

private class GeometryOverflowException(message: String) : IllegalStateException(message)

private fun finiteUnit(value: Double, label: String): LayoutUnit {
    val narrowed = value.toFloat()
    if (!value.isFinite() || !narrowed.isFinite()) throw GeometryOverflowException("$label cannot be represented as a finite layout unit.")
    return LayoutUnit(narrowed)
}

private fun ShapedGlyphRun.clusterFor(token: org.graphiks.kalligraphie.api.ShaperClusterToken): ShaperCluster =
    clusters.firstOrNull { it.token == token }
        ?: throw IllegalArgumentException("A shaped glyph referenced an undeclared cluster token.")

private fun containedBy(owner: TextRange, item: TextRange): Boolean =
    item.start.sharesVersionWith(owner.start) && item.start >= owner.start && item.endExclusive <= owner.endExclusive

private fun fontDiagnostic(diagnostic: org.graphiks.kalligraphie.api.FontDiagnostic): EditableLineDiagnostic =
    EditableLineDiagnostic(
        code = diagnostic.code,
        severity = if (diagnostic.severity == org.graphiks.kalligraphie.api.FontDiagnosticSeverity.ERROR) {
            EditableLineDiagnosticSeverity.ERROR
        } else {
            EditableLineDiagnosticSeverity.WARNING
        },
        message = diagnostic.message,
    )
