package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.IncrementalLayout
import org.graphiks.kalligraphie.api.IncrementalLayoutDiagnostics
import org.graphiks.kalligraphie.api.IncrementalLayoutError
import org.graphiks.kalligraphie.api.IncrementalLayoutRequest
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutCheckpoint
import org.graphiks.kalligraphie.api.LayoutConfigurationSignature
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutCoverage
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LineCheckpointSignature
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.RangeChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange

/** Synchronous boundary that composes complete paragraph lines from one proven target boundary. */
public fun interface IncrementalParagraphComputer {
    /**
     * Composes complete immutable lines beginning at [from] for [request].
     *
     * Successful output must use the request input revision and must cover the requested range;
     * failures and cancellation publish no partial current line.
     */
    public fun compose(from: TextRange, request: IncrementalLayoutRequest): IncrementalLayoutResult
}

/**
 * Portable synchronous orchestrator for exact complete-line incremental paragraph layout.
 *
 * Prior checkpoints are reused only when an internally retained state, all semantic
 * configuration, version transitions, and mapped line boundaries agree. Its private state cache
 * is bounded by [cacheBudgetBytes]; eviction changes performance only and falls back to document
 * start without changing published layout correctness.
 */
public class IncrementalParagraphLayoutEngine(
    private val cacheBudgetBytes: Long,
) {
    private val retainedStates: MutableList<RetainedState> = mutableListOf()
    private var retainedWeightBytes: Long = 0
    private var nextIdentity: Long = 1

    init {
        require(cacheBudgetBytes >= 0) { "Incremental layout cache budget must be non-negative." }
    }

    /**
     * Publishes only whole lines covering the requested range and line overscan.
     *
     * Unsafe or unavailable reuse starts at the target document boundary and reports conservative
     * invalidation. A suffix is considered stable only after an exact mapped line signature match;
     * handle identity alone never establishes stabilization.
     */
    public fun layout(
        request: IncrementalLayoutRequest,
        computer: IncrementalParagraphComputer,
    ): IncrementalLayoutResult {
        if (request.cancellationToken.isCancellationRequested()) return IncrementalLayoutResult.Cancelled

        val configuration = LayoutConfigurationSignature.from(request.input, request.constraints)
        val retained = request.previousState?.let(::findRetainedState)
        val reusable = retained?.takeIf { state ->
            state.key.configuration == configuration &&
                typographyInvalidationIsLocalized(request)
        }
        val affectedStart = firstAffectedTargetBoundary(request)
        val mappedCheckpoints = reusable?.key?.lineCheckpoints
            ?.mapNotNull { checkpoint ->
                mapSourceBoundary(checkpoint.range.start, request)?.let { mapped -> checkpoint to mapped }
            }
            ?.filter { (_, mapped) -> mapped <= affectedStart }
            ?.sortedWith { left, right -> left.second.compareTo(right.second) }
            .orEmpty()
        val checkpointStart = mappedCheckpoints
            .takeIf { it.isNotEmpty() }
            ?.get(maxOf(0, mappedCheckpoints.lastIndex - request.overscan.lineCount))
            ?.second
        val usedConservativeInvalidation = checkpointStart == null
        val reflowStart = checkpointStart ?: request.input.text.range.start
        val composeRange = TextRange(reflowStart, request.input.text.range.endExclusive)

        val composed = computer.compose(composeRange, request)
        if (request.cancellationToken.isCancellationRequested()) return IncrementalLayoutResult.Cancelled
        val composedSuccess = when (composed) {
            is IncrementalLayoutResult.Success -> composed
            is IncrementalLayoutResult.Failure -> return composed
            IncrementalLayoutResult.Cancelled -> return composed
        }
        val validationError = validateComputerOutput(request, reflowStart, composedSuccess.layout)
        if (validationError != null) return IncrementalLayoutResult.Failure(validationError)

        val publishedLines = selectPublishedLines(request, composedSuccess.layout.lines)
            ?: return IncrementalLayoutResult.Failure(
                IncrementalLayoutError.InvalidRange(
                    "Complete composed lines did not cover the requested target range.",
                ),
            )
        val coveredRange = TextRange(publishedLines.first().range.start, publishedLines.last().range.endExclusive)
        val publishedSignatures = publishedLines.map(LineCheckpointSignature::from)
        val stabilizedAt = reusable?.let { state ->
            findStabilizationBoundary(request, publishedSignatures, state.key.lineCheckpoints)
        }
        val invalidatedSuffix = when {
            coveredRange.endExclusive == request.input.text.range.endExclusive -> null
            stabilizedAt != null && stabilizedAt <= coveredRange.endExclusive -> null
            else -> TextRange(coveredRange.endExclusive, request.input.text.range.endExclusive)
        }
        val coverage = when (
            val created = LayoutCoverage.create(
                textVersion = request.input.text.version,
                range = coveredRange,
                isComplete = covers(coveredRange, request.requestedRange),
                invalidatedSuffix = invalidatedSuffix,
            )
        ) {
            is LayoutContractResult.Success -> created.value
            is LayoutContractResult.Failure -> return IncrementalLayoutResult.Failure(created.error)
        }
        val checkpoint = LayoutCheckpoint(request.input.text.version, request.input.typography.version)
        val identity = "incremental-layout-${nextIdentity++}"
        val state = LayoutStateHandle(
            identity = identity,
            checkpoint = checkpoint,
            coverage = coverage,
            configuration = configuration,
            lineCheckpoints = publishedSignatures,
        )
        val key = StateCacheKey(checkpoint, configuration, coverage, publishedSignatures)
        retainState(RetainedState(identity, key, estimateWeightBytes(publishedLines)))
        return IncrementalLayoutResult.Success(
            layout = PublishedIncrementalLayout(request.input, coverage, publishedLines, state),
            diagnostics = IncrementalLayoutDiagnostics(
                reflowStart = reflowStart,
                usedConservativeInvalidation = usedConservativeInvalidation,
                stabilizedAt = stabilizedAt,
            ),
        )
    }

    private fun findRetainedState(handle: LayoutStateHandle): RetainedState? = retainedStates.lastOrNull { retained ->
        retained.identity == handle.identity &&
            retained.key.checkpoint == handle.checkpoint &&
            retained.key.configuration == handle.configuration &&
            retained.key.coverage.sameAs(handle.coverage) &&
            retained.key.lineCheckpoints == handle.lineCheckpoints
    }

    private fun typographyInvalidationIsLocalized(request: IncrementalLayoutRequest): Boolean =
        request.delta?.typography?.rangeChange !is RangeChange.FullInvalidation

    private fun firstAffectedTargetBoundary(request: IncrementalLayoutRequest): TextIndex {
        val candidates = mutableListOf(request.requestedRange.start)
        request.delta?.text?.changes?.mapTo(candidates) { it.insertedTargetRange.start }
        val typographyRanges = request.delta?.typography?.rangeChange as? RangeChange.Proven
        typographyRanges?.targetRanges?.mapTo(candidates, TextRange::start)
        return candidates.minWith(TextIndex::compareTo)
    }

    private fun lastAffectedTargetBoundary(request: IncrementalLayoutRequest): TextIndex {
        val candidates = mutableListOf(request.requestedRange.start)
        request.delta?.text?.changes?.mapTo(candidates) { it.insertedTargetRange.endExclusive }
        val typographyRanges = request.delta?.typography?.rangeChange as? RangeChange.Proven
        typographyRanges?.targetRanges?.mapTo(candidates, TextRange::endExclusive)
        return candidates.maxWith(TextIndex::compareTo)
    }

    private fun mapSourceBoundary(source: TextIndex, request: IncrementalLayoutRequest): TextIndex? {
        val delta = request.delta?.text
        return if (delta == null) {
            source.takeIf { it.sharesVersionWith(request.input.text.range.start) }
        } else {
            delta.mapSourceBoundaryToTarget(source, request.input.text, afterInsertion = true)
        }
    }

    private fun mapSourceRange(
        source: TextRange,
        target: LayoutInput,
        delta: TextChangeSet?,
    ): TextRange? = if (delta == null) {
        source.takeIf { it.start.sharesVersionWith(target.text.range.start) }
    } else {
        delta.mapUnchangedSourceRangeToTarget(source, target.text)
    }

    private fun findStabilizationBoundary(
        request: IncrementalLayoutRequest,
        published: List<LineCheckpointSignature>,
        previous: List<LineCheckpointSignature>,
    ): TextIndex? {
        if (request.previousState?.coverage?.invalidatedSuffix != null) return null
        val affectedEnd = lastAffectedTargetBoundary(request)
        return published.firstNotNullOfOrNull { current ->
            if (current.range.endExclusive < affectedEnd) return@firstNotNullOfOrNull null
            val matchingPrevious = previous.firstOrNull { old ->
                mapSourceRange(old.range, request.input, request.delta?.text) == current.range &&
                    old.hasSameObservableLayout(current)
            }
            matchingPrevious?.let { current.range.endExclusive }
        }
    }

    private fun validateComputerOutput(
        request: IncrementalLayoutRequest,
        reflowStart: TextIndex,
        layout: IncrementalLayout,
    ): IncrementalLayoutError? {
        if (
            layout.input.text.version != request.input.text.version ||
            layout.input.typography.version != request.input.typography.version
        ) {
            return IncrementalLayoutError.VersionMismatch(
                "Incremental paragraph computer output must use the request input revisions.",
            )
        }
        if (layout.lines.isEmpty()) {
            return IncrementalLayoutError.InvalidRange("Incremental paragraph computer published no complete line.")
        }
        var expectedStart = reflowStart
        layout.lines.forEach { line ->
            if (!line.range.start.sharesVersionWith(request.input.text.range.start)) {
                return IncrementalLayoutError.VersionMismatch(
                    "Every composed line must use the request text revision.",
                )
            }
            if (line.range.start != expectedStart || line.range.endExclusive <= line.range.start) {
                return IncrementalLayoutError.InvalidRange(
                    "Incremental paragraph computer lines must be complete, contiguous, and ordered.",
                )
            }
            expectedStart = line.range.endExclusive
        }
        if (expectedStart != request.input.text.range.endExclusive) {
            return IncrementalLayoutError.InvalidRange(
                "Incremental paragraph computer must publish an explicit complete suffix.",
            )
        }
        return null
    }

    private fun selectPublishedLines(
        request: IncrementalLayoutRequest,
        composed: List<LineLayout>,
    ): List<LineLayout>? {
        val requested = request.requestedRange
        val first = composed.indexOfFirst { line ->
            if (requested.start == requested.endExclusive) {
                requested.start >= line.range.start && requested.start < line.range.endExclusive
            } else {
                line.range.start < requested.endExclusive && requested.start < line.range.endExclusive
            }
        }
        if (first < 0) return null
        val last = if (requested.start == requested.endExclusive) {
            first
        } else {
            composed.indexOfLast { line ->
                line.range.start < requested.endExclusive && requested.start < line.range.endExclusive
            }
        }
        if (last < first) return null
        val start = maxOf(0, first - request.overscan.lineCount)
        val endExclusive = minOf(composed.size, last + request.overscan.lineCount + 1)
        return composed.subList(start, endExclusive).immutableSnapshot()
    }

    private fun retainState(state: RetainedState) {
        if (cacheBudgetBytes == 0L || state.weightBytes > cacheBudgetBytes) return
        retainedStates += state
        retainedWeightBytes += state.weightBytes
        while (retainedWeightBytes > cacheBudgetBytes && retainedStates.isNotEmpty()) {
            retainedWeightBytes -= retainedStates.removeAt(0).weightBytes
        }
    }

    private fun estimateWeightBytes(lines: List<LineLayout>): Long = 256L + lines.sumOf { line ->
        256L +
            line.positionedGlyphRuns.size * 128L +
            line.positionedGlyphRuns.sumOf { run -> run.glyphs.size * 96L + run.sourceRun.clusters.size * 96L } +
            line.allCaretCandidates.size * 96L +
            line.diagnostics.size * 128L
    }

    private data class StateCacheKey(
        val checkpoint: LayoutCheckpoint,
        val configuration: LayoutConfigurationSignature,
        val coverage: LayoutCoverage,
        val lineCheckpoints: List<LineCheckpointSignature>,
    )

    private data class RetainedState(
        val identity: String,
        val key: StateCacheKey,
        val weightBytes: Long,
    )
}

