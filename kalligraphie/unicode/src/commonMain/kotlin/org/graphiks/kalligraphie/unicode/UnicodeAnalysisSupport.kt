package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile

/**
 * The half of one Unicode analysis that does not depend on where the Unicode data comes from.
 *
 * Turning a resolved level per scalar into the contract's runs, reordering them into visual order,
 * observing cancellation and limits, and mapping scalar boundaries back to text indices are the
 * same work whatever produced the levels. They live here so a platform can supply the data — the
 * JVM from ICU, the portable analyzer from this module's generated tables — without restating them.
 *
 * What is deliberately *not* here: the levels themselves and the property lookups. Those are what
 * the platforms genuinely differ in.
 */

/** Thrown when an analysis observes cancellation; the analyzer turns it into a `Cancelled` outcome. */
internal data object UnicodeAnalysisCancelled : RuntimeException()

/** Throws [UnicodeAnalysisCancelled] when the token has been cancelled. */
internal fun observeCancellation(cancellationToken: CancellationToken) {
    if (cancellationToken.isCancellationRequested()) throw UnicodeAnalysisCancelled
}

/** Observes cancellation once every [UnicodeAnalysisProfile.cancellationCheckInterval] scalars. */
internal fun observeCancellation(
    scalarIndex: Int,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
) {
    if (scalarIndex % profile.cancellationCheckInterval == 0) observeCancellation(cancellationToken)
}

/** The contract range covering `[start, endExclusive)` of the snapshot's scalars. */
internal fun scalarRange(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
    TextRange(
        snapshot.textIndexAtScalarBoundary(start),
        snapshot.textIndexAtScalarBoundary(endExclusive),
    )

/** Splits the per-scalar levels into the contract's logical runs, one per level change. */
internal fun bidiRuns(
    snapshot: TextSnapshot,
    levels: IntArray,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): List<BidiRun> {
    if (levels.isEmpty()) return emptyList()
    val runs = mutableListOf<BidiRun>()
    var start = 0
    var level = levels.first()
    for (scalarIndex in 1 until levels.size) {
        observeCancellation(scalarIndex, profile, cancellationToken)
        if (levels[scalarIndex] != level) {
            runs += BidiRun(scalarRange(snapshot, start, scalarIndex), level)
            start = scalarIndex
            level = levels[scalarIndex]
        }
    }
    runs += BidiRun(scalarRange(snapshot, start, levels.size), level)
    return runs
}

/**
 * UAX #9 L2: reverses, from the highest level down to the lowest odd level, every run of
 * consecutive runs at or above that level.
 */
internal fun reorderBidiRuns(
    logicalRuns: List<BidiRun>,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): List<BidiRun> {
    var minimumOddLevel: Int? = null
    var maximumLevel = 0
    val visualRuns = ArrayList<BidiRun>(logicalRuns.size)
    logicalRuns.forEachIndexed { runIndex, run ->
        observeCancellation(runIndex, profile, cancellationToken)
        if (run.level.rem(2) == 1) {
            minimumOddLevel = minOf(minimumOddLevel ?: run.level, run.level)
        }
        maximumLevel = maxOf(maximumLevel, run.level)
        visualRuns += run
    }
    val firstReorderingLevel = minimumOddLevel ?: return logicalRuns
    for (level in maximumLevel downTo firstReorderingLevel) {
        var sequenceStart: Int? = null
        for (runIndex in 0..visualRuns.size) {
            observeCancellation(runIndex, profile, cancellationToken)
            if (runIndex < visualRuns.size && visualRuns[runIndex].level >= level) {
                if (sequenceStart == null) sequenceStart = runIndex
            } else if (sequenceStart != null) {
                reverseBidiRunSequence(visualRuns, sequenceStart, runIndex, profile, cancellationToken)
                sequenceStart = null
            }
        }
    }
    return visualRuns
}

private fun reverseBidiRunSequence(
    runs: MutableList<BidiRun>,
    start: Int,
    endExclusive: Int,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
) {
    var left = start
    var right = endExclusive - 1
    var swapIndex = 0
    while (left < right) {
        observeCancellation(swapIndex, profile, cancellationToken)
        val run = runs[left]
        runs[left] = runs[right]
        runs[right] = run
        left += 1
        right -= 1
        swapIndex += 1
    }
}

/** UAX #9 X2–X5b: the least embedding level of the requested direction above [current]. */
internal fun nextEmbeddingLevel(current: Int, rtl: Boolean): Int = if (rtl) {
    if (current % 2 == 0) current + 1 else current + 2
} else {
    if (current % 2 == 0) current + 2 else current + 1
}

/** The paragraph embedding level UAX #9 P2 and P3 assign to the requested base direction. */
internal val BaseDirection.paragraphLevel: Int
    get() = if (this == BaseDirection.LEFT_TO_RIGHT) 0 else 1
