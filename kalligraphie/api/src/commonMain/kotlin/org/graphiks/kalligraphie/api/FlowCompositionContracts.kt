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

    /** Flow-chain composition requires source-preserving [OverflowPolicy.Continue] semantics. */
    public data class UnsupportedOverflowPolicy(
        /** Rejected paragraph overflow behavior. */
        public val overflowPolicy: OverflowPolicy,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-unsupported-overflow-policy"
        override val message: String = "Flow-chain composition requires OverflowPolicy.Continue."
    }

    /** A continuation disagrees with the requested writing mode, constraints, or exact block cursor. */
    public data class IncompatibleContinuation(
        override val message: String,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-incompatible-continuation"
    }

    /** A retained incremental flow state cannot be replayed by the requested composition context. */
    public data class IncompatibleState(
        override val message: String,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-incompatible-state"
    }

    /** Caller-supplied flow state fields contradict one another and cannot form a capability. */
    public data class InvalidState(
        override val message: String,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-invalid-state"
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

    /** Existing paragraph shaping or materialization failed before a flow line could be published. */
    public data class ParagraphFailure(
        /** Exact portable paragraph failure produced by the underlying line composition route. */
        public val paragraphError: ParagraphLayoutError,
    ) : FlowCompositionError {
        override val code: String = "layout.flow-paragraph-failure"
        override val message: String = paragraphError.message
    }

    /** Cooperative cancellation discarded the complete candidate before publication. */
    public data object Cancelled : FlowCompositionError {
        override val code: String = "layout.flow-cancelled"
        override val message: String = "Flow composition was cancelled before a complete line could be published."
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
        continuation: FlowContinuation? = null,
        inputIdentity: FlowCompositionInputIdentity? = null,
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
     * This low-level factory proves region-query reuse only; paragraph composition rejects the
     * result conservatively because no complete advanced paragraph input was captured.
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
            paragraphReplayIdentity = null,
            relaxedConstraints = emptyList(),
            fragmentationCommitment = null,
        )
    }

    /**
     * Creates a continuation whose complete paragraph inputs can be checked on resume.
     *
     * [request] is inspected only to capture resource-free replay data; neither the snapshot,
     * shaping backend, region, nor materialization capability is retained. [relaxedConstraints]
     * carries deterministic fragmentation decisions already made for this paragraph so they are
     * not repeated after a region boundary. [fragmentationCommitment] carries an already-proved
     * minimum-line decision while bounded publication advances within the accepted region.
     */
    public fun createContinuation(
        inputIdentity: FlowCompositionInputIdentity,
        request: ParagraphLayoutRequest,
        paragraphRange: TextRange,
        remainingSourceRange: TextRange,
        regionIndex: Int,
        writingMode: WritingMode,
        nextBlockOffset: Float,
        relaxedConstraints: List<FragmentationConstraintKind> = emptyList(),
        fragmentationCommitment: FlowFragmentationCommitment? = null,
    ): FlowContinuation {
        require(inputIdentity.textVersion == request.snapshot.version) {
            "A flow input identity must name the request text revision."
        }
        require(request.sourceRange.start.sharesVersionWith(paragraphRange.start)) {
            "A flow request and paragraph range must use one text revision."
        }
        require(request.sourceRange.endExclusive == paragraphRange.endExclusive) {
            "A flow request suffix must preserve the complete paragraph end boundary."
        }
        require(remainingSourceRange.start >= request.sourceRange.start) {
            "A flow continuation remainder must stay inside the current request suffix."
        }
        require(relaxedConstraints.distinct().size == relaxedConstraints.size) {
            "A flow continuation must not repeat relaxed fragmentation constraints."
        }
        val relaxationOrder = listOf(
            FragmentationConstraintKind.KEEP_WITH_NEXT,
            FragmentationConstraintKind.KEEP_TOGETHER,
            FragmentationConstraintKind.MIN_LINES_AT_END,
            FragmentationConstraintKind.MIN_LINES_AT_START,
        )
        require(relaxedConstraints == relaxationOrder.filter(relaxedConstraints::contains)) {
            "A flow continuation must preserve deterministic fragmentation relaxation order."
        }
        require(
            fragmentationCommitment == null ||
                fragmentationCommitment.regionIndex == regionIndex &&
                fragmentationCommitment.regionIdentity == regions[regionIndex].identity
        ) {
            "A fragmentation commitment must belong to the continuation region."
        }
        val basic = createContinuation(
            inputIdentity,
            paragraphRange,
            remainingSourceRange,
            regionIndex,
            writingMode,
            nextBlockOffset,
        )
        return FlowContinuation(
            inputIdentity = basic.inputIdentity,
            paragraphRange = basic.paragraphRange,
            remainingSourceRange = basic.remainingSourceRange,
            compositionIdentity = basic.compositionIdentity,
            regionIndex = basic.regionIndex,
            regionIdentity = basic.regionIdentity,
            writingMode = basic.writingMode,
            nextBlockOffset = basic.nextBlockOffset,
            fragmentationConstraints = basic.fragmentationConstraints,
            paragraphReplayIdentity = FlowParagraphReplayIdentity.capture(request, remainingSourceRange),
            relaxedConstraints = relaxedConstraints,
            fragmentationCommitment = fragmentationCommitment,
        )
    }

    /**
     * Validates every provable dependency before a paragraph continuation is consumed.
     *
     * Legacy continuations made without a [ParagraphLayoutRequest] are rejected conservatively
     * because their advanced typography inputs cannot be proven complete. Validation invokes no
     * region or backend code and publishes no layout on failure.
     */
    public fun validateContinuation(
        continuation: FlowContinuation,
        request: ParagraphLayoutRequest,
        inputIdentity: FlowCompositionInputIdentity?,
    ): FlowCompositionResult<Unit> {
        val region = regions.getOrNull(continuation.regionIndex)
            ?: return FlowCompositionResult.Failure(
                FlowCompositionError.InvalidRegionIndex(continuation.regionIndex),
            )
        if (
            continuation.compositionIdentity != compositionIdentity ||
            continuation.regionIdentity != region.identity
        ) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.ForeignContinuation(continuation.regionIndex),
            )
        }
        if (inputIdentity == null || continuation.paragraphReplayIdentity == null) {
            return FlowCompositionResult.Failure(FlowCompositionError.UnprovenInputIdentity)
        }
        if (
            inputIdentity.textVersion != continuation.inputIdentity.textVersion ||
            request.snapshot.version != continuation.inputIdentity.textVersion
        ) {
            return FlowCompositionResult.Failure(FlowCompositionError.TextIdentityMismatch)
        }
        if (inputIdentity.typographyVersion != continuation.inputIdentity.typographyVersion) {
            return FlowCompositionResult.Failure(FlowCompositionError.TypographyIdentityMismatch)
        }
        if (
            continuation.writingMode != request.constraints.writingMode ||
            continuation.fragmentationConstraints != fragmentationConstraints ||
            request.sourceRange != continuation.remainingSourceRange ||
            !continuation.paragraphReplayIdentity.hasSameFlowInputs(request)
        ) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.IncompatibleContinuation(
                    "The continuation must resume at its exact source boundary with unchanged paragraph inputs.",
                ),
            )
        }
        return FlowCompositionResult.Success(Unit)
    }
}

