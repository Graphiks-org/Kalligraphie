// CatalogStage.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Pipeline stage an entry's probe observes. */
public enum class CatalogStage {
    /** SFNT container decoding. */
    DECODE,
    /** Face resolution against access requirements. */
    FACE_RESOLUTION,
    /** Instance creation and variation application. */
    INSTANTIATION,
    /** Horizontal or vertical metric resolution. */
    METRICS,
    /** Shaping through the platform shaper. */
    SHAPING,
    /** Glyph representation materialization (outline, paint, bitmap). */
    GLYPH_REPRESENTATION,
    /** Rasterization into a canonical image. */
    RASTERIZATION,
}
