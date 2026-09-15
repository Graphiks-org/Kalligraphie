package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintIR

/**
 * Deterministic CPU rasterizer for tests and demonstrations.
 *
 * Each route accepts one portable representation and returns an immutable image
 * or a typed refusal. No exception crosses this API: invalid requests return
 * [RasterDiagnostic.InvalidRequest] and exceeded bounds return
 * [RasterDiagnostic.LimitExceeded] without partial output. The rasterizer is
 * stateless and safe to call concurrently.
 */
public object GlyphRasterizer {
    /**
     * Rasterizes [outline] into an eight-bit coverage image.
     *
     * The scale is `pixelsPerEm / outline.unitsPerEm`; [OutlineRasterRequest.originX]
     * and [OutlineRasterRequest.originY] translate the result in whole pixels
     * without changing orientation. An outline without ink yields a zero-sized
     * image.
     */
    public fun rasterizeOutline(
        outline: GlyphOutlineIR,
        request: OutlineRasterRequest,
    ): RasterResult<A8Image> {
        invalidPixelsPerEm(request.pixelsPerEm)?.let { return it }
        return runRaster {
            val contours = ContourFlattener.flattenOutline(
                contours = outline.contours,
                scale = request.pixelsPerEm / outline.unitsPerEm,
                originX = request.originX.toDouble(),
                originY = request.originY.toDouble(),
                limits = request.limits,
            )
            val bounds = boundsOf(contours, request.limits) ?: return@runRaster A8Image(0, 0, 0, 0, ByteArray(0))
            checkImageBounds(bounds.width, bounds.height, request.limits)
            CoverageRaster.rasterize(contours, bounds.left, bounds.top, bounds.width, bounds.height)
        }
    }

    /**
     * Composites [paint] into one non-premultiplied RGBA image.
     *
     * Solid outline nodes use their own `unitsPerEm`; portable paths use
     * [PaintRasterRequest.unitsPerEm]. Group children are painted in index order
     * with `SOURCE_OVER`. Canvas limits may be lowered through
     * [PaintRasterRequest.limits]; callers processing untrusted graphs should
     * bound [RasterLimits.maxPaintNodes] and [RasterLimits.maxPixelsPerImage]
     * together.
     */
    public fun rasterizePaint(
        paint: GlyphPaintIR,
        request: PaintRasterRequest,
    ): RasterResult<Rgba8Image> {
        invalidPixelsPerEm(request.pixelsPerEm)?.let { return it }
        if (request.unitsPerEm <= 0) {
            return RasterResult.Failure(
                listOf(RasterDiagnostic.InvalidRequest("unitsPerEm", "unitsPerEm must be positive.")),
            )
        }
        return runRaster {
            PaintCompositor.rasterize(
                paint = paint,
                pixelsPerEm = request.pixelsPerEm,
                unitsPerEm = request.unitsPerEm,
                originX = request.originX,
                originY = request.originY,
                limits = request.limits,
            )
        }
    }

    /**
     * Renders [bitmap] one-to-one into RGBA using [BitmapRasterRequest.ink].
     *
     * The exact strike is preserved: no scaling, hinting, or subpixel placement
     * participates. Canvas limits are enforced before the compositor allocates.
     */
    public fun rasterizeBitmap(
        bitmap: BitmapGlyphIR,
        request: BitmapRasterRequest,
    ): RasterResult<Rgba8Image> = runRaster {
        checkRgbaCanvas(bitmap.width, bitmap.height, request.limits)
        BitmapCompositor.rasterize(bitmap, request.ink)
    }

    private inline fun <T> runRaster(block: () -> T): RasterResult<T> =
        try {
            RasterResult.Success(block())
        } catch (reached: RasterLimitReached) {
            RasterResult.Failure(
                listOf(RasterDiagnostic.LimitExceeded(reached.field, reached.observed, reached.limit)),
            )
        } catch (rejected: RasterRequestRejected) {
            RasterResult.Failure(
                listOf(RasterDiagnostic.InvalidRequest(rejected.field, rejected.detail)),
            )
        }

    private fun invalidPixelsPerEm(value: Double): RasterResult.Failure? =
        if (!value.isFinite() || value <= 0.0) {
            RasterResult.Failure(
                listOf(RasterDiagnostic.InvalidRequest("pixelsPerEm", "pixelsPerEm must be finite and positive.")),
            )
        } else {
            null
        }

    private fun checkImageBounds(width: Int, height: Int, limits: RasterLimits) {
        if (width > limits.maxWidthPx) {
            throw RasterLimitReached("maxWidthPx", width.toLong(), limits.maxWidthPx.toLong())
        }
        if (height > limits.maxHeightPx) {
            throw RasterLimitReached("maxHeightPx", height.toLong(), limits.maxHeightPx.toLong())
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > limits.maxPixelsPerImage.toLong()) {
            throw RasterLimitReached("maxPixelsPerImage", pixels, limits.maxPixelsPerImage.toLong())
        }
    }

    /**
     * Bounds a four-byte-per-pixel canvas, including the allocation guard used
     * by the RGBA compositors.
     */
    private fun checkRgbaCanvas(width: Int, height: Int, limits: RasterLimits) {
        checkImageBounds(width, height, limits)
        val pixels = width.toLong() * height.toLong()
        if (pixels > Int.MAX_VALUE.toLong() / 4L) {
            throw RasterLimitReached("maxPixelsPerImage", pixels, Int.MAX_VALUE.toLong() / 4L)
        }
    }
}