/**
 * Exact immutable capability for resuming flow composition.
 *
 * A reusable instance binds the source revision and suffix, complete structural Unicode and
 * line-break replay proof, opaque composition and region-revision identities, fragmentation
 * policy, writing mode, region index, and exact logical block cursor. An empty suffix represents
 * only a pending required terminal physical line. Instances created without a complete paragraph
 * request omit replay proof and are rejected conservatively. The value owns no snapshot, page,
 * renderer, provider, or platform resource and is safe for concurrent reads.
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
    /** Complete paragraph replay proof, absent on legacy manually-created continuations. */
    internal val paragraphReplayIdentity: FlowParagraphReplayIdentity?,
    relaxedConstraints: List<FragmentationConstraintKind>,
    /** Accepted remaining line count whose fragmentation feasibility was already proved in this region. */
    public val fragmentationCommitment: FlowFragmentationCommitment?,
) {
    /** Fragmentation rules already relaxed for this paragraph in deterministic order. */
    public val relaxedConstraints: List<FragmentationConstraintKind> = relaxedConstraints.immutableListSnapshot()

    /** Source revision whose boundaries are recorded by this continuation. */
    public val textVersion: TextVersion
        get() = inputIdentity.textVersion

    /** Typography revision required to reproduce subsequent line geometry. */
    public val typographyVersion: TypographyVersion
        get() = inputIdentity.typographyVersion
}

/**
 * Structured proof that bounded publication may continue in one already-accepted flow region.
 *
 * The proof carries no geometry provider. It is valid only with the enclosing [FlowContinuation]
 * whose region ordinal and immutable revision match [regionIndex] and [regionIdentity].
 */
