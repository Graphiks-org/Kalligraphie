package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineDiagnosticSeverity
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EllipsisSide
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphProvenance
import org.graphiks.kalligraphie.api.GlyphProvenanceRole
import org.graphiks.kalligraphie.api.JustificationMode
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineControlKind
import org.graphiks.kalligraphie.api.ParagraphAlignment
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingSafetyFlags
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.TabAlignment
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot

/**
 * Portable builder refining shaped runs into the final glyph stream of one
 * editable line.
 *
 * All derived-content steps run here before positioning: soft hyphen and
 * automatic hyphenation substitution, tab glyph neutralization with synthetic
 * leader fill, kashida insertion, and justification advance overrides. Every
 * synthetic glyph anchors at a real snapshot boundary and every derived glyph
 * names a real source range; no document position is created.
 */
internal object LineContentPlan {
    /** Returns the first glyph relation that cannot be separated into tab and ordinary content. */
    fun mixedLineControlGlyphRelation(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
    ): EditableLineError.MixedLineControlGlyphRelation? {
        request.shapedGlyphRuns.forEach { run ->
            run.glyphs.forEach { glyph ->
                val scalars = glyph.clusterTokens
                    .map(run::clusterFor)
                    .flatMap { cluster -> cluster.scalarRanges }
                    .flatMap(snapshot::scalarValues)
                if (TAB in scalars && scalars.any { scalar -> scalar != TAB }) {
                    return EditableLineError.MixedLineControlGlyphRelation(
                        kind = LineControlKind.HORIZONTAL_TAB,
                        range = mappedRange(snapshot, run, glyph),
                    )
                }
            }
        }
        return null
    }

    fun build(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): List<RefinedRun> {
        val instances = request.fontInstances.associateBy(FontInstance::key)
        val softHyphens = request.softHyphenPolicy
        softHyphens?.materializedBoundaries?.forEach { boundary ->
            val ordinal = snapshot.ordinalOf(boundary)
            require(ordinal > 0 && snapshot.scalars[ordinal - 1] == SOFT_HYPHEN) {
                "A materialized soft-hyphen boundary must immediately follow a soft-hyphen scalar."
            }
        }
        val automatic = request.automaticHyphenBreaks?.materializedBoundaries.orEmpty()
        automatic.forEach { boundary ->
            val ordinal = snapshot.ordinalOf(boundary)
            require(ordinal > 0 && ordinal < snapshot.scalars.size) {
                "An automatic hyphen boundary must lie strictly inside the snapshot."
            }
        }
        return request.shapedGlyphRuns.map { run ->
            val instance = instances[run.fontInstanceKey]
            refineRun(request, snapshot, run, instance, softHyphens, automatic, diagnostics)
        }.let { runPlan -> applyEllipsis(request, snapshot, runPlan, diagnostics) }
            .let { runPlan -> applyKashidaSpacing(request, snapshot, runPlan, diagnostics) }
            .let { runPlan -> applyJustificationSpacing(request, snapshot, runPlan) }
    }

