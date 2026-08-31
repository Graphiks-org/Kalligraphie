package org.graphiks.kalligraphie.api

/** Classification of a legal line-break boundary in the source text. */
public enum class LineBreakKind {
    /** A layout engine may wrap at this boundary when its constraints require it. */
    ALLOWED,

    /** The source text requires line termination at this boundary. */
    MANDATORY,
}

/**
 * One legal line-break boundary tied to the opaque version of its source snapshot.
 *
 * The boundary counts Unicode scalars through [TextIndex], never source code units or a
 * platform string offset. Its containing [LineBreakAnalysis] validates range ownership,
 * ordering, and extended-grapheme-cluster safety.
 */
public data class LineBreakOpportunity(
    /** Snapshot-bound boundary immediately after the source content kept on the preceding line. */
    public val boundary: TextIndex,
    /** Whether wrapping at [boundary] is optional or required by the source. */
    public val kind: LineBreakKind,
)

/**
 * Immutable UAX #14 line-break analysis of one snapshot-bound scalar range.
 *
 * [graphemeClusters] must be the complete extended-grapheme-cluster partition used to vet the
 * result. Every opportunity is strictly ordered, lies at the end of one supplied cluster, and
 * belongs to the same [TextVersion] as [range]. The collections are defensively captured,
 * contain no borrowed native resources, and are safe to share between threads with the
 * immutable snapshot revision they describe.
 *
 * The range end remains the consumer's implicit terminal boundary. An opportunity at that end
 * is published only to preserve a source-required [LineBreakKind.MANDATORY] termination.
 */
public class LineBreakAnalysis(
    /** Complete half-open scalar range analyzed by this result. */
    public val range: TextRange,
    /** Unicode data and implementation release that produced the opportunities. */
    public val unicodeData: UnicodeDataIdentity,
    graphemeClusters: List<TextRange>,
    opportunities: List<LineBreakOpportunity>,
) {
    /** Extended grapheme clusters used to validate all published boundaries. */
    public val graphemeClusters: List<TextRange> = graphemeClusters.immutableListSnapshot()

    /** Legal line-break boundaries in strictly increasing logical order. */
    public val opportunities: List<LineBreakOpportunity> = opportunities.immutableListSnapshot()

    init {
        requireGraphemePartition(range, this.graphemeClusters)
        val graphemeEnds = this.graphemeClusters.map(TextRange::endExclusive).toSet()
        this.opportunities.forEach { opportunity ->
            require(opportunity.boundary.sharesVersionWith(range.start)) {
                "Line-break opportunities must belong to the analysis version."
            }
            require(
                opportunity.boundary.compareTo(range.start) >= 0 &&
                    opportunity.boundary.compareTo(range.endExclusive) <= 0,
            ) {
                "Line-break opportunities must stay within the analysis range."
            }
            require(opportunity.boundary in graphemeEnds) {
                "Line-break opportunities must coincide with extended grapheme cluster ends."
            }
            require(
                opportunity.boundary != range.endExclusive ||
                    opportunity.kind == LineBreakKind.MANDATORY,
            ) {
                "The implicit range-end boundary is published only for source-required termination."
            }
        }
        require(this.opportunities.zipWithNext().all { (left, right) ->
            left.boundary.compareTo(right.boundary) < 0
        }) {
            "Line-break opportunities must be strictly ordered."
        }
    }
}

private fun requireGraphemePartition(owner: TextRange, clusters: List<TextRange>) {
    if (owner.start == owner.endExclusive) {
        require(clusters.isEmpty()) { "Grapheme clusters must be empty for an empty analysis range." }
        return
    }
    require(clusters.isNotEmpty()) { "Grapheme clusters must cover the complete analysis range." }
    var expectedStart = owner.start
    clusters.forEach { cluster ->
        require(cluster.start != cluster.endExclusive) { "Grapheme clusters must not be empty." }
        require(cluster.start == expectedStart) { "Grapheme clusters must be contiguous and ordered." }
        require(cluster.endExclusive.compareTo(owner.endExclusive) <= 0) {
            "Grapheme clusters must stay within the analysis range."
        }
        expectedStart = cluster.endExclusive
    }
    require(expectedStart == owner.endExclusive) {
        "Grapheme clusters must cover the complete analysis range."
    }
}
