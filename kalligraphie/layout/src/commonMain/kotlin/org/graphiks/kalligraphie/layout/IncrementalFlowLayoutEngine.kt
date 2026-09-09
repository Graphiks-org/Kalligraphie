@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationContext
import org.graphiks.kalligraphie.api.FlowCompositionDiagnostic
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionInputIdentity
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowContinuation
import org.graphiks.kalligraphie.api.FlowLayout
import org.graphiks.kalligraphie.api.FlowLayoutCheckpoint
import org.graphiks.kalligraphie.api.FlowLayoutConfigurationSignature
import org.graphiks.kalligraphie.api.FlowLayoutDiagnostics
import org.graphiks.kalligraphie.api.FlowLayoutState
import org.graphiks.kalligraphie.api.IncrementalFlowLayoutRequest
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutCoverage
import org.graphiks.kalligraphie.api.LayoutTailState
import org.graphiks.kalligraphie.api.ParagraphFragment
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.RangeChange
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange

/**
 * Pure portable orchestrator for bounded and incremental flow-chain composition.
 *
 * The engine consumes one completely prepared [ParagraphLayoutRequest], derives all suffix
 * requests from its immutable Unicode and line-break analyses, and invokes [FlowParagraphComposer]
 * only for complete bounded fragments. It owns no backend, analyzer, region, renderer, page, or
 * platform resource and retains none in the returned [FlowLayoutState].
 */
public object IncrementalFlowLayoutEngine {
    private const val MAX_RETAINED_CHECKPOINTS: Int = 12
    private const val REQUEST_CHECKPOINT_WINDOW: Int = 6

    /**
     * Materializes requested flow coverage, reusing semantically valid structured checkpoints.
     *
     * A versioned edit maps unchanged checkpoints into the target revision and restarts at the
     * last valid checkpoint no later than either the first affected dependency or the requested
     * source start. Composition stops after exact coverage plus overscan, at physical paragraph
     * end, or at a matching stable checkpoint after the requested range. Cancellation and failure
     * publish no [FlowLayout].
     */
    public fun layout(
        request: IncrementalFlowLayoutRequest,
        paragraph: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
    ): FlowCompositionResult<FlowLayout> = layout(
        request,
        paragraph,
        materialization,
        EditorOperationContext.create(request.operationProfile, request.cancellationToken),
    )