    /**
     * Applies ellipsis truncation to one line: hidden scalars publish
     * suppressed zero-advance glyphs and one synthetic marker glyph is
     * inserted at the anchor boundary of its glyph stream.
     */
    private fun applyEllipsis(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        runs: List<RefinedRun>,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): List<RefinedRun> {
        val ellipsis = request.ellipsis ?: return runs
        var anchor = ellipsis.hiddenRange.start
        var markerBefore = false
        when (ellipsis.side) {
            EllipsisSide.INLINE_START -> {
                anchor = ellipsis.hiddenRange.endExclusive
                markerBefore = true
            }
            EllipsisSide.INLINE_END, EllipsisSide.MIDDLE -> markerBefore = false
        }
        val inserted = mutableListOf<RefinedGlyph>()
        var markerAttached = false
        val result = runs.toMutableList()
        runs.forEachIndexed { runIndex, run ->
            val instance = request.fontInstances.firstOrNull { it.key == run.sourceRun.fontInstanceKey } ?: return@forEachIndexed
            val markerGlyphs = buildList {
                val resolved = (instance.resolveGlyph(ELLIPSIS_SCALAR) as? FontOperationResult.Success)?.value
                if (resolved != null && resolved.glyphId.value != 0) {
                    val advance = (instance.metrics(resolved.glyphId) as? FontOperationResult.Success)?.value?.advanceWidth
                    if (advance != null && advance.value > 0f) {
                        add(Triple(resolved.glyphId, advance, false))
                    }
                } else {
                    val dot = (instance.resolveGlyph(DOT_SCALAR) as? FontOperationResult.Success)?.value?.glyphId
                    if (dot != null) {
                        val adv = (instance.metrics(dot) as? FontOperationResult.Success)?.value?.advanceWidth
                        if (adv != null) {
                            repeat(3) { add(Triple(dot, adv, true)) }
                        }
                    }
                }
                if (isEmpty()) {
                    diagnostics += EditableLineDiagnostic(
                        code = "layout.ellipsis-marker-unavailable",
                        severity = EditableLineDiagnosticSeverity.WARNING,
                        message = "The selected face supplies neither an ellipsis nor a period glyph; the marker was suppressed deterministically.",
                    )
                }
            }
            val runClusters = run.sourceRun.clusters
            val hiddenInRun = runClusters.any { cluster ->
                val range = cluster.sourceRange
                overlapsOrInside(range, ellipsis.hiddenRange)
            }
            val markerInRun = runClusters.any { cluster ->
                cluster.sourceRange.start <= anchor && anchor <= cluster.sourceRange.endExclusive
            }
            if (!hiddenInRun && !markerInRun) return@forEachIndexed
            val rebuilt = mutableListOf<RefinedGlyph>()
            var anchorEmitted = false
            fun markerFor(reference: ShapedGlyph): List<RefinedGlyph> = markerGlyphs.map { (glyphId, advance, _) ->
                RefinedGlyph(
                    shapedGlyph = ShapedGlyph(
                        glyphId = glyphId,
                        xAdvance = advance,
                        yAdvance = LayoutUnit(0f),
                        xOffset = LayoutUnit(0f),
                        yOffset = LayoutUnit(0f),
                        safetyFlags = reference.safetyFlags,
                        clusterTokens = listOf(reference.clusterTokens.first()),
                    ),
                    provenance = GlyphProvenance.Synthetic(anchor, GlyphProvenanceRole.ELLIPSIS),
                )
            }
            val firstGlyph = run.glyphs.firstOrNull()?.shapedGlyph
            if (!markerAttached && !markerBefore && firstGlyph != null &&
                mappedRange(snapshot, run.sourceRun, firstGlyph).start == anchor
            ) {
                rebuilt += markerFor(firstGlyph)
                anchorEmitted = true
            }
            run.glyphs.forEach { refinedGlyph ->
                val glyph = refinedGlyph.shapedGlyph
                val mapped = mappedRange(snapshot, run.sourceRun, glyph)
                val hidden = rangesOverlap(mapped, ellipsis.hiddenRange)
                val endsAtAnchor = mapped.endExclusive == anchor
                val startsAtAnchor = mapped.start == anchor
                if (!markerAttached && markerBefore && startsAtAnchor && !anchorEmitted) {
                    rebuilt += markerFor(glyph)
                    anchorEmitted = true
                }
                if (hidden) {
                    val suppressed = (instance.resolveGlyph(SPACE) as? FontOperationResult.Success)?.value?.glyphId ?: glyph.glyphId
                    rebuilt += RefinedGlyph(
                        shapedGlyph = zeroAdvanceShape(glyph, suppressed),
                        provenance = GlyphProvenance.Direct(mapped),
                    )
                } else {
                    rebuilt += RefinedGlyph(glyph, GlyphProvenance.Direct(mapped))
                }
                if (!markerAttached && !markerBefore && endsAtAnchor && !anchorEmitted) {
                    rebuilt += markerFor(glyph)
                    anchorEmitted = true
                }
            }
            if (!markerAttached && markerBefore && !anchorEmitted) {
                val lastGlyph = run.glyphs.lastOrNull()?.shapedGlyph
                if (lastGlyph != null && mappedRange(snapshot, run.sourceRun, lastGlyph).endExclusive == anchor) {
                    rebuilt += markerFor(lastGlyph)
                    anchorEmitted = true
                }
            }
            result[runIndex] = RefinedRun(run.sourceRun, rebuilt)
            if (anchorEmitted) markerAttached = true
        }
        if (!markerAttached) {
            diagnostics += EditableLineDiagnostic(
                code = "layout.ellipsis-anchor-unbound",
                severity = EditableLineDiagnosticSeverity.WARNING,
                message = "The ellipsis anchor boundary does not correspond to any shaped cluster; the marker was suppressed deterministically.",
            )
        }
        return result
    }

