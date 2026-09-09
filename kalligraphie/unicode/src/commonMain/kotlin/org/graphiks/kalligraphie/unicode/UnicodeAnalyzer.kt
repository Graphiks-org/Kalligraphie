package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditorOperationContext
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import org.graphiks.kalligraphie.api.LineBreakAnalysis
import org.graphiks.kalligraphie.api.LineBreakAnalysisOutcome
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisOutcome
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

/** Portable contract for complete, snapshot-bound Unicode line analysis. */
public fun interface UnicodeAnalyzer {
    /**
     * Analyzes [snapshot] using the explicit direction and language in [request].
     *
     * Implementations return immutable ranges over the complete snapshot and
     * reject unsupported or malformed explicit inputs deterministically.
     */
    public fun analyze(snapshot: TextSnapshot, request: UnicodeAnalysisRequest): UnicodeAnalysis
}

/** Unicode analyzer that atomically observes explicit resource limits and cancellation. */
public interface BoundedUnicodeAnalyzer : UnicodeAnalyzer {
    /**
     * Analyzes [snapshot] under [profile] while observing [cancellationToken] cooperatively.
     *
     * A limit failure or cancellation publishes no partial grapheme, script, or BiDi result.
     * Successful analysis has exactly the same Unicode semantics as [UnicodeAnalyzer.analyze].
     */
    public fun analyze(
        snapshot: TextSnapshot,
        request: UnicodeAnalysisRequest,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): UnicodeAnalysisOutcome

    /** @suppress Reuses the profile and token of one enclosing high-level operation. */
    @KalligraphieInternalApi
    public fun analyze(
        snapshot: TextSnapshot,
        request: UnicodeAnalysisRequest,
        context: EditorOperationContext,
    ): UnicodeAnalysisOutcome = analyze(
        snapshot,
        request,
        context.profile.unicodeAnalysisProfile,
        context.cancellationToken,
    )
}

/** Portable contract for UAX #14 line-break opportunities over a complete Unicode analysis. */
public fun interface LineBreakAnalyzer {
    /**
     * Analyzes [snapshot] using the exact range, Unicode identity, and extended grapheme
     * clusters in [unicodeAnalysis].
     *
     * Both inputs must describe the same complete immutable snapshot revision. Implementations
     * return only [org.graphiks.kalligraphie.api.TextIndex] boundaries and expose no platform
     * string offsets or borrowed native resources.
     */
    public fun analyze(snapshot: TextSnapshot, unicodeAnalysis: UnicodeAnalysis): LineBreakAnalysis
}

/** Additive bounded UAX #14 analyzer that preserves the historical [LineBreakAnalyzer] SAM. */
public interface BoundedLineBreakAnalyzer : LineBreakAnalyzer {
    /**
     * Analyzes one complete snapshot under [profile] and [cancellationToken].
     *
     * Iterative conversion and boundary work consumes the line-break budget. Cancellation is
     * checked immediately around ICU calls, which remain non-preemptible while in flight. A
     * failure or cancellation publishes no partial [LineBreakAnalysis].
     */
    public fun analyze(
        snapshot: TextSnapshot,
        unicodeAnalysis: UnicodeAnalysis,
        profile: EditorOperationProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): LineBreakAnalysisOutcome

    /** @suppress Reuses the budget of an enclosing high-level editor operation. */
    @KalligraphieInternalApi
    public fun analyze(
        snapshot: TextSnapshot,
        unicodeAnalysis: UnicodeAnalysis,
        context: EditorOperationContext,
    ): LineBreakAnalysisOutcome
}
