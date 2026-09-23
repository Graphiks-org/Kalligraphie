// CatalogAxis.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Dimension of the font-technology surface a catalog entry belongs to. */
public enum class CatalogAxis {
    /** SFNT containers and collections. */
    CONTAINER,
    /** Outline formats and encoding schemes. */
    OUTLINE,
    /** Horizontal and vertical metrics, including variations. */
    METRICS,
    /** Variable-font machinery: axes, deltas, instance resolution. */
    VARIATION,
    /** Colour glyph technologies. */
    COLOR,
    /** Embedded bitmap strikes. */
    BITMAP,
    /** Writing systems and their shaping requirements. */
    SCRIPT,
    /** Several families, styles and representations composed into one artefact. */
    COMPOSITION,
    /** Behaviour on hostile or malformed input. */
    ROBUSTNESS,
}