    /** Materializes flow within a caller-owned complete-operation budget. */
    @org.graphiks.kalligraphie.api.KalligraphieInternalApi
    public fun layout(
        request: IncrementalFlowLayoutRequest,
        paragraph: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
        context: EditorOperationContext,
    ): FlowCompositionResult<FlowLayout> {
        context.sourceLimit(request.input.text)?.let {
            return FlowCompositionResult.Failure(FlowCompositionError.OperationLimitExceeded(it))
        }
        context.scalarLimit(request.input.text)?.let {
            return FlowCompositionResult.Failure(FlowCompositionError.OperationLimitExceeded(it))
        }
        validatePreparedParagraph(request, paragraph)?.let { return FlowCompositionResult.Failure(it) }
        if (request.cancellationToken.isCancellationRequested()) {
            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        val configuration = FlowLayoutConfigurationSignature.capture(request, paragraph)
        val inputIdentity = FlowCompositionInputIdentity(
            request.input.text.version,
            request.input.typography.version,
        )
        val previous = request.previousState
        val sameInput = previous != null && previous.inputIdentity == inputIdentity
        val sameReplayInputs = sameInput && checkNotNull(previous).configuration == configuration
        if (sameInput) {
            val retained = checkNotNull(previous)
            if (
                retained.configuration != configuration &&
                !retained.configuration.matchesExceptPreparedAnalyses(configuration)
            ) {
                return incompatible("The retained flow state uses different paragraph or region inputs.")
            }
        }

        if (sameReplayInputs) {
            checkNotNull(previous)
            selectSatisfiedPrefix(request, previous.materializedFragments)?.let { selected ->
                val tail = selected.last().continuation
                val tailState = tail?.let { LayoutTailState.Invalidated(it.remainingSourceRange) }
                    ?: LayoutTailState.MaterializedThroughDocumentEnd
                return publish(
                    request,
                    inputIdentity,
                    configuration,
                    selected,
                    previous.checkpoints.takeWhile { checkpoint ->
                        checkpoint.laidOutRange.endExclusive <= selected.last().laidOutRange.endExclusive
                    },
                    tail,
                    tailState,
                    FlowLayoutDiagnostics(previous.coverage.range.endExclusive, false),
                    emptyList(),
                )
            }
        }

        val mappedPrevious = if (
            previous != null &&
            (
                previous.configuration == configuration ||
                    (!sameInput && previous.configuration.matchesForVersionedCheckpointMapping(configuration))
            )
        ) {
            mapPreviousCheckpoints(request, paragraph, previous, inputIdentity)
        } else {
            emptyList()
        }
        val affectedStart = firstAffectedTargetBoundary(request, paragraph, previous)
        val resumeFromPublishedTail = sameReplayInputs && checkNotNull(previous).let { state ->
            state.continuation != null && request.requestedRange.start >= state.coverage.range.start
        }
        val restartBoundary = if (affectedStart <= request.requestedRange.start) {
            affectedStart
        } else {
            request.requestedRange.start
        }
        val restart = if (sameReplayInputs && resumeFromPublishedTail) {
            FlowLayoutCheckpoint.capture(checkNotNull(previous).materializedFragments.last())
        } else if (sameReplayInputs) {
            checkNotNull(previous).checkpoints.lastOrNull { checkpoint ->
                checkpoint.laidOutRange.endExclusive <= request.requestedRange.start
            }
        } else {
            mappedPrevious.lastOrNull { checkpoint -> checkpoint.laidOutRange.endExclusive <= restartBoundary }
        }
        val continuationAtStart = restart?.continuation
        val reflowStart = continuationAtStart?.remainingSourceRange?.start ?: request.input.text.range.start
        val conservative = previous != null && continuationAtStart == null
        val carriedCheckpoints = mappedPrevious.takeWhile { checkpoint ->
            checkpoint.laidOutRange.endExclusive <= reflowStart
        }.toMutableList()
        val fragments = if (resumeFromPublishedTail && continuationAtStart != null) {
            checkNotNull(previous).materializedFragments.toMutableList()
        } else {
            mutableListOf()
        }
        var continuation = continuationAtStart
        var sourceRange = continuation?.remainingSourceRange ?: request.input.text.range
        val diagnostics = mutableListOf<FlowCompositionDiagnostic>()
        var stabilizedAt: TextIndex? = null

        while (true) {
            if (request.cancellationToken.isCancellationRequested()) {
                return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
            }
            val suffixRequest = paragraph.forFlowSourceRange(sourceRange)
            val composed = FlowParagraphComposer.layoutFragment(
                request = suffixRequest,
                materialization = materialization,
                chain = request.flowChain,
                inputIdentity = inputIdentity,
                continuation = continuation,
                maximumLines = 1,
                flowConfiguration = configuration,
                context = context,
            )
            val success = when (composed) {
                is FlowCompositionResult.Success -> composed
                is FlowCompositionResult.Failure -> return composed
            }
            val fragment = success.value
            fragments += fragment
            diagnostics += success.diagnostics
            continuation = fragment.continuation
            val currentCheckpoint = continuation?.let { FlowLayoutCheckpoint.capture(fragment) }
            if (currentCheckpoint != null) {
                carriedCheckpoints += currentCheckpoint
                val prior = mappedPrevious.firstOrNull { checkpoint ->
                    checkpoint.laidOutRange == currentCheckpoint.laidOutRange
                }
                if (
                    currentCheckpoint.laidOutRange.endExclusive >= affectedStart &&
                    prior != null &&
                    prior.hasSameObservableLayout(currentCheckpoint) &&
                    prior.hasSameFlowSemantics(currentCheckpoint)
                ) {
                    stabilizedAt = currentCheckpoint.laidOutRange.endExclusive
                }
            }

            val targetCovered = targetSatisfied(request, fragments, continuation)
            val stableAfterTarget = stabilizedAt != null && coversRequestedRange(
                request.requestedRange,
                TextRange(fragments.first().laidOutRange.start, fragments.last().laidOutRange.endExclusive),
            )
            if (targetCovered || stableAfterTarget) {
                val tailState = when {
                    continuation == null -> LayoutTailState.MaterializedThroughDocumentEnd
                    stableAfterTarget -> LayoutTailState.Stable(continuation.remainingSourceRange)
                    else -> LayoutTailState.Invalidated(continuation.remainingSourceRange)
                }
                return publish(
                    request,
                    inputIdentity,
                    configuration,
                    fragments,
                    carriedCheckpoints,
                    continuation,
                    tailState,
                    FlowLayoutDiagnostics(reflowStart, conservative, stabilizedAt),
                    diagnostics,
                )
            }
            continuation ?: return incompatible(
                "Flow composition ended before complete lines covered the requested range.",
            )
            sourceRange = continuation.remainingSourceRange
        }
    }

    private fun mapPreviousCheckpoints(
        request: IncrementalFlowLayoutRequest,
        paragraph: ParagraphLayoutRequest,
        previous: FlowLayoutState,
        inputIdentity: FlowCompositionInputIdentity,
    ): List<FlowLayoutCheckpoint> {
        if (previous.inputIdentity == inputIdentity) return previous.checkpoints
        val textDelta = request.delta?.text
        val contentChanged = textDelta != null || request.delta?.typography != null
        return previous.checkpoints.mapNotNull { checkpoint ->
            if (
                contentChanged &&
                (
                    checkpoint.continuation.fragmentationCommitment != null ||
                        checkpoint.continuation.relaxedConstraints.isNotEmpty()
                )
            ) {
                return@mapNotNull null
            }
            val mappedRange = if (textDelta == null) {
                checkpoint.laidOutRange.takeIf { range ->
                    range.start.sharesVersionWith(request.input.text.range.start)
                }
            } else {
                textDelta.mapUnchangedSourceRangeToTarget(checkpoint.laidOutRange, request.input.text)
            } ?: return@mapNotNull null
            val remaining = TextRange(mappedRange.endExclusive, request.input.text.range.endExclusive)
            val old = checkpoint.continuation
            val region = request.flowChain.regions.getOrNull(old.regionIndex) ?: return@mapNotNull null
            if (region.identity != old.regionIdentity) return@mapNotNull null
            val replayRange = TextRange(mappedRange.start, request.input.text.range.endExclusive)
            val targetContinuation = try {
                request.flowChain.createContinuation(
                    inputIdentity = inputIdentity,
                    request = paragraph.forFlowSourceRange(replayRange),
                    paragraphRange = request.input.text.range,
                    remainingSourceRange = remaining,
                    regionIndex = old.regionIndex,
                    writingMode = old.writingMode,
                    nextBlockOffset = old.nextBlockOffset,
                    relaxedConstraints = old.relaxedConstraints,
                    fragmentationCommitment = old.fragmentationCommitment,
                )
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            checkpoint.remap(mappedRange, targetContinuation)
        }
    }

    private fun firstAffectedTargetBoundary(
        request: IncrementalFlowLayoutRequest,
        paragraph: ParagraphLayoutRequest,
        previous: FlowLayoutState?,
    ): TextIndex {
        val candidates = mutableListOf<TextIndex>()
        val textChanges = request.delta?.text?.changes.orEmpty()
        if (textChanges.isNotEmpty()) {
            val baseLevel = if (paragraph.baseDirection == org.graphiks.kalligraphie.api.BaseDirection.LEFT_TO_RIGHT) {
                0
            } else {
                1
            }
            val targetHasNonLocalBidiDependencies = paragraph.unicodeAnalysis.logicalBidiRuns.let { runs ->
                runs.size != 1 || runs.any { run -> run.level != baseLevel }
            }
            if (
                targetHasNonLocalBidiDependencies ||
                previous?.checkpoints?.any(FlowLayoutCheckpoint::hasNonLocalBidiDependencies) == true
            ) {
                return request.input.text.range.start
            }
            val editStart = textChanges
                .map { change -> change.insertedTargetRange.start }
                .minWith(TextIndex::compareTo)
            if (paragraph.hyphenationMode == HyphenationMode.AUTO) {
                candidates += automaticHyphenationDependencyStart(request, editStart)
            } else {
                val precedingCluster = paragraph.unicodeAnalysis.graphemeClusters.lastOrNull { cluster ->
                    cluster.start < editStart && cluster.endExclusive <= editStart
                }
                candidates += precedingCluster?.start ?: request.input.text.range.start
            }
        }
        when (val typographyChange = request.delta?.typography?.rangeChange) {
            is RangeChange.Proven -> typographyChange.targetRanges.mapTo(candidates, TextRange::start)
            RangeChange.FullInvalidation -> candidates += request.input.text.range.start
            null -> Unit
        }
        return candidates.minWithOrNull(TextIndex::compareTo) ?: request.input.text.range.start
    }

    private fun automaticHyphenationDependencyStart(
        request: IncrementalFlowLayoutRequest,
        editStart: TextIndex,
    ): TextIndex {
        val snapshot = request.input.text
        var ordinal = (0..snapshot.scalars.size).first { scalarBoundary ->
            snapshot.textIndexAtScalarBoundary(scalarBoundary) == editStart
        }
        while (ordinal > 0) {
            val scalar = snapshot.scalars[ordinal - 1]
            when {
                scalar in 'A'.code..'Z'.code || scalar in 'a'.code..'z'.code -> ordinal -= 1
                scalar == ' '.code || scalar == '\t'.code || scalar == '\n'.code || scalar == '\r'.code ->
                    return snapshot.textIndexAtScalarBoundary(ordinal)
                else -> return snapshot.range.start
            }
        }
        return snapshot.range.start
    }

    private fun selectSatisfiedPrefix(
        request: IncrementalFlowLayoutRequest,
        fragments: List<ParagraphFragment>,
    ): List<ParagraphFragment>? = fragments.indices.firstOrNull { index ->
        val prefix = fragments.take(index + 1)
        targetSatisfied(request, prefix, prefix.last().continuation)
    }?.let { index -> fragments.take(index + 1) }

    private fun targetSatisfied(
        request: IncrementalFlowLayoutRequest,
        fragments: List<ParagraphFragment>,
        tail: FlowContinuation?,
    ): Boolean {
        if (fragments.isEmpty()) return false
        val coverage = TextRange(fragments.first().laidOutRange.start, fragments.last().laidOutRange.endExclusive)
        if (!coversRequestedRange(request.requestedRange, coverage)) return false
        if (tail == null) return true
        val lines = fragments.flatMap(ParagraphFragment::lines)
        val requested = request.requestedRange
        val targetLineIndex = if (requested.start == requested.endExclusive) {
            lines.indexOfFirst { line ->
                requested.start >= line.range.start && requested.start < line.range.endExclusive
            }
        } else {
            lines.indexOfFirst { line -> line.range.endExclusive >= requested.endExclusive }
        }
        return targetLineIndex >= 0 && lines.lastIndex - targetLineIndex >= request.overscan.lineCount
    }

    private fun coversRequestedRange(requested: TextRange, coverage: TextRange): Boolean =
        if (requested.start == requested.endExclusive) {
            requested.start >= coverage.start && requested.start <= coverage.endExclusive
        } else {
            requested.start >= coverage.start && requested.endExclusive <= coverage.endExclusive
        }

    private fun publish(
        request: IncrementalFlowLayoutRequest,
        inputIdentity: FlowCompositionInputIdentity,
        configuration: FlowLayoutConfigurationSignature,
        fragments: List<ParagraphFragment>,
        checkpoints: List<FlowLayoutCheckpoint>,
        tail: FlowContinuation?,
        tailState: LayoutTailState,
        diagnostics: FlowLayoutDiagnostics,
        flowDiagnostics: List<FlowCompositionDiagnostic>,
    ): FlowCompositionResult<FlowLayout> {
        val range = TextRange(fragments.first().laidOutRange.start, fragments.last().laidOutRange.endExclusive)
        val coverage = when (
            val created = LayoutCoverage.create(
                request.input.text.version,
                range,
                coversRequestedRange(request.requestedRange, range),
                tailState,
            )
        ) {
            is LayoutContractResult.Success -> created.value
            is LayoutContractResult.Failure -> return incompatible(created.error.message)
        }
        val state = when (
            val created = FlowLayoutState.create(
                inputIdentity,
                request.flowChain.compositionIdentity,
                coverage,
                configuration,
                fragments,
                retainCheckpoints(request, fragments, checkpoints, tail),
                tail,
            )
        ) {
            is FlowCompositionResult.Success -> created.value
            is FlowCompositionResult.Failure -> return created
        }
        if (request.cancellationToken.isCancellationRequested()) {
            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        return when (
            val created = FlowLayout.create(
                inputIdentity,
                request.requestedRange,
                fragments,
                coverage,
                tail,
                state,
                diagnostics,
            )
        ) {
            is FlowCompositionResult.Success -> if (request.cancellationToken.isCancellationRequested()) {
                FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
            } else {
                FlowCompositionResult.Success(created.value, flowDiagnostics)
            }
            is FlowCompositionResult.Failure -> created
        }
    }

    private fun retainCheckpoints(
        request: IncrementalFlowLayoutRequest,
        fragments: List<ParagraphFragment>,
        checkpoints: List<FlowLayoutCheckpoint>,
        tail: FlowContinuation?,
    ): List<FlowLayoutCheckpoint> {
        val tailCheckpoint = tail?.let { continuation ->
            fragments.lastOrNull { fragment -> fragment.continuation === continuation }
                ?.let(FlowLayoutCheckpoint::capture)
        }
        val ordered = (checkpoints + listOfNotNull(tailCheckpoint))
            .asReversed()
            .distinctBy { checkpoint -> checkpoint.laidOutRange.endExclusive }
            .asReversed()
            .sortedWith { left, right ->
                left.laidOutRange.endExclusive.compareTo(right.laidOutRange.endExclusive)
            }
        if (ordered.size <= MAX_RETAINED_CHECKPOINTS) return ordered

        val pivot = ordered.indexOfFirst { checkpoint ->
            checkpoint.laidOutRange.endExclusive >= request.requestedRange.endExclusive
        }.takeIf { it >= 0 } ?: ordered.lastIndex
        val selected = linkedSetOf<Int>()
        val windowStart = (pivot - REQUEST_CHECKPOINT_WINDOW / 2).coerceAtLeast(0)
            .coerceAtMost(ordered.size - REQUEST_CHECKPOINT_WINDOW)
        repeat(REQUEST_CHECKPOINT_WINDOW) { offset -> selected += windowStart + offset }
        if (tail != null) selected += ordered.lastIndex

        val anchorSlots = MAX_RETAINED_CHECKPOINTS - selected.size
        repeat(anchorSlots) { ordinal ->
            selected += ((ordinal + 1) * ordered.size) / (anchorSlots + 1)
        }
        if (selected.size < MAX_RETAINED_CHECKPOINTS) {
            for (index in ordered.indices.reversed()) {
                selected += index
                if (selected.size == MAX_RETAINED_CHECKPOINTS) break
            }
        }
        return selected.sorted().map(ordered::get)
    }

    private fun validatePreparedParagraph(
        request: IncrementalFlowLayoutRequest,
        paragraph: ParagraphLayoutRequest,
    ): FlowCompositionError? {
        if (paragraph.snapshot.version != request.input.text.version || paragraph.sourceRange != request.input.text.range) {
            return FlowCompositionError.IncompatibleState(
                "The prepared paragraph must cover the complete target text revision.",
            )
        }
        if (paragraph.constraints != request.constraints) {
            return FlowCompositionError.IncompatibleState(
                "The prepared paragraph constraints must equal the portable flow request constraints.",
            )
        }
        if (paragraph.continuation != null) {
            return FlowCompositionError.IncompatibleContinuation(
                "Incremental flow composition does not consume rectangular paragraph continuations.",
            )
        }
        if (paragraph.overflowPolicy != org.graphiks.kalligraphie.api.OverflowPolicy.Continue) {
            return FlowCompositionError.UnsupportedOverflowPolicy(paragraph.overflowPolicy)
        }
        return null
    }

    private fun incompatible(message: String): FlowCompositionResult.Failure =
        FlowCompositionResult.Failure(FlowCompositionError.IncompatibleState(message))
}

private fun ParagraphLayoutRequest.forFlowSourceRange(sourceRange: TextRange): ParagraphLayoutRequest =
    ParagraphLayoutRequest(
        snapshot = snapshot,
        sourceRange = sourceRange,
        unicodeAnalysis = unicodeAnalysis,
        lineBreakAnalysis = lineBreakAnalysis,
        constraints = constraints,
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
        inlineObjects = inlineObjects?.let { objects ->
            InlineObjectSnapshot(objects.entries.filter { entry ->
                entry.index >= sourceRange.start && entry.index < sourceRange.endExclusive
            })
        },
        textOrientation = textOrientation,
        verticalMetricsPolicy = verticalMetricsPolicy,
        cancellationToken = cancellationToken,
        operationProfile = operationProfile,
    )
