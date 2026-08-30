package org.graphiks.kalligraphie.api

/** Explicit paragraph base direction used by Unicode Bidirectional Algorithm analysis. */
public enum class BaseDirection {
    /** Resolve the paragraph with a left-to-right base embedding level. */
    LEFT_TO_RIGHT,

    /** Resolve the paragraph with a right-to-left base embedding level. */
    RIGHT_TO_LEFT,
}

/** Immutable inputs required to analyze one complete text snapshot. */
public data class UnicodeAnalysisRequest(
    /** Explicit paragraph base direction; it is never inferred from the text. */
    public val baseDirection: BaseDirection,
    /** Explicit BCP 47 language tag used for script itemization and run metadata. */
    public val language: String,
) {
    init {
        require(language.isNotBlank()) { "Language must not be blank." }
    }
}

/** Versioned identity of the Unicode data and implementation used for analysis. */
public data class UnicodeDataIdentity(
    /** Unicode Standard data version used for segmentation, scripts, and BiDi. */
    public val unicodeVersion: String,
    /** Portable implementation name that supplied the Unicode data. */
    public val implementation: String,
    /** Exact implementation release whose data produced the analysis. */
    public val implementationVersion: String,
) {
    init {
        require(unicodeVersion.isNotBlank()) { "Unicode version must not be blank." }
        require(implementation.isNotBlank()) { "Unicode implementation must not be blank." }
        require(implementationVersion.isNotBlank()) { "Unicode implementation version must not be blank." }
    }
}

/** Half-open text run sharing one ISO 15924 script and explicit language. */
public data class ScriptLanguageRun(
    /** Snapshot-bound half-open scalar range covered by this run. */
    public val range: TextRange,
    /** ISO 15924 four-letter script code. */
    public val script: String,
    /** Canonical BCP 47 language tag explicitly supplied for analysis. */
    public val language: String,
) {
    init {
        require(script.isNotBlank()) { "Script must not be blank." }
        require(language.isNotBlank()) { "Language must not be blank." }
    }
}

/** Half-open logical text run sharing one UAX #9 embedding level. */
public data class BidiRun(
    /** Snapshot-bound half-open scalar range covered by this run. */
    public val range: TextRange,
    /** UAX #9 embedding level, where even levels are LTR and odd levels are RTL. */
    public val level: Int,
) {
    init {
        require(level in 0..MAX_BIDI_LEVEL) { "BiDi level must be between 0 and 125." }
    }
}

/**
 * Immutable Unicode analysis of one complete snapshot revision.
 *
 * @param graphemeClusters Extended grapheme cluster ranges in logical text order.
 * @param scriptLanguageRuns Script and language runs in logical text order.
 * @param logicalBidiRuns UAX #9 runs and embedding levels in logical text order.
 * @param visualBidiRuns The same UAX #9 runs reordered into visual display order.
 */
public class UnicodeAnalysis(
    /** Complete snapshot-bound half-open range analyzed by this result. */
    public val range: TextRange,
    /** Versioned Unicode data identity that produced this result. */
    public val unicodeData: UnicodeDataIdentity,
    graphemeClusters: List<TextRange>,
    scriptLanguageRuns: List<ScriptLanguageRun>,
    logicalBidiRuns: List<BidiRun>,
    visualBidiRuns: List<BidiRun>,
) {
    /** Extended grapheme cluster ranges in logical text order. */
    public val graphemeClusters: List<TextRange> = graphemeClusters.immutableListSnapshot()

    /** Script and language runs in logical text order. */
    public val scriptLanguageRuns: List<ScriptLanguageRun> = scriptLanguageRuns.immutableListSnapshot()

    /** UAX #9 runs and their embedding levels in logical text order. */
    public val logicalBidiRuns: List<BidiRun> = logicalBidiRuns.immutableListSnapshot()

    /** The same UAX #9 runs reordered into visual display order. */
    public val visualBidiRuns: List<BidiRun> = visualBidiRuns.immutableListSnapshot()

    init {
        requirePartition(range, this.graphemeClusters, "Grapheme clusters")
        requirePartition(range, this.scriptLanguageRuns.map(ScriptLanguageRun::range), "Script-language runs")
        requirePartition(range, this.logicalBidiRuns.map(BidiRun::range), "Logical BiDi runs")
        val remainingLogicalRuns = this.logicalBidiRuns.toMutableList()
        this.visualBidiRuns.forEach { visualRun ->
            require(remainingLogicalRuns.remove(visualRun)) {
                "Visual BiDi runs must be a reordering of logical BiDi runs."
            }
        }
        require(remainingLogicalRuns.isEmpty()) {
            "Visual BiDi runs must be a reordering of logical BiDi runs."
        }
    }
}

private fun requirePartition(owner: TextRange, ranges: List<TextRange>, label: String) {
    if (owner.start == owner.endExclusive) {
        require(ranges.isEmpty()) { "$label must be empty for an empty analysis range." }
        return
    }
    require(ranges.isNotEmpty()) { "$label must cover the complete analysis range." }
    var expectedStart = owner.start
    ranges.forEach { item ->
        require(item.start == expectedStart) { "$label must be contiguous and ordered." }
        require(item.endExclusive.compareTo(owner.endExclusive) <= 0) { "$label must stay within the analysis range." }
        expectedStart = item.endExclusive
    }
    require(expectedStart == owner.endExclusive) { "$label must cover the complete analysis range." }
}

private const val MAX_BIDI_LEVEL: Int = 125