    private fun overlapsOrInside(range: TextRange, hidden: TextRange): Boolean =
        rangesOverlap(range, hidden) || (range.start.sharesVersionWith(hidden.start) &&
            range.start >= hidden.start && range.endExclusive <= hidden.endExclusive)

    private fun rangesOverlap(left: TextRange, right: TextRange): Boolean =
        left.start.sharesVersionWith(right.start) &&
            left.start < right.endExclusive && right.start < left.endExclusive

    /**
     * Inserts sized kashida glyphs into Arabic-script justified runs.
     *
     * The extra advance required by the target is distributed deterministically
     * over the eligible gaps; the remainder smaller than one tatweel advance is
     * left to the justification pass when it applies, and dropped otherwise.
     */
    private fun applyKashidaSpacing(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        runs: List<RefinedRun>,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): List<RefinedRun> {
        val target = request.targetInlineExtent ?: return runs
        val positioning = request.positioning ?: return runs
        if (positioning.alignment != ParagraphAlignment.JUSTIFY || request.isLastLine) return runs
        val mode = positioning.justificationMode
        if (mode != JustificationMode.KASHIDA && mode != JustificationMode.AUTO) return runs
        val eligible = runs.mapIndexedNotNull { index, run ->
            index.takeIf { run.sourceRun.script.value == ARABIC_SCRIPT }
        }
        if (eligible.isEmpty()) return runs
        val natural = runs.sumOf { run ->
            run.glyphs.sumOf { glyph -> glyph.shapedGlyph.xAdvance.value.toDouble() }
        }
        val extra = target.value.toDouble() - natural
        if (extra <= 0.0) return runs
        val gapsByRun = eligible.associateWith { runIndex -> kashidaGaps(snapshot, runs[runIndex]) }
        val totalGaps = gapsByRun.values.sumOf(List<KashidaGap>::size)
        if (totalGaps <= 0) return runs
        val perGapAdvance = extra / totalGaps
        val result = runs.toMutableList()
        eligible.forEach { index ->
            val run = runs[index]
            val gaps = gapsByRun.getValue(index).associateBy(KashidaGap::afterGlyphIndex)
            if (gaps.isEmpty()) return@forEach
            val instance = request.fontInstances.firstOrNull { it.key == run.sourceRun.fontInstanceKey } ?: return@forEach
            val tatweel = (instance.resolveGlyph(KASHIDA_SCALAR) as? FontOperationResult.Success)?.value
            if (tatweel == null || tatweel.glyphId.value == 0) {
                diagnostics += kashidaUnavailable("The selected face has no kashida glyph; the Arabic line was justified with spaced base glyphs.")
                return@forEach
            }
            val advance = (instance.metrics(tatweel.glyphId) as? FontOperationResult.Success)?.value?.advanceWidth
            if (advance == null || advance.value <= 0f) {
                diagnostics += kashidaUnavailable("The selected face could not measure its kashida glyph.")
                return@forEach
            }
            val countPerGap = (perGapAdvance / advance.value.toDouble()).toInt()
            if (countPerGap <= 0) return@forEach
            val expanded = mutableListOf<RefinedGlyph>()
            run.glyphs.forEachIndexed { glyphIndex, glyph ->
                expanded += glyph
                gaps[glyphIndex]?.let { gap ->
                    val anchorToken = glyph.shapedGlyph.clusterTokens.firstOrNull()
                    if (anchorToken != null) {
                        repeat(countPerGap) {
                            expanded += RefinedGlyph(
                                shapedGlyph = ShapedGlyph(
                                    glyphId = tatweel.glyphId,
                                    xAdvance = advance,
                                    yAdvance = LayoutUnit(0f),
                                    xOffset = LayoutUnit(0f),
                                    yOffset = LayoutUnit(0f),
                                    safetyFlags = glyph.shapedGlyph.safetyFlags,
                                    clusterTokens = listOf(anchorToken),
                                ),
                                provenance = GlyphProvenance.Synthetic(gap.anchor, GlyphProvenanceRole.KASHIDA),
                            )
                        }
                    }
                }
            }
            result[index] = RefinedRun(run.sourceRun, expanded)
        }
        return result
    }

