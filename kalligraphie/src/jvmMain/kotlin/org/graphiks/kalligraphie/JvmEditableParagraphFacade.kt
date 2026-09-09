@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie

import java.util.Collections
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineDiagnosticSeverity
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationContext
import org.graphiks.kalligraphie.api.EditorOperationLimitExceeded
import org.graphiks.kalligraphie.api.EditorOperationLimitKind
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.ParagraphConstraints
import org.graphiks.kalligraphie.api.InlineObjectSnapshot
import org.graphiks.kalligraphie.api.HyphenationMode
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.LayoutContinuation
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OverflowPolicy
import org.graphiks.kalligraphie.api.ParagraphLayoutError
import org.graphiks.kalligraphie.api.ParagraphLayoutRequest
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphMaterializationIdentity
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextOrientation
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.UnicodeAnalysisOutcome
import org.graphiks.kalligraphie.api.VerticalMetricsPolicy
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.layout.ParagraphComposer
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.api.LineBreakAnalysisOutcome

/**
 * Complete input to the JVM reference editable-paragraph journey.
 *
 * The request describes one rectangular composition call. [snapshot], [sourceRange],
 * font catalog and policy, geometry, language, direction, features, publication mode, and any
 * exact [continuation] are explicit. The feature list is defensively captured. A resolver inside
 * [materialization] is borrowed only during the synchronous call and is never copied into a
 * paragraph or continuation; callers remain responsible for closing it. This request is safe to
 * share between threads when that borrowed resolver and [cancellationToken] support concurrent
 * access.
 *
 * Input incompatibilities discovered after pinned Unicode and shaping identities are available
 * are returned as [ParagraphLayoutError.InvalidInput], rather than escaping from
 * [JvmEditableParagraphFacade.layout].
 */
public class JvmEditableParagraphFacadeRequest(
    /** Complete immutable source snapshot analyzed by the facade. */
    public val snapshot: TextSnapshot,
    /** Source range to compose, or the exact remainder named by [continuation]. */
    public val sourceRange: TextRange = snapshot.range,
    /** Physical paragraph region and line rhythm in renderer-independent layout coordinates. */
    public val constraints: ParagraphConstraints,
    /** Explicit UAX #9 paragraph base direction. */
    public val baseDirection: BaseDirection,
    /** Explicit BCP 47 language used for Unicode analysis and shaping. */
    public val language: String,
    /** Immutable embedded or provider catalog used for deterministic fallback. */
    public val fontCatalog: FontCatalogSnapshot,
    /** Total ordered fallback policy bound to [fontCatalog]. */
    public val resolutionPolicy: FontResolutionPolicySnapshot,
    /** Font geometry applied to every selected face. */
    public val fontInstanceDescriptor: FontInstanceDescriptor,
    features: List<OpenTypeFeature> = emptyList(),
    /** Layout-only or synchronously profile-certified publication mode. */
    public val materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    /** Complete-line overflow behavior; ellipsis truncation when [OverflowPolicy.Ellipsis] is selected. */
    public val overflowPolicy: OverflowPolicy = OverflowPolicy.Continue,    /** Tab stops, alignment, and justification applied to the paragraph lines. */
    public val positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
    /** Hyphenation mode applied by line selection and final line content. */
    public val hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
    /** Immutable versioned service used by [HyphenationMode.AUTO], or `null` when absent. */
    public val hyphenationService: HyphenationService? = null,
    /** Definitions bound to `U+FFFC` object replacement scalars inside the requested range. */
    public val inlineObjects: InlineObjectSnapshot? = null,
    /** Unicode orientation policy applied to extended grapheme clusters in vertical composition. */
    public val textOrientation: TextOrientation = TextOrientation.MIXED,
    /** Policy for missing OpenType `vhea` and `vmtx` metrics in vertical composition. */
    public val verticalMetricsPolicy: VerticalMetricsPolicy = VerticalMetricsPolicy.SYNTHESIZE_IF_UNAVAILABLE,
    /** Immutable replay capability returned by a preceding partial call. */
    public val continuation: LayoutContinuation? = null,
    /** Cooperative signal checked before and during bounded composition work. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
    /** Shared finite resource policy for this complete analysis-through-composition operation. */
    public val operationProfile: EditorOperationProfile,
) {
    /** Creates a request through the historical constructor with an unbounded operation policy. */
    public constructor(
        snapshot: TextSnapshot,
        sourceRange: TextRange = snapshot.range,
        constraints: ParagraphConstraints,
        baseDirection: BaseDirection,
        language: String,
        fontCatalog: FontCatalogSnapshot,
        resolutionPolicy: FontResolutionPolicySnapshot,
        fontInstanceDescriptor: FontInstanceDescriptor,
        features: List<OpenTypeFeature> = emptyList(),
        materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
        overflowPolicy: OverflowPolicy = OverflowPolicy.Continue,
        positioning: ParagraphPositioningPolicy = ParagraphPositioningPolicy(),
        hyphenationMode: HyphenationMode = HyphenationMode.MANUAL,
        hyphenationService: HyphenationService? = null,
        inlineObjects: InlineObjectSnapshot? = null,
        textOrientation: TextOrientation = TextOrientation.MIXED,
        verticalMetricsPolicy: VerticalMetricsPolicy = VerticalMetricsPolicy.SYNTHESIZE_IF_UNAVAILABLE,
        continuation: LayoutContinuation? = null,
        cancellationToken: CancellationToken = CancellationToken.none,
    ) : this(
        snapshot,
        sourceRange,
        constraints,
        baseDirection,
        language,
        fontCatalog,
        resolutionPolicy,
        fontInstanceDescriptor,
        features,
        materialization,
        overflowPolicy,
        positioning,
        hyphenationMode,
        hyphenationService,
        inlineObjects,
        textOrientation,
        verticalMetricsPolicy,
        continuation,
        cancellationToken,
        EditorOperationProfile.unbounded,
    )

    /** Immutable defensive snapshot of deterministic OpenType feature overrides in caller order. */
    public val features: List<OpenTypeFeature> = Collections.unmodifiableList(features.toList())
}

