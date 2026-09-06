package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.CaretCandidate
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowParagraphLayouter
import org.graphiks.kalligraphie.api.FlowRegion
import org.graphiks.kalligraphie.api.FlowRegionResult
import org.graphiks.kalligraphie.api.InlineInterval
import org.graphiks.kalligraphie.api.LayoutBounds
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutSegment
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBand
import org.graphiks.kalligraphie.api.LineFragment
import org.graphiks.kalligraphie.api.NoProgressReason
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphFragment
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.PositionedGlyph
import org.graphiks.kalligraphie.api.PositionedGlyphRun
import org.graphiks.kalligraphie.api.PositionedInlineObject
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.api.queryFlowRegion

/**
 * Pure flow-region adapter over the exact paragraph line finalizer.
 *
 * A call selects and finalizes one logical line against the sum of the region's stable logical
 * intervals. Fragment boundaries only translate and split already-positioned visual content at
 * existing shaping-cluster boundaries; they never restart shaping or UAX #9 resolution.
 */
public object FlowParagraphComposer : FlowParagraphLayouter {
    private const val IMPLEMENTATION_REFINEMENT_LIMIT: Int = 32

    override fun layoutLine(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        region: FlowRegion,
        blockStart: Float,
    ): FlowCompositionResult<ParagraphFragment> {
        if (request.continuation != null) {
            return paragraphFailure("Line-level flow composition does not consume paragraph continuations.")
        }
        if (request.cancellationToken.isCancellationRequested()) {
            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        var currentBlockStart = blockStart
        var bandExtent = request.constraints.lineMetrics.height.value
        var previousBandExtent: Float? = null
        var previousIntervals: List<InlineInterval>? = null
        var refinements = 0
        val seen = mutableSetOf<RefinementFingerprint>()

        while (true) {
            if (refinements >= minOf(region.maximumRefinements, IMPLEMENTATION_REFINEMENT_LIMIT)) {
                return nonConvergent("The flow region exceeded its bounded line-band refinement count.")
            }
            refinements += 1
            val band = LineBand(currentBlockStart, bandExtent)
            val queried = stableQuery(region, request.constraints.writingMode, band)
            val regionResult = when (queried) {
                is FlowCompositionResult.Success -> queried.value
                is FlowCompositionResult.Failure -> return queried
            }
            when (regionResult) {
                is FlowRegionResult.Empty -> {
                    currentBlockStart = regionResult.nextBlockOffset
                    previousBandExtent = null
                    previousIntervals = null
                    bandExtent = request.constraints.lineMetrics.height.value
                    seen.clear()
                    continue
                }

                FlowRegionResult.EndOfRegion -> return noSpaceForFirstUnit(request)
                is FlowRegionResult.AvailableIntervals -> {
                    val intervals = regionResult.intervals
                    if (previousIntervals != null && !intervals.areSubsetOf(previousIntervals)) {
                        return nonConvergent(
                            "A growing line band regained logical inline space after it had been excluded.",
                        )
                    }
                    val totalInlineExtent = intervals.sumOf { interval ->
                        interval.endExclusive.toDouble() - interval.start.toDouble()
                    }
                    if (!totalInlineExtent.isFinite() || totalInlineExtent <= 0.0 || !totalInlineExtent.toFloat().isFinite()) {
                        return geometryOverflow("The total flow inline extent overflowed finite layout coordinates.")
                    }
                    val candidate = when (
                        val composed = ParagraphComposer.compose(
                            request.withSingleLineExtent(totalInlineExtent.toFloat()),
                            materialization,
                        )
                    ) {
                        is ParagraphCompositionResult.Success -> composed.lines.singleOrNull()
                            ?: return paragraphFailure("Flow line composition did not produce exactly one complete candidate line.")

                        is ParagraphCompositionResult.Failure -> return FlowCompositionResult.Failure(
                            FlowCompositionError.ParagraphFailure(composed.error.toParagraphError()),
                        )

                        is ParagraphCompositionResult.Cancelled ->
                            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
                    }
                    if (candidate.line.range != request.sourceRange) {
                        return paragraphFailure(
                            "Line-level flow composition requires the request range to fit one complete logical line.",
                        )
                    }
                    val measured = when (
                        val projection = ParagraphComposer.projectLine(candidate, request.cancellationToken)
                    ) {
                        is ParagraphComposer.ProjectedLine.Success -> projection.line
                        is ParagraphComposer.ProjectedLine.Failure -> return FlowCompositionResult.Failure(
                            FlowCompositionError.ParagraphFailure(projection.error),
                        )

                        is ParagraphComposer.ProjectedLine.Cancelled ->
                            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
                    }
                    val requiredBandExtent = requiredBlockExtent(candidate.line, measured.designInkBounds, measured.baseline)
                    if (!requiredBandExtent.isFinite()) {
                        return geometryOverflow("The refined flow line band overflowed finite layout coordinates.")
                    }
                    if (previousBandExtent != null && requiredBandExtent < previousBandExtent) {
                        return nonConvergent("A refined candidate attempted to shrink its established line band.")
                    }
                    val fingerprint = RefinementFingerprint(
                        bandExtent = bandExtent,
                        intervals = intervals.map { it.start to it.endExclusive },
                        lineRange = candidate.line.range,
                    )
                    if (requiredBandExtent > bandExtent) {
                        if (!seen.add(fingerprint)) {
                            return nonConvergent("The flow region entered a repeated line-band refinement cycle.")
                        }
                        previousBandExtent = requiredBandExtent
                        previousIntervals = intervals
                        bandExtent = requiredBandExtent
                        continue
                    }
                    return publish(request, region, currentBlockStart, intervals, candidate)
                }
            }
        }
    }

    private fun stableQuery(
        region: FlowRegion,
        writingMode: WritingMode,
        band: LineBand,
    ): FlowCompositionResult<FlowRegionResult> {
        val first = safeQuery(region, writingMode, band)
        if (first is FlowCompositionResult.Failure) return first
        val second = safeQuery(region, writingMode, band)
        if (second is FlowCompositionResult.Failure) return second
        val firstValue = (first as FlowCompositionResult.Success).value
        val secondValue = (second as FlowCompositionResult.Success).value
        return if (firstValue.sameFlowAnswerAs(secondValue)) {
            FlowCompositionResult.Success(firstValue)
        } else {
            nonConvergent("A flow region returned different answers for identical line-band input.")
        }
    }

    private fun safeQuery(
        region: FlowRegion,
        writingMode: WritingMode,
        band: LineBand,
    ): FlowCompositionResult<FlowRegionResult> = try {
        queryFlowRegion(region, writingMode, band)
    } catch (failure: RuntimeException) {
        nonConvergent("The flow region threw while evaluating a line band: ${failure::class.simpleName}.")
    }

    private fun publish(
        request: ParagraphLayoutRequest,
        region: FlowRegion,
        blockStart: Float,
        intervals: List<InlineInterval>,
        candidate: ComposedParagraphLine,
    ): FlowCompositionResult<ParagraphFragment> {
        val placed = try {
            candidate.atFlowPosition(region.bounds, blockStart)
        } catch (overflow: IllegalArgumentException) {
            return geometryOverflow("The final flow line position overflowed finite layout coordinates.")
        }
        val projectedFragments = when (val result = fragmentLine(candidate.line, placed.baseline, intervals, request.constraints.writingMode)) {
            is FragmentProjection.Success -> result.fragments
            is FragmentProjection.Failure -> return FlowCompositionResult.Failure(result.error)
        }
        val projected = try {
            ParagraphComposer.projectLine(placed, request.cancellationToken, projectedFragments)
        } catch (overflow: ParagraphGeometryOverflowException) {
            return geometryOverflow(overflow.message ?: "Final flow geometry overflowed.")
        }
        return when (projected) {
            is ParagraphComposer.ProjectedLine.Success -> FlowCompositionResult.Success(
                ParagraphFragment(
                    paragraphRange = request.sourceRange,
                    laidOutRange = request.sourceRange,
                    isFirstFragment = true,
                    isLastFragment = true,
                    lines = listOf(projected.line),
                ),
            )

            is ParagraphComposer.ProjectedLine.Failure -> when (val error = projected.error) {
                is ParagraphLayoutError.GeometryOverflow -> geometryOverflow(error.message)
                else -> FlowCompositionResult.Failure(FlowCompositionError.ParagraphFailure(error))
            }

            is ParagraphComposer.ProjectedLine.Cancelled ->
                FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
    }

    private fun fragmentLine(
        line: EditableLine,
        baseline: LayoutPoint,
        intervals: List<InlineInterval>,
        writingMode: WritingMode,
    ): FragmentProjection {
        val glyphsByFragment = intervals.indices.map { mutableListOf<AllocatedGlyph>() }
        val allocations = mutableListOf<Allocation>()
        var fragmentIndex = 0
        var cursor = intervals.first().start.toDouble()
        var precedingEnd = 0.0

        line.positionedGlyphRuns.forEach { run ->
            run.atomicGlyphGroups().forEach { group ->
                val originalStart = group.first().penStart(writingMode)
                val width = group.sumOf { glyph -> glyph.inlineAdvance(writingMode) }
                val leading = (originalStart - precedingEnd).coerceAtLeast(0.0)
                if (leading > 0.0) {
                    val advanced = advanceWhitespace(intervals, fragmentIndex, cursor, leading)
                    fragmentIndex = advanced.first
                    cursor = advanced.second
                }
                val objectItem = line.positionedInlineObjects.firstOrNull { item ->
                    group.any { glyph -> rangesOverlap(glyph.mappedSourceRange, item.sourceRange) }
                }
                val maximum = intervals.maxOf { interval -> interval.endExclusive.toDouble() - interval.start.toDouble() }
                if (width > maximum) {
                    val range = objectItem?.sourceRange ?: group.sourceRange()
                    return FragmentProjection.Failure(
                        FlowCompositionError.NoProgress(
                            range,
                            if (objectItem == null) NoProgressReason.CLUSTER_DOES_NOT_FIT
                            else NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT,
                        ),
                    )
                }
                while (fragmentIndex < intervals.size && cursor + width > intervals[fragmentIndex].endExclusive.toDouble()) {
                    fragmentIndex += 1
                    if (fragmentIndex < intervals.size) cursor = intervals[fragmentIndex].start.toDouble()
                }
                if (fragmentIndex >= intervals.size) {
                    val range = objectItem?.sourceRange ?: group.sourceRange()
                    return FragmentProjection.Failure(
                        FlowCompositionError.NoProgress(
                            range,
                            if (objectItem == null) NoProgressReason.CLUSTER_DOES_NOT_FIT
                            else NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT,
                        ),
                    )
                }
                val translation = cursor - originalStart
                group.forEach { glyph ->
                    glyphsByFragment[fragmentIndex] += AllocatedGlyph(run, glyph.translatedInline(translation, baseline, writingMode))
                }
                allocations += Allocation(
                    fragmentIndex = fragmentIndex,
                    originalStart = originalStart,
                    originalEnd = originalStart + width,
                    translation = translation,
                    objectRange = objectItem?.sourceRange,
                )
                cursor += width
                precedingEnd = originalStart + width
            }
        }

        if (allocations.isEmpty()) {
            allocations += Allocation(0, 0.0, 0.0, intervals.first().start.toDouble(), null)
        }
        val runs = glyphsByFragment.map { allocated -> allocated.toProjectedRuns() }
        val carets = intervals.indices.map { mutableListOf<CaretCandidate>() }
        line.allCaretCandidates.forEach { candidate ->
            val inline = candidate.inlineCoordinate(writingMode)
            val allocation = allocations.minWith(compareBy<Allocation>({ it.distanceFrom(inline) }, { it.fragmentIndex }))
            carets[allocation.fragmentIndex] += candidate.translatedInline(allocation.translation, baseline, writingMode)
        }
        val objects = intervals.indices.map { mutableListOf<PositionedInlineObject>() }
        line.positionedInlineObjects.forEach { item ->
            val allocation = allocations.firstOrNull { it.objectRange == item.sourceRange }
                ?: return FragmentProjection.Failure(
                    FlowCompositionError.NoProgress(item.sourceRange, NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT),
                )
            objects[allocation.fragmentIndex] += item.translatedInline(allocation.translation, baseline, writingMode)
        }
        return FragmentProjection.Success(intervals.indices.map { index ->
            LineFragment(intervals[index], runs[index], carets[index], objects[index])
        })
    }

    private fun List<AllocatedGlyph>.toProjectedRuns(): List<PositionedGlyphRun> {
        if (isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<AllocatedGlyph>>()
        forEach { item ->
            val previous = groups.lastOrNull()
            if (previous != null && previous.last().sourceRun === item.sourceRun) previous += item
            else groups += mutableListOf(item)
        }
        return groups.map { group ->
            val sourceRun = group.first().sourceRun
            val glyphs = group.map(AllocatedGlyph::glyph)
            PositionedGlyphRun(
                sourceRun = sourceRun.sliceFor(glyphs),
                visualOrder = sourceRun.visualOrder,
                renderAssetKey = sourceRun.renderAssetKey,
                glyphs = glyphs,
            )
        }
    }

    private fun PositionedGlyphRun.sliceFor(glyphs: List<PositionedGlyph>): ShapedGlyphRun {
        val tokens = glyphs.flatMap { glyph -> glyph.shapedGlyph.clusterTokens }.toSet()
        val clusters = sourceRun.clusters.filter { cluster -> cluster.token in tokens }
        val range = TextRange(clusters.first().sourceRange.start, clusters.last().sourceRange.endExclusive)
        val sourceGlyphIndexes = sourceRun.glyphs.indices.filter { index ->
            sourceRun.glyphs[index].clusterTokens.any(tokens::contains)
        }
        val shaped = sourceGlyphIndexes.map(sourceRun.glyphs::get)
        val oldToNew = sourceGlyphIndexes.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }
        val facts = sourceRun.ligatureCaretFacts.mapNotNull { fact ->
            oldToNew[fact.glyphIndex]?.let { newIndex ->
                org.graphiks.kalligraphie.api.GdefLigatureCaretFact(
                    glyphIndex = newIndex,
                    state = fact.state,
                    logicalSourceBoundaries = fact.logicalSourceBoundaries,
                    positions = fact.positions,
                )
            }
        }
        return ShapedGlyphRun(
            range = range,
            fontInstanceKey = sourceRun.fontInstanceKey,
            backendIdentity = sourceRun.backendIdentity,
            direction = sourceRun.direction,
            script = sourceRun.script,
            language = sourceRun.language,
            bidiLevel = sourceRun.bidiLevel,
            bot = sourceRun.bot && range.start == sourceRun.range.start,
            eot = sourceRun.eot && range.endExclusive == sourceRun.range.endExclusive,
            featurePolicy = sourceRun.featurePolicy,
            features = sourceRun.features,
            graphemeClusters = sourceRun.graphemeClusters.filter { cluster ->
                cluster.start >= range.start && cluster.endExclusive <= range.endExclusive
            },
            glyphs = shaped,
            clusters = clusters,
            ligatureCaretFacts = facts,
        )
    }

    private fun PositionedGlyphRun.atomicGlyphGroups(): List<List<PositionedGlyph>> {
        val groups = mutableListOf<MutableList<PositionedGlyph>>()
        val graphemes = mutableListOf<MutableSet<TextRange>>()
        glyphs.forEach { glyph ->
            val related = sourceRun.graphemeClusters.filter { grapheme ->
                glyph.sourceClusters.any { cluster -> rangesOverlap(grapheme, cluster.sourceRange) }
            }.toSet()
            if (groups.isNotEmpty() && graphemes.last().any(related::contains)) {
                groups.last() += glyph
                graphemes.last() += related
            } else {
                groups += mutableListOf(glyph)
                graphemes += related.toMutableSet()
            }
        }
        return groups
    }

    private fun requiredBlockExtent(line: EditableLine, ink: LayoutBounds, baseline: LayoutPoint): Float {
        var before = line.verticalMetrics.ascent.value.toDouble()
        var after = line.verticalMetrics.descent.value.toDouble()
        when (line.writingMode) {
            WritingMode.HORIZONTAL_TB -> {
                before = maxOf(before, baseline.y.value.toDouble() - ink.minY.value.toDouble())
                after = maxOf(after, ink.maxY.value.toDouble() - baseline.y.value.toDouble())
                line.positionedInlineObjects.forEach { item ->
                    before = maxOf(before, -item.rect.top.value.toDouble())
                    after = maxOf(after, item.rect.bottom.value.toDouble())
                }
            }

            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> {
                before = maxOf(before, baseline.x.value.toDouble() - ink.minX.value.toDouble())
                after = maxOf(after, ink.maxX.value.toDouble() - baseline.x.value.toDouble())
                line.positionedInlineObjects.forEach { item ->
                    before = maxOf(before, -item.rect.left.value.toDouble())
                    after = maxOf(after, item.rect.right.value.toDouble())
                }
            }
        }
        return (before + after).toFloat()
    }

    private fun ParagraphLayoutRequest.withSingleLineExtent(inlineExtent: Float): ParagraphLayoutRequest {
        val blockExtent = constraints.lineMetrics.height
        val zero = LayoutUnit(0f)
        val inline = LayoutUnit(inlineExtent)
        val syntheticBounds = when (constraints.writingMode) {
            WritingMode.HORIZONTAL_TB -> LayoutRect(zero, zero, inline, blockExtent)
            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> LayoutRect(zero, zero, blockExtent, inline)
        }
        return ParagraphLayoutRequest(
            snapshot = snapshot,
            sourceRange = sourceRange,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = ParagraphConstraints(syntheticBounds, constraints.lineMetrics, constraints.writingMode),
            baseDirection = baseDirection,
            language = language,
            featurePolicy = featurePolicy,
            features = features,
            fontCatalog = fontCatalog,
            resolutionPolicy = resolutionPolicy,
            fontInstanceDescriptor = fontInstanceDescriptor,
            shapingBackend = shapingBackend,
            materializationIdentity = materializationIdentity,
            overflowPolicy = overflowPolicy,
            positioning = positioning,
            hyphenationMode = hyphenationMode,
            hyphenationService = hyphenationService,
            inlineObjects = inlineObjects,
            textOrientation = textOrientation,
            verticalMetricsPolicy = verticalMetricsPolicy,
            cancellationToken = cancellationToken,
        )
    }

    private fun ComposedParagraphLine.atFlowPosition(bounds: LayoutRect, blockStart: Float): ComposedParagraphLine {
        val metrics = line.verticalMetrics
        return when (line.writingMode) {
            WritingMode.HORIZONTAL_TB -> {
                val top = finite(bounds.top.value.toDouble() + blockStart.toDouble(), "horizontal flow line top")
                val baseline = LayoutPoint(
                    bounds.left,
                    finite(top.value.toDouble() + metrics.ascent.value.toDouble(), "horizontal flow baseline"),
                )
                ComposedParagraphLine(
                    line,
                    baseline,
                    LayoutRect(
                        bounds.left,
                        top,
                        bounds.right,
                        finite(top.value.toDouble() + metrics.height.value.toDouble(), "horizontal flow line bottom"),
                    ),
                    inlineAdvance,
                    fontInstances,
                )
            }

            WritingMode.VERTICAL_RL -> {
                val right = finite(bounds.right.value.toDouble() - blockStart.toDouble(), "vertical-rl flow line right")
                val baseline = LayoutPoint(
                    finite(right.value.toDouble() - metrics.descent.value.toDouble(), "vertical-rl flow baseline"),
                    bounds.top,
                )
                ComposedParagraphLine(
                    line,
                    baseline,
                    LayoutRect(
                        finite(right.value.toDouble() - metrics.height.value.toDouble(), "vertical-rl flow line left"),
                        bounds.top,
                        right,
                        bounds.bottom,
                    ),
                    inlineAdvance,
                    fontInstances,
                )
            }

            WritingMode.VERTICAL_LR -> {
                val left = finite(bounds.left.value.toDouble() + blockStart.toDouble(), "vertical-lr flow line left")
                val baseline = LayoutPoint(
                    finite(left.value.toDouble() + metrics.ascent.value.toDouble(), "vertical-lr flow baseline"),
                    bounds.top,
                )
                ComposedParagraphLine(
                    line,
                    baseline,
                    LayoutRect(
                        left,
                        bounds.top,
                        finite(left.value.toDouble() + metrics.height.value.toDouble(), "vertical-lr flow line right"),
                        bounds.bottom,
                    ),
                    inlineAdvance,
                    fontInstances,
                )
            }
        }
    }

    private fun PositionedGlyph.penStart(writingMode: WritingMode): Double = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> origin.x.value.toDouble() - shapedGlyph.xOffset.value.toDouble()
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> origin.y.value.toDouble() - shapedGlyph.yOffset.value.toDouble()
    }

    private fun PositionedGlyph.inlineAdvance(writingMode: WritingMode): Double = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> advance.x.value.toDouble()
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> advance.y.value.toDouble()
    }