public data class FlowFragmentationCommitment(
    /** Region ordinal in which the fragmentation decision was accepted. */
    public val regionIndex: Int,
    /** Immutable revision identity of the accepted region. */
    public val regionIdentity: FlowRegionIdentity,
    /** Number of complete accepted lines still awaiting publication in that region. */
    public val remainingLineCount: Int,
) {
    init {
        require(regionIndex >= 0) { "A fragmentation commitment region index must be non-negative." }
        require(remainingLineCount > 0) { "A fragmentation commitment must retain at least one line." }
    }
}

internal class FlowParagraphReplayIdentity private constructor(
    private val paragraph: LayoutContinuation,
    private val unicodeRange: TextRange,
    private val unicodeData: UnicodeDataIdentity,
    private val graphemeClusters: List<TextRange>,
    private val scriptLanguageRuns: List<ScriptLanguageRun>,
    private val logicalBidiRuns: List<BidiRun>,
    private val visualBidiRuns: List<BidiRun>,
    private val lineBreakRange: TextRange,
    private val lineBreakGraphemeClusters: List<TextRange>,
    private val lineBreakOpportunities: List<LineBreakOpportunity>,
) {
    val hasNonLocalBidiDependencies: Boolean = logicalBidiRuns.let { runs ->
        val baseLevel = if (paragraph.baseDirection == BaseDirection.LEFT_TO_RIGHT) 0 else 1
        runs.size != 1 || runs.any { run -> run.level != baseLevel }
    }

    fun hasSameReplayIdentity(other: FlowParagraphReplayIdentity): Boolean =
        paragraph.originalVersion == other.paragraph.originalVersion &&
            paragraph.originalSourceRange == other.paragraph.originalSourceRange &&
            paragraph.remainingSourceRange == other.paragraph.remainingSourceRange &&
            paragraph.regionWidth == other.paragraph.regionWidth &&
            paragraph.regionLeft == other.paragraph.regionLeft &&
            paragraph.resumptionRegionTop == other.paragraph.resumptionRegionTop &&
            paragraph.writingMode == other.paragraph.writingMode &&
            paragraph.resumptionBlockCursor == other.paragraph.resumptionBlockCursor &&
            paragraph.inlineExtent == other.paragraph.inlineExtent &&
            paragraph.lineMetrics == other.paragraph.lineMetrics &&
            paragraph.baseDirection == other.paragraph.baseDirection &&
            paragraph.language == other.paragraph.language &&
            paragraph.unicodeData == other.paragraph.unicodeData &&
            paragraph.fontCatalogGeneration == other.paragraph.fontCatalogGeneration &&
            paragraph.resolutionPolicyId == other.paragraph.resolutionPolicyId &&
            paragraph.resolutionPolicyVersion == other.paragraph.resolutionPolicyVersion &&
            paragraph.fontInstanceDescriptor == other.paragraph.fontInstanceDescriptor &&
            paragraph.shapingBackendIdentity == other.paragraph.shapingBackendIdentity &&
            paragraph.featurePolicy == other.paragraph.featurePolicy &&
            paragraph.features == other.paragraph.features &&
            paragraph.materializationIdentity == other.paragraph.materializationIdentity &&
            paragraph.overflowPolicy == other.paragraph.overflowPolicy &&
            paragraph.positioning == other.paragraph.positioning &&
            paragraph.hyphenationMode == other.paragraph.hyphenationMode &&
            paragraph.hyphenationServiceIdentity == other.paragraph.hyphenationServiceIdentity &&
            paragraph.inlineObjects == other.paragraph.inlineObjects &&
            paragraph.textOrientation == other.paragraph.textOrientation &&
            paragraph.verticalMetricsPolicy == other.paragraph.verticalMetricsPolicy &&
            unicodeRange == other.unicodeRange &&
            unicodeData == other.unicodeData &&
            graphemeClusters == other.graphemeClusters &&
            scriptLanguageRuns == other.scriptLanguageRuns &&
            logicalBidiRuns == other.logicalBidiRuns &&
            visualBidiRuns == other.visualBidiRuns &&
            lineBreakRange == other.lineBreakRange &&
            lineBreakGraphemeClusters == other.lineBreakGraphemeClusters &&
            lineBreakOpportunities == other.lineBreakOpportunities

    fun hasSameFlowInputs(request: ParagraphLayoutRequest): Boolean =
        request.snapshot.version == paragraph.originalVersion &&
            request.constraints.writingMode == paragraph.writingMode &&
            request.constraints.lineMetrics == paragraph.lineMetrics &&
            request.baseDirection == paragraph.baseDirection &&
            request.language == paragraph.language &&
            request.fontCatalog.generation == paragraph.fontCatalogGeneration &&
            request.resolutionPolicy.policyId == paragraph.resolutionPolicyId &&
            request.resolutionPolicy.version == paragraph.resolutionPolicyVersion &&
            request.fontInstanceDescriptor == paragraph.fontInstanceDescriptor &&
            request.shapingBackend.identity == paragraph.shapingBackendIdentity &&
            request.featurePolicy == paragraph.featurePolicy &&
            request.features == paragraph.features &&
            request.materializationIdentity == paragraph.materializationIdentity &&
            request.overflowPolicy == paragraph.overflowPolicy &&
            request.positioning == paragraph.positioning &&
            request.hyphenationMode == paragraph.hyphenationMode &&
            request.hyphenationService?.identity == paragraph.hyphenationServiceIdentity &&
            request.inlineObjects?.entries.orEmpty() == paragraph.inlineObjects?.entries.orEmpty().filter { entry ->
                entry.index >= request.sourceRange.start && entry.index < request.sourceRange.endExclusive
            } &&
            request.textOrientation == paragraph.textOrientation &&
            request.verticalMetricsPolicy == paragraph.verticalMetricsPolicy &&
            request.unicodeAnalysis.let { analysis ->
                analysis.range == unicodeRange &&
                    analysis.unicodeData == unicodeData &&
                    analysis.graphemeClusters == graphemeClusters &&
                    analysis.scriptLanguageRuns == scriptLanguageRuns &&
                    analysis.logicalBidiRuns == logicalBidiRuns &&
                    analysis.visualBidiRuns == visualBidiRuns
            } &&
            request.lineBreakAnalysis.let { analysis ->
                analysis.range == lineBreakRange &&
                    analysis.unicodeData == unicodeData &&
                    analysis.graphemeClusters == lineBreakGraphemeClusters &&
                    analysis.opportunities == lineBreakOpportunities
            }

    companion object {
        fun capture(request: ParagraphLayoutRequest, remainingSourceRange: TextRange): FlowParagraphReplayIdentity =
            FlowParagraphReplayIdentity(
                paragraph = LayoutContinuation.create(request, remainingSourceRange),
                unicodeRange = request.unicodeAnalysis.range,
                unicodeData = request.unicodeAnalysis.unicodeData,
                graphemeClusters = request.unicodeAnalysis.graphemeClusters,
                scriptLanguageRuns = request.unicodeAnalysis.scriptLanguageRuns,
                logicalBidiRuns = request.unicodeAnalysis.logicalBidiRuns,
                visualBidiRuns = request.unicodeAnalysis.visualBidiRuns,
                lineBreakRange = request.lineBreakAnalysis.range,
                lineBreakGraphemeClusters = request.lineBreakAnalysis.graphemeClusters,
                lineBreakOpportunities = request.lineBreakAnalysis.opportunities,
            )
    }
}

