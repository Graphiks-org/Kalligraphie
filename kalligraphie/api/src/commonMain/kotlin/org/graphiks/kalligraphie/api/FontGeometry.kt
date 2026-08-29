package org.graphiks.kalligraphie.api

public class LayoutUnit private constructor(public val value: Float) : Comparable<LayoutUnit> {
    public companion object {
        public operator fun invoke(value: Float): LayoutUnit {
            require(value.isFinite()) { "LayoutUnit value must be finite." }
            return LayoutUnit(if (value == 0f) 0f else value)
        }
    }

    override fun compareTo(other: LayoutUnit): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is LayoutUnit && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "LayoutUnit($value)"
}

public data class DesignBounds(
    public val minX: Int,
    public val minY: Int,
    public val maxX: Int,
    public val maxY: Int,
)

public data class LayoutBounds(
    public val minX: LayoutUnit,
    public val minY: LayoutUnit,
    public val maxX: LayoutUnit,
    public val maxY: LayoutUnit,
)