    /** Returns visual insertion sites whose source scalars form a real Arabic joining opportunity. */
    private fun kashidaGaps(snapshot: TextSnapshot, run: RefinedRun): List<KashidaGap> =
        run.glyphs.zipWithNext().mapIndexedNotNull { glyphIndex, (left, right) ->
            if (left.provenance is GlyphProvenance.Synthetic || right.provenance is GlyphProvenance.Synthetic ||
                left.tabMarker || right.tabMarker || left.inlineObjectWidth != null || right.inlineObjectWidth != null
            ) {
                return@mapIndexedNotNull null
            }
            val leftRange = mappedRange(snapshot, run.sourceRun, left.shapedGlyph)
            val rightRange = mappedRange(snapshot, run.sourceRun, right.shapedGlyph)
            val (earlier, later) = if (leftRange.start <= rightRange.start) {
                leftRange to rightRange
            } else {
                rightRange to leftRange
            }
            if (earlier.endExclusive != later.start) return@mapIndexedNotNull null
            val preceding = snapshot.scalarValues(earlier).lastOrNull { scalar -> !scalar.isArabicJoiningMark() }
                ?: return@mapIndexedNotNull null
            val following = snapshot.scalarValues(later).firstOrNull { scalar -> !scalar.isArabicJoiningMark() }
                ?: return@mapIndexedNotNull null
            if (preceding.canJoinFollowingArabic() && following.canJoinPrecedingArabic()) {
                KashidaGap(glyphIndex, earlier.endExclusive)
            } else {
                null
            }
        }

    /** One visual stream insertion site anchored at its logical Arabic joining boundary. */
    private data class KashidaGap(
        val afterGlyphIndex: Int,
        val anchor: TextIndex,
    )