/**
 * Complete resource-free signature of inputs that may affect flow breaking or geometry.
 *
 * The signature snapshots region revision identities and paragraph configuration while retaining
 * no [FlowRegion], text snapshot, shaping backend, resolver, renderer, or platform resource.
 */
public class FlowLayoutConfigurationSignature private constructor(
    private val value: FlowLayoutConfigurationValue,
) {
    /** Compares every captured flow and paragraph input. */
    override fun equals(other: Any?): Boolean =
        other is FlowLayoutConfigurationSignature && value == other.value

    /** Returns a stable hash of the captured resource-free configuration. */
    override fun hashCode(): Int = value.hashCode()

    internal fun matchesFlowCompositionIdentity(identity: FlowCompositionIdentity): Boolean =
        value.flowCompositionIdentity == identity

    internal fun acceptsContinuation(continuation: FlowContinuation): Boolean =
        value.flowCompositionIdentity == continuation.compositionIdentity &&
            value.regionIdentities.getOrNull(continuation.regionIndex) == continuation.regionIdentity

    internal fun acceptsProvenance(provenance: FlowFragmentProvenance): Boolean =
        this == provenance.configuration &&
            value.flowCompositionIdentity == provenance.flowCompositionIdentity &&
            value.regionIdentities.getOrNull(provenance.regionIndex) == provenance.regionIdentity

    internal fun matchesFontResolutionPolicy(policy: FontResolutionPolicySnapshot): Boolean =
        value.layout.matchesFontResolutionPolicy(policy)

    /** Factories for portable flow configuration signatures. */
    public companion object {
        /** Captures all replay-relevant values from [request], [paragraph], and its region chain. */
        public fun capture(
            request: IncrementalFlowLayoutRequest,
            paragraph: ParagraphLayoutRequest,
        ): FlowLayoutConfigurationSignature = FlowLayoutConfigurationSignature(
            FlowLayoutConfigurationValue(
                layout = LayoutConfigurationSignature.from(request.input, request.constraints),
                baseDirection = paragraph.baseDirection,
                language = paragraph.language,
                backendIdentity = paragraph.shapingBackend.identity,
                materialization = paragraph.materializationIdentity,
                overflowPolicy = paragraph.overflowPolicy,
                positioning = paragraph.positioning,
                hyphenationMode = paragraph.hyphenationMode,
                hyphenationServiceIdentity = paragraph.hyphenationService?.identity,
                inlineObjects = paragraph.inlineObjects,
                textOrientation = paragraph.textOrientation,
                verticalMetricsPolicy = paragraph.verticalMetricsPolicy,
                features = paragraph.features,
                flowCompositionIdentity = request.flowChain.compositionIdentity,
                regionIdentities = request.flowChain.regions.map(FlowRegion::identity),
            ),
        )
    }
}

