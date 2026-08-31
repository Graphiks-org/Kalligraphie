package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/** Opaque identity of one immutable typography configuration revision. */
public class TypographyVersion private constructor() {
    /** Factories for typography revision identities. */
    public companion object {
        /** Creates a fresh typography revision identity. */
        public fun create(): TypographyVersion = TypographyVersion()
    }
}

/**
 * Immutable typography inputs used to lay out one text revision.
 *
 * The feature list is defensively captured, and the snapshot retains no resolver, renderer, or
 * other live resource.
 *
 * @param features deterministic feature overrides captured in caller order.
 */
public class TypographySnapshot(
    /** Identity of this exact typography revision. */
    public val version: TypographyVersion,
    /** Immutable catalogue generation used for font lookup. */
    public val fontCatalog: FontCatalogSnapshot,
    /** Immutable fallback order compatible with [fontCatalog]. */
    public val resolutionPolicy: FontResolutionPolicySnapshot,
    /** Geometry applied to resolved faces. */
    public val fontInstanceDescriptor: FontInstanceDescriptor,
    features: List<OpenTypeFeature> = emptyList(),
) {
    /** Deterministic feature overrides in caller order. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    init {
        require(fontCatalog.generation == resolutionPolicy.generation) {
            "Typography font catalog and resolution policy must use the same generation."
        }
        require(this.features.map(OpenTypeFeature::tag).distinct().size == this.features.size) {
            "Typography features must not repeat a tag."
        }
    }
}

/** One replacement from a source snapshot range to a range in the decoded target snapshot. */
public class TextChange(
    /** Half-open range removed from the source revision. */
    public val sourceRange: TextRange,
    /** Half-open range inserted from the target revision. */
    public val insertedTargetRange: TextRange,
) {
    /** Number of decoded Unicode scalars inserted by this replacement. */
    public val insertedScalarCount: Int =
        insertedTargetRange.endExclusive.ordinal - insertedTargetRange.start.ordinal

    /** Compares the source and target ranges. */
    override fun equals(other: Any?): Boolean =
        other is TextChange &&
            sourceRange == other.sourceRange &&
            insertedTargetRange == other.insertedTargetRange

    /** Returns a stable hash of the source and target ranges. */
    override fun hashCode(): Int = 31 * sourceRange.hashCode() + insertedTargetRange.hashCode()

    /** Returns a diagnostic form containing only opaque ranges and the derived scalar count. */
    override fun toString(): String =
        "TextChange(sourceRange=$sourceRange, insertedTargetRange=$insertedTargetRange, " +
            "insertedScalarCount=$insertedScalarCount)"
}

/**
 * Authoritative, normalized edit sequence between two immutable text revisions.
 *
 * Records retain target ranges and derived scalar counts, never copied inserted text. The change
 * list is a defensive immutable snapshot ordered in logical source and target order.
 */
public class TextChangeSet private constructor(
    /** Source text revision consumed by [changes]. */
    public val sourceVersion: TextVersion,
    /** Target text revision produced by [changes]. */
    public val targetVersion: TextVersion,
    changes: List<TextChange>,
) {
    /** Normalized replacements in logical order. */
    public val changes: List<TextChange> = changes.immutableListSnapshot()

    /** Validated construction of versioned text changes. */
    public companion object {
        /**
         * Validates source and target spaces, complete target accounting, and non-overlap before
         * normalizing adjacent replacements.
         */
        public fun create(
            source: TextSnapshot,
            target: TextSnapshot,
            changes: List<TextChange>,
        ): LayoutContractResult<TextChangeSet> = createTextChangeSet(source, target, changes)

        internal fun validated(
            source: TextSnapshot,
            target: TextSnapshot,
            changes: List<TextChange>,
        ): TextChangeSet = TextChangeSet(source.version, target.version, changes)
    }
}

/** Result of constructing an incremental layout contract from caller-controlled values. */
public sealed interface LayoutContractResult<out Value> {
    /** Successfully validated immutable contract value. */
    public data class Success<Value>(
        /** Validated value. */
        public val value: Value,
    ) : LayoutContractResult<Value>

    /** Typed rejection of invalid or incompatible contract values. */
    public data class Failure(
        /** Reason validation failed. */
        public val error: IncrementalLayoutError,
    ) : LayoutContractResult<Nothing>
}

/**
 * Validates and normalizes an edit sequence between [source] and [target].
 *
 * Same-boundary inserts merge only when their target ranges are contiguous. Replacements merge
 * only when both their non-empty source ranges and target ranges are adjacent.
 */
public fun createTextChangeSet(
    source: TextSnapshot,
    target: TextSnapshot,
    changes: List<TextChange>,
): LayoutContractResult<TextChangeSet> {
    val capturedChanges = changes.toList()
    capturedChanges.forEach { change ->
        val sourceError = validateRangeDomain(source, change.sourceRange, "Text change source range")
        if (sourceError != null) return LayoutContractResult.Failure(sourceError)
        val targetError = validateRangeDomain(target, change.insertedTargetRange, "Text change target range")
        if (targetError != null) return LayoutContractResult.Failure(targetError)
        if (change.sourceRange.isEmpty() && change.insertedTargetRange.isEmpty()) {
            return LayoutContractResult.Failure(
                IncrementalLayoutError.InvalidTextChange("A text change must remove or insert at least one scalar."),
            )
        }
    }

    val ordered = capturedChanges.sortedWith(
        compareBy<TextChange> { it.sourceRange.start.ordinal }
            .thenBy { it.insertedTargetRange.start.ordinal },
    )
    val overlap = ordered.zipWithNext().firstOrNull { (previous, current) ->
        current.sourceRange.start.ordinal < previous.sourceRange.endExclusive.ordinal
    }
    if (overlap != null) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.OverlappingRanges(overlap.first.sourceRange, overlap.second.sourceRange),
        )
    }

    var sourceCursor = 0
    var targetCursor = 0
    ordered.forEach { change ->
        val unchangedSourceCount = change.sourceRange.start.ordinal - sourceCursor
        val unchangedTargetCount = change.insertedTargetRange.start.ordinal - targetCursor
        if (unchangedSourceCount != unchangedTargetCount || unchangedSourceCount < 0) {
            return LayoutContractResult.Failure(
                IncrementalLayoutError.InvalidTextChange(
                    "Target ranges must account exactly for unchanged text between source edits.",
                ),
            )
        }
        if (!unchangedScalarsMatch(source, target, sourceCursor, targetCursor, unchangedSourceCount)) {
            return LayoutContractResult.Failure(
                IncrementalLayoutError.InvalidTextChange(
                    "Text outside target insertion ranges must equal the unchanged source text.",
                ),
            )
        }
        sourceCursor = change.sourceRange.endExclusive.ordinal
        targetCursor = change.insertedTargetRange.endExclusive.ordinal
    }
    if (source.scalars.size - sourceCursor != target.scalars.size - targetCursor) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.InvalidTextChange(
                "Target ranges must account exactly for unchanged text after the final source edit.",
            ),
        )
    }
    val trailingScalarCount = source.scalars.size - sourceCursor
    if (!unchangedScalarsMatch(source, target, sourceCursor, targetCursor, trailingScalarCount)) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.InvalidTextChange(
                "Text after the final target insertion range must equal the unchanged source text.",
            ),
        )
    }

    val normalized = mutableListOf<TextChange>()
    ordered.forEach { change ->
        val previous = normalized.lastOrNull()
        if (previous != null && previous.canMergeWith(change)) {
            normalized[normalized.lastIndex] = TextChange(
                sourceRange = TextRange(previous.sourceRange.start, change.sourceRange.endExclusive),
                insertedTargetRange = TextRange(
                    previous.insertedTargetRange.start,
                    change.insertedTargetRange.endExclusive,
                ),
            )
        } else {
            normalized += change
        }
    }
    return LayoutContractResult.Success(TextChangeSet.validated(source, target, normalized))
}

