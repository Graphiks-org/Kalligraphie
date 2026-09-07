package org.graphiks.kalligraphie

import java.util.Collections
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FlowChain
import org.graphiks.kalligraphie.api.FlowCompositionDiagnostic
import org.graphiks.kalligraphie.api.FlowCompositionError
import org.graphiks.kalligraphie.api.FlowCompositionIdentity
import org.graphiks.kalligraphie.api.FlowCompositionInputIdentity
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowContinuation
import org.graphiks.kalligraphie.api.FlowRegionIdentity
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.HyphenationServiceIdentity
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.LayoutConfigurationSignature
import org.graphiks.kalligraphie.api.LayoutContractResult
import org.graphiks.kalligraphie.api.LayoutCoverage
import org.graphiks.kalligraphie.api.LayoutDelta
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutTailState
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.ParagraphFragment
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextOrientation
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.VerticalMetricsPolicy
import org.graphiks.kalligraphie.layout.FlowParagraphComposer
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend

/**
 * Immutable JVM request for bounded composition through an application-owned [FlowChain].
 *
 * The request reuses the portable versioned [input], [delta], range, constraints, and line
 * overscan contracts. A [previousState] is an optional resource-free capability returned by this
 * facade for the same chain. The materialization resolver, when present, is borrowed only during
 * [JvmFlowCompositionFacade.layout] and is never retained in published layout or state.
 *
 * @param features immutable OpenType feature overrides; defaults to the typography snapshot.
 */
public class JvmFlowCompositionRequest(
    /** Target text and typography snapshots. */
    public val input: LayoutInput,
    /** Source range whose containing complete flow lines must be materialized. */
    public val requestedRange: TextRange,
    /** Logical writing mode and line metrics used in every flow region. */
    public val constraints: ParagraphConstraints,
    /** Ordered application-owned geometry queried during composition. */
    public val flowChain: FlowChain,
    /** Number of complete lines retained after the line covering [requestedRange]. */
    public val overscan: LineOverscan,
    /** Optional prior immutable flow publication used for incremental continuation. */
    public val previousState: JvmFlowCompositionState? = null,
    /** Optional authoritative version transition from [previousState] to [input]. */
    public val delta: LayoutDelta? = null,
    /** Explicit UAX #9 paragraph base direction. */
    public val baseDirection: BaseDirection,
    /** Explicit BCP 47 language used by Unicode analysis and shaping. */
    public val language: String,
    /** Layout-only or synchronously outline-certified publication mode. */
    public val materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    /** Flow composition requires source-preserving continuation overflow. */
    public val overflowPolicy: OverflowPolicy = OverflowPolicy.Continue,
    /** Tab stops, alignment, and justification applied to every flow line. */
    public val positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
    /** Hyphenation policy applied by exact line selection. */
    public val hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
    /** Immutable service required by automatic hyphenation, when selected. */
    public val hyphenationService: HyphenationService? = null,
    /** Definitions for indivisible inline objects in the target snapshot. */
    public val inlineObjects: InlineObjectSnapshot? = null,
    /** Grapheme orientation policy for vertical flow composition. */
    public val textOrientation: TextOrientation = TextOrientation.MIXED,
    /** Missing vertical-metrics policy used by the shaping route. */
    public val verticalMetricsPolicy: VerticalMetricsPolicy = VerticalMetricsPolicy.SYNTHESIZE_IF_UNAVAILABLE,
    features: List<OpenTypeFeature> = input.typography.features,
    /** Cooperative signal; cancellation causes no layout value to be published. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
) {
    /** Immutable OpenType feature overrides in deterministic caller order. */
    public val features: List<OpenTypeFeature> = Collections.unmodifiableList(features.toList())

    init {
        require(language.isNotBlank()) { "Flow composition language must not be blank." }
    }
}

/**
 * Structured checkpoint immediately before an explicitly unmaterialized flow suffix.
 *
 * [continuation] carries the complete replay identity: exact text and typography revisions,
 * source suffix, chain and region identities, writing mode, block cursor, fragmentation state,
 * and paragraph shaping/analysis inputs. No private string serialization participates in reuse.
 */
public class JvmFlowCompositionCheckpoint internal constructor(
    /** Exact continuation used to resume the suffix. */
    public val continuation: FlowContinuation,
) {
    /** Zero-based region ordinal at which replay resumes. */
    public val resumeRegionOrdinal: Int = continuation.regionIndex

    /** Exact immutable region revision at [resumeRegionOrdinal]. */
    public val resumeRegionIdentity: FlowRegionIdentity = continuation.regionIdentity

    /** Exact logical block-axis cursor at which replay resumes. */
    public val blockCursor: Float = continuation.nextBlockOffset
}