private data class FlowLayoutConfigurationValue(
    val layout: LayoutConfigurationSignature,
    val baseDirection: BaseDirection,
    val language: String,
    val backendIdentity: ShapingBackendIdentity,
    val materialization: ParagraphMaterializationIdentity,
    val overflowPolicy: OverflowPolicy,
    val positioning: ParagraphPositioningPolicy,
    val hyphenationMode: HyphenationMode,
    val hyphenationServiceIdentity: HyphenationServiceIdentity?,
    val inlineObjects: InlineObjectSnapshot?,
    val textOrientation: TextOrientation,
    val verticalMetricsPolicy: VerticalMetricsPolicy,
    val features: List<OpenTypeFeature>,
    val flowCompositionIdentity: FlowCompositionIdentity,
    val regionIdentities: List<FlowRegionIdentity>,
)

/**
 * Immutable portable request for bounded incremental composition through [flowChain].
 *
 * The chain is borrowed synchronously by the layout engine. Published state snapshots only opaque
 * chain and region identities and therefore retains no consumer geometry provider.
 */
public class IncrementalFlowLayoutRequest internal constructor(
    /** Target text and typography snapshots. */
    public val input: LayoutInput,
    /** Source range whose containing complete flow lines must be materialized. */
    public val requestedRange: TextRange,
    /** Writing mode and line metrics shared by the flow regions. */
    public val constraints: ParagraphConstraints,
    /** Ordered application-owned region chain borrowed for this operation. */
    public val flowChain: FlowChain,
    /** Number of complete lines requested after the line covering [requestedRange]. */
    public val overscan: LineOverscan,
    /** Optional prior portable flow state. */
    public val previousState: FlowLayoutState?,
    /** Optional authoritative transition from [previousState] to [input]. */
    public val delta: LayoutDelta?,
    /** Cooperative cancellation signal checked between bounded operations. */
    public val cancellationToken: CancellationToken,
)

/** Validates and creates a portable incremental flow-layout request. */
public fun createIncrementalFlowLayoutRequest(
    input: LayoutInput,
    requestedRange: TextRange,
    constraints: ParagraphConstraints,
    flowChain: FlowChain,
    overscan: LineOverscan,
    previousState: FlowLayoutState? = null,
    delta: LayoutDelta? = null,
    cancellationToken: CancellationToken = CancellationToken.none,
): FlowCompositionResult<IncrementalFlowLayoutRequest> {
    if (!requestedRange.start.sharesVersionWith(input.text.range.start) || !input.text.contains(requestedRange)) {
        return FlowCompositionResult.Failure(
            FlowCompositionError.IncompatibleState(
                "The requested flow range must belong to and stay inside the target snapshot.",
            ),
        )
    }
    if (delta?.text != null && delta.text.targetVersion != input.text.version) {
        return FlowCompositionResult.Failure(
            FlowCompositionError.IncompatibleState("The text delta target does not match the requested text revision."),
        )
    }
    if (delta?.typography != null && delta.typography.targetVersion != input.typography.version) {
        return FlowCompositionResult.Failure(
            FlowCompositionError.IncompatibleState(
                "The typography delta target does not match the requested typography revision.",
            ),
        )
    }
    if (delta?.typography != null) {
        val sourceTextVersion = previousState?.inputIdentity?.textVersion
            ?: delta.text?.sourceVersion
            ?: input.text.version
        val proofError = validateTypographyProofs(
            sourceTextVersion = sourceTextVersion,
            target = input.text,
            targetConfiguration = LayoutConfigurationSignature.from(input, constraints),
            typographyDelta = delta.typography,
        )
        if (proofError != null) {
            return FlowCompositionResult.Failure(FlowCompositionError.IncompatibleState(proofError.message))
        }
        val policyDelta = delta.typography.fontResolutionPolicy
        if (
            previousState != null &&
            policyDelta != null &&
            !previousState.configuration.matchesFontResolutionPolicy(policyDelta.source)
        ) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.IncompatibleState(
                    "Font resolution policy delta source does not match the retained flow configuration.",
                ),
            )
        }
    }
    if (previousState != null && previousState.flowCompositionIdentity != flowChain.compositionIdentity) {
        return FlowCompositionResult.Failure(
            FlowCompositionError.IncompatibleState("The retained flow state belongs to another flow chain."),
        )
    }
    if (previousState != null) {
        val textChanged = previousState.inputIdentity.textVersion != input.text.version
        val textDelta = delta?.text
        if (textChanged && textDelta == null || textDelta != null && textDelta.sourceVersion != previousState.inputIdentity.textVersion) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.IncompatibleState(
                    "A text delta must start at the retained flow state when the text revision changes.",
                ),
            )
        }
        val typographyChanged = previousState.inputIdentity.typographyVersion != input.typography.version
        val typographyDelta = delta?.typography
        if (
            typographyChanged && typographyDelta == null ||
            typographyDelta != null && typographyDelta.sourceVersion != previousState.inputIdentity.typographyVersion
        ) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.IncompatibleState(
                    "A typography delta must start at the retained flow state when the typography revision changes.",
                ),
            )
        }
    }
    return FlowCompositionResult.Success(
        IncrementalFlowLayoutRequest(
            input,
            requestedRange,
            constraints,
            flowChain,
            overscan,
            previousState,
            delta,
            cancellationToken,
        ),
    )
}