    private fun PositionedGlyph.translatedInline(
        inlineTranslation: Double,
        baseline: LayoutPoint,
        writingMode: WritingMode,
    ): PositionedGlyph {
        val translatedOrigin = when (writingMode) {
            WritingMode.HORIZONTAL_TB -> LayoutPoint(
                finite(origin.x.value.toDouble() + inlineTranslation + baseline.x.value.toDouble(), "fragment glyph x"),
                finite(origin.y.value.toDouble() + baseline.y.value.toDouble(), "fragment glyph y"),
            )

            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> LayoutPoint(
                finite(origin.x.value.toDouble() + baseline.x.value.toDouble(), "vertical fragment glyph x"),
                finite(origin.y.value.toDouble() + inlineTranslation + baseline.y.value.toDouble(), "vertical fragment glyph y"),
            )
        }
        return PositionedGlyph(
            shapedGlyph,
            sourceClusters,
            translatedOrigin,
            advance,
            transform,
            renderAssetKey,
            materializationCertificate,
            provenance,
        )
    }

    private fun CaretCandidate.translatedInline(
        inlineTranslation: Double,
        baseline: LayoutPoint,
        writingMode: WritingMode,
    ): CaretCandidate {
        fun point(value: LayoutPoint): LayoutPoint = when (writingMode) {
            WritingMode.HORIZONTAL_TB -> LayoutPoint(
                finite(value.x.value.toDouble() + inlineTranslation + baseline.x.value.toDouble(), "fragment caret x"),
                finite(value.y.value.toDouble() + baseline.y.value.toDouble(), "fragment caret y"),
            )

            WritingMode.VERTICAL_RL,
            WritingMode.VERTICAL_LR,
            -> LayoutPoint(
                finite(value.x.value.toDouble() + baseline.x.value.toDouble(), "vertical fragment caret x"),
                finite(value.y.value.toDouble() + inlineTranslation + baseline.y.value.toDouble(), "vertical fragment caret y"),
            )
        }
        return CaretCandidate(position, LayoutSegment(point(geometry.start), point(geometry.end)), visualOrder,
            visualRunOrder, bidiLevel, direction, strength, edge)
    }

