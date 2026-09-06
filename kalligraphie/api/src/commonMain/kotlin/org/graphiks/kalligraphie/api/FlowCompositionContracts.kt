package org.graphiks.kalligraphie.api

/**
 * Opaque identity of one immutable revision of a [FlowRegion].
 *
 * A region must publish a fresh identity whenever its bounds or query behavior changes. Identities
 * are equality-comparable only; they expose no caller-controlled string or numeric value.
 */
public class FlowRegionIdentity private constructor() {
    /** Factories for resource-free region revision identities. */
    public companion object {
        /** Creates a fresh identity for one immutable region revision. */
        public fun create(): FlowRegionIdentity = FlowRegionIdentity()
    }

    /** Returns a diagnostic representation without exposing identity data. */
    override fun toString(): String = "FlowRegionIdentity()"
}

/**
 * Opaque identity of one flow-composition context.
 *
 * It binds continuations to the exact region chain and fragmentation configuration that created
 * them. The token owns no document, page, renderer, or platform resource.
 */
public class FlowCompositionIdentity private constructor() {
    /** Factories for resource-free composition identities. */
    public companion object {
        /** Creates a fresh identity for one immutable composition context. */
        public fun create(): FlowCompositionIdentity = FlowCompositionIdentity()
    }

    /** Returns a diagnostic representation without exposing identity data. */
    override fun toString(): String = "FlowCompositionIdentity()"
}

/**
 * Resource-free input identity required to validate flow continuation reuse.
 *
 * Both revisions are opaque equality tokens. The value retains neither source text nor typography
 * configuration and is therefore safe to store in an immutable [FlowContinuation].
 */
public data class FlowCompositionInputIdentity(
    /** Exact immutable text revision being composed. */
    public val textVersion: TextVersion,
    /** Exact immutable typography revision being composed. */
    public val typographyVersion: TypographyVersion,
)

/**
 * Logical block-axis band occupied by one candidate line box.
 *
 * Coordinates are offsets from the region's logical block start. Instances deliberately permit
 * malformed provider input so [queryFlowRegion] can report a typed public error. A validated band
 * has finite coordinates, a non-negative start, a strictly positive extent, and stays within the
 * queried region's logical block extent.
 */
public data class LineBand(
    /** Logical block offset at which the candidate line starts. */
    public val blockStart: Float,
    /** Logical block extent of the complete candidate line box. */
    public val blockExtent: Float,
)

/**
 * Half-open logical inline interval returned by a [FlowRegion].
 *
 * Instances deliberately permit malformed provider output. [queryFlowRegion] accepts only finite,
 * non-empty intervals in ascending logical order, disjoint from their neighbors, and bounded by
 * the region's inline extent. Invalid input is never sorted, merged, clipped, or otherwise repaired.
 */
public data class InlineInterval(
    /** Inclusive logical inline offset from the region's inline start. */
    public val start: Float,
    /** Exclusive logical inline offset from the region's inline start. */
    public val endExclusive: Float,
)

/** Typed raw answer supplied by [FlowRegion.query]. */
public sealed interface FlowRegionResult {
    /** One or more candidate spaces in logical inline progression order. */
    public class AvailableIntervals(
        intervals: List<InlineInterval>,
    ) : FlowRegionResult {
        /** Immutable snapshot of the provider's intervals, preserved without normalization. */
        public val intervals: List<InlineInterval> = intervals.immutableListSnapshot()
    }

    /** No inline space exists at this band; composition may continue at [nextBlockOffset]. */
    public data class Empty(
        /** Finite logical block offset that must be strictly greater than the requested start. */
        public val nextBlockOffset: Float,
    ) : FlowRegionResult

    /** The region contains no further block-axis space. */
    public data object EndOfRegion : FlowRegionResult
}

/**
 * Consumer-supplied portable geometry for line composition.
 *
 * Implementations must be immutable, pure, deterministic, thread-safe, and stable for identical
 * [WritingMode]/[LineBand] inputs. [bounds] is a finite non-empty physical local rectangle; query
 * coordinates are logical offsets within its width or height. The interface owns neither its
 * containing page nor any renderer resource.
 */
public interface FlowRegion {
    /** Identity of this exact immutable geometry and query-behavior revision. */
    public val identity: FlowRegionIdentity

