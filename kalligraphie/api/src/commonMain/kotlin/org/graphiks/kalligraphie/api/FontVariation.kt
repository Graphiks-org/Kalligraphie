package org.graphiks.kalligraphie.api

/**
 * One OpenType variation axis selection expressed in design (user) coordinates.
 *
 * Design coordinates are the values declared by the font's `fvar` axis records, for example a
 * `wght` value of `700`. [tag] contains exactly four characters and [value] is finite; negative
 * zero is canonicalized to positive zero so equivalent selections compare equal.
 *
 * @property tag four-character OpenType axis tag.
 * @property value design-unit axis value.
 */
public class FontVariationCoordinate(
    public val tag: String,
    value: Float,
) {
    /** Canonical finite design value; negative zero is represented as positive zero. */
    public val value: Float = if (value == 0f) 0f else value

    init {
        require(tag.length == 4) { "Font variation axis tags must contain exactly four characters." }
        require(value.isFinite()) { "Font variation axis values must be finite." }
    }

    /** Copies this coordinate while revalidating and canonicalizing its value. */
    public fun copy(tag: String = this.tag, value: Float = this.value): FontVariationCoordinate =
        FontVariationCoordinate(tag, value)

    override fun equals(other: Any?): Boolean =
        other is FontVariationCoordinate && tag == other.tag && value == other.value

    override fun hashCode(): Int = 31 * tag.hashCode() + value.hashCode()

    override fun toString(): String = "FontVariationCoordinate(tag=$tag, value=$value)"
}

/**
 * Immutable, canonical selection of variation axes in design coordinates.
 *
 * Coordinates are copied into a tag-sorted, tag-unique snapshot. An empty selection denotes the
 * font default and never enables variation. Callers convert a selection to normalized coordinates
 * through `FontFace.normalize`, which applies the font's `fvar` and `avar` tables.
 */
public class FontVariationCoordinates(
    coordinates: List<FontVariationCoordinate> = emptyList(),
) {
    /** Canonical coordinates sorted by unique axis tag. */
    public val coordinates: List<FontVariationCoordinate> =
        coordinates.sortedBy { it.tag }.immutableListSnapshot()

    init {
        require(this.coordinates.zipWithNext().all { (left, right) -> left.tag < right.tag }) {
            "Font variation coordinates must be sorted by unique axis tag."
        }
    }

    /** Copies this selection while retaining canonical ordering. */
    public fun copy(coordinates: List<FontVariationCoordinate> = this.coordinates): FontVariationCoordinates =
        FontVariationCoordinates(coordinates)

    override fun equals(other: Any?): Boolean =
        other is FontVariationCoordinates && coordinates == other.coordinates

    override fun hashCode(): Int = coordinates.hashCode()

    override fun toString(): String = "FontVariationCoordinates(coordinates=$coordinates)"

    /** Canonical selections. */
    public companion object {
        /** Default selection with no explicit variation axes. */
        public val default: FontVariationCoordinates = FontVariationCoordinates()
    }
}
