package org.graphiks.kalligraphie.e2e

/** Route a golden scene exercises. */
public enum class GoldenSceneFamily {
    /** One glyph rendered from an outline representation. */
    GLYPH_OUTLINE,

    /** One glyph composited from a paint graph. */
    GLYPH_PAINT,

    /** One glyph rendered from a bitmap strike. */
    GLYPH_BITMAP,

    /** A multi-glyph alphabet sheet for one script. */
    ALPHABET_SHEET,

    /** A composed text line through the paragraph facade. */
    COMPOSED_LINE,

    /** Several families, styles and representations composed into one image. */
    MOSAIC,

    /** A composed paragraph through the facade. */
    PARAGRAPH,

    /** A composed flow across one or more regions. */
    FLOW_REGION,
}