    /** Finite physical local bounds used to validate logical block and inline offsets. */
    public val bounds: LayoutRect

    /**
     * Maximum same-origin line-band refinements accepted from this region.
     *
     * The value must be positive. A composer additionally applies its own implementation ceiling.
     */
    public val maximumRefinements: Int
        get() = 8

    /**
     * Returns raw available space for [lineBand] in [writingMode].
     *
     * Callers use [queryFlowRegion] or [FlowChain.query] to validate this untrusted protocol answer
     * before composition. Implementations must not mutate retained state or vary identical answers.
     */
    public fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult
}

/** Coordinate field identified by [FlowCompositionError.NonFiniteCoordinate]. */
public enum class FlowCoordinate {
    /** [LineBand.blockStart]. */
    BAND_BLOCK_START,

    /** [LineBand.blockExtent]. */
    BAND_BLOCK_EXTENT,

    /** [InlineInterval.start]. */
    INTERVAL_START,

    /** [InlineInterval.endExclusive]. */
    INTERVAL_END,

    /** [FlowRegionResult.Empty.nextBlockOffset]. */
    NEXT_BLOCK_OFFSET,
}

/** Typed reason a flow contract could not publish validated output. */
public sealed interface FlowCompositionError {
    /** Stable machine-readable error code. */
    public val code: String

    /** Deterministic human-readable explanation. */
    public val message: String

    /** A provider or request supplied a non-finite logical coordinate. */
    public data class NonFiniteCoordinate(
        /** Field containing `NaN` or an infinity. */
        public val coordinate: FlowCoordinate,
        /** Interval index for interval fields, otherwise `null`. */
        public val intervalIndex: Int? = null,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-non-finite-coordinate"
        override val message: String = "Flow coordinates must be finite."
    }

    /** A line band is empty, negative, or exceeds the region's logical block extent. */
    public data class InvalidLineBand(
        /** Rejected band, preserved exactly for diagnosis. */
        public val lineBand: LineBand,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-invalid-line-band"
        override val message: String = "A line band must be non-empty and bounded by its flow region."
    }

    /** Available intervals are empty, reversed, overlapping, or not in logical progression order. */
    public data class NonCanonicalIntervals(
        /** Index of the first rejected interval. */
        public val intervalIndex: Int,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-non-canonical-intervals"
        override val message: String = "Flow intervals must be non-empty, ordered, and disjoint."
    }

    /** An available interval falls outside the region's logical inline extent. */
    public data class IntervalOutOfBounds(
        /** Index of the rejected interval. */
        public val intervalIndex: Int,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-interval-out-of-bounds"
        override val message: String = "Flow intervals must stay within the region inline extent."
    }

    /** An empty response did not advance strictly beyond the requested block start. */
    public data class NonProgressingEmpty(
        /** Requested logical block start. */
        public val blockStart: Float,
        /** Rejected next logical block offset. */
        public val nextBlockOffset: Float,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-non-progressing-empty"
        override val message: String = "An empty flow response must make strict block-axis progress."
    }

    /** An empty response advances beyond the region's logical block extent. */
    public data class EmptyOutOfBounds(
        /** Rejected next logical block offset. */
        public val nextBlockOffset: Float,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-empty-out-of-bounds"
        override val message: String = "An empty flow response must stay within the region block extent."
    }

    /** A chain query selected no region at the supplied zero-based index. */
    public data class InvalidRegionIndex(
        /** Rejected region index. */
        public val regionIndex: Int,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-invalid-region-index"
        override val message: String = "The selected flow region does not exist in the chain."
    }

    /** A continuation belongs to another composition or region revision. */
    public data class ForeignContinuation(
        /** Region index at which reuse was attempted. */
        public val regionIndex: Int,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-foreign-continuation"
        override val message: String = "The flow continuation does not belong to this composition and region revision."
    }

    /** A continuation was supplied without proof of the current text and typography revisions. */
    public data object UnprovenInputIdentity : FlowCompositionError {
        override val code: String = "layout.flow-unproven-input-identity"
        override val message: String = "Continuation reuse requires the current text and typography identities."
    }

    /** The current text revision differs from the continuation's captured revision. */
    public data object TextIdentityMismatch : FlowCompositionError {
        override val code: String = "layout.flow-text-identity-mismatch"
        override val message: String = "The flow continuation belongs to another text revision."
    }