    private fun refineRun(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        run: ShapedGlyphRun,
        instance: FontInstance?,
        softHyphens: org.graphiks.kalligraphie.api.SoftHyphenLinePolicy?,
        automaticBreaks: List<TextIndex>,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): RefinedRun {
        val stream = mutableListOf<RefinedGlyph>()
        val tabScalars = run.clusters.flatMap { cluster ->
            cluster.scalarRanges.mapNotNull { scalarRange ->
                if (snapshot.scalarValues(scalarRange) == listOf(TAB)) {
                    cluster.token to scalarRange
                } else {
                    null
                }
            }
        }
        val tabTokens = tabScalars.map(Pair<ShaperClusterToken, TextRange>::first).toSet()
        val automaticGlyphsRemaining = run.glyphs
            .map { glyph -> mappedRange(snapshot, run, glyph).endExclusive }
            .filter { boundary -> boundary in automaticBreaks }
            .groupingBy { boundary -> boundary }
            .eachCount()
            .toMutableMap()
        run.glyphs.forEach { glyph ->
            val mapped = mappedRange(snapshot, run, glyph)
            val scalars = snapshot.scalarValues(mapped)
            if (instance != null && glyph.clusterTokens.any(tabTokens::contains)) {
                return@forEach
            }
            if (scalars.any { it == SOFT_HYPHEN } && instance != null && softHyphens != null) {
                val materialized = mapped.endExclusive in softHyphens.materializedBoundaries
                val suppressed = suppressSoftHyphen(instance, glyph, diagnostics)
                val replacement = if (materialized) {
                    substituteHyphen(instance, glyph, diagnostics) ?: suppressed
                } else {
                    suppressed
                }
                stream += RefinedGlyph(
                    replacement,
                    if (materialized) {
                        GlyphProvenance.Derived(mapped, GlyphProvenanceRole.SOFT_HYPHEN)
                    } else {
                        GlyphProvenance.Direct(mapped)
                    },
                )
                return@forEach
            }
            if (mapped.endExclusive in automaticBreaks && instance != null) {
                stream += RefinedGlyph(glyph, GlyphProvenance.Direct(mapped))
                val remaining = automaticGlyphsRemaining.getValue(mapped.endExclusive) - 1
                automaticGlyphsRemaining[mapped.endExclusive] = remaining
                if (remaining == 0) {
                    substituteHyphen(instance, glyph, diagnostics)?.let { hyphen ->
                        stream += RefinedGlyph(
                            hyphen,
                            GlyphProvenance.Synthetic(mapped.endExclusive, GlyphProvenanceRole.AUTOMATIC_HYPHEN),
                        )
                    }
                }
                return@forEach
            }
            stream += RefinedGlyph(glyph, GlyphProvenance.Direct(mapped))
        }
        if (instance != null && tabScalars.isNotEmpty()) {
            val markersByToken = tabScalars
                .groupBy(Pair<ShaperClusterToken, TextRange>::first, Pair<ShaperClusterToken, TextRange>::second)
                .mapValues { (token, scalarRanges) ->
                    val logicalRanges = scalarRanges.sortedWith { left, right -> left.start.compareTo(right.start) }
                    val producedRanges = if (run.direction == ShapingDirection.RIGHT_TO_LEFT) {
                        logicalRanges.asReversed()
                    } else {
                        logicalRanges
                    }
                    producedRanges.map { scalarRange ->
                        RefinedGlyph(
                            shapedGlyph = zeroAdvanceTab(token),
                            provenance = GlyphProvenance.Synthetic(
                                scalarRange.start,
                                GlyphProvenanceRole.TAB_STOP,
                            ),
                            tabMarker = true,
                            lineControlRange = scalarRange,
                        )
                    }
                }
            stream.replaceClustersInGlyphOrder(run, markersByToken)
        }
        val inlineObjects = request.inlineObjects
        if (instance != null && inlineObjects != null &&
            run.clusters.any { cluster -> snapshot.scalarValues(cluster.sourceRange).any { it == OBJECT_REPLACEMENT } }
        ) {
            val objectsByToken = run.clusters.mapNotNull { cluster ->
                val definition = inlineObjects.definition(cluster.sourceRange.start)
                if (snapshot.scalarValues(cluster.sourceRange).any { it == OBJECT_REPLACEMENT } && definition != null) {
                    cluster.token to (cluster.sourceRange to definition)
                } else {
                    null
                }
            }
            if (objectsByToken.isNotEmpty()) {
                val space = (instance.resolveGlyph(SPACE) as? FontOperationResult.Success)?.value?.glyphId ?: GlyphId(0)
                val replacements = objectsByToken.associate { (token, objectEntry) ->
                    token to listOf(
                        RefinedGlyph(
                            shapedGlyph = zeroAdvanceShapeForObject(space, token, objectEntry.second.width),
                            provenance = GlyphProvenance.Direct(objectEntry.first),
                            inlineObjectWidth = objectEntry.second.width,
                        ),
                    )
                }
                stream.replaceClustersInGlyphOrder(run, replacements)
            }
        }
        return RefinedRun(run, stream)
    }

    /** Reorders reconstructed cluster entries in shaping output order for both inline directions. */
    private fun MutableList<RefinedGlyph>.replaceClustersInGlyphOrder(
        run: ShapedGlyphRun,
        replacements: Map<ShaperClusterToken, List<RefinedGlyph>>,
    ) {
        val remaining = toMutableList()
        val rebuilt = mutableListOf<RefinedGlyph>()
        val clusters = when (run.direction) {
            ShapingDirection.LEFT_TO_RIGHT -> run.clusters
            ShapingDirection.RIGHT_TO_LEFT -> run.clusters.asReversed()
            ShapingDirection.TOP_TO_BOTTOM -> run.clusters
        }
        clusters.forEach { cluster ->
            val attached = remaining.filter { entry -> cluster.token in entry.shapedGlyph.clusterTokens }
            remaining.removeAll(attached.toSet())
            val replacement = replacements[cluster.token]
            if (replacement != null) {
                rebuilt += replacement
            } else {
                rebuilt += attached
            }
        }
        rebuilt += remaining
        clear()
        addAll(rebuilt)
    }

    private fun zeroAdvanceShapeForObject(glyphId: GlyphId, token: ShaperClusterToken, width: LayoutUnit): ShapedGlyph = ShapedGlyph(
        glyphId = glyphId,
        xAdvance = width,
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(0f),
        yOffset = LayoutUnit(0f),
        safetyFlags = ShapingSafetyFlags(false, false),
        clusterTokens = listOf(token),
    )