/**
 * JVM-reference consumer facade for immutable editable multiline paragraphs.
 *
 * Each call owns its temporary ICU analysis objects and HarfBuzz backend. It performs full
 * Unicode analysis, UAX #14 line-break analysis, provisional and boundary-correct final shaping,
 * per-line UAX #9 finalization, placement, metrics, and editing geometry through the portable
 * paragraph composer. The HarfBuzz backend is closed before the call returns, including failure
 * and cancellation paths. A renderable request only lends its resolver for the call.
 *
 * Successful results contain immutable snapshot-bound paragraph values and resource-free
 * continuations only. No backend, resolver, native handle, renderer, or platform object is
 * retained. The facade itself has no mutable state and can be called concurrently.
 */
public object JvmEditableParagraphFacade {
    /**
     * Composes [request] through the complete pinned JVM reference route.
     *
     * Invalid consumer inputs, font and shaping failures, geometry overflow, cancellation, and
     * backend-close failures are represented by [ParagraphLayoutResult]. Only unexpected virtual
     * machine failures escape the call. A successful result publishes complete lines only.
     */
    public fun layout(request: JvmEditableParagraphFacadeRequest): ParagraphLayoutResult {
        val context = EditorOperationContext.create(request.operationProfile, request.cancellationToken)
        context.sourceLimit(request.snapshot)?.let {
            return ParagraphLayoutResult.Failure(ParagraphLayoutError.OperationLimitExceeded(it))
        }
        context.scalarLimit(request.snapshot)?.let {
            return ParagraphLayoutResult.Failure(ParagraphLayoutError.OperationLimitExceeded(it))
        }
        if (context.isCancellationRequested()) return ParagraphLayoutResult.Cancelled()
        val backend = when (val opened = JvmHarfBuzzShapingBackend.open()) {
            is FontOperationResult.Success -> opened.value
            is FontOperationResult.Failure -> return ParagraphLayoutResult.Failure(
                ParagraphLayoutError.FontFailure(opened.error),
                opened.diagnostics.toParagraphDiagnostics(),
            )

            is FontOperationResult.Cancelled -> return ParagraphLayoutResult.Cancelled(
                opened.diagnostics.toParagraphDiagnostics(),
            )
        }
        return layoutOwned(request, backend, context)
    }

