package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphColor

/**
 * Resource bounds enforced by every raster operation before allocation.
 *
 * Limits are checked against observed values; exceeding any of them returns a
 * [RasterDiagnostic.LimitExceeded] failure and no partial image. Values are
 * expressed in pixels, counters, or recursion depth and never depend on a
 * renderer.
 */
public data class RasterLimits(
    /** Maximum accepted image width in pixels. */
    public val maxWidthPx: Int,
    /** Maximum accepted image height in pixels. */
    public val maxHeightPx: Int,
    /** Maximum accepted pixel count for one image (`width × height`). */
    public val maxPixelsPerImage: Int,
    /** Maximum number of contours accepted in one geometry. */
    public val maxContours: Int,
    /** Maximum number of flattened points accepted in one geometry. */
    public val maxTotalPoints: Int,
    /** Maximum number of paint nodes visited while rasterizing one paint graph. */
    public val maxPaintNodes: Int,
    /** Maximum group nesting depth accepted in one paint graph. */
    public val maxPaintDepth: Int,
) {
    init {
        require(maxWidthPx > 0) { "maxWidthPx must be positive." }
        require(maxHeightPx > 0) { "maxHeightPx must be positive." }
        require(maxPixelsPerImage > 0) { "maxPixelsPerImage must be positive." }
        require(maxContours > 0) { "maxContours must be positive." }
        require(maxTotalPoints > 0) { "maxTotalPoints must be positive." }
        require(maxPaintNodes > 0) { "maxPaintNodes must be positive." }
        require(maxPaintDepth > 0) { "maxPaintDepth must be positive." }
    }

    /** Default bounds suitable for tests and demonstrations. */
    public companion object {
        /** Bounds applied when a request does not supply its own [RasterLimits]. */
        public val Default: RasterLimits = RasterLimits(
            maxWidthPx = 4_096,
            maxHeightPx = 4_096,
            maxPixelsPerImage = 1 shl 22,
            maxContours = 4_096,
            maxTotalPoints = 262_144,
            maxPaintNodes = 4_096,
            maxPaintDepth = 64,
        )
    }
}

/**
 * Typed reason for a refused raster operation.
 *
 * Diagnostics are immutable, carry no sensitive data, and are stable across
 * platforms so callers can compare [field] values deterministically.
 */
public sealed interface RasterDiagnostic {
    /** Name of the request field or resource bound that produced this diagnostic. */
    public val field: String

    /** A declared limit was exceeded; no partial output was produced. */
    public data class LimitExceeded(
        override val field: String,
        /** Observed value at the moment of refusal. */
        public val observed: Long,
        /** Declared limit from [RasterLimits]. */
        public val limit: Long,
    ) : RasterDiagnostic

    /** The request itself is invalid and no work was attempted. */
    public data class InvalidRequest(
        override val field: String,
        /** Stable, human-readable reason without sensitive data. */
        public val detail: String,
    ) : RasterDiagnostic
}

/**
 * Outcome of a raster operation: one immutable value or a typed refusal.
 *
 * No exception crosses the public API; a refusal always carries at least one
 * [RasterDiagnostic] and never transfers partial output.
 */
public sealed interface RasterResult<out T> {
    /** Successful rasterization producing one immutable value. */
    public data class Success<T>(
        /** The immutable rasterization output. */
        public val value: T,
    ) : RasterResult<T>

    /** Typed refusal with at least one diagnostic. */
    public data class Failure(
        /** At least one typed reason for the refusal; never empty. */
        public val diagnostics: List<RasterDiagnostic>,
    ) : RasterResult<Nothing> {
        init {
            require(diagnostics.isNotEmpty()) { "A failure must carry at least one diagnostic." }
        }
    }
}

/**
 * Inputs for [GlyphRasterizer.rasterizeOutline].
 *
 * [pixelsPerEm] is the target size in pixels for one em; the outline's own
 * `unitsPerEm` provides the design-unit scale. [originX] and [originY] translate
 * the result in whole pixels without changing orientation: source axes are kept
 * unchanged and row zero corresponds to `top`.
 */
public class OutlineRasterRequest(
    /** Target size in pixels per em. Must be finite and positive. */
    public val pixelsPerEm: Double,
    /** Whole-pixel horizontal translation applied after scaling. */
    public val originX: Int = 0,
    /** Whole-pixel vertical translation applied after scaling. */
    public val originY: Int = 0,
    /** Resource bounds enforced before allocation. */
    public val limits: RasterLimits = RasterLimits.Default,
)

/**
 * Inputs for [GlyphRasterizer.rasterizePaint].
 *
 * [unitsPerEm] is the design-unit scale of portable paint paths; solid outline
 * nodes use their own `unitsPerEm`. [originX] and [originY] translate the union
 * canvas in whole pixels.
 */
public class PaintRasterRequest(
    /** Target size in pixels per em. Must be finite and positive. */
    public val pixelsPerEm: Double,
    /** Design units in one em for portable paint paths. Must be positive. */
    public val unitsPerEm: Int,
    /** Whole-pixel horizontal translation applied to the union canvas. */
    public val originX: Int = 0,
    /** Whole-pixel vertical translation applied to the union canvas. */
    public val originY: Int = 0,
    /** Resource bounds enforced before allocation. */
    public val limits: RasterLimits = RasterLimits.Default,
)

/**
 * Inputs for [GlyphRasterizer.rasterizeBitmap].
 *
 * [ink] tints the normalized `ALPHA_8` samples; the bitmap strike is rasterized
 * one-to-one without scaling so the exact strike identity is preserved.
 */
public class BitmapRasterRequest(
    /** Foreground color applied to decoded samples. */
    public val ink: GlyphColor,
    /** Resource bounds enforced before allocation. */
    public val limits: RasterLimits = RasterLimits.Default,
)
