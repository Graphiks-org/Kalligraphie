package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.IncrementalLayout
import org.graphiks.kalligraphie.api.IncrementalLayoutDiagnostics
import org.graphiks.kalligraphie.api.IncrementalLayoutError
import org.graphiks.kalligraphie.api.IncrementalLayoutRequest
import org.graphiks.kalligraphie.api.IncrementalLayoutResult
import org.graphiks.kalligraphie.api.LayoutCheckpoint
import org.graphiks.kalligraphie.api.LayoutConfigurationSignature
import org.graphiks.kalligraphie.api.LayoutContinuationSignature
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutCoverage
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutStateHandle
import org.graphiks.kalligraphie.api.LayoutTailState
import org.graphiks.kalligraphie.api.LineCheckpointSignature
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.RangeChange
import org.graphiks.kalligraphie.api.TextChangeSet
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange

/** Bounded complete-line materialization requested from a paragraph computer. */
public data class IncrementalMaterializationTarget(
    /** Earliest proven target boundary from which the computer may resume analysis. */
    public val reflowStart: TextIndex,
    /** Exact caller range whose complete containing lines must be returned. */
    public val requestedRange: TextRange,
) {
    init {
        require(reflowStart.sharesVersionWith(requestedRange.start)) {
            "The reflow boundary and requested range must use the same text revision."
        }
        require(reflowStart <= requestedRange.start) {
            "The reflow boundary must not follow the requested range."
        }
    }
}

/** One complete computed line and the resource-free continuation immediately after it. */
public data class IncrementalComputedLine(
    /** Complete observable line. */
    public val line: LineLayout,
    /** Complete semantic continuation after [line]. */
    public val continuation: LayoutContinuationSignature,
) {
    init {
        require(continuation.boundary == line.range.endExclusive) {
            "A computed-line continuation must begin at the line end boundary."
        }
    }
}

/** Explicit source state following the last line returned by a paragraph computer. */
public sealed interface IncrementalComputationTail {
    /** The returned complete lines reach the target document end. */
    public data object MaterializedThroughDocumentEnd : IncrementalComputationTail

    /** Exact suffix deliberately left outside this bounded computation. */
    public data class Unmaterialized(
        /** Suffix beginning immediately after the final returned line. */
        public val range: TextRange,
    ) : IncrementalComputationTail
}

/** Result of one synchronous bounded paragraph computation. */
public sealed interface IncrementalParagraphComputation {
    /** Complete lines needed for the target and overscan, plus explicit tail state. */
    public data class Success(
        /** Complete lines in logical order. */
        public val lines: List<IncrementalComputedLine>,
        /** State of source text following [lines]. */
        public val tail: IncrementalComputationTail,
    ) : IncrementalParagraphComputation

    /** Typed computation failure. */
    public data class Failure(
        /** Reason no layout was published. */
        public val error: IncrementalLayoutError,
    ) : IncrementalParagraphComputation

    /** Computation stopped cooperatively without publishing partial output. */
    public data object Cancelled : IncrementalParagraphComputation
}

/** Synchronous boundary that computes only complete lines for one bounded target and overscan. */
public fun interface IncrementalParagraphComputer {
    /**
     * Computes complete lines covering [target] plus [overscan].
     *
     * The implementation may inspect context outside the materialized target, but successful
     * output contains only complete lines and explicitly describes its unmaterialized tail.
     */
    public fun compose(
        target: IncrementalMaterializationTarget,
        overscan: LineOverscan,
        request: IncrementalLayoutRequest,
    ): IncrementalParagraphComputation
}

/**
 * Portable synchronous orchestrator for exact complete-line incremental paragraph layout.
 *
 * Reuse decisions are derived entirely from [LayoutStateHandle] and the current request. The
 * optional private cache budget is accepted for implementations that intern signatures, but this
 * implementation retains no cache, so eviction cannot affect any published observable.
 */