/** Describes source and target ranges invalidated by an input delta. */
public sealed interface RangeChange {
    /** Proven ranges derived from one authoritative [TextChangeSet]. */
    public class Proven internal constructor(
        sourceRanges: List<TextRange>,
        targetRanges: List<TextRange>,
    ) : RangeChange {
        /** Changed ranges in the source text space. */
        public val sourceRanges: List<TextRange> = sourceRanges.immutableListSnapshot()

        /** Changed ranges in the target text space. */
        public val targetRanges: List<TextRange> = targetRanges.immutableListSnapshot()
    }

    /** Conservative invalidation used when no affected ranges have been proven. */
    public data object FullInvalidation : RangeChange

    /** Factories for range invalidation contracts. */
    public companion object {
        /** Derives affected source and target ranges from the authoritative text delta. */
        public fun from(changeSet: TextChangeSet): Proven = Proven(
            sourceRanges = changeSet.changes.map(TextChange::sourceRange),
            targetRanges = changeSet.changes.map(TextChange::insertedTargetRange),
        )
    }
}

/**
 * Immutable font-policy transition and its conservatively affected ranges.
 *
 * @param provenRanges optional ranges derived from an authoritative change set.
 */
public class FontResolutionPolicyDelta(
    /** Policy used by the source typography revision. */
    public val source: FontResolutionPolicySnapshot,
    /** Policy used by the target typography revision. */
    public val target: FontResolutionPolicySnapshot,
    provenRanges: RangeChange.Proven? = null,
) {
    /** Proven invalidation ranges, or full invalidation when none were supplied. */
    public val rangeChange: RangeChange = provenRanges ?: RangeChange.FullInvalidation
}