    /** The current typography revision differs from the continuation's captured revision. */
    public data object TypographyIdentityMismatch : FlowCompositionError {
        override val code: String = "layout.flow-typography-identity-mismatch"
        override val message: String = "The flow continuation belongs to another typography revision."
    }

    /** A continuation disagrees with the requested writing mode, constraints, or exact block cursor. */
    public data class IncompatibleContinuation(
        override val message: String,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-incompatible-continuation"
    }

    /** A region violated deterministic monotone bounded refinement. */
    public data class NonConvergentFlowRegion(
        override val message: String,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-non-convergent-region"
    }

    /** Available space could not consume a complete cluster or inline object. */
    public data class NoProgress(
        /** Exact source unit that could not be placed. */
        public val offendingRange: TextRange,
        /** Structured reason no indivisible unit could be placed. */
        public val reason: NoProgressReason,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-no-progress"
        override val message: String = "Available flow space could not consume the next indivisible source unit."
    }

    /** Finite public geometry could not be produced. */
    public data class GeometryOverflow(
        override val message: String,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-geometry-overflow"
    }
}

/** Structured cause attached to [FlowCompositionError.NoProgress]. */
public enum class NoProgressReason {
    /** The next complete shaping cluster is wider than every available interval. */
    CLUSTER_DOES_NOT_FIT,

    /** The next indivisible inline object is larger than every available interval. */
    INLINE_OBJECT_DOES_NOT_FIT,
}

/** Typed validation or composition outcome that never carries partial layout on failure. */
public sealed interface FlowCompositionResult<out Value> {
    /** Successfully validated or composed immutable output. */
    public class Success<Value>(
        /** Validated output value. */
        public val value: Value,
        diagnostics: List<FlowCompositionDiagnostic> = emptyList(),
    ) : FlowCompositionResult<Value> {
        /** Immutable diagnostics produced with this successful output. */
        public val diagnostics: List<FlowCompositionDiagnostic> = diagnostics.immutableListSnapshot()
    }

    /** Typed failure with no published fragment or approximate geometry. */
    public class Failure(
        /** Error that prevented publication. */
        public val error: FlowCompositionError,
        diagnostics: List<FlowCompositionDiagnostic> = emptyList(),
    ) : FlowCompositionResult<Nothing> {
        /** Immutable diagnostics produced before the failed candidate was discarded. */
        public val diagnostics: List<FlowCompositionDiagnostic> = diagnostics.immutableListSnapshot()
    }
}

/**
 * Validates one pure region query without repairing provider output.
 *
 * The region is not called when [lineBand] is malformed. Successful available intervals retain
 * the provider's exact order and coordinates.
 */
public fun queryFlowRegion(
    region: FlowRegion,
    writingMode: WritingMode,
    lineBand: LineBand,
): FlowCompositionResult<FlowRegionResult> {
    if (!lineBand.blockStart.isFinite()) {
        return FlowCompositionResult.Failure(
            FlowCompositionError.NonFiniteCoordinate(FlowCoordinate.BAND_BLOCK_START),
        )
    }
    if (!lineBand.blockExtent.isFinite()) {
        return FlowCompositionResult.Failure(
            FlowCompositionError.NonFiniteCoordinate(FlowCoordinate.BAND_BLOCK_EXTENT),
        )
    }
    val blockExtent = region.logicalBlockExtent(writingMode)
    val bandEnd = lineBand.blockStart.toDouble() + lineBand.blockExtent.toDouble()
    if (region.maximumRefinements <= 0) {
        return FlowCompositionResult.Failure(
            FlowCompositionError.NonConvergentFlowRegion(
                "A flow region must declare a positive maximum refinement count.",
            ),
        )
    }
    if (
        lineBand.blockStart < 0f ||
        lineBand.blockExtent <= 0f ||
        bandEnd > blockExtent.toDouble()
    ) {
        return FlowCompositionResult.Failure(FlowCompositionError.InvalidLineBand(lineBand))
    }

    return when (val result = region.query(writingMode, lineBand)) {
        is FlowRegionResult.AvailableIntervals -> validateAvailableIntervals(
            result,
            region.logicalInlineExtent(writingMode),
        )

        is FlowRegionResult.Empty -> when {
            !result.nextBlockOffset.isFinite() -> FlowCompositionResult.Failure(
                FlowCompositionError.NonFiniteCoordinate(FlowCoordinate.NEXT_BLOCK_OFFSET),
            )

            result.nextBlockOffset <= lineBand.blockStart -> FlowCompositionResult.Failure(
                FlowCompositionError.NonProgressingEmpty(lineBand.blockStart, result.nextBlockOffset),
            )

            result.nextBlockOffset > blockExtent -> FlowCompositionResult.Failure(
                FlowCompositionError.EmptyOutOfBounds(result.nextBlockOffset),
            )

            else -> FlowCompositionResult.Success(result)
        }

        FlowRegionResult.EndOfRegion -> FlowCompositionResult.Success(result)
    }
}