    internal fun layout(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
    ): ParagraphLayoutResult = layoutOwned(
        request,
        backend,
        EditorOperationContext.create(request.operationProfile, request.cancellationToken),
    )

    internal fun layout(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
        paragraphLayout: (ParagraphLayoutRequest, EditableLineMaterialization) -> ParagraphLayoutResult =
            ParagraphComposer::layout,
    ): ParagraphLayoutResult {
        var result: ParagraphLayoutResult? = null
        var closeResult: FontOperationResult<Unit>? = null
        try {
            result = layoutBorrowing(request, backend, paragraphLayout)
        } finally {
            closeResult = backend.close()
        }
        return includeBackendCloseResult(checkNotNull(result), checkNotNull(closeResult))
    }

    private fun layoutOwned(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
        context: EditorOperationContext,
    ): ParagraphLayoutResult {
        var result: ParagraphLayoutResult? = null
        var closeResult: FontOperationResult<Unit>? = null
        try {
            result = layoutBorrowing(request, backend, context)
        } finally {
            closeResult = backend.close()
        }
        return includeBackendCloseResult(checkNotNull(result), checkNotNull(closeResult))
    }

    /**
     * Composes [request] with a caller-owned [backend] without closing it.
     *
     * The backend and any resolver in [request] are borrowed only for this synchronous call. The
     * caller remains responsible for their lifecycle on success, failure, cancellation, and
     * exceptions. This seam runs the same Unicode, line-breaking, fallback, shaping, metrics, and
     * geometry route as the public facade.
     */
    internal fun layoutBorrowing(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
    ): ParagraphLayoutResult = layoutBorrowing(
        request,
        backend,
        EditorOperationContext.create(request.operationProfile, request.cancellationToken),
    )

    internal fun layoutBorrowing(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
        context: EditorOperationContext,
    ): ParagraphLayoutResult = try {
        when (val prepared = prepareParagraphRequestBorrowing(request, backend, context)) {
            is ParagraphPreparation.Success -> ParagraphComposer.layout(
                prepared.request,
                request.materialization,
                context,
            )
            is ParagraphPreparation.Failure -> prepared.result
            ParagraphPreparation.Cancelled -> ParagraphLayoutResult.Cancelled()
        }
    } catch (error: IllegalArgumentException) {
        ParagraphLayoutResult.Failure(
            ParagraphLayoutError.InvalidInput(error.message ?: "Paragraph input is invalid."),
        )
    }

    internal fun layoutBorrowing(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
        paragraphLayout: (ParagraphLayoutRequest, EditableLineMaterialization) -> ParagraphLayoutResult =
            ParagraphComposer::layout,
    ): ParagraphLayoutResult = try {
        val paragraphRequest = prepareParagraphRequestBorrowing(request, backend) ?: return ParagraphLayoutResult.Cancelled()
        paragraphLayout(paragraphRequest, request.materialization)
    } catch (error: IllegalArgumentException) {
        ParagraphLayoutResult.Failure(
            ParagraphLayoutError.InvalidInput(error.message ?: "Paragraph input is invalid."),
        )
    }

    /**
     * Creates a resource-free continuation for a proven line boundary without shaping its prefix.
     *
     * Unicode and line-break context are analyzed through the same pinned JVM route. [backend] and
     * any materialization resolver are borrowed and never closed or retained. Cancellation and
     * every preparation failure remain distinct typed outcomes.
     */
    internal fun continuationBorrowing(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
        remainingSourceRange: TextRange,
        resumptionRegionTop: org.graphiks.kalligraphie.api.LayoutUnit,
        resumptionBlockCursor: org.graphiks.kalligraphie.api.LayoutUnit,
    ): ParagraphContinuationPreparation = continuationBorrowing(
        request,
        backend,
        remainingSourceRange,
        resumptionRegionTop,
        resumptionBlockCursor,
        EditorOperationContext.create(request.operationProfile, request.cancellationToken),
    )

