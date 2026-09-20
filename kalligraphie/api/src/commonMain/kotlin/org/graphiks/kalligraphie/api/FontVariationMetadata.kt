package org.graphiks.kalligraphie.api

/**
 * Metadata for one `fvar` variation axis in design coordinates.
 *
 * @property tag four-character axis tag.
 * @property minValue minimum design value.
 * @property defaultValue default design value used by the font.
 * @property maxValue maximum design value.
 * @property nameId `name` table identifier for the axis name.
 * @property hidden whether the axis should be hidden from user interfaces.
 */
public data class FontVariationAxis(
    public val tag: String,
    public val minValue: Float,
    public val defaultValue: Float,
    public val maxValue: Float,
    public val nameId: Int,
    public val hidden: Boolean,
) {
    init {
        require(tag.length == 4) { "Font variation axis tags must contain exactly four characters." }
        require(minValue <= defaultValue && defaultValue <= maxValue) {
            "Font variation axis bounds must satisfy min <= default <= max."
        }
    }
}

/**
 * One `fvar` named instance expressed as a design-coordinate selection.
 *
 * @property index zero-based instance index in `fvar` order.
 * @property nameId `name` table identifier for the instance name.
 * @property postScriptNameId optional `name` table identifier for the PostScript name.
 * @property coordinates design coordinates selected by this instance.
 */
public data class FontNamedInstance(
    public val index: Int,
    public val nameId: Int,
    public val postScriptNameId: Int?,
    public val coordinates: FontVariationCoordinates,
) {
    init {
        require(index >= 0) { "Named instance index must be non-negative." }
    }
}

/**
 * Font-wide metrics in design units at a concrete instance.
 *
 * Values are not scaled to layout units; callers apply the instance's layout size.
 */
public data class FontMetrics(
    public val ascender: Float,
    public val descender: Float,
    public val lineGap: Float,
    public val underlinePosition: Float = 0f,
    public val underlineThickness: Float = 0f,
    public val xHeight: Float = 0f,
    public val capHeight: Float = 0f,
)

/** One `STAT` axis-value table in read-only form. */
public data class StatAxisValue(
    public val axisTag: String,
    public val format: Int,
    public val values: List<Float>,
    public val flags: Int,
    public val nameId: Int,
) {
    init {
        require(axisTag.length == 4) { "STAT axis tags must contain exactly four characters." }
    }
}

/** Read-only `STAT` table surface used to map instances to style attributes. */
public data class StatTable(
    public val axisValueTables: List<StatAxisValue>,
)
