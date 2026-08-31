package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretBoundaryEdge
import org.graphiks.kalligraphie.api.CaretCandidate
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.CaretStrength
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
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.GlyphMaterializationCertificate
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutSegment
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LayoutVector
import org.graphiks.kalligraphie.api.MultiFontEditableLineRequest
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
    /** Returns the deterministic physical advance of an already finalized line. */
    internal fun inlineAdvance(line: EditableLine): LayoutUnit {
        val advance = line.positionedGlyphRuns.sumOf { run ->
            run.glyphs.sumOf { glyph -> glyph.advance.x.value.toDouble() }
        }
        return finiteUnit(advance, "line inline advance")
    }

    /**
     * Resolves and positions one line from a captured catalogue and deterministic policy.
     *
     * The fallback resolver shapes every atomic Unicode unit with exactly one selected face and
     * validates final outline routes before this method publishes an [EditableLineResult]. The
     * supplied resolver remains borrowed by the caller; all temporary assets are closed before
     * this method returns. Failure and cancellation never publish a partial line.
     */
    public fun layout(request: MultiFontEditableLineRequest): EditableLineResult {
        return when (val resolved = FontFallbackResolver.resolve(request)) {
            is FontOperationResult.Success -> {
                when (
                    val positioned = layout(
                        EditableLineRequest(
                            unicodeAnalysis = request.unicodeAnalysis,
                            shapedGlyphRuns = resolved.value.shapedRuns,
                            baseDirection = when (request.baseDirection) {
                                org.graphiks.kalligraphie.api.BaseDirection.LEFT_TO_RIGHT -> org.graphiks.kalligraphie.api.ShapingDirection.LEFT_TO_RIGHT
                                org.graphiks.kalligraphie.api.BaseDirection.RIGHT_TO_LEFT -> org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT
                            },
                            emptyLineBidiLevel = if (resolved.value.shapedRuns.isEmpty()) {
                                when (request.baseDirection) {
                                    org.graphiks.kalligraphie.api.BaseDirection.LEFT_TO_RIGHT -> 0
                                    org.graphiks.kalligraphie.api.BaseDirection.RIGHT_TO_LEFT -> 1
                                }
                            } else {
                                null
                            },
                            font = resolved.value.instances.firstOrNull(),
                            fontInstances = resolved.value.instances,
                            verticalMetrics = request.verticalMetrics,
                            materialization = request.materialization,
                            cancellationToken = request.cancellationToken,
                        ),
                    )
                ) {
                    is EditableLineResult.Success -> EditableLineResult.Success(
                        EditableLine(
                            range = positioned.line.range,
                            baseDirection = positioned.line.baseDirection,
                            verticalMetrics = positioned.line.verticalMetrics,
                            positionedGlyphRuns = positioned.line.positionedGlyphRuns,
                            caretCandidates = positioned.line.allCaretCandidates,
                            diagnostics = positioned.line.diagnostics + resolved.value.diagnostics.map(::fontDiagnostic),
                        ),
                    )

                    is EditableLineResult.Failure -> positioned
                    is EditableLineResult.Cancelled -> positioned
                }
            }

            is FontOperationResult.Failure -> EditableLineResult.Failure(
                EditableLineError.FontResolutionFailure(resolved.error),
                resolved.diagnostics.map(::fontDiagnostic),
            )

            is FontOperationResult.Cancelled -> EditableLineResult.Cancelled(resolved.diagnostics.map(::fontDiagnostic))
        }
    }

    /**
     * Produces one immutable editable line from already analyzed and shaped input.
     *
     * The request must provide a complete compatible Unicode, BiDi, and shaping partition; its
     * validation failures are reported by [EditableLineRequest] before this method runs. Finite
     * geometry overflow and render-asset failures become typed [EditableLineResult] failures.
     * This singleton retains no request resource and is safe for concurrent calls; renderable
     * mode borrows and closes its asset before returning.
     */
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
        val certification = when (val result = certifyFinalGlyphs(request, placements)) {
            is CertificationResult.Success -> result
            is CertificationResult.Failure -> return EditableLineResult.Failure(result.error, diagnostics + result.diagnostics)
            is CertificationResult.Cancelled -> return EditableLineResult.Cancelled(diagnostics + result.diagnostics)
        }

        val positionedRuns = placements.map { placement ->
            PositionedGlyphRun(
                sourceRun = placement.sourceRun,
                visualOrder = placement.visualOrder,
                renderAssetKey = certification.assetKeys[placement.visualOrder],
                glyphs = placement.glyphs.mapIndexed { glyphIndex, glyph ->
                    PositionedGlyph(
                        shapedGlyph = glyph.shapedGlyph,
                        sourceClusters = glyph.sourceClusters,
                        origin = glyph.origin,
                        advance = glyph.advance,
                        renderAssetKey = certification.assetKeys[placement.visualOrder],
                        materializationCertificate = certification.certificates[GlyphPosition(placement.visualOrder, glyphIndex)],
                    )
                },
            )
        }
        val candidates = candidates(request, placements)
        return EditableLineResult.Success(
            EditableLine(
                range = request.unicodeAnalysis.range,
                baseDirection = request.baseDirection,
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
            val initialPen = finiteUnit(pen, "run initial pen")
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
            val finalPen = finiteUnit(pen, "run final pen")
            RunPlacement(
                sourceRun = sourceRun,
                visualOrder = visualOrder,
                glyphs = glyphs,
                xStart = initialPen,
                xEnd = finalPen,
                caretPositions = endpointCarets(sourceRun, initialPen, finalPen),
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
            val supplied = fact.takeIf { it.state == GdefLigatureCaretState.AVAILABLE }
                ?.let { availableGdefCarets(placement.sourceRun, glyph, it) }
            if (supplied != null) {
                supplied.forEach { (boundary, x) ->
                    values[boundary] = CaretLocation(x, CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.INTERNAL)
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
        if (advance == 0f) return null
        val logicalDelta = when (run.direction) {
            org.graphiks.kalligraphie.api.ShapingDirection.LEFT_TO_RIGHT -> advance
            org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT -> -advance
        }
        val strictOrder = fact.positions.zipWithNext().all { (left, right) ->
            (right.value - left.value) * logicalDelta > 0f
        }
        val lower = minOf(0f, advance)
        val upper = maxOf(0f, advance)
        if (!strictOrder || fact.positions.any { it.value <= lower || it.value >= upper }) return null
        return fact.logicalSourceBoundaries.zip(fact.positions).associate { (boundary, position) ->
            boundary to finiteUnit(glyph.origin.x.value.toDouble() + position.value.toDouble(), "GDEF caret")
        }
    }

    private fun fallbackCarets(
        run: ShapedGlyphRun,
        relatedGlyphs: List<GlyphPlacement>,
        boundaries: List<TextIndex>,
    ): List<Pair<TextIndex, LayoutUnit>> {
        val pathStart = relatedGlyphs.firstOrNull()?.penStart?.value?.toDouble() ?: return emptyList()
        val pathEnd = relatedGlyphs.last().penEnd.value.toDouble()
        return boundaries.mapIndexed { index, boundary ->
            val fraction = (index + 1).toDouble() / (boundaries.size + 1).toDouble()
            val coordinate = when (run.direction) {
                org.graphiks.kalligraphie.api.ShapingDirection.LEFT_TO_RIGHT -> pathStart + (pathEnd - pathStart) * fraction
                org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT -> pathEnd + (pathStart - pathEnd) * fraction
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
                org.graphiks.kalligraphie.api.ShapingDirection.LEFT_TO_RIGHT -> beforeGlyphs.lastOrNull()?.penEnd?.value
                    ?: afterGlyphs.firstOrNull()?.penStart?.value

                org.graphiks.kalligraphie.api.ShapingDirection.RIGHT_TO_LEFT -> beforeGlyphs.firstOrNull()?.penStart?.value
                    ?: afterGlyphs.lastOrNull()?.penEnd?.value
            } ?: return@forEach
            values[boundary] = CaretLocation(LayoutUnit(coordinate), CaretAffinity.DOWNSTREAM, CaretBoundaryEdge.INTERNAL)
        }
    }

    private fun certifyFinalGlyphs(
        request: EditableLineRequest,
        placements: List<RunPlacement>,
    ): CertificationResult {
        return when (val materialization = request.materialization) {
            EditableLineMaterialization.LayoutOnly -> CertificationResult.Success(emptyMap(), emptyMap())
            is EditableLineMaterialization.Renderable -> {
                if (request.cancellationToken.isCancellationRequested()) return CertificationResult.Cancelled(emptyList())
                val assetKeys = mutableMapOf<Int, FontRenderAssetKey>()
                val certificates = mutableMapOf<GlyphPosition, GlyphMaterializationCertificate>()
                placements.forEach { placement ->
                    val instance = request.fontInstances.single { it.key == placement.sourceRun.fontInstanceKey }
                    when (
                        val acquired = instance.acquireRenderAsset(
                            resolver = materialization.resolver,
                            variant = materialization.variant,
                            requirements = FontAccessRequirementsSnapshot.renderable(materialization.outlineProfile),
                        )
                    ) {
                        is FontOperationResult.Success -> when (
                            val certified = certifyWithAsset(request, listOf(placement), instance, acquired.value, materialization)
                        ) {
                            is CertificationResult.Success -> {
                                assetKeys.putAll(certified.assetKeys)
                                certificates.putAll(certified.certificates)
                            }

                            is CertificationResult.Failure -> return certified
                            is CertificationResult.Cancelled -> return certified
                        }

                        is FontOperationResult.Failure -> return CertificationResult.Failure(
                            EditableLineError.FontMaterializationFailure(acquired.error),
                            acquired.diagnostics.map(::fontDiagnostic),
                        )

                        is FontOperationResult.Cancelled -> return CertificationResult.Cancelled(acquired.diagnostics.map(::fontDiagnostic))
                    }
                }
                CertificationResult.Success(assetKeys, certificates)
            }
        }
    }

    private fun certifyWithAsset(
        request: EditableLineRequest,
        placements: List<RunPlacement>,
        instance: org.graphiks.kalligraphie.api.FontInstance,
        asset: FontRenderAssetHandle,
        materialization: EditableLineMaterialization.Renderable,
    ): CertificationResult {
        val expectedAssetKey = FontRenderAssetKey(
            fontInstanceKey = instance.key,
            variant = materialization.variant,
            outlineProfile = materialization.outlineProfile,
            generation = materialization.resolver.generation,
        )
        var result: CertificationResult = if (asset.key == expectedAssetKey) {
            CertificationResult.Success(emptyMap(), emptyMap())
        } else {
            CertificationResult.Failure(
                EditableLineError.FontMaterializationFailure(
                    org.graphiks.kalligraphie.api.FontError.InvalidFontData(
                        "Acquired render asset key does not match the requested font instance, variant, and outline profile.",
                    ),
                ),
                emptyList(),
            )
        }
        try {
            val certificates = mutableMapOf<GlyphPosition, GlyphMaterializationCertificate>()
            certification@ for (placement in placements) {
                if (result !is CertificationResult.Success) break
                for ((glyphIndex, glyph) in placement.glyphs.withIndex()) {
                    when (val representation = asset.resolveGlyph(org.graphiks.kalligraphie.api.FontGlyphRequest(glyph.shapedGlyph.glyphId), request.cancellationToken)) {
                        is FontOperationResult.Success -> {
                            val resolvedGlyph = representation.value
                            val route = when (resolvedGlyph) {
                                GlyphRepresentation.Empty -> GlyphMaterializationRoute.EMPTY
                                is GlyphRepresentation.Outline -> {
                                    if (resolvedGlyph.outline.glyphId != glyph.shapedGlyph.glyphId.value) {
                                        result = CertificationResult.Failure(
                                            EditableLineError.FontMaterializationFailure(
                                                org.graphiks.kalligraphie.api.FontError.InvalidFontData(
                                                    "Resolved outline glyph identifier does not match the requested final glyph.",
                                                ),
                                            ),
                                            emptyList(),
                                        )
                                        break@certification
                                    }
                                    GlyphMaterializationRoute.OUTLINE
                                }
                            }
                            certificates[GlyphPosition(placement.visualOrder, glyphIndex)] = GlyphMaterializationCertificate(
                                assetKey = asset.key,
                                glyphId = glyph.shapedGlyph.glyphId,
                                route = route,
                            )
                        }

                        is FontOperationResult.Failure -> {
                            result = CertificationResult.Failure(
                                EditableLineError.FontMaterializationFailure(representation.error),
                                representation.diagnostics.map(::fontDiagnostic),
                            )
                            break@certification
                        }

                        is FontOperationResult.Cancelled -> {
                            result = CertificationResult.Cancelled(representation.diagnostics.map(::fontDiagnostic))
                            break@certification
                        }
                    }
                }
            }
            if (result is CertificationResult.Success) {
                result = CertificationResult.Success(
                    placements.associate { it.visualOrder to asset.key },
                    certificates,
                )
            }
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
                    visualRunOrder = CaretCandidate.NO_POSITIONED_RUN,
                    bidiLevel = checkNotNull(request.emptyLineBidiLevel),
                    direction = request.baseDirection,
                    edge = CaretBoundaryEdge.LOGICAL_START,
                ),
                CandidateDraft(
                    index = request.unicodeAnalysis.range.endExclusive,
                    affinity = CaretAffinity.UPSTREAM,
                    x = LayoutUnit(0f),
                    visualRunOrder = CaretCandidate.NO_POSITIONED_RUN,
                    bidiLevel = checkNotNull(request.emptyLineBidiLevel),
                    direction = request.baseDirection,
                    edge = CaretBoundaryEdge.LOGICAL_END,
                ),
            )
        } else {
            val legalBoundaries = request.unicodeAnalysis.graphemeClusters
                .flatMap { cluster -> listOf(cluster.start, cluster.endExclusive) }
                .toSet()
            placements.flatMap { placement ->
                placement.caretPositions.filter { (index, _) -> index in legalBoundaries }.map { (index, location) ->
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
                    strength = if (draft.direction == request.baseDirection) CaretStrength.STRONG else CaretStrength.WEAK,
                    edge = draft.edge,
                )
            }
    }
}

private sealed interface CertificationResult {
    data class Success(
        val assetKeys: Map<Int, org.graphiks.kalligraphie.api.FontRenderAssetKey>,
        val certificates: Map<GlyphPosition, GlyphMaterializationCertificate>,
    ) : CertificationResult
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
