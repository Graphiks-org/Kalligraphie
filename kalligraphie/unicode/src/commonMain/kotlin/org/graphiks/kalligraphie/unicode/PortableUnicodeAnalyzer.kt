package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisLimit
import org.graphiks.kalligraphie.api.UnicodeAnalysisOutcome
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.UnicodeDataIdentity

/** Factory for the portable Unicode analyzer backed by this module's generated UCD tables. */
public object PortableUnicodeAnalyzer {
    /** Creates an analyzer backed internally by the generated Unicode 16.0 tables. */
    public fun create(): BoundedUnicodeAnalyzer = UcdUnicodeAnalyzer()
}

/**
 * The complete portable Unicode analysis: extended grapheme clusters, script runs, and BiDi runs
 * over one snapshot, all of it resolved from this module's own generated tables.
 *
 * The three resolutions are the ones the previous PRs built and the conformance corpora pinned:
 * the BiDi levels come from [UnicodeBidiEngine], the grapheme boundaries from
 * [UnicodeGraphemeSegmenter], and the script runs from [PortableScriptResolver], which also reads
 * the BD16 bracket pairs the BiDi resolution already computed. The language grammar lives in
 * [parseLanguageTag]. What remains here is only the orchestration: the scalar budget, the atomic
 * cancellation, and the order that assembles one immutable [UnicodeAnalysis].
 *
 * A limit failure or cancellation publishes nothing; a successful analysis has exactly the same
 * Unicode semantics as the JVM reference analyzer, which the JVM cross-check test pins case by
 * case.
 */
internal class UcdUnicodeAnalyzer : BoundedUnicodeAnalyzer {

    override fun analyze(snapshot: TextSnapshot, request: UnicodeAnalysisRequest): UnicodeAnalysis =
        requireComplete(analyze(snapshot, request, UnicodeAnalysisProfile.unbounded, CancellationToken.none))

    override fun analyze(
        snapshot: TextSnapshot,
        request: UnicodeAnalysisRequest,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): UnicodeAnalysisOutcome {
        if (snapshot.scalars.size > profile.maxScalars) {
            return UnicodeAnalysisOutcome.LimitExceeded(UnicodeAnalysisLimit.SCALARS, snapshot.scalars.size)
        }
        return try {
            observeCancellation(cancellationToken)
            val language = parseLanguageTag(request.language)
            val bidi = UnicodeBidiEngine.resolve(
                snapshot.scalars,
                request.baseDirection.paragraphLevel,
                profile,
                cancellationToken,
            )
            val logicalBidiRuns = bidiRuns(snapshot, bidi.levels, profile, cancellationToken)
            val graphemes = graphemeClusters(snapshot, profile, cancellationToken)
            val scripts = PortableScriptResolver.scriptLanguageRuns(
                snapshot,
                language,
                bidi.bracketPairs.pairs,
                profile,
                cancellationToken,
            )
            val visualBidiRuns = reorderBidiRuns(logicalBidiRuns, profile, cancellationToken)
            observeCancellation(cancellationToken)
            UnicodeAnalysisOutcome.Success(
                UnicodeAnalysis(
                    range = snapshot.range,
                    unicodeData = UNICODE_DATA,
                    graphemeClusters = graphemes,
                    scriptLanguageRuns = scripts,
                    logicalBidiRuns = logicalBidiRuns,
                    visualBidiRuns = visualBidiRuns,
                ),
            )
        } catch (_: UnicodeAnalysisCancelled) {
            UnicodeAnalysisOutcome.Cancelled
        }
    }

    private fun graphemeClusters(
        snapshot: TextSnapshot,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): List<TextRange> {
        if (snapshot.scalars.isEmpty()) return emptyList()
        val boundaries = UnicodeGraphemeSegmenter.boundaries(snapshot.scalars)
        val ranges = mutableListOf<TextRange>()
        for (boundaryIndex in 0 until boundaries.lastIndex) {
            observeCancellation(boundaryIndex, profile, cancellationToken)
            ranges += scalarRange(snapshot, boundaries[boundaryIndex], boundaries[boundaryIndex + 1])
        }
        return ranges
    }

    private fun requireComplete(outcome: UnicodeAnalysisOutcome): UnicodeAnalysis = when (outcome) {
        is UnicodeAnalysisOutcome.Success -> outcome.value
        is UnicodeAnalysisOutcome.LimitExceeded -> error("The unbounded Unicode analyzer exceeded ${outcome.limit}.")
        UnicodeAnalysisOutcome.Cancelled -> error("The non-cancellable Unicode analyzer was cancelled.")
    }
}

/**
 * The data identity of the portable analysis: the generated tables *are* the implementation, and
 * their release is the Unicode version they were generated from, so both names carry that version
 * the way the JVM reference carries ICU's.
 */
private val UNICODE_DATA: UnicodeDataIdentity = UnicodeDataIdentity(
    unicodeVersion = UnicodeScript.unicodeVersion,
    implementation = "Kalligraphie",
    implementationVersion = UnicodeScript.unicodeVersion,
)
