package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.raster.A8Image

/**
 * One rasterized glyph kept at the pen position and baseline its own layout gave it.
 *
 * The composed-line scenes produce these through the paragraph facade, which is why they live where
 * they do, but the holder itself is portable: a rasterized coverage image and two integers. Keeping
 * it shared is what lets the geometry helpers — the ink box of one glyph, the ink box of a row —
 * work for a scene that does not compose text at all.
 */
internal class PlacedGlyph(
    val image: A8Image,
    val penX: Int,
    val baselineY: Int,
)