/** Versioned typography transition with explicit invalidation scope. */
public class TypographyDelta(
    /** Source typography revision. */
    public val sourceVersion: TypographyVersion,
    /** Target typography revision. */
    public val targetVersion: TypographyVersion,
    /** Affected ranges, conservatively full when the change is not localized. */
    public val rangeChange: RangeChange = RangeChange.FullInvalidation,
    /** Optional policy-specific details. */
    public val fontResolutionPolicy: FontResolutionPolicyDelta? = null,
)

/** Complete immutable text and typography inputs for one layout revision. */
public data class LayoutInput(
    /** Canonical decoded text revision. */
    public val text: TextSnapshot,
    /** Typography revision applied to [text]. */
    public val typography: TypographySnapshot,
)

/** Versioned changes from a previous layout input to a target [LayoutInput]. */
public class LayoutDelta(
    /** Optional authoritative text transition. */
    public val text: TextChangeSet? = null,
    /** Optional typography transition. */
    public val typography: TypographyDelta? = null,
)

/** Non-negative number of complete lines retained beyond the requested range. */
@JvmInline
public value class LineOverscan(
    /** Number of extra complete lines on each applicable boundary. */
    public val lineCount: Int,
) {
    init {
        require(lineCount >= 0) { "Line overscan must be non-negative." }
    }
}

/**
 * Immutable coverage metadata for a laid-out text revision.
 *
 * Public callers construct coverage through [create], which proves that [range] carries
 * [textVersion]. The internal constructor supports trusted layout implementations while request
 * validation still rejects an inconsistent internal value at the public boundary.
 */
public class LayoutCoverage internal constructor(
    /** Text revision to which [range] belongs. */
    public val textVersion: TextVersion,
    /** Contiguous half-open range covered by complete layout output. */
    public val range: TextRange,
    /** Whether [range] completely covers the corresponding request. */
    public val isComplete: Boolean,
) {
    /** Validated construction of immutable coverage metadata. */
    public companion object {
        /** Creates coverage only when [range] belongs to [textVersion]. */
        public fun create(
            textVersion: TextVersion,
            range: TextRange,
            isComplete: Boolean,
        ): LayoutContractResult<LayoutCoverage> =
            if (range.usesVersion(textVersion)) {
                LayoutContractResult.Success(LayoutCoverage(textVersion, range, isComplete))
            } else {
                LayoutContractResult.Failure(
                    IncrementalLayoutError.VersionMismatch(
                        "Layout coverage range must use the declared text version.",
                    ),
                )
            }
    }
}