/** Immutable incremental restart information for one portable flow publication. */
public data class FlowLayoutDiagnostics(
    /** Exact target boundary at which forward composition began. */
    public val reflowStart: TextIndex,
    /** Whether insufficient semantic proof forced restart at the target document start. */
    public val usedConservativeInvalidation: Boolean,
    /** Mapped checkpoint where recomposed output converged, or `null` when none was observed. */
    public val stabilizedAt: TextIndex? = null,
)

/**
 * Resource-free immutable flow state retained between bounded layout requests.
 *
 * Checkpoints carry current structured continuations and observable fragment signatures. The
 * state retains no region, snapshot, backend, resolver, renderer, page, or native handle. Public
 * callers obtain coherent instances through [create].
 */
public class FlowLayoutState private constructor(
    /** Exact text and typography revisions represented by this state. */
    public val inputIdentity: FlowCompositionInputIdentity,
    /** Opaque identity of the chain that produced this state. */
    public val flowCompositionIdentity: FlowCompositionIdentity,
    /** Complete materialized coverage published with this state. */
    public val coverage: LayoutCoverage,
    /** Complete semantic configuration required for reuse. */
    public val configuration: FlowLayoutConfigurationSignature,
    materializedFragments: List<ParagraphFragment>,
    checkpoints: List<FlowLayoutCheckpoint>,
    /** Exact continuation of the unmaterialized suffix, or `null` at physical paragraph end. */
    public val continuation: FlowContinuation?,
) {
    /** Immutable fragments available for a no-work compatible publication. */
    public val materializedFragments: List<ParagraphFragment> = materializedFragments.immutableListSnapshot()

    /** Immutable sparse structured checkpoints ordered by their source boundary. */
    public val checkpoints: List<FlowLayoutCheckpoint> = checkpoints.immutableListSnapshot()

    /** Validated construction for portable flow-state capabilities. */
    public companion object {
        /**
         * Creates a state only when coverage, fragments, checkpoints, identities, and continuation
         * describe one coherent publication. Contradictions return [FlowCompositionError.InvalidState].
         */
        public fun create(
            inputIdentity: FlowCompositionInputIdentity,
            flowCompositionIdentity: FlowCompositionIdentity,
            coverage: LayoutCoverage,
            configuration: FlowLayoutConfigurationSignature,
            materializedFragments: List<ParagraphFragment>,
            checkpoints: List<FlowLayoutCheckpoint>,
            continuation: FlowContinuation?,
        ): FlowCompositionResult<FlowLayoutState> {
            val fragments = materializedFragments.immutableListSnapshot()
            val capturedCheckpoints = checkpoints.immutableListSnapshot()
            fun invalid(message: String): FlowCompositionResult.Failure =
                FlowCompositionResult.Failure(FlowCompositionError.InvalidState(message))

            if (fragments.isEmpty()) return invalid("Flow state must retain at least one complete fragment.")
            if (!configuration.matchesFlowCompositionIdentity(flowCompositionIdentity)) {
                return invalid("Flow state configuration must belong to its flow composition identity.")
            }
            if (coverage.textVersion != inputIdentity.textVersion) {
                return invalid("Flow state coverage must use its input text revision.")
            }
            val versionOrigin = TextIndex(inputIdentity.textVersion, 0)
            if (fragments.any { fragment ->
                    !fragment.laidOutRange.start.sharesVersionWith(versionOrigin) ||
                        !fragment.paragraphRange.start.sharesVersionWith(versionOrigin)
                }
            ) {
                return invalid("Every flow state fragment must use its input text revision.")
            }
            val paragraphRange = fragments.first().paragraphRange
            if (fragments.any { fragment -> fragment.paragraphRange != paragraphRange }) {
                return invalid("Every flow state fragment must describe the same paragraph range.")
            }
            if (fragments.any { fragment ->
                    fragment.flowProvenance?.let { provenance ->
                        provenance.inputIdentity != inputIdentity ||
                            provenance.paragraphRange != fragment.paragraphRange ||
                            provenance.laidOutRange != fragment.laidOutRange ||
                            !configuration.acceptsProvenance(provenance)
                    } != false
                }
            ) {
                return invalid("Every flow state fragment must carry compatible structured flow provenance.")
            }
            if (fragments.any { fragment ->
                    fragment.continuation?.let { fragmentContinuation ->
                        fragmentContinuation.inputIdentity != inputIdentity ||
                            fragmentContinuation.compositionIdentity != flowCompositionIdentity ||
                            !configuration.acceptsContinuation(fragmentContinuation) ||
                            fragmentContinuation.paragraphRange != paragraphRange ||
                            fragmentContinuation.remainingSourceRange.start != fragment.laidOutRange.endExclusive
                    } == true
                }
            ) {
                return invalid("Every non-final flow fragment must link to this state's exact continuation context.")
            }
            if (fragments.zipWithNext().any transition@{ (left, right) ->
                    val producer = left.flowProvenance ?: return@transition true
                    val outgoing = left.continuation ?: return@transition true
                    val consumer = right.flowProvenance ?: return@transition true
                    val targetsProducerOrNext =
                        outgoing.regionIndex == producer.regionIndex ||
                            outgoing.regionIndex == producer.regionIndex + 1
                    val consumerDoesNotPrecedeTarget = consumer.regionIndex >= outgoing.regionIndex
                    val sameTargetKeepsIdentity =
                        consumer.regionIndex != outgoing.regionIndex ||
                            consumer.regionIdentity == outgoing.regionIdentity
                    !targetsProducerOrNext || !consumerDoesNotPrecedeTarget || !sameTargetKeepsIdentity
                }
            ) {
                return invalid("Flow fragment provenance must progress through each outgoing continuation region.")
            }
            if (fragments.zipWithNext().any { (left, right) ->
                    left.laidOutRange.endExclusive != right.laidOutRange.start
                }
            ) {
                return invalid("Flow state materialized fragments must be consecutive.")
            }
            val fragmentCoverage = TextRange(
                fragments.first().laidOutRange.start,
                fragments.last().laidOutRange.endExclusive,
            )
            if (coverage.range != fragmentCoverage) {
                return invalid("Flow state coverage must equal its complete fragment coverage.")
            }
            if (continuation !== fragments.last().continuation) {
                return invalid("Flow state continuation must be the exact final fragment continuation.")
            }
            val tailRange = when (val tail = coverage.tailState) {
                is LayoutTailState.Invalidated -> tail.range
                is LayoutTailState.Stable -> tail.range
                LayoutTailState.MaterializedThroughDocumentEnd -> null
            }
            if (continuation == null && tailRange != null) {
                return invalid("A complete flow state cannot retain an unmaterialized coverage tail.")
            }
            if (continuation != null && tailRange != continuation.remainingSourceRange) {
                return invalid("Flow state coverage tail must equal its continuation remainder.")
            }
            if (continuation != null && (
                    continuation.inputIdentity != inputIdentity ||
                        continuation.compositionIdentity != flowCompositionIdentity
                    )
            ) {
                return invalid("Flow state continuation identities must equal the state identities.")
            }
            if (capturedCheckpoints.zipWithNext().any { (left, right) ->
                    left.laidOutRange.endExclusive > right.laidOutRange.endExclusive
                }
            ) {
                return invalid("Flow state checkpoints must be ordered.")
            }
            if (capturedCheckpoints.any { checkpoint ->
                    checkpoint.continuation.inputIdentity != inputIdentity ||
                        checkpoint.continuation.compositionIdentity != flowCompositionIdentity ||
                        checkpoint.continuation.paragraphRange != paragraphRange ||
                        !configuration.acceptsContinuation(checkpoint.continuation)
                }
            ) {
                return invalid("Every flow checkpoint must use the state input and chain identities.")
            }
            if (continuation != null && capturedCheckpoints.lastOrNull()?.continuation !== continuation) {
                return invalid("The final flow checkpoint must carry the published continuation.")
            }
            return FlowCompositionResult.Success(
                FlowLayoutState(
                    inputIdentity,
                    flowCompositionIdentity,
                    coverage,
                    configuration,
                    fragments,
                    capturedCheckpoints,
                    continuation,
                ),
            )
        }
    }
}

