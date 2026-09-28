package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintColorStop
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintNode

/**
 * Shades a clipped region with one linear gradient.
 *
 * The colour line is evaluated at the pixel centre with basic `+ - * /` on `Double`, then
 * interpolated in linear-light sRGB with premultiplied alpha and converted through the committed
 * integer transfer table. Only `+ - * /` and `floor` participate, so no platform math can move a
 * byte. [p0], [p1] and [p2] are already in device space.
 */
internal object LinearGradientShader {
    /** Below this projection denominator the gradient is degenerate. */
    private const val DENOMINATOR_EPSILON = 1e-12

    fun shade(
        gradient: GlyphPaintNode.LinearGradient,
        coverage: A8Image,
        p0: FlatPoint,
        p1: FlatPoint,
        p2: FlatPoint,
    ): ByteArray {
        if (!isFinite(p0) || !isFinite(p1) || !isFinite(p2)) {
            throw RasterRequestRejected("gradient", "the gradient geometry is not finite.")
        }
        val nx = -(p2.y - p0.y)
        val ny = p2.x - p0.x
        val denominator = (p1.x - p0.x) * nx + (p1.y - p0.y) * ny
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < DENOMINATOR_EPSILON) {
            throw RasterRequestRejected("gradient", "the linear gradient projection is degenerate.")
        }
        val pixels = ByteArray(coverage.width * coverage.height * 4)
        for (row in 0 until coverage.height) {
            for (column in 0 until coverage.width) {
                val sample = coverage[column, row]
                if (sample == 0) continue
                val x = coverage.left + column + 0.5
                val y = coverage.top + row + 0.5
                val t = ((x - p0.x) * nx + (y - p0.y) * ny) / denominator
                if (!t.isFinite()) {
                    throw RasterRequestRejected("gradient", "the gradient parameter is not finite.")
                }
                val argb = colorAt(gradient, t)
                val alpha = (((argb ushr 24) and 0xFF) * sample + 127) / 255
                val base = (row * coverage.width + column) * 4
                pixels[base] = (argb ushr 16).toByte()
                pixels[base + 1] = (argb ushr 8).toByte()
                pixels[base + 2] = argb.toByte()
                pixels[base + 3] = alpha.toByte()
            }
        }
        return pixels
    }

    /** Returns the straight `0xAARRGGBB` colour of the line at [raw]. */
    private fun colorAt(gradient: GlyphPaintNode.LinearGradient, raw: Double): Int {
        val stops = gradient.colorLine.colorStops
        if (stops.isEmpty()) return 0
        if (stops.size == 1) return straight(stops.first())
        val first = stops.first().offset
        val last = stops.last().offset
        val span = last - first
        if (!span.isFinite()) {
            throw RasterRequestRejected("gradient", "the colour line span is not finite.")
        }
        if (gradient.colorLine.extendMode == GlyphPaintExtendMode.PAD) {
            // Duplicate offsets: the first stop applies below the offset, the last at/above it.
            if (raw < first) return straight(stops.first())
            if (raw > last) return straight(stops.last())
            return interpolate(stops, raw)
        }
        if (span <= 0.0) return 0
        val t = when (gradient.colorLine.extendMode) {
            GlyphPaintExtendMode.REPEAT -> first + (((raw - first) % span) + span) % span
            GlyphPaintExtendMode.REFLECT -> {
                val phase = (((raw - first) % (2 * span)) + 2 * span) % (2 * span)
                if (phase <= span) first + phase else last - (phase - span)
            }
            GlyphPaintExtendMode.PAD -> raw
        }
        return interpolate(stops, t)
    }

    private fun isFinite(point: FlatPoint): Boolean = point.x.isFinite() && point.y.isFinite()

    private fun interpolate(stops: List<GlyphPaintColorStop>, t: Double): Int {
        var index = 0
        while (index < stops.size - 1 && stops[index + 1].offset <= t) index += 1
        if (index == stops.size - 1) return straight(stops[index])
        val left = stops[index]
        val right = stops[index + 1]
        val width = right.offset - left.offset
        val u = if (width <= 0.0) 0.0 else ((t - left.offset) / width).coerceIn(0.0, 1.0)
        val leftAlpha = effectiveAlpha(left.color, left.opacity)
        val rightAlpha = effectiveAlpha(right.color, right.opacity)
        val alpha = lerp(leftAlpha, rightAlpha, u)
        val alpha8 = roundToInt(alpha * 255.0).coerceIn(0, 255)
        if (alpha8 == 0) return 0
        val red = unpremultipliedChannel(left, right, u, alpha, leftAlpha, rightAlpha) { color -> color.red }
        val green = unpremultipliedChannel(left, right, u, alpha, leftAlpha, rightAlpha) { color -> color.green }
        val blue = unpremultipliedChannel(left, right, u, alpha, leftAlpha, rightAlpha) { color -> color.blue }
        return (alpha8 shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private inline fun unpremultipliedChannel(
        left: GlyphPaintColorStop,
        right: GlyphPaintColorStop,
        u: Double,
        alpha: Double,
        leftAlpha: Double,
        rightAlpha: Double,
        channel: (GlyphColor) -> Int,
    ): Int {
        val premultiplied = lerp(
            SrgbTransfer.toLinear(channel(left.color)) * leftAlpha,
            SrgbTransfer.toLinear(channel(right.color)) * rightAlpha,
            u,
        )
        val straight = premultiplied / alpha
        return SrgbTransfer.toSrgb(roundToInt(straight).coerceIn(0, 65535))
    }

    private fun straight(stop: GlyphPaintColorStop): Int {
        val alpha = roundToInt(effectiveAlpha(stop.color, stop.opacity) * 255.0).coerceIn(0, 255)
        return (alpha shl 24) or (stop.color.red shl 16) or (stop.color.green shl 8) or stop.color.blue
    }

    private fun effectiveAlpha(color: GlyphColor, opacity: Double): Double = color.alpha / 255.0 * opacity

    private fun lerp(left: Double, right: Double, u: Double): Double = left + (right - left) * u

    private fun roundToInt(value: Double): Int = kotlin.math.floor(value + 0.5).toInt()
}