/** Immutable version checkpoint represented by a retained layout state. */
public data class LayoutCheckpoint(
    /** Text revision captured by the state. */
    public val textVersion: TextVersion,
    /** Typography revision captured by the state. */
    public val typographyVersion: TypographyVersion,
)

/**
 * Resource-free capability for reusing a prior layout state.
 *
 * Only immutable identity, version checkpoint, and coverage metadata are exposed; no document,
 * resolver, renderer, or platform resource can be reached through this handle.
 */
public class LayoutStateHandle(
    /** Stable implementation-defined state identity. */
    public val identity: String,
    /** Exact input versions captured by the state. */
    public val checkpoint: LayoutCheckpoint,
    /** Complete-line text coverage available in the state. */
    public val coverage: LayoutCoverage,
) {
    init {
        require(identity.isNotBlank()) { "Layout state identity must not be blank." }
        require(checkpoint.textVersion == coverage.textVersion) {
            "Layout state checkpoint and coverage must use the same text version."
        }
    }
}

/** Fully validated immutable request for incremental horizontal layout. */
public class IncrementalLayoutRequest internal constructor(
    /** Target text and typography inputs. */
    public val input: LayoutInput,
    /** Target text range requested by the caller. */
    public val requestedRange: TextRange,
    /** Physical horizontal paragraph constraints. */
    public val constraints: HorizontalParagraphConstraints,
    /** Complete-line overscan retained outside the requested range. */
    public val overscan: LineOverscan,
    /** Optional resource-free prior state metadata. */
    public val previousState: LayoutStateHandle?,
    /** Optional versioned changes from [previousState] to [input]. */
    public val delta: LayoutDelta?,
    /** Cooperative cancellation signal observed by layout work. */
    public val cancellationToken: CancellationToken,
)

/**
 * Validates an incremental layout request without retaining mutable document or renderer state.
 */
public fun createIncrementalLayoutRequest(
    input: LayoutInput,
    requestedRange: TextRange,
    constraints: HorizontalParagraphConstraints,
    overscan: LineOverscan,
    previousState: LayoutStateHandle?,
    delta: LayoutDelta?,
    cancellationToken: CancellationToken,
): LayoutContractResult<IncrementalLayoutRequest> {
    val rangeError = validateRangeDomain(input.text, requestedRange, "Requested layout range")
    if (rangeError != null) return LayoutContractResult.Failure(rangeError)

    if (previousState != null && !previousState.coverage.range.usesVersion(previousState.coverage.textVersion)) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch(
                "Previous layout coverage range does not match its declared text version.",
            ),
        )
    }
    if (
        previousState != null &&
        previousState.checkpoint.textVersion != input.text.version &&
        delta?.text == null
    ) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("A changed text checkpoint requires a versioned text delta."),
        )
    }
    if (
        previousState != null &&
        previousState.checkpoint.typographyVersion != input.typography.version &&
        delta?.typography == null
    ) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch(
                "A changed typography checkpoint requires a versioned typography delta.",
            ),
        )
    }
    if (delta?.text != null && delta.text.targetVersion != input.text.version) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("Text delta target does not match the layout input revision."),
        )
    }
    if (delta?.typography != null && delta.typography.targetVersion != input.typography.version) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("Typography delta target does not match the layout input revision."),
        )
    }
    if (previousState != null && delta?.text != null && previousState.checkpoint.textVersion != delta.text.sourceVersion) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("Text delta source does not match the previous layout checkpoint."),
        )
    }
    if (
        previousState != null &&
        delta?.typography != null &&
        previousState.checkpoint.typographyVersion != delta.typography.sourceVersion
    ) {
        return LayoutContractResult.Failure(
            IncrementalLayoutError.VersionMismatch("Typography delta source does not match the previous layout checkpoint."),
        )
    }
    return LayoutContractResult.Success(
        IncrementalLayoutRequest(
            input,
            requestedRange,
            constraints,
            overscan,
            previousState,
            delta,
            cancellationToken,
        ),
    )
}