/**
 * Immutable consumer-visible bounded flow layout.
 *
 * The value contains complete existing [ParagraphFragment] and [LineLayout] models only. Its tail
 * is explicit and it owns no page, renderer, resolver, backend, or platform resource.
 */
public class FlowLayout(
    /** Target text and typography revisions used by every fragment. */
    public val inputIdentity: FlowCompositionInputIdentity,
    /** Caller range that drove bounded materialization. */
    public val requestedRange: TextRange,
    fragments: List<ParagraphFragment>,
    /** Exact source coverage represented by [fragments]. */
    public val coverage: LayoutCoverage,
    /** Exact suffix continuation, or `null` at physical paragraph end. */
    public val unmaterializedTail: FlowContinuation?,
    /** Resource-free state accepted by a compatible later request. */
    public val state: FlowLayoutState,
    /** Incremental restart and convergence information. */
    public val diagnostics: FlowLayoutDiagnostics,
) {
    /** Immutable complete fragments in source order. */
    public val fragments: List<ParagraphFragment> = fragments.immutableListSnapshot()

    /** Immutable complete lines flattened in logical source order. */
    public val lines: List<LineLayout> = this.fragments.flatMap(ParagraphFragment::lines).immutableListSnapshot()
}

/** Whether published flow fragments cover the complete paragraph or an exact prefix. */
public enum class FlowCoverageStatus {
    /** The paragraph source and every required physical line are represented. */
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