    private fun PositionedInlineObject.translatedInline(
        inlineTranslation: Double,
        baseline: LayoutPoint,
        writingMode: WritingMode,
    ): PositionedInlineObject = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> PositionedInlineObject(
            sourceRange,
            definition,
            LayoutRect(
                finite(rect.left.value.toDouble() + inlineTranslation + baseline.x.value.toDouble(), "fragment object left"),
                finite(rect.top.value.toDouble() + baseline.y.value.toDouble(), "fragment object top"),
                finite(rect.right.value.toDouble() + inlineTranslation + baseline.x.value.toDouble(), "fragment object right"),
                finite(rect.bottom.value.toDouble() + baseline.y.value.toDouble(), "fragment object bottom"),
            ),
        )

        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> PositionedInlineObject(
            sourceRange,
            definition,
            LayoutRect(
                finite(rect.left.value.toDouble() + baseline.x.value.toDouble(), "vertical fragment object left"),
                finite(rect.top.value.toDouble() + inlineTranslation + baseline.y.value.toDouble(), "vertical fragment object top"),
                finite(rect.right.value.toDouble() + baseline.x.value.toDouble(), "vertical fragment object right"),
                finite(rect.bottom.value.toDouble() + inlineTranslation + baseline.y.value.toDouble(), "vertical fragment object bottom"),
            ),
        )
    }

    private fun CaretCandidate.inlineCoordinate(writingMode: WritingMode): Double = when (writingMode) {
        WritingMode.HORIZONTAL_TB -> geometry.start.x.value.toDouble()
        WritingMode.VERTICAL_RL,
        WritingMode.VERTICAL_LR,
        -> geometry.start.y.value.toDouble()
    }

    private fun List<PositionedGlyph>.sourceRange(): TextRange {
        val starts = map { glyph -> glyph.mappedSourceRange.start }.sortedWith(TextIndex::compareTo)
        val ends = map { glyph -> glyph.mappedSourceRange.endExclusive }.sortedWith(TextIndex::compareTo)
        return TextRange(starts.first(), ends.last())
    }

    private fun advanceWhitespace(
        intervals: List<InlineInterval>,
        startIndex: Int,
        startCursor: Double,
        amount: Double,
    ): Pair<Int, Double> {
        var index = startIndex
        var cursor = startCursor
        var remaining = amount
        while (remaining > 0.0 && index < intervals.size) {
            val available = intervals[index].endExclusive.toDouble() - cursor
            if (remaining <= available) return index to (cursor + remaining)
            remaining -= available
            index += 1
            if (index < intervals.size) cursor = intervals[index].start.toDouble()
        }
        return index to cursor
    }

    private fun FlowRegionResult.sameFlowAnswerAs(other: FlowRegionResult): Boolean = when {
        this is FlowRegionResult.AvailableIntervals && other is FlowRegionResult.AvailableIntervals -> intervals == other.intervals
        this is FlowRegionResult.Empty && other is FlowRegionResult.Empty -> nextBlockOffset == other.nextBlockOffset
        this === FlowRegionResult.EndOfRegion && other === FlowRegionResult.EndOfRegion -> true
        else -> false
    }

    private fun List<InlineInterval>.areSubsetOf(previous: List<InlineInterval>): Boolean = all { current ->
        previous.any { old -> current.start >= old.start && current.endExclusive <= old.endExclusive }
    }

    private fun noSpaceForFirstUnit(request: ParagraphLayoutRequest): FlowCompositionResult.Failure {
        val objectEntry = request.inlineObjects?.entries?.firstOrNull { it.index == request.sourceRange.start }
        if (objectEntry != null) {
            val end = request.snapshot.scalarRanges(request.sourceRange).first().endExclusive
            return FlowCompositionResult.Failure(
                FlowCompositionError.NoProgress(
                    TextRange(request.sourceRange.start, end),
                    NoProgressReason.INLINE_OBJECT_DOES_NOT_FIT,
                ),
            )
        }
        val range = request.unicodeAnalysis.graphemeClusters.firstOrNull { cluster -> cluster.start == request.sourceRange.start }
            ?: request.sourceRange
        return FlowCompositionResult.Failure(
            FlowCompositionError.NoProgress(range, NoProgressReason.CLUSTER_DOES_NOT_FIT),
        )
    }

    private fun paragraphFailure(message: String): FlowCompositionResult.Failure = FlowCompositionResult.Failure(
        FlowCompositionError.ParagraphFailure(ParagraphLayoutError.InvalidInput(message)),
    )

    private fun nonConvergent(message: String): FlowCompositionResult.Failure = FlowCompositionResult.Failure(
        FlowCompositionError.NonConvergentFlowRegion(message),
    )

    private fun geometryOverflow(message: String): FlowCompositionResult.Failure = FlowCompositionResult.Failure(
        FlowCompositionError.GeometryOverflow(message),
    )

    private fun finite(value: Double, label: String): LayoutUnit {
        val narrowed = value.toFloat()
        if (!value.isFinite() || !narrowed.isFinite()) throw IllegalArgumentException("$label overflowed.")
        return LayoutUnit(narrowed)
    }

    private fun rangesOverlap(left: TextRange, right: TextRange): Boolean =
        left.start < right.endExclusive && right.start < left.endExclusive

    private data class RefinementFingerprint(
        val bandExtent: Float,
        val intervals: List<Pair<Float, Float>>,
        val lineRange: TextRange,
    )

    private data class AllocatedGlyph(val sourceRun: PositionedGlyphRun, val glyph: PositionedGlyph)

    private data class Allocation(
        val fragmentIndex: Int,
        val originalStart: Double,
        val originalEnd: Double,
        val translation: Double,
        val objectRange: TextRange?,
    ) {
        fun distanceFrom(position: Double): Double = when {
            position < originalStart -> originalStart - position
            position > originalEnd -> position - originalEnd
            else -> 0.0
        }
    }

    private sealed interface FragmentProjection {
        data class Success(val fragments: List<LineFragment>) : FragmentProjection
        data class Failure(val error: FlowCompositionError) : FragmentProjection
    }
}
