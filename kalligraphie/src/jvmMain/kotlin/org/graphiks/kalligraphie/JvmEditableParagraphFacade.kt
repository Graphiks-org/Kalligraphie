package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineDiagnostic
import org.graphiks.kalligraphie.api.EditableLineDiagnosticSeverity
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
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
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.layout.ParagraphComposer
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmLineBreakAnalyzer
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer

/**
 * Complete input to the JVM reference editable-paragraph journey.
 *
 * The request describes one horizontal rectangular composition call. [snapshot], [sourceRange],
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
    public val constraints: HorizontalParagraphConstraints,
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
    /** Layout-only or synchronously outline-certified publication mode. */
    public val materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    /** Complete-line overflow behavior; only [OverflowPolicy.CONTINUE] is available. */
    public val overflowPolicy: OverflowPolicy = OverflowPolicy.CONTINUE,
    /** Immutable replay capability returned by a preceding partial call. */
    public val continuation: LayoutContinuation? = null,
    /** Cooperative signal checked before and during bounded composition work. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
) {
    /** Immutable deterministic OpenType feature overrides in caller order. */
    public val features: List<OpenTypeFeature> = features.toList()
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
        if (request.cancellationToken.isCancellationRequested()) {
            return ParagraphLayoutResult.Cancelled()
        }
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
        return layout(request, backend)
    }

    internal fun layout(
        request: JvmEditableParagraphFacadeRequest,
        backend: ShapingBackend,
    ): ParagraphLayoutResult {
        var result: ParagraphLayoutResult? = null
        var closeResult: FontOperationResult<Unit>? = null
        try {
            result = try {
                if (request.cancellationToken.isCancellationRequested()) {
                    ParagraphLayoutResult.Cancelled()
                } else {
                    val unicodeAnalysis = JvmUnicodeAnalyzer.create().analyze(
                        request.snapshot,
                        UnicodeAnalysisRequest(request.baseDirection, request.language),
                    )
                    val canonicalLanguage = unicodeAnalysis.scriptLanguageRuns
                        .firstOrNull()
                        ?.language
                        ?: JvmUnicodeAnalyzer.canonicalizeLanguageTag(request.language)
                    val lineBreakAnalysis = JvmLineBreakAnalyzer.create().analyze(
                        request.snapshot,
                        unicodeAnalysis,
                    )
                    val paragraphRequest = ParagraphLayoutRequest(
                        snapshot = request.snapshot,
                        sourceRange = request.sourceRange,
                        unicodeAnalysis = unicodeAnalysis,
                        lineBreakAnalysis = lineBreakAnalysis,
                        constraints = request.constraints,
                        baseDirection = request.baseDirection,
                        language = canonicalLanguage,
                        featurePolicy = backend.identity.featurePolicy,
                        features = request.features,
                        fontCatalog = request.fontCatalog,
                        resolutionPolicy = request.resolutionPolicy,
                        fontInstanceDescriptor = request.fontInstanceDescriptor,
                        shapingBackend = backend,
                        materializationIdentity = ParagraphMaterializationIdentity.from(request.materialization),
                        overflowPolicy = request.overflowPolicy,
                        continuation = request.continuation,
                        cancellationToken = request.cancellationToken,
                    )
                    ParagraphComposer.layout(paragraphRequest, request.materialization)
                }
            } catch (error: IllegalArgumentException) {
                ParagraphLayoutResult.Failure(
                    ParagraphLayoutError.InvalidInput(error.message ?: "Paragraph input is invalid."),
                )
            }
        } finally {
            closeResult = backend.close()
        }
        return includeBackendCloseResult(checkNotNull(result), checkNotNull(closeResult))
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