    internal fun continuationBorrowing(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
        remainingSourceRange: TextRange,
        resumptionRegionTop: org.graphiks.kalligraphie.api.LayoutUnit,
        resumptionBlockCursor: org.graphiks.kalligraphie.api.LayoutUnit,
        context: EditorOperationContext,
    ): ParagraphContinuationPreparation {
        val paragraphRequest = when (val prepared = prepareParagraphRequestBorrowing(request, backend, context)) {
            is ParagraphPreparation.Success -> prepared.request
            is ParagraphPreparation.Failure -> return ParagraphContinuationPreparation.Failure(prepared.result)
            ParagraphPreparation.Cancelled -> return ParagraphContinuationPreparation.Cancelled
        }
        return try {
            ParagraphContinuationPreparation.Success(
                LayoutContinuation.create(
                    request = paragraphRequest,
                    remainingSourceRange = remainingSourceRange,
                    resumptionRegionTop = resumptionRegionTop,
                    resumptionBlockCursor = resumptionBlockCursor,
                ),
            )
        } catch (error: IllegalArgumentException) {
            ParagraphContinuationPreparation.Failure(
                ParagraphLayoutResult.Failure(
                    ParagraphLayoutError.InvalidInput(error.message ?: "Paragraph continuation input is invalid."),
                ),
            )
        }
    }

    internal fun prepareParagraphRequestBorrowing(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
    ): ParagraphLayoutRequest? = when (
        val prepared = prepareParagraphRequestBorrowing(
            request,
            backend,
            EditorOperationContext.create(request.operationProfile, request.cancellationToken),
        )
    ) {
        is ParagraphPreparation.Success -> prepared.request
        is ParagraphPreparation.Failure, ParagraphPreparation.Cancelled -> null
    }

    internal fun prepareParagraphRequestBorrowing(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
        context: EditorOperationContext,
    ): ParagraphPreparation {
        context.sourceLimit(request.snapshot)?.let {
            return ParagraphPreparation.Failure(
                ParagraphLayoutResult.Failure(ParagraphLayoutError.OperationLimitExceeded(it)),
            )
        }
        context.scalarLimit(request.snapshot)?.let {
            return ParagraphPreparation.Failure(
                ParagraphLayoutResult.Failure(ParagraphLayoutError.OperationLimitExceeded(it)),
            )
        }
        if (context.isCancellationRequested()) return ParagraphPreparation.Cancelled
        val unicodeAnalysis = when (
            val analyzed = JvmUnicodeAnalyzer.create().analyze(
                snapshot = request.snapshot,
                request = UnicodeAnalysisRequest(request.baseDirection, request.language),
                profile = context.profile.unicodeAnalysisProfile,
                cancellationToken = context.cancellationToken,
            )
        ) {
            is UnicodeAnalysisOutcome.Success -> analyzed.value
            is UnicodeAnalysisOutcome.LimitExceeded -> return ParagraphPreparation.Failure(
                ParagraphLayoutResult.Failure(
                    ParagraphLayoutError.OperationLimitExceeded(
                        EditorOperationLimitExceeded(
                            EditorOperationLimitKind.ANALYZED_SCALARS,
                            context.profile.maxAnalyzedScalars.toLong(),
                            analyzed.observed.toLong(),
                        ),
                    ),
                ),
            )
            UnicodeAnalysisOutcome.Cancelled -> return ParagraphPreparation.Cancelled
        }
        if (context.isCancellationRequested()) return ParagraphPreparation.Cancelled
        val canonicalLanguage = unicodeAnalysis.scriptLanguageRuns
            .firstOrNull()
            ?.language
            ?: JvmUnicodeAnalyzer.canonicalizeLanguageTag(request.language)
        val lineBreakAnalysis = when (
            val analyzed = JvmLineBreakAnalyzer.createBounded().analyze(
                request.snapshot,
                unicodeAnalysis,
                context,
            )
        ) {
            is LineBreakAnalysisOutcome.Success -> analyzed.value
            is LineBreakAnalysisOutcome.LimitExceeded -> return ParagraphPreparation.Failure(
                ParagraphLayoutResult.Failure(ParagraphLayoutError.OperationLimitExceeded(analyzed.limit)),
            )
            LineBreakAnalysisOutcome.Cancelled -> return ParagraphPreparation.Cancelled
        }
        if (context.isCancellationRequested()) return ParagraphPreparation.Cancelled
        return ParagraphPreparation.Success(ParagraphLayoutRequest(
            snapshot = request.snapshot,
            sourceRange = request.sourceRange,
            unicodeAnalysis = unicodeAnalysis,
            lineBreakAnalysis = lineBreakAnalysis,
            constraints = request.constraints,
            baseDirection = request.baseDirection,
            language = canonicalLanguage,
            featurePolicy = backend.identity.semantic.featurePolicy,
            features = request.features,
            fontCatalog = request.fontCatalog,
            resolutionPolicy = request.resolutionPolicy,
            fontInstanceDescriptor = request.fontInstanceDescriptor,
            shapingBackend = context.boundedBackend(backend),
            materializationIdentity = ParagraphMaterializationIdentity.from(request.materialization),
            overflowPolicy = request.overflowPolicy,
            positioning = request.positioning,
            hyphenationMode = request.hyphenationMode,
            hyphenationService = request.hyphenationService,
            inlineObjects = request.inlineObjects,
            textOrientation = request.textOrientation,
            verticalMetricsPolicy = request.verticalMetricsPolicy,
            continuation = request.continuation,
            cancellationToken = context.cancellationToken,
            operationProfile = context.profile,
        ))
    }