    /**
     * Immutable final caret candidates in line visual order.
     *
     * When an unavailable inline gap splits one logical boundary, the preceding fragment exposes
     * its upstream geometry and the following fragment exposes its downstream geometry. Both
     * candidates retain the same real snapshot-bound text index.
     */
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
 * end. That continuation may be empty only when a source-required terminal physical empty line
 * remains. The value owns no page, renderer, font handle, or mutable collection.
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
    /** Structured incremental-flow provenance, including for a final fragment without continuation. */
    public val flowProvenance: FlowFragmentProvenance? = null,
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
        require(
            isLastFragment ==
                (laidOutRange.endExclusive == paragraphRange.endExclusive && continuation == null),
        ) {
            "The last-fragment flag must agree with complete physical paragraph coverage."
        }
        require((continuation == null) == isLastFragment) {
            "Every non-final paragraph fragment must publish its exact continuation."
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

/**
 * Complete resource-free provenance binding a [ParagraphFragment] to one incremental flow input.
 *
 * Unlike a continuation, this value also exists for a final fragment. It records the complete
 * configuration signature and the exact region revision that produced the fragment, without
 * retaining a region provider, renderer, page, backend, or native resource.
 */
public data class FlowFragmentProvenance(
    /** Exact text and typography revisions used to produce the fragment. */
    public val inputIdentity: FlowCompositionInputIdentity,
    /** Opaque identity of the producing flow chain. */
    public val flowCompositionIdentity: FlowCompositionIdentity,
    /** Zero-based ordinal of the region containing the fragment. */
    public val regionIndex: Int,
    /** Exact immutable revision of the producing region. */
    public val regionIdentity: FlowRegionIdentity,
    /** Complete paragraph range represented by the composition operation. */
    public val paragraphRange: TextRange,
    /** Exact source range covered by this fragment. */
    public val laidOutRange: TextRange,
    /** Complete resource-free configuration required to accept this fragment in retained state. */
    public val configuration: FlowLayoutConfigurationSignature,
) {
    init {
        require(regionIndex >= 0) { "A flow fragment provenance region index must be non-negative." }
        require(paragraphRange.start.sharesVersionWith(laidOutRange.start)) {
            "Flow fragment provenance ranges must use one text revision."
        }
        require(laidOutRange.start >= paragraphRange.start && laidOutRange.endExclusive <= paragraphRange.endExclusive) {
            "Flow fragment provenance coverage must stay inside its paragraph."
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