    private fun kashidaUnavailable(reason: String): EditableLineDiagnostic = EditableLineDiagnostic(
        code = "layout.kashida-unavailable",
        severity = EditableLineDiagnosticSeverity.WARNING,
        message = reason,
    )

    private fun zeroAdvanceTab(token: ShaperClusterToken): ShapedGlyph = ShapedGlyph(
        glyphId = NO_INK_LINE_CONTROL_MARKER,
        xAdvance = LayoutUnit(0f),
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(0f),
        yOffset = LayoutUnit(0f),
        safetyFlags = ShapingSafetyFlags(false, false),
        clusterTokens = listOf(token),
    )

    private fun applyJustificationSpacing(
        request: EditableLineRequest,
        snapshot: TextSnapshot,
        runs: List<RefinedRun>,
    ): List<RefinedRun> {
        val target = request.targetInlineExtent ?: return runs
        val positioning = request.positioning ?: return runs
        if (positioning.alignment != ParagraphAlignment.JUSTIFY &&
            !(request.isLastLine && positioning.lastLineAlignment == ParagraphAlignment.JUSTIFY)
        ) {
            return runs
        }
        if (request.isLastLine && positioning.lastLineAlignment != ParagraphAlignment.JUSTIFY) return runs
        val natural = runs.sumOf { run ->
            run.glyphs.sumOf { glyph -> glyph.shapedGlyph.xAdvance.value.toDouble() }
        }
        val extra = target.value.toDouble() - natural
        if (extra <= 0.0) return runs
        val mode = positioning.justificationMode
        val eligible = runs.mapIndexed { runIndex, run ->
            run.glyphs.mapIndexed { index, glyph ->
                val scalars = refScalars(snapshot, run, glyph)
                JustificationUnit(runIndex, index, scalars.any { it.isWhitespaceScalar() }, scalars.any { it.isCjkScalar() })
            }
        }
        val visible = eligible.flatten().filter { unit ->
            val glyph = runs[unit.runIndex].glyphs[unit.glyphIndex]
            !glyph.tabMarker && glyph.inlineObjectWidth == null && glyph.provenance !is GlyphProvenance.Synthetic
        }
        val lastVisible = visible.lastOrNull()
        val applicable = when (mode) {
            JustificationMode.INTER_CHARACTER -> visible.dropLast(1)
            JustificationMode.AUTO -> {
                if (visible.any { unit -> unit.cjk }) {
                    visible.filter { unit -> unit.cjk && unit != lastVisible }
                } else {
                    visible.filter { unit -> unit.whitespace && unit != lastVisible }
                }
            }
            JustificationMode.INTER_WORD,
            JustificationMode.KASHIDA,
            -> visible.filter { unit -> unit.whitespace && unit != lastVisible }
        }
        if (applicable.isEmpty()) return runs
        val share = extra / applicable.size
        val result = runs.toMutableList()
        applicable.forEach { unit ->
            val run = result[unit.runIndex]
            val current = run.glyphs[unit.glyphIndex]
            val shaped = current.shapedGlyph
            val updated = run.glyphs.toMutableList()
            updated[unit.glyphIndex] = RefinedGlyph(
                shapedGlyph = ShapedGlyph(
                    glyphId = shaped.glyphId,
                    xAdvance = LayoutUnit(shaped.xAdvance.value + share.toFloat()),
                    yAdvance = shaped.yAdvance,
                    xOffset = shaped.xOffset,
                    yOffset = shaped.yOffset,
                    safetyFlags = shaped.safetyFlags,
                    clusterTokens = shaped.clusterTokens,
                ),
                provenance = when (val provenance = current.provenance) {
                    is GlyphProvenance.Direct -> GlyphProvenance.Derived(
                        provenance.sourceRange,
                        GlyphProvenanceRole.JUSTIFICATION_SPACING,
                    )
                    is GlyphProvenance.Derived,
                    is GlyphProvenance.Synthetic,
                    -> provenance
                },
                tabMarker = current.tabMarker,
                lineControlRange = current.lineControlRange,
                inlineObjectWidth = current.inlineObjectWidth,
            )
            result[unit.runIndex] = RefinedRun(run.sourceRun, updated)
        }
        return result
    }