private fun validateAvailableIntervals(
    result: FlowRegionResult.AvailableIntervals,
    inlineExtent: Float,
): FlowCompositionResult<FlowRegionResult> {
    if (result.intervals.isEmpty()) {
        return FlowCompositionResult.Failure(FlowCompositionError.NonCanonicalIntervals(0))
    }
    result.intervals.forEachIndexed { index, interval ->
        if (!interval.start.isFinite()) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.NonFiniteCoordinate(FlowCoordinate.INTERVAL_START, index),
            )
        }
        if (!interval.endExclusive.isFinite()) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.NonFiniteCoordinate(FlowCoordinate.INTERVAL_END, index),
            )
        }
        if (interval.start < 0f || interval.endExclusive > inlineExtent) {
            return FlowCompositionResult.Failure(FlowCompositionError.IntervalOutOfBounds(index))
        }
        if (interval.start >= interval.endExclusive) {
            return FlowCompositionResult.Failure(FlowCompositionError.NonCanonicalIntervals(index))
        }
        if (index > 0 && result.intervals[index - 1].endExclusive > interval.start) {
            return FlowCompositionResult.Failure(FlowCompositionError.NonCanonicalIntervals(index))
        }
    }
    return FlowCompositionResult.Success(result)
}

/** Immutable paragraph fragmentation policy applied between consecutive flow regions. */
public data class FragmentationConstraints(
    /** Minimum complete lines retained at the start of a paragraph fragment. */
    public val minLinesAtStart: Int = 1,
    /** Minimum complete lines retained at the end of a paragraph fragment. */
    public val minLinesAtEnd: Int = 1,
    /** Requests that the paragraph remain in one region when possible. */
    public val keepTogether: Boolean = false,
    /** Requests that the paragraph and its following paragraph share a region when possible. */
    public val keepWithNext: Boolean = false,
) {
    init {
        require(minLinesAtStart > 0) { "Minimum lines at a fragment start must be positive." }
        require(minLinesAtEnd > 0) { "Minimum lines at a fragment end must be positive." }
    }
}

/**
 * Immutable ordered sequence of application-owned flow regions.
 *
 * Construction snapshots the region sequence and creates an opaque composition identity. The
 * chain places no pages and owns no region, document, renderer, or platform resource.
 */
