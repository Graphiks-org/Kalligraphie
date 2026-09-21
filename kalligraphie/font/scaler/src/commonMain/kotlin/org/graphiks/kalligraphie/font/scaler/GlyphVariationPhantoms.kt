@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

/**
 * Additive `gvar` deltas for a glyph's four phantom points at a variation instance.
 *
 * Phantom points follow the outline or component points in `gvar` point order: the left and right
 * side-bearing points carry horizontal deltas and the top and bottom side-bearing points carry
 * vertical deltas. The values are in design units and are consumed by the metric-variation step in
 * `PreparedTrueTypeFont.readGlyphMetrics`/`readVerticalGlyphMetrics`, not by the outline geometry.
 *
 * A composite glyph that has a component with `COMPOSITE_USE_MY_METRICS` set takes its phantom
 * positions from that component, so the metrics step MUST resolve the metrics-source glyph with
 * `GlyfReader.horizontalMetricsGlyphId(prepared, glyphId)` and read that glyph's
 * `variationPhantoms`, never the composite's own. This reader surfaces the composite's own deltas
 * unconditionally and does not redirect them; the redirect is the consumer's responsibility.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public data class GlyphVariationPhantoms(
    /** Horizontal delta of the left side-bearing phantom point. */
    public val leftX: Double,
    /** Horizontal delta of the right side-bearing phantom point. */
    public val rightX: Double,
    /** Vertical delta of the top side-bearing phantom point. */
    public val topY: Double,
    /** Vertical delta of the bottom side-bearing phantom point. */
    public val bottomY: Double,
) {
    /** Advance-width delta: the right side-bearing delta minus the left side-bearing delta. */
    public val horizontalAdvanceDelta: Double get() = rightX - leftX

    /** Advance-height delta: the top side-bearing delta minus the bottom side-bearing delta. */
    public val verticalAdvanceDelta: Double get() = topY - bottomY
}
