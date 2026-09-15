package org.graphiks.kalligraphie.raster

/**
 * Fills flattened contours into an eight-bit coverage image.
 *
 * Every pixel is sampled at sixteen fixed sub-pixel positions and the non-zero
 * winding rule decides coverage. Sample positions, crossing conventions, and
 * the coverage formula are fixed, so identical contours always produce identical
 * bytes on every platform.
 */
internal object CoverageRaster {
    private val sampleOffsets = doubleArrayOf(0.125, 0.375, 0.625, 0.875)

    /** Returns whether [x], [y] lies inside the contours under the non-zero rule. */
    fun contains(contours: List<FlatContour>, x: Double, y: Double): Boolean =
        windingNumber(contours, x, y) != 0

    /**
     * Rasterizes [contours] into an [A8Image] covering `[left, left + width)` by
     * `[top, top + height)`.
     */
    fun rasterize(
        contours: List<FlatContour>,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): A8Image {
        val pixels = ByteArray(width * height)
        for (row in 0 until height) {
            val baseY = top + row
            for (column in 0 until width) {
                val baseX = left + column
                var inside = 0
                for (offsetY in sampleOffsets) {
                    val sampleY = baseY + offsetY
                    for (offsetX in sampleOffsets) {
                        if (windingNumber(contours, baseX + offsetX, sampleY) != 0) {
                            inside += 1
                        }
                    }
                }
                if (inside > 0) {
                    pixels[row * width + column] = (((inside * 255) + 8) / 16).toByte()
                }
            }
        }
        return A8Image(width, height, left, top, pixels)
    }

    private fun windingNumber(contours: List<FlatContour>, x: Double, y: Double): Int {
        var winding = 0
        for (contour in contours) {
            val points = contour.points
            if (points.size < 2) continue
            for (index in points.indices) {
                val a = points[index]
                val b = points[(index + 1) % points.size]
                if (a.y <= y) {
                    if (b.y > y && isLeft(a, b, x, y) > 0.0) winding += 1
                } else if (b.y <= y && isLeft(a, b, x, y) < 0.0) {
                    winding -= 1
                }
            }
        }
        return winding
    }

    private fun isLeft(a: FlatPoint, b: FlatPoint, x: Double, y: Double): Double =
        (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y)
}
