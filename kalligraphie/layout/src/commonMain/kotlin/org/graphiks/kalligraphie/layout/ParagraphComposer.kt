package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFaceCapabilities
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBreakKind
import org.graphiks.kalligraphie.api.MultiFontEditableLineRequest
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ScriptLanguageRun
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.UnicodeAnalysis

/** One finalized line and its physical placement, before content/ink metric enrichment. */
internal class ComposedParagraphLine(
    val line: EditableLine,
    val baseline: LayoutPoint,
    val lineBox: LayoutRect,
    val inlineAdvance: LayoutUnit,
) {
    init {
        require(lineBox.left == baseline.x)
        require(lineBox.top == LayoutUnit(baseline.y.value - line.verticalMetrics.ascent.value))
        require(lineBox.bottom == LayoutUnit(baseline.y.value + line.verticalMetrics.descent.value))
    }
}

/** Pure complete-line output consumed by the later paragraph geometry layer. */
internal sealed interface ParagraphCompositionResult {
    class Success(
        lines: List<ComposedParagraphLine>,
        val remainingSourceRange: TextRange?,
        val hasUnplacedTrailingEmptyLine: Boolean = false,
    ) : ParagraphCompositionResult {
        val lines: List<ComposedParagraphLine> = lines.immutableSnapshot()
    }