    private data class JustificationUnit(
        val runIndex: Int,
        val glyphIndex: Int,
        val whitespace: Boolean,
        val cjk: Boolean,
    )

    private fun refScalars(snapshot: TextSnapshot, run: RefinedRun, glyph: RefinedGlyph): List<Int> =
        glyph.shapedGlyph.clusterTokens
            .map(run.sourceRun::clusterFor)
            .flatMap { cluster -> snapshot.scalarValues(cluster.sourceRange) }

    private fun substituteHyphen(
        instance: FontInstance,
        glyph: ShapedGlyph,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): ShapedGlyph? {
        val resolved = when (val result = instance.resolveGlyph(HYPHEN_MINUS)) {
            is FontOperationResult.Success -> result.value.glyphId
            is FontOperationResult.Failure -> return substitutionUnavailable(diagnostics, result)
            is FontOperationResult.Cancelled -> return substitutionUnavailable(diagnostics, result)
        }
        val advance = when (val metrics = instance.metrics(resolved)) {
            is FontOperationResult.Success -> metrics.value.advanceWidth
            is FontOperationResult.Failure -> return substitutionUnavailable(diagnostics, metrics)
            is FontOperationResult.Cancelled -> return substitutionUnavailable(diagnostics, metrics)
        }
        return ShapedGlyph(
            glyphId = resolved,
            xAdvance = advance,
            yAdvance = LayoutUnit(0f),
            xOffset = LayoutUnit(0f),
            yOffset = LayoutUnit(0f),
            safetyFlags = glyph.safetyFlags,
            clusterTokens = glyph.clusterTokens,
        )
    }

    private fun suppressSoftHyphen(
        instance: FontInstance,
        glyph: ShapedGlyph,
        diagnostics: MutableList<EditableLineDiagnostic>,
    ): ShapedGlyph {
        val space = when (val result = instance.resolveGlyph(SPACE)) {
            is FontOperationResult.Success -> result.value.glyphId
            is FontOperationResult.Failure -> {
                substitutionUnavailable(diagnostics, result)
                return zeroAdvanceShape(glyph, glyph.glyphId)
            }
            is FontOperationResult.Cancelled -> {
                substitutionUnavailable(diagnostics, result)
                return zeroAdvanceShape(glyph, glyph.glyphId)
            }
        }
        return zeroAdvanceShape(glyph, space)
    }

    private fun zeroAdvanceShape(template: ShapedGlyph, glyphId: GlyphId): ShapedGlyph = ShapedGlyph(
        glyphId = glyphId,
        xAdvance = LayoutUnit(0f),
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(0f),
        yOffset = LayoutUnit(0f),
        safetyFlags = template.safetyFlags,
        clusterTokens = template.clusterTokens,
    )

    private fun <Result> substitutionUnavailable(
        diagnostics: MutableList<EditableLineDiagnostic>,
        result: FontOperationResult<Result>,
    ): ShapedGlyph? {
        diagnostics += EditableLineDiagnostic(
            code = "layout.hyphen-glyph-unavailable",
            severity = EditableLineDiagnosticSeverity.WARNING,
            message = "The selected face could not supply the hyphen substitution glyph; the hyphen was suppressed deterministically. " +
                (when (result) {
                    is FontOperationResult.Failure -> result.error.message
                    is FontOperationResult.Cancelled -> "Operation was cancelled."
                    is FontOperationResult.Success -> ""
                }),
        )
        return null
    }
}

internal fun mappedRange(snapshot: TextSnapshot, run: ShapedGlyphRun, glyph: ShapedGlyph): TextRange {
    val mapped = glyph.clusterTokens.map(run::clusterFor)
    val start = mapped.minWith { left, right -> left.sourceRange.start.compareTo(right.sourceRange.start) }.sourceRange.start
    val endExclusive = mapped.maxWith { left, right -> left.sourceRange.endExclusive.compareTo(right.sourceRange.endExclusive) }
        .sourceRange.endExclusive
    return TextRange(start, endExclusive)
}

internal fun ShapedGlyphRun.clusterFor(token: ShaperClusterToken): org.graphiks.kalligraphie.api.ShaperCluster =
    clusters.first { cluster -> cluster.token == token }

