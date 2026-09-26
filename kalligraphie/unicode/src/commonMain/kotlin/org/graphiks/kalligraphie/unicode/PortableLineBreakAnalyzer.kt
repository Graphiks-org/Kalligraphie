package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditorOperationContext
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import org.graphiks.kalligraphie.api.LineBreakAnalysis
import org.graphiks.kalligraphie.api.LineBreakAnalysisOutcome
import org.graphiks.kalligraphie.api.LineBreakKind
import org.graphiks.kalligraphie.api.LineBreakOpportunity
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis

/** Factory for the portable UAX #14 line-break analyzer backed by the generated UCD tables. */
public object PortableLineBreakAnalyzer {
    /** Creates an analyzer backed internally by the generated Unicode 16.0 tables. */
    public fun create(): LineBreakAnalyzer = UcdLineBreakAnalyzer()

    /** Creates the additive bounded analyzer backed by the same generated tables. */
    public fun createBounded(): BoundedLineBreakAnalyzer = UcdLineBreakAnalyzer()
}

/**
 * The portable line-break analysis: the UAX #14 opportunities of one snapshot that already carries
 * a complete portable Unicode analysis.
 *
 * The engine is [UnicodeLineBreakEngine]; what remains here is the contract: the analysis must
 * cover the whole snapshot and carry the portable data identity (the line-break rules read the
 * same tables the analysis was built from), the operation's source and scalar limits and
 * line-break budget apply, cancellation is observed around the work, and the engine's mandatory
 * breaks and ordinary breaks are published only where a complete extended grapheme cluster ends.
 * The end of the range stays the consumer's implicit terminal boundary: an opportunity there is
 * published only when the source text mandates a termination.
 */
@OptIn(KalligraphieInternalApi::class)
internal class UcdLineBreakAnalyzer : BoundedLineBreakAnalyzer {

    override fun analyze(snapshot: TextSnapshot, unicodeAnalysis: UnicodeAnalysis): LineBreakAnalysis {
        val outcome = analyze(
            snapshot,
            unicodeAnalysis,
            EditorOperationProfile.unbounded,
            CancellationToken.none,
        )
        return when (outcome) {
            is LineBreakAnalysisOutcome.Success -> outcome.value
            is LineBreakAnalysisOutcome.LimitExceeded ->
                error("The unbounded line-break analyzer exceeded ${outcome.limit.kind}.")
            LineBreakAnalysisOutcome.Cancelled ->
                error("The non-cancellable line-break analyzer was cancelled.")
        }
    }

    override fun analyze(
        snapshot: TextSnapshot,
        unicodeAnalysis: UnicodeAnalysis,
        profile: EditorOperationProfile,
        cancellationToken: CancellationToken,
    ): LineBreakAnalysisOutcome = analyze(
        snapshot,
        unicodeAnalysis,
        EditorOperationContext.create(profile, cancellationToken),
    )

    override fun analyze(
        snapshot: TextSnapshot,
        unicodeAnalysis: UnicodeAnalysis,
        context: EditorOperationContext,
    ): LineBreakAnalysisOutcome {
        require(unicodeAnalysis.range == snapshot.range) {
            "Unicode analysis must cover the complete supplied snapshot."
        }
        require(unicodeAnalysis.unicodeData == PORTABLE_UNICODE_DATA) {
            "Unicode analysis must use the portable Unicode 16.0 data this analyzer resolves from."
        }
        context.sourceLimit(snapshot)?.let { return LineBreakAnalysisOutcome.LimitExceeded(it) }
        context.scalarLimit(snapshot)?.let { return LineBreakAnalysisOutcome.LimitExceeded(it) }
        if (context.isCancellationRequested()) return LineBreakAnalysisOutcome.Cancelled
        context.chargeLineBreakWork(snapshot.scalars.size.toLong())?.let {
            return LineBreakAnalysisOutcome.LimitExceeded(it)
        }

        val graphemeEnds = unicodeAnalysis.graphemeClusters
            .map { cluster -> cluster.endExclusive }
            .toSet()
        val decisions = UnicodeLineBreakEngine.decisions(
            snapshot.scalars,
            context.profile.unicodeAnalysisProfile,
            context.cancellationToken,
        )
        if (context.isCancellationRequested()) return LineBreakAnalysisOutcome.Cancelled
        val opportunities = mutableListOf<LineBreakOpportunity>()
        for (boundaryIndex in 1..snapshot.scalars.size) {
            val decision = decisions[boundaryIndex]
            if (decision == UnicodeLineBreakEngine.Decision.NO_BREAK) continue
            val boundary = snapshot.textIndexAtScalarBoundary(boundaryIndex)
            if (boundary !in graphemeEnds) continue
            val kind = when (decision) {
                UnicodeLineBreakEngine.Decision.MANDATORY -> LineBreakKind.MANDATORY
                else -> LineBreakKind.ALLOWED
            }
            if (boundary != snapshot.range.endExclusive || kind == LineBreakKind.MANDATORY) {
                opportunities += LineBreakOpportunity(boundary, kind)
            }
            if (context.isCancellationRequested()) return LineBreakAnalysisOutcome.Cancelled
        }
        return LineBreakAnalysisOutcome.Success(
            LineBreakAnalysis(
                range = unicodeAnalysis.range,
                unicodeData = unicodeAnalysis.unicodeData,
                graphemeClusters = unicodeAnalysis.graphemeClusters,
                opportunities = opportunities,
            ),
        )
    }
}