/**
 * Resource-free immutable state produced by a bounded JVM flow composition.
 *
 * The state owns only fragments, structured continuations, coverage, and opaque identities. It
 * retains no flow region, text snapshot, page, renderer, resolver, shaping backend, or native
 * handle. Passing it with a different chain or unproven version transition is rejected.
 */
public class JvmFlowCompositionState internal constructor(
    /** Exact target text and typography revisions represented by this state. */
    public val inputIdentity: FlowCompositionInputIdentity,
    /** Opaque identity of the exact chain and fragmentation context. */
    public val flowCompositionIdentity: FlowCompositionIdentity,
    /** Exact complete-fragment source coverage retained by the state. */
    public val coverage: LayoutCoverage,
    materializedFragments: List<ParagraphFragment>,
    checkpoints: List<JvmFlowCompositionCheckpoint>,
    /** Exact continuation of the currently unmaterialized suffix, or `null` at physical paragraph end. */
    public val continuation: FlowContinuation?,
    internal val configuration: JvmFlowConfiguration,
) {
    /** Immutable ordered fragments retained for incremental extension. */
    public val materializedFragments: List<ParagraphFragment> =
        Collections.unmodifiableList(materializedFragments.toList())

    /** Immutable structured replay checkpoints in source order. */
    public val checkpoints: List<JvmFlowCompositionCheckpoint> =
        Collections.unmodifiableList(checkpoints.toList())

    init {
        require(coverage.textVersion == inputIdentity.textVersion) {
            "Flow state coverage must use its input text revision."
        }
        require(this.materializedFragments.zipWithNext().all { (left, right) ->
            left.laidOutRange.endExclusive == right.laidOutRange.start
        }) {
            "Flow state fragments must be consecutive in source order."
        }
        require(this.checkpoints.size <= this.materializedFragments.size) {
            "Flow state cannot retain more replay checkpoints than fragments."
        }
        require(continuation == null || continuation.remainingSourceRange.start == coverage.range.endExclusive) {
            "Flow state continuation must begin at the coverage end."
        }
    }
}

/** Immutable incremental diagnostics for one flow materialization. */
public data class JvmFlowCompositionDiagnostics(
    /** Exact boundary from which this call reused or recomposed forward flow. */
    public val reflowStart: TextIndex,
    /** Whether identity or version evidence forced composition from document start. */
    public val usedConservativeInvalidation: Boolean,
)

/**
 * Immutable consumer-visible output of one bounded flow composition.
 *
 * [fragments] are ordered in source and region progression order. [coverage] describes their
 * exact contiguous source prefix, while [unmaterializedTail] explicitly owns every remaining
 * source unit and any pending terminal physical line. The value owns no page or renderer.
 */
public class JvmFlowCompositionLayout internal constructor(
    /** Target text and typography revisions used by every fragment. */
    public val inputIdentity: FlowCompositionInputIdentity,
    /** Caller range that drove bounded materialization. */
    public val requestedRange: TextRange,
    fragments: List<ParagraphFragment>,
    /** Exact coverage of complete published flow fragments. */
    public val coverage: LayoutCoverage,
    /** Explicit exact suffix continuation, or `null` when physical paragraph layout is complete. */
    public val unmaterializedTail: FlowContinuation?,
    /** Resource-free state accepted by a compatible later request. */
    public val state: JvmFlowCompositionState,
    /** Incremental restart decision made for this call. */
    public val diagnostics: JvmFlowCompositionDiagnostics,
) {
    /** Immutable complete paragraph fragments in flow progression order. */
    public val fragments: List<ParagraphFragment> = Collections.unmodifiableList(fragments.toList())

    /** Immutable complete lines flattened in logical source order. */
    public val lines: List<LineLayout> = Collections.unmodifiableList(this.fragments.flatMap(ParagraphFragment::lines))
}

/**
 * JVM reference facade for bounded and incrementally extendable flow-chain composition.
 *
 * Every call performs pinned ICU analysis, UAX #14 breaking, HarfBuzz shaping and portable
 * [FlowParagraphComposer] composition. The facade may conservatively reflow the complete prefix;
 * it never changes shaping or geometry to meet a bound. Only complete fragments are returned,
 * and a cancelled or failed call publishes no layout value.
 */
