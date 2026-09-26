package org.graphiks.kalligraphie.unicode

/**
 * Portable extended-grapheme cluster segmentation, UAX #29.
 *
 * The segmenter walks the scalars once, keeping exactly the state the boundary rules need: the
 * previous scalar's break class, how long the run of Regional_Indicators ending at it is (GB12,
 * GB13), whether the text up to it is `Extended_Pictographic Extend*` and whether it ends in the
 * zero-width joiner that follows such a run (GB11), and whether it matches
 * `Consonant [Extend Linker]* Linker [Extend Linker]*` (GB9c).
 *
 * Every class it reads comes from the generated tables of this module, so the same boundaries are
 * produced on every platform — which is the point: the JVM's `BreakIterator` exists nowhere else,
 * and a segmentation that differed per platform would move every composed line with it.
 */
internal object UnicodeGraphemeSegmenter {
    /**
     * Returns the half-open scalar-index boundaries of [scalars]' extended grapheme clusters.
     *
     * The first boundary is always 0 and the last is always `scalars.size`, so each consecutive
     * pair delimits one non-empty cluster. An empty input yields no boundaries, and a single scalar
     * yields `[0, 1]`.
     */
    internal fun boundaries(scalars: List<Int>): IntArray {
        if (scalars.isEmpty()) return IntArray(0)
        val boundaries = ArrayList<Int>(scalars.size / 2 + 1)
        boundaries.add(0)

        var previous = UnicodeGraphemeBreak.of(scalars[0])
        val firstIsPictographic = UnicodeExtendedPictographic.isExtendedPictographic(scalars[0])
        var consonantRun = UnicodeIndicConjunctBreak.of(scalars[0]) == IndicConjunctBreak.CONSONANT
        var pictographicRun = firstIsPictographic
        var zwjAfterPictographicRun = false
        var linkerAfterConsonantRun = false
        var regionalRun = if (previous == GraphemeClusterBreak.REGIONAL_INDICATOR) 1 else 0

        for (index in 1 until scalars.size) {
            val scalar = scalars[index]
            val current = UnicodeGraphemeBreak.of(scalar)
            val currentPictographic = UnicodeExtendedPictographic.isExtendedPictographic(scalar)
            val currentConjunct = UnicodeIndicConjunctBreak.of(scalar)

            if (isBoundary(previous, current, regionalRun, zwjAfterPictographicRun, linkerAfterConsonantRun, currentPictographic, currentConjunct)) {
                boundaries.add(index)
            }

            // Advance the contexts to describe the text ending at this scalar. GB11's joiner state
            // reads the pictographic state of the text *before* this scalar, so it is set first.
            zwjAfterPictographicRun = current == GraphemeClusterBreak.ZWJ && pictographicRun
            pictographicRun = currentPictographic || (current == GraphemeClusterBreak.EXTEND && pictographicRun)
            val extendOrLinker =
                currentConjunct == IndicConjunctBreak.EXTEND || currentConjunct == IndicConjunctBreak.LINKER
            linkerAfterConsonantRun =
                (currentConjunct == IndicConjunctBreak.LINKER && consonantRun) ||
                    (extendOrLinker && linkerAfterConsonantRun)
            consonantRun = currentConjunct == IndicConjunctBreak.CONSONANT || (extendOrLinker && consonantRun)
            regionalRun = if (current == GraphemeClusterBreak.REGIONAL_INDICATOR) regionalRun + 1 else 0
            previous = current
        }

        boundaries.add(scalars.size)
        return boundaries.toIntArray()
    }

    /** Whether a cluster boundary falls between the previous and the current scalar. */
    private fun isBoundary(
        previous: GraphemeClusterBreak,
        current: GraphemeClusterBreak,
        regionalRun: Int,
        zwjAfterPictographicRun: Boolean,
        linkerAfterConsonantRun: Boolean,
        currentPictographic: Boolean,
        currentConjunct: IndicConjunctBreak,
    ): Boolean = when {
        // GB3
        previous == GraphemeClusterBreak.CR && current == GraphemeClusterBreak.LF -> false
        // GB4
        previous == GraphemeClusterBreak.CONTROL ||
            previous == GraphemeClusterBreak.CR ||
            previous == GraphemeClusterBreak.LF -> true
        // GB5
        current == GraphemeClusterBreak.CONTROL ||
            current == GraphemeClusterBreak.CR ||
            current == GraphemeClusterBreak.LF -> true
        // GB6
        previous == GraphemeClusterBreak.L &&
            (current == GraphemeClusterBreak.L ||
                current == GraphemeClusterBreak.V ||
                current == GraphemeClusterBreak.LV ||
                current == GraphemeClusterBreak.LVT) -> false
        // GB7
        (previous == GraphemeClusterBreak.LV || previous == GraphemeClusterBreak.V) &&
            (current == GraphemeClusterBreak.V || current == GraphemeClusterBreak.T) -> false
        // GB8
        (previous == GraphemeClusterBreak.LVT || previous == GraphemeClusterBreak.T) &&
            current == GraphemeClusterBreak.T -> false
        // GB9
        current == GraphemeClusterBreak.EXTEND || current == GraphemeClusterBreak.ZWJ -> false
        // GB9a
        current == GraphemeClusterBreak.SPACINGMARK -> false
        // GB9b
        previous == GraphemeClusterBreak.PREPEND -> false
        // GB9c
        linkerAfterConsonantRun && currentConjunct == IndicConjunctBreak.CONSONANT -> false
        // GB11
        zwjAfterPictographicRun && currentPictographic -> false
        // GB12, GB13: an odd run of Regional_Indicators pairs the next one with this one.
        previous == GraphemeClusterBreak.REGIONAL_INDICATOR &&
            current == GraphemeClusterBreak.REGIONAL_INDICATOR && regionalRun % 2 == 1 -> false
        // GB999
        else -> true
    }
}
