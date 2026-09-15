package org.graphiks.kalligraphie.raster

/**
 * Conservative integer envelope of flattened contours.
 *
 * Returns `null` when the geometry has no points or empty area. Bounds are
 * `floor` of the minima and `ceil` of the maxima and stay conservative for the
 * flattened point set. A span that cannot be represented within [limits] is
 * refused with [RasterLimitReached] instead of being discarded.
 */
internal fun boundsOf(contours: List<FlatContour>, limits: RasterLimits): PixelBounds? {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var found = false
    for (contour in contours) {
        for (point in contour.points) {
            found = true
            if (point.x < minX) minX = point.x
            if (point.y < minY) minY = point.y
            if (point.x > maxX) maxX = point.x
            if (point.y > maxY) maxY = point.y
        }
    }
    if (!found) return null
    val left = kotlin.math.floor(minX)
    val top = kotlin.math.floor(minY)
    val width = kotlin.math.ceil(maxX) - left
    val height = kotlin.math.ceil(maxY) - top
    if (width <= 0.0 || height <= 0.0) return null
    if (width > limits.maxWidthPx.toDouble()) {
        throw RasterLimitReached("maxWidthPx", width.toLong(), limits.maxWidthPx.toLong())
    }
    if (height > limits.maxHeightPx.toDouble()) {
        throw RasterLimitReached("maxHeightPx", height.toLong(), limits.maxHeightPx.toLong())
    }
    return PixelBounds(left.toInt(), top.toInt(), width.toInt(), height.toInt())
}

internal class PixelBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)