/** Immutable published incremental layout metadata. */
public interface IncrementalLayout {
    /** Target input represented by this output. */
    public val input: LayoutInput

    /** Complete-line coverage published by this output. */
    public val coverage: LayoutCoverage

    /** Resource-free state metadata available for a later request. */
    public val state: LayoutStateHandle
}

/** Typed reason an incremental layout contract or operation could not produce output. */
public sealed interface IncrementalLayoutError {
    /** Stable machine-readable error code. */
    public val code: String

    /** Deterministic human-readable explanation. */
    public val message: String

    /** A range, delta, or state belongs to an incompatible text or typography revision. */
    public data class VersionMismatch(
        override val message: String,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-version-mismatch"
    }

    /** A supplied range lies outside the snapshot to which it claims to belong. */
    public data class InvalidRange(
        override val message: String,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-invalid-range"
    }

    /** Two source ranges overlap and therefore cannot describe a deterministic edit sequence. */
    public data class OverlappingRanges(
        /** Earlier overlapping source range. */
        public val first: TextRange,
        /** Later overlapping source range. */
        public val second: TextRange,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-overlapping-ranges"
        override val message: String = "Text change source ranges must not overlap."
    }

    /** Source and target ranges do not describe a complete deterministic text transition. */
    public data class InvalidTextChange(
        override val message: String,
    ) : IncrementalLayoutError {
        override val code: String = "layout.incremental-invalid-text-change"
    }
}

/** Typed outcome of incremental layout without partial output on failure or cancellation. */
public sealed interface IncrementalLayoutResult {
    /** Successfully published immutable incremental layout output. */
    public data class Success(
        /** Published layout. */
        public val layout: IncrementalLayout,
    ) : IncrementalLayoutResult

    /** No layout was published because validation or layout failed. */
    public data class Failure(
        /** Typed failure. */
        public val error: IncrementalLayoutError,
    ) : IncrementalLayoutResult

    /** No layout was published because cooperative cancellation was observed. */
    public data object Cancelled : IncrementalLayoutResult
}

private fun validateRangeDomain(
    snapshot: TextSnapshot,
    range: TextRange,
    label: String,
): IncrementalLayoutError? {
    if (!range.start.belongsTo(snapshot) || !range.endExclusive.belongsTo(snapshot)) {
        return IncrementalLayoutError.VersionMismatch("$label must use the supplied snapshot version.")
    }
    if (!snapshot.contains(range)) {
        return IncrementalLayoutError.InvalidRange("$label must lie within the supplied snapshot.")
    }
    return null
}

private fun TextRange.isEmpty(): Boolean = start == endExclusive

private fun TextRange.usesVersion(version: TextVersion): Boolean =
    start.sharesVersionWith(TextIndex(version, 0))

private fun unchangedScalarsMatch(
    source: TextSnapshot,
    target: TextSnapshot,
    sourceStart: Int,
    targetStart: Int,
    scalarCount: Int,
): Boolean =
    source.scalars.subList(sourceStart, sourceStart + scalarCount) ==
        target.scalars.subList(targetStart, targetStart + scalarCount)

private fun TextChange.canMergeWith(other: TextChange): Boolean {
    val targetAdjacent = insertedTargetRange.endExclusive == other.insertedTargetRange.start
    val sameBoundaryInserts =
        sourceRange.isEmpty() &&
            other.sourceRange.isEmpty() &&
            sourceRange.start == other.sourceRange.start
    val adjacentReplacements =
        !sourceRange.isEmpty() &&
            !other.sourceRange.isEmpty() &&
            sourceRange.endExclusive == other.sourceRange.start
    return targetAdjacent && (sameBoundaryInserts || adjacentReplacements)
}
