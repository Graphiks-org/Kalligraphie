package org.graphiks.kalligraphie.api

/**
 * One styled sub-range of a paragraph.
 *
 * A span never changes the paragraph's layout size, geometry, shaping features, or materialization:
 * it may only name a preferred [face] and/or a [variation] applied to the face finally selected.
 * [range] must belong to the request snapshot and must not be empty. At least one of [face] and
 * [variation] must be present.
 */
public class ParagraphStyleSpan(
    /** Non-empty, snapshot-bound source range covered by this span. */
    public val range: TextRange,
    /** Preferred face, a member of the request's resolution policy, or `null`. */
    public val face: FontFaceId? = null,
    /**
     * Design-axis variation applied to the selected face, or `null` to inherit the paragraph
     * descriptor's variation. [FontVariationCoordinates.default] resets to the face default.
     */
    public val variation: FontVariationCoordinates? = null,
) {
    init {
        require(range.start < range.endExclusive) { "A paragraph style span must not be empty." }
        require(face != null || variation != null) {
            "A paragraph style span must name a face, a variation, or both."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ParagraphStyleSpan && range == other.range && face == other.face && variation == other.variation

    override fun hashCode(): Int {
        var result = range.hashCode()
        result = 31 * result + (face?.hashCode() ?: 0)
        result = 31 * result + (variation?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "ParagraphStyleSpan(range=$range, face=$face, variation=$variation)"
}

/**
 * Immutable ordered set of non-overlapping paragraph style spans.
 *
 * All spans must share one text version; positions outside every span use the paragraph default.
 * The value compares by content and is safe to retain concurrently.
 */
public class ParagraphStyleSnapshot(
    spans: List<ParagraphStyleSpan>,
) {
    /** Immutable spans in strictly increasing start order. */
    public val spans: List<ParagraphStyleSpan> = spans.immutableListSnapshot()

    init {
        require(this.spans.isNotEmpty()) { "A paragraph style snapshot must hold at least one span." }
        require(this.spans.all { it.range.start.sharesVersionWith(this.spans.first().range.start) }) {
            "Paragraph style spans must all belong to one text version."
        }
        require(this.spans.zipWithNext().all { (left, right) -> left.range.endExclusive <= right.range.start }) {
            "Paragraph style spans must be ordered and must not overlap."
        }
    }

    /**
     * Returns the span covering [index], or `null` when [index] lies in a gap.
     *
     * The span whose `range.start <= index < range.endExclusive` wins. [index] must belong to the
     * snapshot version.
     */
    public fun styleAt(index: TextIndex): ParagraphStyleSpan? {
        require(index.sharesVersionWith(this.spans.first().range.start)) {
            "A style lookup index must belong to the snapshot text version."
        }
        val atOrBefore = lastStartAtOrBefore(index)
        return atOrBefore?.takeIf { span -> index < span.range.endExclusive }
    }

    // Binary search for the last span whose range.start <= index.
    private fun lastStartAtOrBefore(index: TextIndex): ParagraphStyleSpan? {
        var low = 0
        var high = this.spans.lastIndex
        var result: ParagraphStyleSpan? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val span = this.spans[mid]
            if (span.range.start <= index) {
                result = span
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    override fun equals(other: Any?): Boolean = other is ParagraphStyleSnapshot && spans == other.spans

    override fun hashCode(): Int = spans.hashCode()

    override fun toString(): String = "ParagraphStyleSnapshot(spans=$spans)"
}
