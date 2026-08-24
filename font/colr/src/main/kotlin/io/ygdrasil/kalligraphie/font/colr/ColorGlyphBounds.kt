package io.ygdrasil.kalligraphie.font.colr

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Immutable bounds for one color glyph layer or aggregate color glyph plan.
 *
 * Bounds are carried as font-design-space extrema so later CPU/GPU handoff work can reason about
 * geometry without re-reading COLR/CPAL tables. The bounds are value-object evidence only; this
 * ticket does not claim rasterization or GPU composite support.
 */
data class ColorGlyphBounds(
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
) {
    init {
        require(xMin < xMax) { "Color glyph bounds must have xMin < xMax." }
        require(yMin < yMax) { "Color glyph bounds must have yMin < yMax." }
    }

    fun union(other: ColorGlyphBounds): ColorGlyphBounds = ColorGlyphBounds(
        xMin = minOf(xMin, other.xMin),
        yMin = minOf(yMin, other.yMin),
        xMax = maxOf(xMax, other.xMax),
        yMax = maxOf(yMax, other.yMax),
    )

    fun intersectConservative(other: ColorGlyphBounds): ColorGlyphBounds {
        val clippedXMin = maxOf(xMin, other.xMin)
        val clippedYMin = maxOf(yMin, other.yMin)
        val clippedXMax = minOf(xMax, other.xMax)
        val clippedYMax = minOf(yMax, other.yMax)
        return if (clippedXMin < clippedXMax && clippedYMin < clippedYMax) {
            ColorGlyphBounds(
                xMin = clippedXMin,
                yMin = clippedYMin,
                xMax = clippedXMax,
                yMax = clippedYMax,
            )
        } else {
            other
        }
    }

    fun transformedBy(
        xx: Float,
        yx: Float,
        xy: Float,
        yy: Float,
        dx: Float,
        dy: Float,
    ): ColorGlyphBounds {
        val corners = listOf(
            xMin.toFloat() to yMin.toFloat(),
            xMax.toFloat() to yMin.toFloat(),
            xMin.toFloat() to yMax.toFloat(),
            xMax.toFloat() to yMax.toFloat(),
        )
        val transformedCorners = corners.map { (x, y) ->
            ((xx * x) + (xy * y) + dx) to ((yx * x) + (yy * y) + dy)
        }
        val minX = floor(transformedCorners.minOf { (x, _) -> x }.toDouble()).toInt()
        val minY = floor(transformedCorners.minOf { (_, y) -> y }.toDouble()).toInt()
        val maxX = ceil(transformedCorners.maxOf { (x, _) -> x }.toDouble()).toInt()
        val maxY = ceil(transformedCorners.maxOf { (_, y) -> y }.toDouble()).toInt()
        return ColorGlyphBounds(xMin = minX, yMin = minY, xMax = maxX, yMax = maxY)
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("xMin")).append(": ").append(xMin).append(", ")
        append(colorGlyphJsonString("yMin")).append(": ").append(yMin).append(", ")
        append(colorGlyphJsonString("xMax")).append(": ").append(xMax).append(", ")
        append(colorGlyphJsonString("yMax")).append(": ").append(yMax)
        append("}")
    }
}