public class FlowChain(
    regions: List<FlowRegion>,
    /** Fragmentation policy captured by continuations from this chain. */
    public val fragmentationConstraints: FragmentationConstraints = FragmentationConstraints(),
) {
    /** Exact region sequence in application-supplied flow order. */
    public val regions: List<FlowRegion> = regions.immutableListSnapshot()

    /** Opaque identity binding continuations to this composition context. */
    public val compositionIdentity: FlowCompositionIdentity = FlowCompositionIdentity.create()

    init {
        require(this.regions.isNotEmpty()) { "A flow chain must contain at least one region." }
        require(this.regions.map(FlowRegion::identity).distinct().size == this.regions.size) {
            "A flow chain must not repeat a region revision identity."
        }
    }

    /**
     * Validates a query for one region, including optional exact continuation reuse.
     *
     * A continuation requires [inputIdentity] as current revision proof. Missing, foreign, changed,
     * or otherwise incompatible proof fails before invoking consumer region code.
     */
    public fun query(
        regionIndex: Int,
        writingMode: WritingMode,
        lineBand: LineBand,
        inputIdentity: FlowCompositionInputIdentity? = null,
        continuation: FlowContinuation? = null,
    ): FlowCompositionResult<FlowRegionResult> {
        val region = regions.getOrNull(regionIndex)
            ?: return FlowCompositionResult.Failure(FlowCompositionError.InvalidRegionIndex(regionIndex))
        if (
            continuation != null &&
            (continuation.compositionIdentity != compositionIdentity ||
                continuation.regionIndex != regionIndex ||
                continuation.regionIdentity != region.identity)
        ) {
            return FlowCompositionResult.Failure(FlowCompositionError.ForeignContinuation(regionIndex))
        }
        if (continuation != null && inputIdentity == null) {
            return FlowCompositionResult.Failure(FlowCompositionError.UnprovenInputIdentity)
        }
        if (continuation != null && inputIdentity?.textVersion != continuation.inputIdentity.textVersion) {
            return FlowCompositionResult.Failure(FlowCompositionError.TextIdentityMismatch)
        }
        if (continuation != null && inputIdentity?.typographyVersion != continuation.inputIdentity.typographyVersion) {
            return FlowCompositionResult.Failure(FlowCompositionError.TypographyIdentityMismatch)
        }
        if (
            continuation != null &&
            (continuation.writingMode != writingMode ||
                continuation.nextBlockOffset != lineBand.blockStart ||
                continuation.fragmentationConstraints != fragmentationConstraints)
        ) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.IncompatibleContinuation(
                    "The continuation must resume with its exact writing mode, block cursor, and fragmentation policy.",
                ),
            )
        }
        return queryFlowRegion(region, writingMode, lineBand)
    }

    /**
     * Creates an exact resource-free continuation for a remaining paragraph suffix.
     *
     * Ranges must belong to [inputIdentity]'s text revision, [remainingSourceRange] must be a suffix of
     * [paragraphRange], [regionIndex] must exist, and [nextBlockOffset] must be finite and bounded.
     */
    public fun createContinuation(
        inputIdentity: FlowCompositionInputIdentity,
        paragraphRange: TextRange,
        remainingSourceRange: TextRange,
        regionIndex: Int,
        writingMode: WritingMode,
        nextBlockOffset: Float,
    ): FlowContinuation {
        val region = requireNotNull(regions.getOrNull(regionIndex)) { "A continuation region must exist in the flow chain." }
        require(paragraphRange.start.sharesVersionWith(TextIndex(inputIdentity.textVersion, 0))) {
            "Continuation ranges must use the declared text revision."
        }
        require(paragraphRange.start.sharesVersionWith(remainingSourceRange.start)) {
            "Continuation ranges must use one text revision."
        }
        require(remainingSourceRange.start >= paragraphRange.start) {
            "A continuation remainder must be a paragraph suffix."
        }
        require(remainingSourceRange.endExclusive == paragraphRange.endExclusive) {
            "A continuation remainder must preserve the paragraph end boundary."
        }
        require(nextBlockOffset.isFinite() && nextBlockOffset >= 0f) {
            "A continuation block offset must be finite and non-negative."
        }
        require(nextBlockOffset <= region.logicalBlockExtent(writingMode)) {
            "A continuation block offset must stay within its region."
        }
        return FlowContinuation(
            inputIdentity = inputIdentity,
            paragraphRange = paragraphRange,
            remainingSourceRange = remainingSourceRange,
            compositionIdentity = compositionIdentity,
            regionIndex = regionIndex,
            regionIdentity = region.identity,
            writingMode = writingMode,
            nextBlockOffset = nextBlockOffset,
            fragmentationConstraints = fragmentationConstraints,
        )
    }
}

/**
 * Exact immutable capability for resuming flow composition.
 *
 * It binds the source revision and suffix, opaque composition and region-revision identities,
 * fragmentation policy, writing mode, region index, and exact logical block cursor. It owns no
 * snapshot, page, renderer, provider, or platform resource and is safe for concurrent reads.
 */