    class Failure(
        val error: EditableLineError,
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : ParagraphCompositionResult {
        val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableSnapshot()
    }

    class Cancelled(
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : ParagraphCompositionResult {
        val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableSnapshot()
    }
}

/** Selects, finally shapes, positions, and physically stacks complete editable lines. */
internal object ParagraphComposer {
    fun compose(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
    ): ParagraphCompositionResult {
        if (materialization.identity() != request.materializationIdentity) {
            return ParagraphCompositionResult.Failure(
                EditableLineError.InvalidInput("Paragraph materialization does not match the captured request identity."),
            )
        }
        if (request.cancellationToken.isCancellationRequested()) return ParagraphCompositionResult.Cancelled()

        val sourceClusters = request.unicodeAnalysis.graphemeClusters.filter { cluster ->
            cluster.start >= request.sourceRange.start && cluster.endExclusive <= request.sourceRange.endExclusive
        }
        if (request.sourceRange.start != request.sourceRange.endExclusive &&
            (sourceClusters.isEmpty() || sourceClusters.first().start != request.sourceRange.start ||
                sourceClusters.last().endExclusive != request.sourceRange.endExclusive)
        ) {
            return ParagraphCompositionResult.Failure(
                EditableLineError.InvalidInput("Paragraph source ranges must begin and end at extended grapheme boundaries."),
            )
        }

        val assignments = when (val resolved = resolveAssignments(request, materialization, sourceClusters)) {
            is AssignmentResult.Success -> resolved.assignments
            is AssignmentResult.Failure -> return ParagraphCompositionResult.Failure(resolved.error)
            AssignmentResult.Cancelled -> return ParagraphCompositionResult.Cancelled()
        }
        val provisionalAnalysis = analysisForLine(request, request.sourceRange, resetLineTrailingWhitespace = false)
        val provisionalRuns = when (val shaped = shapeRange(request, request.sourceRange, provisionalAnalysis, assignments)) {
            is ShapeResult.Success -> shaped.runs
            is ShapeResult.Failure -> return ParagraphCompositionResult.Failure(shaped.error)
            ShapeResult.Cancelled -> return ParagraphCompositionResult.Cancelled()
        }

        val placed = mutableListOf<ComposedParagraphLine>()
        val region = request.constraints.region
        val metrics = request.constraints.lineMetrics
        var lineTop = region.top

        fun fullLineFits(): Boolean =
            lineTop.value.toDouble() + metrics.height.value.toDouble() <= region.bottom.value.toDouble()

        if (request.sourceRange.start == request.sourceRange.endExclusive) {
            if (!fullLineFits()) {
                return ParagraphCompositionResult.Success(emptyList(), null, hasUnplacedTrailingEmptyLine = true)
            }
            return when (val empty = emptyLine(request, request.sourceRange, materialization)) {
                is EditableLineResult.Success -> ParagraphCompositionResult.Success(
                    listOf(place(empty.line, region, lineTop)),
                    remainingSourceRange = null,
                )
                is EditableLineResult.Failure -> ParagraphCompositionResult.Failure(empty.error, empty.diagnostics)
                is EditableLineResult.Cancelled -> ParagraphCompositionResult.Cancelled(empty.diagnostics)
            }
        }

        var lineStart = request.sourceRange.start
        while (lineStart < request.sourceRange.endExclusive) {
            if (request.cancellationToken.isCancellationRequested()) return ParagraphCompositionResult.Cancelled()
            if (!fullLineFits()) {
                return ParagraphCompositionResult.Success(
                    placed,
                    TextRange(lineStart, request.sourceRange.endExclusive),
                )
            }

            val candidates = candidatesForLine(request, lineStart)
            val selectedEnd = selectBoundary(
                start = lineStart,
                candidates = candidates,
                width = request.constraints.width,
                provisionalRuns = provisionalRuns,
            )
            check(selectedEnd > lineStart) { "Paragraph composition must strictly advance at every selected line." }
            val lineRange = TextRange(lineStart, selectedEnd)
            val finalAnalysis = analysisForLine(request, lineRange, resetLineTrailingWhitespace = true)
            val finalRuns = when (val shaped = shapeRange(request, lineRange, finalAnalysis, assignments)) {
                is ShapeResult.Success -> shaped.runs
                is ShapeResult.Failure -> return ParagraphCompositionResult.Failure(shaped.error)
                ShapeResult.Cancelled -> return ParagraphCompositionResult.Cancelled()
            }
            val instances = assignments
                .filter { assigned -> assigned.range.start >= lineRange.start && assigned.range.endExclusive <= lineRange.endExclusive }
                .map(AssignedUnit::instance)
                .distinctBy(FontInstance::key)
            val lineResult = ExactEditableLineLayouter.layout(
                EditableLineRequest(
                    unicodeAnalysis = finalAnalysis,
                    shapedGlyphRuns = finalRuns,
                    baseDirection = request.baseDirection.shapingDirection(),
                    font = instances.firstOrNull(),
                    fontInstances = instances,
                    verticalMetrics = metrics,
                    materialization = materialization,
                    cancellationToken = request.cancellationToken,
                ),
            )
            when (lineResult) {
                is EditableLineResult.Success -> placed += place(lineResult.line, region, lineTop)
                is EditableLineResult.Failure -> return ParagraphCompositionResult.Failure(lineResult.error, lineResult.diagnostics)
                is EditableLineResult.Cancelled -> return ParagraphCompositionResult.Cancelled(lineResult.diagnostics)
            }
            lineTop = finiteUnit(lineTop.value.toDouble() + metrics.height.value.toDouble(), "paragraph line top")
            lineStart = selectedEnd
        }

        val trailingEmptyRequired = request.lineBreakAnalysis.opportunities.any { opportunity ->
            opportunity.boundary == request.sourceRange.endExclusive && opportunity.kind == LineBreakKind.MANDATORY
        }
        if (trailingEmptyRequired) {
            if (!fullLineFits()) {
                return ParagraphCompositionResult.Success(placed, null, hasUnplacedTrailingEmptyLine = true)
            }
            val emptyRange = TextRange(request.sourceRange.endExclusive, request.sourceRange.endExclusive)
            when (val empty = emptyLine(request, emptyRange, materialization)) {
                is EditableLineResult.Success -> placed += place(empty.line, region, lineTop)
                is EditableLineResult.Failure -> return ParagraphCompositionResult.Failure(empty.error, empty.diagnostics)
                is EditableLineResult.Cancelled -> return ParagraphCompositionResult.Cancelled(empty.diagnostics)
            }
        }
        return ParagraphCompositionResult.Success(placed, remainingSourceRange = null)
    }

    private fun candidatesForLine(request: ParagraphLayoutRequest, start: TextIndex): List<TextIndex> {
        val opportunities = request.lineBreakAnalysis.opportunities.filter { opportunity ->
            opportunity.boundary > start && opportunity.boundary <= request.sourceRange.endExclusive
        }
        val firstMandatory = opportunities.firstOrNull { it.kind == LineBreakKind.MANDATORY }
        val terminal = firstMandatory?.boundary ?: request.sourceRange.endExclusive
        return buildList {
            opportunities
                .takeWhile { opportunity -> opportunity.boundary <= terminal }
                .forEach { opportunity -> add(opportunity.boundary) }
            if (lastOrNull() != terminal) add(terminal)
        }
    }

    private fun selectBoundary(
        start: TextIndex,
        candidates: List<TextIndex>,
        width: LayoutUnit,
        provisionalRuns: List<ShapedGlyphRun>,
    ): TextIndex {
        require(candidates.isNotEmpty())
        var latestFitting: TextIndex? = null
        candidates.forEach { boundary ->
            val advance = measuredAdvance(TextRange(start, boundary), provisionalRuns)
            if (advance.value <= width.value) latestFitting = boundary
        }
        return latestFitting ?: candidates.first()
    }

    private fun measuredAdvance(range: TextRange, runs: List<ShapedGlyphRun>): LayoutUnit {
        var sum = 0.0
        runs.forEach { run ->
            run.glyphs.forEach { glyph ->
                val overlaps = glyph.clusterTokens
                    .map { token -> run.clusters.single { cluster -> cluster.token == token } }
                    .any { cluster -> cluster.sourceRange.start < range.endExclusive && range.start < cluster.sourceRange.endExclusive }
                if (overlaps) sum += glyph.xAdvance.value.toDouble()
            }
        }
        return finiteUnit(sum, "provisional line advance")
    }

    private fun resolveAssignments(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        clusters: List<TextRange>,
    ): AssignmentResult {
        if (clusters.isEmpty()) return AssignmentResult.Success(emptyList())
        if (request.snapshot.scalarValues(request.snapshot.range).none { scalar -> scalar.isMandatoryControl() }) {
            val provisional = FontFallbackResolver.resolve(
                MultiFontEditableLineRequest(
                    snapshot = request.snapshot,
                    unicodeAnalysis = request.unicodeAnalysis,
                    fontCatalog = request.fontCatalog,
                    resolutionPolicy = request.resolutionPolicy,
                    fontInstanceDescriptor = request.fontInstanceDescriptor,
                    shapingBackend = request.shapingBackend,
                    baseDirection = request.baseDirection,
                    verticalMetrics = request.constraints.lineMetrics,
                    materialization = materialization,
                    features = request.features,
                    cancellationToken = request.cancellationToken,
                ),
            )
            return when (provisional) {
                is FontOperationResult.Success -> {
                    val instances = provisional.value.instances.associateBy(FontInstance::key)
                    AssignmentResult.Success(
                        clusters.map { cluster ->
                            val keys = provisional.value.shapedRuns
                                .filter { run -> overlaps(run.range, cluster) }
                                .map(ShapedGlyphRun::fontInstanceKey)
                                .distinct()
                            check(keys.size == 1) { "A complete fallback unit must resolve to exactly one font instance." }
                            AssignedUnit(cluster, instances.getValue(keys.single()), controlOnly = false)
                        },
                    )
                }
                is FontOperationResult.Failure -> AssignmentResult.Failure(
                    EditableLineError.FontResolutionFailure(provisional.error),
                )
                is FontOperationResult.Cancelled -> AssignmentResult.Cancelled
            }
        }
        val requirements = materialization.requirements()
        val records = request.fontCatalog.faces.associateBy { it.id }
        val instances = mutableMapOf<FontFaceId, FontInstance>()
        val assignments = mutableListOf<AssignedUnit>()
        clusters.forEach { cluster ->
            if (request.cancellationToken.isCancellationRequested()) return AssignmentResult.Cancelled
            val controlOnly = request.snapshot.scalarValues(cluster).all { scalar -> scalar.isMandatoryControl() }
            var selected: AssignedUnit? = null
            request.resolutionPolicy.candidates.forEach candidateLoop@ { candidate ->
                if (selected != null) return@candidateLoop
                val record = records.getValue(candidate.faceId)
                if (!record.capabilities.supports(requirements)) return@candidateLoop
                val instance = instances[record.id] ?: when (val face = request.fontCatalog.resolveFace(record.id, requirements)) {
                    is FontOperationResult.Success -> when (val instantiated = face.value.instantiate(request.fontInstanceDescriptor)) {
                        is FontOperationResult.Success -> instantiated.value.also { instances[record.id] = it }
                        is FontOperationResult.Failure -> return@candidateLoop
                        is FontOperationResult.Cancelled -> return AssignmentResult.Cancelled
                    }
                    is FontOperationResult.Failure -> return@candidateLoop
                    is FontOperationResult.Cancelled -> return AssignmentResult.Cancelled
                }
                if (controlOnly || mapsCompleteUnit(request, cluster, instance)) {
                    selected = AssignedUnit(cluster, instance, controlOnly)
                }
            }
            assignments += selected ?: return AssignmentResult.Failure(
                EditableLineError.FontResolutionFailure(
                    FontError.UnrenderableFontResolution(
                        "No policy candidate covers one complete paragraph fallback unit.",
                        FontDiagnosticLocation.Source,
                    ),
                ),
            )
        }
        return AssignmentResult.Success(assignments)
    }

    private fun mapsCompleteUnit(request: ParagraphLayoutRequest, range: TextRange, instance: FontInstance): Boolean {
        var precedingScalar: Int? = null
        request.snapshot.scalarValues(range).forEach { scalar ->
            if (scalar.isVariationSelector()) {
                val base = precedingScalar ?: return false
                when (val mapped = instance.resolveGlyph(base, scalar)) {
                    is FontOperationResult.Success -> if (mapped.value.glyphId.value == 0) return false
                    is FontOperationResult.Failure -> return false
                    is FontOperationResult.Cancelled -> return false
                }
                precedingScalar = null
            } else {
                if (!scalar.isFallbackIgnorable()) {
                    when (val mapped = instance.resolveGlyph(scalar)) {
                        is FontOperationResult.Success -> if (mapped.value.glyphId.value == 0) return false
                        is FontOperationResult.Failure -> return false
                        is FontOperationResult.Cancelled -> return false
                    }
                }
                precedingScalar = scalar.takeUnless { value -> value.isFallbackIgnorable() }
            }
        }
        return true
    }

    private fun shapeRange(
        request: ParagraphLayoutRequest,
        range: TextRange,
        analysis: UnicodeAnalysis,
        assignments: List<AssignedUnit>,
    ): ShapeResult {
        if (range.start == range.endExclusive) return ShapeResult.Success(emptyList())
        val lineAssignments = assignments.filter { assigned ->
            assigned.range.start >= range.start && assigned.range.endExclusive <= range.endExclusive
        }
        val groups = mutableListOf<MutableList<AssignedUnit>>()
        lineAssignments.forEach { assigned ->
            val previous = groups.lastOrNull()?.lastOrNull()
            if (previous != null && previous.instance.key == assigned.instance.key &&
                previous.controlOnly == assigned.controlOnly && previous.range.endExclusive == assigned.range.start
            ) {
                groups.last() += assigned
            } else {
                groups += mutableListOf(assigned)
            }
        }
        val runs = mutableListOf<ShapedGlyphRun>()
        groups.forEach { group ->
            val groupRange = TextRange(group.first().range.start, group.last().range.endExclusive)
            val fragments = shapingFragments(groupRange, analysis)
            fragments.forEach { fragment ->
                if (request.cancellationToken.isCancellationRequested()) return ShapeResult.Cancelled
                if (group.first().controlOnly) {
                    runs += zeroWidthControlRun(request, fragment, group.first().instance, analysis)
                    return@forEach
                }
                val shaped = request.shapingBackend.shape(
                    ShapingRequest(
                        snapshot = request.snapshot,
                        range = fragment.range,
                        font = group.first().instance,
                        direction = fragment.level.direction(),
                        script = OpenTypeScript(fragment.script),
                        language = fragment.language,
                        bidiLevel = fragment.level,
                        bot = fragment.range.start == range.start,
                        eot = fragment.range.endExclusive == range.endExclusive,
                        featurePolicy = request.featurePolicy,
                        features = request.features,
                        graphemeClusters = graphemeFragments(fragment.range, analysis.graphemeClusters),
                    ),
                )
                when (shaped) {
                    is FontOperationResult.Success -> {
                        if (shaped.value.glyphs.any { glyph -> glyph.glyphId.value == 0 }) {
                            return ShapeResult.Failure(
                                EditableLineError.ShapingFailure(
                                    FontError.InvalidFontData(
                                        "Final paragraph shaping produced the missing-glyph identifier.",
                                        FontDiagnosticLocation.Source,
                                    ),
                                ),
                            )
                        }
                        runs += shaped.value
                    }
                    is FontOperationResult.Failure -> return ShapeResult.Failure(EditableLineError.ShapingFailure(shaped.error))
                    is FontOperationResult.Cancelled -> return ShapeResult.Cancelled
                }
            }
        }
        return ShapeResult.Success(runs)
    }

    private fun zeroWidthControlRun(
        request: ParagraphLayoutRequest,
        fragment: ShapingFragment,
        instance: FontInstance,
        analysis: UnicodeAnalysis,
    ): ShapedGlyphRun {
        val scalarRanges = request.snapshot.scalarRanges(fragment.range)
        val graphemes = graphemeFragments(fragment.range, analysis.graphemeClusters)
        val boundaries = graphemes.flatMap { grapheme -> listOf(grapheme.start, grapheme.endExclusive) }.distinct()
        return ShapedGlyphRun(
            range = fragment.range,
            fontInstanceKey = instance.key,
            backendIdentity = request.shapingBackend.identity,
            direction = fragment.level.direction(),
            script = OpenTypeScript(fragment.script),
            language = fragment.language,
            bidiLevel = fragment.level,
            bot = fragment.range.start == analysis.range.start,
            eot = fragment.range.endExclusive == analysis.range.endExclusive,
            featurePolicy = request.featurePolicy,
            features = request.features,
            graphemeClusters = graphemes,
            glyphs = emptyList(),
            clusters = scalarRanges.mapIndexed { index, scalarRange ->
                ShaperCluster(
                    token = ShaperClusterToken(index),
                    sourceRange = scalarRange,
                    scalarRanges = listOf(scalarRange),
                    admissibleGraphemeBoundaries = boundaries.filter { boundary ->
                        boundary >= scalarRange.start && boundary <= scalarRange.endExclusive
                    },
                )
            },
        )
    }

    private fun analysisForLine(
        request: ParagraphLayoutRequest,
        range: TextRange,
        resetLineTrailingWhitespace: Boolean,
    ): UnicodeAnalysis {
        if (range.start == range.endExclusive) {
            return UnicodeAnalysis(range, request.unicodeAnalysis.unicodeData, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val graphemes = request.unicodeAnalysis.graphemeClusters.mapNotNull { intersection(it, range) }
        val scripts = request.unicodeAnalysis.scriptLanguageRuns.mapNotNull { source ->
            intersection(source.range, range)?.let { clipped -> ScriptLanguageRun(clipped, source.script, source.language) }
        }
        val baseLevel = if (request.baseDirection == BaseDirection.LEFT_TO_RIGHT) 0 else 1
        val levels = request.snapshot.scalarRanges(range).map { scalarRange ->
            val paragraphLevel = request.unicodeAnalysis.logicalBidiRuns.first { bidi -> overlaps(bidi.range, scalarRange) }.level
            MutableSourceLevel(scalarRange, paragraphLevel)
        }.toMutableList()
        levels.forEach { item ->
            if (request.snapshot.scalarValues(item.range).all { scalar -> scalar.isMandatoryControl() }) item.level = baseLevel
        }
        if (resetLineTrailingWhitespace) {
            for (index in levels.indices.reversed()) {
                val scalar = request.snapshot.scalarValues(levels[index].range).single()
                if (isL1TrailingScalar(scalar)) levels[index].level = baseLevel else break
            }
        }
        val logical = mutableListOf<BidiRun>()
        levels.forEach { item ->
            val previous = logical.lastOrNull()
            if (previous != null && previous.level == item.level && previous.range.endExclusive == item.range.start) {
                logical[logical.lastIndex] = BidiRun(TextRange(previous.range.start, item.range.endExclusive), item.level)
            } else {
                logical += BidiRun(item.range, item.level)
            }
        }
        return UnicodeAnalysis(
            range = range,
            unicodeData = request.unicodeAnalysis.unicodeData,
            graphemeClusters = graphemes,
            scriptLanguageRuns = scripts,
            logicalBidiRuns = logical,
            visualBidiRuns = reorderVisualRuns(logical),
        )
    }

    private fun reorderVisualRuns(logical: List<BidiRun>): List<BidiRun> {
        val reordered = logical.toMutableList()
        val maximum = logical.maxOfOrNull(BidiRun::level) ?: return emptyList()
        val lowestOdd = logical.map(BidiRun::level).filter { it % 2 != 0 }.minOrNull() ?: return reordered
        for (level in maximum downTo lowestOdd) {
            var start = 0
            while (start < reordered.size) {
                while (start < reordered.size && reordered[start].level < level) start++
                var end = start
                while (end < reordered.size && reordered[end].level >= level) end++
                if (start < end) reordered.subList(start, end).reverse()
                start = end
            }
        }
        return reordered
    }

    private fun shapingFragments(range: TextRange, analysis: UnicodeAnalysis): List<ShapingFragment> =
        scriptFragments(range, analysis.scriptLanguageRuns).flatMap { script ->
            analysis.logicalBidiRuns.mapNotNull { bidi ->
                intersection(script.range, bidi.range)?.let { fragment ->
                    ShapingFragment(fragment, script.script, script.language, bidi.level)
                }
            }
        }

    private fun scriptFragments(range: TextRange, scripts: List<ScriptLanguageRun>): List<ScriptFragment> {
        val intersections = scripts.mapNotNull { script ->
            intersection(range, script.range)?.let { clipped -> ScriptFragment(clipped, script.script, script.language) }
        }
        val first = intersections.first()
        var fragmentStart = range.start
        var active = intersections.firstOrNull { it.script.isExplicitScript() } ?: first
        val result = mutableListOf<ScriptFragment>()
        intersections.forEach { fragment ->
            if (fragment.script.isExplicitScript() && (fragment.script != active.script || fragment.language != active.language)) {
                result += ScriptFragment(TextRange(fragmentStart, fragment.range.start), active.script, active.language)
                fragmentStart = fragment.range.start
                active = fragment
            }
        }
        result += ScriptFragment(TextRange(fragmentStart, range.endExclusive), active.script, active.language)
        return result
    }

    private fun emptyLine(
        request: ParagraphLayoutRequest,
        range: TextRange,
        materialization: EditableLineMaterialization,
    ): EditableLineResult = ExactEditableLineLayouter.layout(
        EditableLineRequest(
            unicodeAnalysis = UnicodeAnalysis(
                range,
                request.unicodeAnalysis.unicodeData,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            ),
            shapedGlyphRuns = emptyList(),
            baseDirection = request.baseDirection.shapingDirection(),
            emptyLineBidiLevel = if (request.baseDirection == BaseDirection.LEFT_TO_RIGHT) 0 else 1,
            verticalMetrics = request.constraints.lineMetrics,
            materialization = materialization,
            cancellationToken = request.cancellationToken,
        ),
    )

    private fun place(line: EditableLine, region: LayoutRect, top: LayoutUnit): ComposedParagraphLine {
        val baseline = LayoutPoint(
            region.left,
            finiteUnit(top.value.toDouble() + line.verticalMetrics.ascent.value.toDouble(), "paragraph baseline"),
        )
        val bottom = finiteUnit(top.value.toDouble() + line.verticalMetrics.height.value.toDouble(), "paragraph line bottom")
        return ComposedParagraphLine(
            line = line,
            baseline = baseline,
            lineBox = LayoutRect(region.left, top, region.right, bottom),
            inlineAdvance = ExactEditableLineLayouter.inlineAdvance(line),
        )
    }

    private fun finiteUnit(value: Double, label: String): LayoutUnit {
        val narrowed = value.toFloat()
        if (!value.isFinite() || !narrowed.isFinite()) {
            throw ParagraphGeometryOverflowException("$label overflowed finite layout coordinates.")
        }
        return LayoutUnit(narrowed)
    }

    private fun intersection(left: TextRange, right: TextRange): TextRange? {
        val start = if (left.start >= right.start) left.start else right.start
        val end = if (left.endExclusive <= right.endExclusive) left.endExclusive else right.endExclusive
        return if (start < end) TextRange(start, end) else null
    }

    private fun overlaps(left: TextRange, right: TextRange): Boolean =
        left.start < right.endExclusive && right.start < left.endExclusive

    private fun graphemeFragments(range: TextRange, graphemes: List<TextRange>): List<TextRange> =
        graphemes.mapNotNull { intersection(it, range) }

    private fun BaseDirection.shapingDirection(): ShapingDirection = when (this) {
        BaseDirection.LEFT_TO_RIGHT -> ShapingDirection.LEFT_TO_RIGHT
        BaseDirection.RIGHT_TO_LEFT -> ShapingDirection.RIGHT_TO_LEFT
    }

    private fun Int.direction(): ShapingDirection =
        if (this % 2 == 0) ShapingDirection.LEFT_TO_RIGHT else ShapingDirection.RIGHT_TO_LEFT

    private fun FontFaceCapabilities.supports(requirements: FontAccessRequirementsSnapshot): Boolean =
        characterMapping && shaping && (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE || outline)

    private fun EditableLineMaterialization.requirements(): FontAccessRequirementsSnapshot = when (this) {
        EditableLineMaterialization.LayoutOnly -> FontAccessRequirementsSnapshot.layoutOnly()
        is EditableLineMaterialization.Renderable -> FontAccessRequirementsSnapshot.renderable(outlineProfile)
    }

    private fun EditableLineMaterialization.identity(): ParagraphMaterializationIdentity = when (this) {
        EditableLineMaterialization.LayoutOnly -> ParagraphMaterializationIdentity.LayoutOnly
        is EditableLineMaterialization.Renderable -> ParagraphMaterializationIdentity.Renderable(variant, outlineProfile)
    }

    private fun Int.isVariationSelector(): Boolean = this in 0xFE00..0xFE0F || this in 0xE0100..0xE01EF

    private fun Int.isFallbackIgnorable(): Boolean = this == 0x200D || isMandatoryControl()

    private fun Int.isMandatoryControl(): Boolean = this in MANDATORY_CONTROLS

    private fun isL1TrailingScalar(scalar: Int): Boolean = scalar in L1_TRAILING_SCALARS

    private fun String.isExplicitScript(): Boolean = this != COMMON_SCRIPT && this != INHERITED_SCRIPT

    private data class AssignedUnit(val range: TextRange, val instance: FontInstance, val controlOnly: Boolean)
    private data class MutableSourceLevel(val range: TextRange, var level: Int)
    private data class ShapingFragment(val range: TextRange, val script: String, val language: String, val level: Int)
    private data class ScriptFragment(val range: TextRange, val script: String, val language: String)

    private sealed interface AssignmentResult {
        data class Success(val assignments: List<AssignedUnit>) : AssignmentResult
        data class Failure(val error: EditableLineError) : AssignmentResult
        data object Cancelled : AssignmentResult
    }

    private sealed interface ShapeResult {
        data class Success(val runs: List<ShapedGlyphRun>) : ShapeResult
        data class Failure(val error: EditableLineError) : ShapeResult
        data object Cancelled : ShapeResult
    }

    private val MANDATORY_CONTROLS: Set<Int> = setOf(0x000A, 0x000B, 0x000C, 0x000D, 0x0085, 0x2028, 0x2029)
    private val L1_TRAILING_SCALARS: Set<Int> = MANDATORY_CONTROLS + setOf(
        0x0009,
        0x0020,
        0x1680,
        0x2000,
        0x2001,
        0x2002,
        0x2003,
        0x2004,
        0x2005,
        0x2006,
        0x2007,
        0x2008,
        0x2009,
        0x200A,
        0x205F,
        0x3000,
        0x202A,
        0x202B,
        0x202C,
        0x202D,
        0x202E,
        0x2066,
        0x2067,
        0x2068,
        0x2069,
    )
    private const val COMMON_SCRIPT: String = "Zyyy"
    private const val INHERITED_SCRIPT: String = "Zinh"
}

private class ParagraphGeometryOverflowException(message: String) : IllegalStateException(message)

private fun <Element> Iterable<Element>.immutableSnapshot(): List<Element> = ParagraphImmutableList(toList())

private class ParagraphImmutableList<Element>(source: List<Element>) : AbstractMutableList<Element>() {
    private val elements: List<Element> = source.toList()

    override val size: Int
        get() = elements.size

    override fun get(index: Int): Element = elements[index]

    override fun add(index: Int, element: Element): Unit = immutableMutation()

    override fun removeAt(index: Int): Element = immutableMutation()

    override fun set(index: Int, element: Element): Element = immutableMutation()

    private fun <Value> immutableMutation(): Value = throw UnsupportedOperationException("Immutable paragraph composition snapshot.")
}
