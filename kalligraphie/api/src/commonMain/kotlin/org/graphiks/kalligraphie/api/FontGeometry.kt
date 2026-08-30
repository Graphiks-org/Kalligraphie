package org.graphiks.kalligraphie.api

/** A finite layout-space coordinate or distance. */
public class LayoutUnit private constructor(
    /** The value expressed in layout units. */
    public val value: Float,
) : Comparable<LayoutUnit> {
    /** Factory for finite layout units. */
    public companion object {
        /** Creates a layout unit and rejects non-finite values. */
        public operator fun invoke(value: Float): LayoutUnit {
            require(value.isFinite()) { "LayoutUnit value must be finite." }
            return LayoutUnit(if (value == 0f) 0f else value)
        }
    }

    /** Compares layout-unit values numerically. */
    override fun compareTo(other: LayoutUnit): Int = value.compareTo(other.value)

    /** Compares the finite numeric value. */
    override fun equals(other: Any?): Boolean = other is LayoutUnit && value == other.value

    /** Returns a hash derived from the numeric value. */
    override fun hashCode(): Int = value.hashCode()

    /** Returns a diagnostic representation of this layout unit. */
    override fun toString(): String = "LayoutUnit($value)"
}

/** Axis-aligned bounds expressed in font design units. */
public data class DesignBounds(
    /** Minimum horizontal coordinate. */
    public val minX: Int,
    /** Minimum vertical coordinate. */
    public val minY: Int,
    /** Maximum horizontal coordinate. */
    public val maxX: Int,
    /** Maximum vertical coordinate. */
    public val maxY: Int,
) {
    /** Factory value for empty design bounds. */
    public companion object {
        /** Empty bounds for glyphs without ink. */
        public val empty: DesignBounds = DesignBounds(0, 0, 0, 0)
    }
}

/** Axis-aligned bounds expressed in layout units. */
public data class LayoutBounds(
    /** Minimum horizontal coordinate. */
    public val minX: LayoutUnit,
    /** Minimum vertical coordinate. */
    public val minY: LayoutUnit,
    /** Maximum horizontal coordinate. */
    public val maxX: LayoutUnit,
    /** Maximum vertical coordinate. */
    public val maxY: LayoutUnit,
) {
    /** Factory value for empty layout bounds. */
    public companion object {
        /** Empty bounds for glyphs without ink. */
        public val empty: LayoutBounds = LayoutBounds(
            minX = LayoutUnit(0f),
            minY = LayoutUnit(0f),
            maxX = LayoutUnit(0f),
            maxY = LayoutUnit(0f),
        )
    }
}
