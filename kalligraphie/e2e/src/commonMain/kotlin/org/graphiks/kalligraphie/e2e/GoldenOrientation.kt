package org.graphiks.kalligraphie.e2e

/**
 * Row order of one [GoldenImage]'s canonical bytes.
 *
 * Two producers feed the canonical model and they do not agree on where row zero sits. The CPU
 * rasterizer writes in design orientation — row zero is the smallest design y, which is the visual
 * *bottom* of a y-up outline — while the composition canvases draw every glyph with an explicit
 * flip and hand back image orientation. Naming both keeps the difference visible instead of letting
 * each consumer guess; the dumps are where a guess used to be visible, and five of them opened
 * mirrored.
 */
public enum class GoldenOrientation {
    /**
     * Row zero is the visual top. Produced by the composition canvases, which draw each glyph
     * flipped, and by the normalized bitmap strikes, whose decoded rows already run top to bottom.
     * Every reader shows such bytes as they are.
     */
    IMAGE,

    /**
     * Row zero is the visual bottom: the row order the CPU rasterizer returns, matching the font's
     * y-up design space. Produced by the raw outline and paint routes, which hand the rasterizer's
     * own bytes to the model without drawing them on a canvas. A reader has to reverse the rows.
     */
    DESIGN,
}