public class IncrementalParagraphLayoutEngine(
    cacheBudgetBytes: Long,
) {
    private var nextIdentity: Long = 1

    init {
        require(cacheBudgetBytes >= 0) { "Incremental layout cache budget must be non-negative." }
    }

    /**
     * Publishes only whole lines covering the requested range and line overscan.
     *
     * Unsafe or unavailable reuse starts at the target document boundary. A suffix is stable only
     * when both mapped observable line output and its continuation signature match prior state.
     */
    public fun layout(
        request: IncrementalLayoutRequest,
        computer: IncrementalParagraphComputer,
    ): IncrementalLayoutResult {
        if (request.cancellationToken.isCancellationRequested()) return IncrementalLayoutResult.Cancelled

        val configuration = LayoutConfigurationSignature.from(request.input, request.constraints)
        val reusable = reusableState(request, configuration)
        val affectedStart = firstAffectedTargetBoundary(request)
        val mappedCheckpoints = reusable?.lineCheckpoints
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
        val target = IncrementalMaterializationTarget(reflowStart, request.requestedRange)

        val computation = computer.compose(target, request.overscan, request)
        if (request.cancellationToken.isCancellationRequested()) return IncrementalLayoutResult.Cancelled
        val success = when (computation) {
            is IncrementalParagraphComputation.Success -> computation
            is IncrementalParagraphComputation.Failure -> return IncrementalLayoutResult.Failure(computation.error)
            IncrementalParagraphComputation.Cancelled -> return IncrementalLayoutResult.Cancelled
        }
        val validationError = validateComputerOutput(request, target, success)
        if (validationError != null) return IncrementalLayoutResult.Failure(validationError)

        val published = selectPublishedLines(request, success.lines)
            ?: return IncrementalLayoutResult.Failure(
                IncrementalLayoutError.InvalidRange(
                    "Complete computed lines did not cover the requested target range.",
                ),
            )
        val coveredRange = TextRange(published.first().line.range.start, published.last().line.range.endExclusive)
        val publishedSignatures = published.map { computed ->
            LineCheckpointSignature.from(computed.line, computed.continuation)
        }.immutableSnapshot()
        val stabilizedAt = reusable?.let { state ->
            findStabilizationBoundary(request, publishedSignatures, state.lineCheckpoints)
        }
        val tailState = when {
            coveredRange.endExclusive == request.input.text.range.endExclusive ->
                LayoutTailState.MaterializedThroughDocumentEnd
            stabilizedAt == coveredRange.endExclusive ->
                LayoutTailState.Stable(TextRange(coveredRange.endExclusive, request.input.text.range.endExclusive))
            else -> LayoutTailState.Invalidated(
                TextRange(coveredRange.endExclusive, request.input.text.range.endExclusive),
            )
        }
        val coverage = when (
            val created = LayoutCoverage.create(
                textVersion = request.input.text.version,
                range = coveredRange,
                isComplete = covers(coveredRange, request.requestedRange),
                tailState = tailState,
            )
        ) {
            is LayoutContractResult.Success -> created.value
            is LayoutContractResult.Failure -> return IncrementalLayoutResult.Failure(created.error)
        }
        val checkpoint = LayoutCheckpoint(request.input.text.version, request.input.typography.version)
        val state = LayoutStateHandle(
            identity = "incremental-layout-${nextIdentity++}",
            checkpoint = checkpoint,
            coverage = coverage,
            configuration = configuration,
            continuation = publishedSignatures.last().continuation,
            lineCheckpoints = publishedSignatures,
        )
        return IncrementalLayoutResult.Success(
            layout = PublishedIncrementalLayout(
                request.input,
                coverage,
                published.map(IncrementalComputedLine::line),
                state,
            ),
            diagnostics = IncrementalLayoutDiagnostics(
                reflowStart = reflowStart,
                usedConservativeInvalidation = usedConservativeInvalidation,
                stabilizedAt = stabilizedAt,
            ),
        )
    }

    private fun reusableState(
        request: IncrementalLayoutRequest,
        configuration: LayoutConfigurationSignature,
    ): LayoutStateHandle? {
        val previous = request.previousState ?: return null
        if (previous.configuration != configuration) return null
        if (previous.lineCheckpoints.isEmpty() || previous.continuation == null) return null
        if (!textTransitionIsProven(previous, request)) return null
        if (!typographyTransitionIsProven(previous, request)) return null
        return previous
    }

    private fun textTransitionIsProven(
        previous: LayoutStateHandle,
        request: IncrementalLayoutRequest,
    ): Boolean {
        val source = previous.checkpoint.textVersion
        val target = request.input.text.version
        val delta = request.delta?.text
        return if (source == target) {
            delta == null || (delta.sourceVersion == source && delta.targetVersion == target)
        } else {
            delta != null && delta.sourceVersion == source && delta.targetVersion == target
        }
    }

    private fun typographyTransitionIsProven(
        previous: LayoutStateHandle,
        request: IncrementalLayoutRequest,
    ): Boolean {
        val source = previous.checkpoint.typographyVersion
        val target = request.input.typography.version
        val delta = request.delta?.typography
        if (source == target && delta == null) return true
        if (delta == null || delta.sourceVersion != source || delta.targetVersion != target) return false
        val proven = delta.rangeChange as? RangeChange.Proven ?: return false
        if (proven.sourceRanges.isEmpty() || proven.targetRanges.isEmpty()) return false
        return proven.sourceRanges.all { range ->
            range.start.sharesVersionWith(previous.coverage.range.start)
        } && proven.targetRanges.all { range ->
            range.start.sharesVersionWith(request.input.text.range.start)
        }
    }

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
        if (request.previousState?.coverage?.tailState is LayoutTailState.Invalidated) return null
        val affectedEnd = lastAffectedTargetBoundary(request)
        return published.firstNotNullOfOrNull { current ->
            if (current.range.endExclusive < affectedEnd) return@firstNotNullOfOrNull null
            val matchingPrevious = previous.firstOrNull { old ->
                mapSourceRange(old.range, request.input, request.delta?.text) == current.range &&
                    old.hasSameObservableLayout(current) &&
                    old.continuation.hasSameSemantics(current.continuation)
            }
            matchingPrevious?.let { current.range.endExclusive }
        }
    }

    private fun validateComputerOutput(
        request: IncrementalLayoutRequest,
        target: IncrementalMaterializationTarget,
        computation: IncrementalParagraphComputation.Success,
    ): IncrementalLayoutError? {
        if (computation.lines.isEmpty()) {
            return IncrementalLayoutError.InvalidRange("Incremental paragraph computer published no complete line.")
        }
        val first = computation.lines.first().line.range
        if (!first.start.sharesVersionWith(request.input.text.range.start)) {
            return IncrementalLayoutError.VersionMismatch(
                "Every computed line must use the request text revision.",
            )
        }
        if (first.start < target.reflowStart) {
            return IncrementalLayoutError.InvalidRange(
                "Incremental paragraph computer output must not precede its reflow boundary.",
            )
        }
        var expectedStart = first.start
        computation.lines.forEach { computed ->
            val line = computed.line
            if (!line.range.start.sharesVersionWith(request.input.text.range.start)) {
                return IncrementalLayoutError.VersionMismatch(
                    "Every computed line must use the request text revision.",
                )
            }
            if (line.range.start != expectedStart || line.range.endExclusive <= line.range.start) {
                return IncrementalLayoutError.InvalidRange(
                    "Incremental paragraph computer lines must be complete, contiguous, and ordered.",
                )
            }
            if (computed.continuation.boundary != line.range.endExclusive) {
                return IncrementalLayoutError.InvalidRange(
                    "Every computed continuation must begin at its complete line end.",
                )
            }
            expectedStart = line.range.endExclusive
        }
        if (!covers(TextRange(first.start, expectedStart), request.requestedRange)) {
            return IncrementalLayoutError.InvalidRange(
                "Incremental paragraph computer output must cover the requested target range.",
            )
        }
        val documentEnd = request.input.text.range.endExclusive
        return when (val tail = computation.tail) {
            IncrementalComputationTail.MaterializedThroughDocumentEnd -> if (expectedStart != documentEnd) {
                IncrementalLayoutError.InvalidRange(
                    "A fully materialized computation tail requires the final line to reach document end.",
                )
            } else {
                null
            }
            is IncrementalComputationTail.Unmaterialized -> if (
                tail.range.start != expectedStart || tail.range.endExclusive != documentEnd
            ) {
                IncrementalLayoutError.InvalidRange(
                    "An unmaterialized computation tail must be the exact suffix after the final line.",
                )
            } else {
                null
            }
        }
    }

    private fun selectPublishedLines(
        request: IncrementalLayoutRequest,
        computed: List<IncrementalComputedLine>,
    ): List<IncrementalComputedLine>? {
        val requested = request.requestedRange
        val first = computed.indexOfFirst { value -> value.line.range.intersectsOrContains(requested) }
        if (first < 0) return null
        val last = if (requested.start == requested.endExclusive) {
            first
        } else {
            computed.indexOfLast { value -> value.line.range.intersectsOrContains(requested) }
        }
        if (last < first) return null
        val start = maxOf(0, first - request.overscan.lineCount)
        val endExclusive = minOf(computed.size, last + request.overscan.lineCount + 1)
        return computed.subList(start, endExclusive).immutableSnapshot()
    }
}

private class PublishedIncrementalLayout(
    override val input: LayoutInput,
    override val coverage: LayoutCoverage,
    lines: List<LineLayout>,
    override val state: LayoutStateHandle,
) : IncrementalLayout {
    override val lines: List<LineLayout> = lines.immutableSnapshot()
}

private fun TextRange.intersectsOrContains(other: TextRange): Boolean = if (other.start == other.endExclusive) {
    other.start >= start && other.start < endExclusive
} else {
    start < other.endExclusive && other.start < endExclusive
}

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