    private fun includeBackendCloseResult(
        result: ParagraphLayoutResult,
        closeResult: FontOperationResult<Unit>,
    ): ParagraphLayoutResult = when (closeResult) {
        is FontOperationResult.Success -> result
        is FontOperationResult.Failure -> {
            val closeDiagnostics = closeResult.diagnostics
                .ifEmpty { listOf(closeResult.error.toDiagnostic()) }
                .toParagraphDiagnostics()
            when (result) {
                is ParagraphLayoutResult.Success -> ParagraphLayoutResult.Failure(
                    ParagraphLayoutError.FontFailure(closeResult.error),
                    closeDiagnostics,
                )

                is ParagraphLayoutResult.Failure -> ParagraphLayoutResult.Failure(
                    result.error,
                    result.diagnostics + closeDiagnostics,
                )

                is ParagraphLayoutResult.Cancelled -> ParagraphLayoutResult.Cancelled(
                    result.diagnostics + closeDiagnostics,
                )
            }
        }

        is FontOperationResult.Cancelled -> {
            val closeDiagnostics = closeResult.diagnostics.toParagraphDiagnostics()
            when (result) {
                is ParagraphLayoutResult.Success -> ParagraphLayoutResult.Cancelled(closeDiagnostics)
                is ParagraphLayoutResult.Failure -> ParagraphLayoutResult.Failure(
                    result.error,
                    result.diagnostics + closeDiagnostics,
                )

                is ParagraphLayoutResult.Cancelled -> ParagraphLayoutResult.Cancelled(
                    result.diagnostics + closeDiagnostics,
                )
            }
        }
    }
}

internal sealed interface ParagraphPreparation {
    class Success(val request: ParagraphLayoutRequest) : ParagraphPreparation
    class Failure(val result: ParagraphLayoutResult.Failure) : ParagraphPreparation
    data object Cancelled : ParagraphPreparation
}

internal sealed interface ParagraphContinuationPreparation {
    class Success(val continuation: LayoutContinuation) : ParagraphContinuationPreparation
    class Failure(val result: ParagraphLayoutResult.Failure) : ParagraphContinuationPreparation
    data object Cancelled : ParagraphContinuationPreparation
}

private fun List<FontDiagnostic>.toParagraphDiagnostics(): List<EditableLineDiagnostic> = map { diagnostic ->
    EditableLineDiagnostic(
        code = diagnostic.code,
        severity = diagnostic.severity.toParagraphSeverity(),
        message = diagnostic.message,
        glyphId = (diagnostic.location as? FontDiagnosticLocation.Glyph)
            ?.let { org.graphiks.kalligraphie.api.GlyphId(it.glyphId) },
    )
}

private fun FontDiagnosticSeverity.toParagraphSeverity(): EditableLineDiagnosticSeverity = when (this) {
    FontDiagnosticSeverity.INFO,
    FontDiagnosticSeverity.WARNING,
    -> EditableLineDiagnosticSeverity.WARNING

    FontDiagnosticSeverity.ERROR -> EditableLineDiagnosticSeverity.ERROR
}