private class PublishedIncrementalLayout(
    override val input: LayoutInput,
    override val coverage: LayoutCoverage,
    lines: List<LineLayout>,
    override val state: LayoutStateHandle,
) : IncrementalLayout {
    override val lines: List<LineLayout> = lines.immutableSnapshot()
}

private fun LayoutCoverage.sameAs(other: LayoutCoverage): Boolean =
    textVersion == other.textVersion &&
        range == other.range &&
        isComplete == other.isComplete &&
        invalidatedSuffix == other.invalidatedSuffix

private fun covers(coverage: TextRange, requested: TextRange): Boolean =
    coverage.start <= requested.start && coverage.endExclusive >= requested.endExclusive

private fun <Element> Iterable<Element>.immutableSnapshot(): List<Element> = EngineImmutableList(toList())

private class EngineImmutableList<Element>(source: List<Element>) : AbstractMutableList<Element>() {
    private val elements: List<Element> = source.toList()

    override val size: Int
        get() = elements.size

    override fun get(index: Int): Element = elements[index]

    override fun add(index: Int, element: Element): Unit = immutableMutation()

    override fun removeAt(index: Int): Element = immutableMutation()

    override fun set(index: Int, element: Element): Element = immutableMutation()

    private fun <Value> immutableMutation(): Value =
        throw UnsupportedOperationException("Immutable incremental layout snapshot.")
}