public class FlowContinuation internal constructor(
    /** Exact text and typography revisions captured by this continuation. */
    public val inputIdentity: FlowCompositionInputIdentity,
    /** Complete paragraph range from which this continuation was produced. */
    public val paragraphRange: TextRange,
    /** Exact unconsumed paragraph suffix. */
    public val remainingSourceRange: TextRange,
    /** Opaque identity of the composition context that produced this continuation. */
    public val compositionIdentity: FlowCompositionIdentity,
    /** Zero-based region index at which composition resumes. */
    public val regionIndex: Int,
    /** Exact immutable region revision at [regionIndex]. */
    public val regionIdentity: FlowRegionIdentity,
    /** Logical writing mode used to interpret region coordinates. */
    public val writingMode: WritingMode,
    /** Exact logical block offset for the next line-band query. */
    public val nextBlockOffset: Float,
    /** Fragmentation policy whose state must be replayed. */
    public val fragmentationConstraints: FragmentationConstraints,
) {
    /** Source revision whose boundaries are recorded by this continuation. */
    public val textVersion: TextVersion
        get() = inputIdentity.textVersion

    /** Typography revision required to reproduce subsequent line geometry. */
    public val typographyVersion: TypographyVersion
        get() = inputIdentity.typographyVersion
}

/** Whether published flow fragments cover the complete paragraph or an exact prefix. */
public enum class FlowCoverageStatus {
    /** The paragraph is represented through its requested end boundary. */
    COMPLETE,

    /** Complete fragments cover a prefix and [ParagraphFragment.continuation] owns the suffix. */
    PARTIAL,
}

/** Fragmentation rule identified by a deterministic relaxation diagnostic. */
public enum class FragmentationConstraintKind {
    /** [FragmentationConstraints.keepWithNext]. */
    KEEP_WITH_NEXT,

    /** [FragmentationConstraints.keepTogether]. */
    KEEP_TOGETHER,

    /** [FragmentationConstraints.minLinesAtEnd]. */
    MIN_LINES_AT_END,

    /** [FragmentationConstraints.minLinesAtStart]. */
    MIN_LINES_AT_START,
}

/** Structured, resource-free diagnostic produced by flow composition. */
public sealed interface FlowCompositionDiagnostic {
    /** Stable machine-readable diagnostic code. */
    public val code: String

    /** A requested fragmentation rule was impossible and was relaxed deterministically. */
    public data class FragmentationRelaxed(
        /** Rule relaxed at this point in the flow. */
        public val constraint: FragmentationConstraintKind,
        /** Paragraph to which the relaxation applies. */
        public val paragraphRange: TextRange,
        /** Region in which the deterministic decision was made. */
        public val regionIndex: Int,
    ) : FlowCompositionDiagnostic {
        override val code: String = "layout.flow-fragmentation-relaxed"

        init {
            require(regionIndex >= 0) { "A flow diagnostic region index must be non-negative." }
        }
    }
}

/**
 * Geometric portion of one already-resolved logical [LineLayout].
 *
 * [availableInterval] is expressed in logical inline offsets from the line box's inline start.
 * Runs may be split only at source cluster boundaries by the producer; this value never performs
 * shaping or BiDi resolution. All positioned items use final paragraph coordinates. Caller lists
 * are defensively captured, so the resource-free fragment is safe for concurrent reads.
 */
public class LineFragment(
    /** One finite non-empty half-open interval made available by the region. */
    public val availableInterval: InlineInterval,
    positionedGlyphRuns: List<PositionedGlyphRun>,
    caretCandidates: List<CaretCandidate>,
    positionedInlineObjects: List<PositionedInlineObject> = emptyList(),
) {
    /** Immutable final glyph runs in their original line visual order. */
    public val positionedGlyphRuns: List<PositionedGlyphRun> = positionedGlyphRuns.immutableListSnapshot()

    /** Immutable final caret candidates in their original line visual order. */
    public val caretCandidates: List<CaretCandidate> = caretCandidates.immutableListSnapshot()

    /** Immutable indivisible inline objects assigned to this interval. */
    public val positionedInlineObjects: List<PositionedInlineObject> =
        positionedInlineObjects.immutableListSnapshot()

    init {
        require(availableInterval.start.isFinite() && availableInterval.endExclusive.isFinite()) {
            "A line fragment interval must be finite."
        }
        require(availableInterval.start >= 0f && availableInterval.start < availableInterval.endExclusive) {
            "A line fragment interval must be non-negative and non-empty."
        }
        require(this.positionedGlyphRuns.zipWithNext().all { (left, right) -> left.visualOrder <= right.visualOrder }) {
            "Fragment glyph runs must preserve their logical line visual order."
        }
        require(this.caretCandidates.zipWithNext().all { (left, right) -> left.visualOrder <= right.visualOrder }) {
            "Fragment caret candidates must preserve their logical line visual order."
        }
    }
}