private fun TextSnapshot.ordinalOf(boundary: TextIndex): Int {
    require(boundary.sharesVersionWith(range.start)) { "Boundary must belong to this snapshot version." }
    return (0..scalars.size).firstOrNull { index -> textIndexAtScalarBoundary(index) == boundary }
        ?: throw IllegalArgumentException("Boundary does not belong to this snapshot.")
}

private fun Int.isWhitespaceScalar(): Boolean =
    this == 0x0020 || this == 0x00A0 || this in 0x2000..0x200A || this == 0x3000

private fun Int.isCjkScalar(): Boolean =
    this in 0x3400..0x4DBF ||
        this in 0x4E00..0x9FFF ||
        this in 0xF900..0xFAFF ||
        this in 0x3040..0x30FF ||
        this in 0xAC00..0xD7AF

/** Unicode 16 Arabic-script letters whose joining type accepts a following letter. */
private fun Int.canJoinFollowingArabic(): Boolean =
    this == 0x0620 ||
        this == 0x0626 ||
        this == 0x0628 ||
        this in 0x062A..0x062E ||
        this in 0x0633..0x063F ||
        this in 0x0641..0x0647 ||
        this in 0x0649..0x064A ||
        this in 0x066E..0x066F ||
        this in 0x0678..0x0687 ||
        this in 0x069A..0x06BF ||
        this in 0x06C1..0x06C2 ||
        this == 0x06CC ||
        this == 0x06CE ||
        this in 0x06D0..0x06D1 ||
        this in 0x06FA..0x06FC ||
        this == 0x06FF ||
        this in 0x0750..0x0758 ||
        this in 0x075C..0x076A ||
        this in 0x076D..0x0770 ||
        this == 0x0772 ||
        this in 0x0775..0x0777 ||
        this in 0x077A..0x077F ||
        this == 0x0886 ||
        this in 0x0889..0x088D ||
        this in 0x08A0..0x08A9 ||
        this in 0x08AF..0x08B0 ||
        this in 0x08B3..0x08B8 ||
        this in 0x08BA..0x08C8 ||
        this in 0x10EC3..0x10EC4

/** Unicode 16 Arabic-script letters whose joining type accepts a preceding letter. */
private fun Int.canJoinPrecedingArabic(): Boolean =
    canJoinFollowingArabic() ||
        this in 0x0622..0x0625 ||
        this in 0x0629..0x062E ||
        this in 0x062F..0x0632 ||
        this == 0x0648 ||
        this in 0x0671..0x0673 ||
        this in 0x0675..0x0677 ||
        this in 0x0688..0x0699 ||
        this == 0x06C0 ||
        this in 0x06C3..0x06C8 ||
        this in 0x06C9..0x06CB ||
        this == 0x06CD ||
        this == 0x06CF ||
        this in 0x06D2..0x06D3 ||
        this == 0x06D5 ||
        this in 0x06EE..0x06EF ||
        this in 0x0759..0x075B ||
        this in 0x076B..0x076C ||
        this == 0x0771 ||
        this in 0x0773..0x0774 ||
        this in 0x0778..0x0779 ||
        this in 0x0870..0x0882 ||
        this == 0x088E ||
        this in 0x08AA..0x08AC ||
        this == 0x08AE ||
        this in 0x08B1..0x08B2 ||
        this == 0x08B9 ||
        this == 0x10EC2

/** Combining marks are transparent when the surrounding Arabic letters are tested for joining. */
private fun Int.isArabicJoiningMark(): Boolean =
    this in 0x0610..0x061A || this in 0x064B..0x065F || this == 0x0670 || this in 0x06D6..0x06ED

private const val SOFT_HYPHEN: Int = 0x00AD
private const val HYPHEN_MINUS: Int = 0x002D
private const val SPACE: Int = 0x0020
private const val TAB: Int = 0x0009
private val NO_INK_LINE_CONTROL_MARKER: GlyphId = GlyphId(0x10000)
private const val KASHIDA_SCALAR: Int = 0x0640
private const val ARABIC_SCRIPT: String = "Arab"
internal const val ELLIPSIS_SCALAR: Int = 0x2026
internal const val DOT_SCALAR: Int = 0x002E
internal const val OBJECT_REPLACEMENT: Int = 0xFFFC