public object JvmFlowCompositionFacade {
    /**
     * Materializes [request] synchronously and closes all facade-owned native resources.
     *
     * Invalid state, region protocols, font failures, and cancellation are returned as typed
     * [FlowCompositionResult.Failure] values.
     */
    public fun layout(request: JvmFlowCompositionRequest): FlowCompositionResult<JvmFlowCompositionLayout> {
        validateBeforeBackend(request)?.let { return FlowCompositionResult.Failure(it) }
        if (request.cancellationToken.isCancellationRequested()) {
            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        val backend = when (val opened = JvmHarfBuzzShapingBackend.open()) {
            is FontOperationResult.Success -> opened.value
            is FontOperationResult.Failure -> return FlowCompositionResult.Failure(
                FlowCompositionError.ParagraphFailure(ParagraphLayoutError.FontFailure(opened.error)),
            )
            is FontOperationResult.Cancelled -> return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        var result: FlowCompositionResult<JvmFlowCompositionLayout>? = null
        var closeResult: FontOperationResult<Unit>? = null
        try {
            result = layoutBorrowing(request, backend)
        } finally {
            closeResult = backend.close()
        }
        return when (val closed = checkNotNull(closeResult)) {
            is FontOperationResult.Success -> checkNotNull(result)
            is FontOperationResult.Failure -> FlowCompositionResult.Failure(
                FlowCompositionError.ParagraphFailure(ParagraphLayoutError.FontFailure(closed.error)),
            )
            is FontOperationResult.Cancelled -> FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
    }

    internal fun layoutBorrowing(
        request: JvmFlowCompositionRequest,
        backend: ShapingBackend,
    ): FlowCompositionResult<JvmFlowCompositionLayout> {
        validateBeforeBackend(request)?.let { return FlowCompositionResult.Failure(it) }
        if (request.cancellationToken.isCancellationRequested()) {
            return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
        }
        val configuration = JvmFlowConfiguration.from(request)
        val previous = request.previousState
        val sameInput = previous != null &&
            previous.inputIdentity.textVersion == request.input.text.version &&
            previous.inputIdentity.typographyVersion == request.input.typography.version
        if (sameInput && previous.configuration != configuration) {
            return FlowCompositionResult.Failure(
                FlowCompositionError.IncompatibleState(
                    "The retained flow state uses different paragraph or materialization inputs.",
                ),
            )
        }

        val canResume = sameInput && previous.continuation != null
        val fragments = if (canResume) previous.materializedFragments.toMutableList() else mutableListOf()
        val checkpoints = if (canResume) previous.checkpoints.toMutableList() else mutableListOf()
        var continuation = if (canResume) previous.continuation else null
        var sourceRange = continuation?.remainingSourceRange ?: request.input.text.range
        val reflowStart = sourceRange.start
        val conservative = previous != null && !canResume
        val diagnostics = mutableListOf<FlowCompositionDiagnostic>()

        if (fragments.isNotEmpty() && targetSatisfied(request, fragments, continuation)) {
            val selectedCount = selectedFragmentCount(request, fragments)
            val selected = fragments.take(selectedCount)
            val selectedTail = selected.last().continuation
            return publish(
                request,
                selected,
                checkpoints.take(selectedCount),
                selectedTail,
                configuration,
                selectedTail?.remainingSourceRange?.start ?: selected.last().laidOutRange.endExclusive,
                false,
                diagnostics,
            )
        }

        while (true) {
            if (request.cancellationToken.isCancellationRequested()) {
                return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
            }
            val paragraphRequest = try {
                JvmEditableParagraphFacade.prepareParagraphRequestBorrowing(
                    JvmEditableParagraphFacadeRequest(
                        snapshot = request.input.text,
                        sourceRange = sourceRange,
                        constraints = request.constraints,
                        baseDirection = request.baseDirection,
                        language = request.language,
                        fontCatalog = request.input.typography.fontCatalog,
                        resolutionPolicy = request.input.typography.resolutionPolicy,
                        fontInstanceDescriptor = request.input.typography.fontInstanceDescriptor,
                        features = request.features,
                        materialization = request.materialization,
                        overflowPolicy = request.overflowPolicy,
                        positioning = request.positioning,
                        hyphenationMode = request.hyphenationMode,
                        hyphenationService = request.hyphenationService,
                        inlineObjects = request.inlineObjects,
                        textOrientation = request.textOrientation,
                        verticalMetricsPolicy = request.verticalMetricsPolicy,
                        cancellationToken = request.cancellationToken,
                    ),
                    backend,
                ) ?: return FlowCompositionResult.Failure(FlowCompositionError.Cancelled)
            } catch (error: IllegalArgumentException) {
                return FlowCompositionResult.Failure(
                    FlowCompositionError.ParagraphFailure(
                        ParagraphLayoutError.InvalidInput(error.message ?: "Flow paragraph input is invalid."),
                    ),
                )
            }
            val composed = FlowParagraphComposer.layoutFragment(
                request = paragraphRequest,
                materialization = request.materialization,
                chain = request.flowChain,
                inputIdentity = FlowCompositionInputIdentity(
                    request.input.text.version,
                    request.input.typography.version,
                ),
                continuation = continuation,
            )
            val success = when (composed) {
                is FlowCompositionResult.Success -> composed
                is FlowCompositionResult.Failure -> return composed
            }
            val fragment = success.value
            fragments += fragment
            diagnostics += success.diagnostics
            continuation = fragment.continuation
            if (continuation != null) {
                checkpoints += JvmFlowCompositionCheckpoint(continuation)
            }

            if (targetSatisfied(request, fragments, continuation)) {
                return publish(
                    request,
                    fragments,
                    checkpoints,
                    continuation,
                    configuration,
                    reflowStart,
                    conservative,
                    diagnostics,
                )
            }
            continuation ?: return FlowCompositionResult.Failure(
                FlowCompositionError.ParagraphFailure(
                    ParagraphLayoutError.InvalidInput(
                        "Flow composition ended before complete lines covered the requested range and overscan.",
                    ),
                ),
            )
            sourceRange = continuation.remainingSourceRange
        }
    }

    private fun publish(
        request: JvmFlowCompositionRequest,
        fragments: List<ParagraphFragment>,
        checkpoints: List<JvmFlowCompositionCheckpoint>,
        tail: FlowContinuation?,
        configuration: JvmFlowConfiguration,
        reflowStart: TextIndex,
        conservative: Boolean,
        diagnostics: List<FlowCompositionDiagnostic>,
    ): FlowCompositionResult<JvmFlowCompositionLayout> {
        val range = TextRange(
            fragments.first().laidOutRange.start,
            fragments.last().laidOutRange.endExclusive,
        )
        val tailState = tail?.let { LayoutTailState.Invalidated(it.remainingSourceRange) }
            ?: LayoutTailState.MaterializedThroughDocumentEnd
        val coverage = when (
            val created = LayoutCoverage.create(
                request.input.text.version,
                range,
                isComplete = coversRequestedRange(request.requestedRange, range),
                tailState = tailState,
            )
        ) {
            is LayoutContractResult.Success -> created.value
            is LayoutContractResult.Failure -> return FlowCompositionResult.Failure(
                FlowCompositionError.ParagraphFailure(ParagraphLayoutError.InvalidInput(created.error.message)),
            )
        }
        val identity = FlowCompositionInputIdentity(
            request.input.text.version,
            request.input.typography.version,
        )
        val state = JvmFlowCompositionState(
            inputIdentity = identity,
            flowCompositionIdentity = request.flowChain.compositionIdentity,
            coverage = coverage,
            materializedFragments = fragments,
            checkpoints = checkpoints,
            continuation = tail,
            configuration = configuration,
        )
        return FlowCompositionResult.Success(
            JvmFlowCompositionLayout(
                inputIdentity = identity,
                requestedRange = request.requestedRange,
                fragments = fragments,
                coverage = coverage,
                unmaterializedTail = tail,
                state = state,
                diagnostics = JvmFlowCompositionDiagnostics(reflowStart, conservative),
            ),
            diagnostics,
        )
    }

    private fun validateBeforeBackend(request: JvmFlowCompositionRequest): FlowCompositionError? {
        val document = request.input.text.range
        if (
            !request.requestedRange.start.sharesVersionWith(document.start) ||
            request.requestedRange.start < document.start ||
            request.requestedRange.endExclusive > document.endExclusive
        ) {
            return incompatible("The requested flow range must belong to and stay inside the target snapshot.")
        }
        if (request.overflowPolicy != OverflowPolicy.Continue) {
            return FlowCompositionError.UnsupportedOverflowPolicy(request.overflowPolicy)
        }
        request.delta?.text?.let { delta ->
            if (delta.targetVersion != request.input.text.version) {
                return incompatible("The text delta target does not match the requested text revision.")
            }
        }
        request.delta?.typography?.let { delta ->
            if (delta.targetVersion != request.input.typography.version) {
                return incompatible("The typography delta target does not match the requested typography revision.")
            }
        }
        val previous = request.previousState ?: return null
        if (previous.flowCompositionIdentity != request.flowChain.compositionIdentity) {
            return incompatible("The retained flow state belongs to another flow chain.")
        }
        if (
            previous.inputIdentity.textVersion != request.input.text.version &&
            request.delta?.text?.sourceVersion != previous.inputIdentity.textVersion
        ) {
            return incompatible("A changed text revision requires a delta from the retained flow state.")
        }
        if (
            previous.inputIdentity.typographyVersion != request.input.typography.version &&
            request.delta?.typography?.sourceVersion != previous.inputIdentity.typographyVersion
        ) {
            return incompatible("A changed typography revision requires a delta from the retained flow state.")
        }
        val textDelta = request.delta?.text
        if (textDelta != null && textDelta.sourceVersion != previous.inputIdentity.textVersion) {
            return incompatible("The text delta source does not match the retained flow state.")
        }
        val typographyDelta = request.delta?.typography
        if (typographyDelta != null && typographyDelta.sourceVersion != previous.inputIdentity.typographyVersion) {
            return incompatible("The typography delta source does not match the retained flow state.")
        }
        return null
    }

    private fun targetSatisfied(
        request: JvmFlowCompositionRequest,
        fragments: List<ParagraphFragment>,
        tail: FlowContinuation?,
    ): Boolean {
        val lines = fragments.flatMap(ParagraphFragment::lines)
        if (lines.isEmpty()) return false
        val requested = request.requestedRange
        val documentEnd = request.input.text.range.endExclusive
        val targetLineIndex = if (requested.start == requested.endExclusive) {
            if (requested.start == documentEnd) {
                if (tail != null) return false
                lines.indexOfLast { line -> line.range.endExclusive == documentEnd }
            } else {
                lines.indexOfFirst { line -> requested.start >= line.range.start && requested.start < line.range.endExclusive }
            }
        } else {
            if (requested.endExclusive == documentEnd && tail != null) return false
            lines.indexOfFirst { line -> line.range.endExclusive >= requested.endExclusive }
        }
        return targetLineIndex >= 0 && lines.lastIndex - targetLineIndex >= request.overscan.lineCount
    }

    private fun selectedFragmentCount(
        request: JvmFlowCompositionRequest,
        fragments: List<ParagraphFragment>,
    ): Int = fragments.indices.firstOrNull { index ->
        val prefix = fragments.take(index + 1)
        targetSatisfied(request, prefix, prefix.last().continuation)
    }?.plus(1) ?: fragments.size

    private fun coversRequestedRange(requested: TextRange, coverage: TextRange): Boolean =
        if (requested.start == requested.endExclusive) {
            requested.start >= coverage.start && requested.start <= coverage.endExclusive
        } else {
            requested.start >= coverage.start && requested.endExclusive <= coverage.endExclusive
        }

    private fun incompatible(message: String): FlowCompositionError =
        FlowCompositionError.IncompatibleState(message)
}

internal data class JvmFlowConfiguration(
    val layout: LayoutConfigurationSignature,
    val features: List<OpenTypeFeature>,
    val baseDirection: BaseDirection,
    val language: String,
    val materialization: ParagraphMaterializationIdentity,
    val overflowPolicy: OverflowPolicy,
    val positioning: ParagraphPositioningPolicy,
    val hyphenationMode: HyphenationMode,
    val hyphenationServiceIdentity: HyphenationServiceIdentity?,
    val inlineObjects: InlineObjectSnapshot?,
    val textOrientation: TextOrientation,
    val verticalMetricsPolicy: VerticalMetricsPolicy,
    val flowCompositionIdentity: FlowCompositionIdentity,
    val regionIdentities: List<FlowRegionIdentity>,
) {
    companion object {
        fun from(request: JvmFlowCompositionRequest): JvmFlowConfiguration = JvmFlowConfiguration(
            layout = LayoutConfigurationSignature.from(request.input, request.constraints),
            features = request.features,
            baseDirection = request.baseDirection,
            language = request.language,
            materialization = ParagraphMaterializationIdentity.from(request.materialization),
            overflowPolicy = request.overflowPolicy,
            positioning = request.positioning,
            hyphenationMode = request.hyphenationMode,
            hyphenationServiceIdentity = request.hyphenationService?.identity,
            inlineObjects = request.inlineObjects,
            textOrientation = request.textOrientation,
            verticalMetricsPolicy = request.verticalMetricsPolicy,
            flowCompositionIdentity = request.flowChain.compositionIdentity,
            regionIdentities = request.flowChain.regions.map { region -> region.identity },
        )
    }
}