/**
 * Immutable consecutive portion of one logical paragraph placed in a flow region.
 *
 * [lines] contains complete logical lines only. [laidOutRange] is an exact subrange of
 * [paragraphRange]; a partial final fragment publishes a [continuation] beginning exactly at its
 * end. The value owns no page, renderer, font handle, or mutable collection.
 */
public class ParagraphFragment(
    /** Complete logical paragraph range. */
    public val paragraphRange: TextRange,
    /** Exact source range represented by complete [lines]. */
    public val laidOutRange: TextRange,
    /** Whether this is the first published fragment of [paragraphRange]. */
    public val isFirstFragment: Boolean,
    /** Whether this is the final published fragment of [paragraphRange]. */
    public val isLastFragment: Boolean,
    lines: List<LineLayout>,
    /** Exact continuation for partial coverage, otherwise `null`. */
    public val continuation: FlowContinuation? = null,
    diagnostics: List<FlowCompositionDiagnostic> = emptyList(),
) {
    /** Complete immutable logical lines in block-progression order. */
    public val lines: List<LineLayout> = lines.immutableListSnapshot()

    /** Immutable structured diagnostics associated with this fragment. */
    public val diagnostics: List<FlowCompositionDiagnostic> = diagnostics.immutableListSnapshot()

    /** Exact coverage state derived from [continuation]. */
    public val coverageStatus: FlowCoverageStatus =
        if (continuation == null) FlowCoverageStatus.COMPLETE else FlowCoverageStatus.PARTIAL

    init {
        require(paragraphRange.start.sharesVersionWith(laidOutRange.start)) {
            "Paragraph and laid-out fragment ranges must use one text revision."
        }
        require(laidOutRange.start >= paragraphRange.start && laidOutRange.endExclusive <= paragraphRange.endExclusive) {
            "A laid-out fragment range must stay inside its paragraph."
        }
        require(isFirstFragment == (laidOutRange.start == paragraphRange.start)) {
            "The first-fragment flag must agree with the laid-out paragraph start."
        }
        require(isLastFragment == (laidOutRange.endExclusive == paragraphRange.endExclusive)) {
            "The last-fragment flag must agree with complete paragraph coverage."
        }
        require(this.lines.all { line ->
            line.range.start >= laidOutRange.start && line.range.endExclusive <= laidOutRange.endExclusive
        }) {
            "Every fragment line must stay inside the laid-out source range."
        }
        require(this.lines.zipWithNext().all { (left, right) -> left.range.endExclusive == right.range.start }) {
            "Paragraph fragment lines must be consecutive in logical source order."
        }
        require(
            this.lines.isEmpty() && laidOutRange.start == laidOutRange.endExclusive ||
                this.lines.isNotEmpty() &&
                this.lines.first().range.start == laidOutRange.start &&
                this.lines.last().range.endExclusive == laidOutRange.endExclusive,
        ) {
            "Paragraph fragment lines must cover the laid-out source range exactly."
        }
        require((continuation == null) == isLastFragment) {
            "Only a non-final paragraph fragment may publish a continuation."
        }
        if (continuation != null) {
            require(continuation.paragraphRange == paragraphRange) {
                "A paragraph fragment and its continuation must describe the same paragraph."
            }
            require(continuation.remainingSourceRange.start == laidOutRange.endExclusive) {
                "A paragraph fragment must end exactly where its continuation begins."
            }
        }
    }
}

private fun FlowRegion.logicalInlineExtent(writingMode: WritingMode): Float = when (writingMode) {
    WritingMode.HORIZONTAL_TB -> bounds.right.value - bounds.left.value
    WritingMode.VERTICAL_RL,
    WritingMode.VERTICAL_LR,
    -> bounds.bottom.value - bounds.top.value
}

private fun FlowRegion.logicalBlockExtent(writingMode: WritingMode): Float = when (writingMode) {
    WritingMode.HORIZONTAL_TB -> bounds.bottom.value - bounds.top.value
    WritingMode.VERTICAL_RL,
    WritingMode.VERTICAL_LR,
    -> bounds.right.value - bounds.left.value
}
