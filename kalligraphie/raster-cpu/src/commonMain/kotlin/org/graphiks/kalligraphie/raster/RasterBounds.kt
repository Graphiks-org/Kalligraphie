package org.graphiks.kalligraphie.raster

internal class PixelBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * Conservative integer envelope of flattened contours.
 *
 * Returns `null` when the geometry has no points or empty area. Bounds are
 * `floor` of the minima and `ceil` of the maxima, so every pixel that can carry
 * coverage is included.
 */
internal fun boundsOf(contours: List<FlatContour>): PixelBounds? {
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
    val left = kotlin.math.floor(minX).toInt()
    val top = kotlin.math.floor(minY).toInt()
    val width = kotlin.math.ceil(maxX).toInt() - left
    val height = kotlin.math.ceil(maxY).toInt() - top
    if (width <= 0 || height <= 0) return null
    return PixelBounds(left, top, width, height)
}
