package org.graphiks.kalligraphie.glyph

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** Immutable result of applying one canonical blur key to an A8 glyph mask. */
data class BlurredA8GlyphMask(
    val mask: A8GlyphMask,
    val paddingPx: Int,
)

/**
 * Applies the canonical glyph-mask blur before paint coloration.
 *
 * Convolution stays in floating point through both separable passes and is
 * rounded to an integer exactly once, after the vertical pass.
 */
fun blurGlyphMask(
    source: A8GlyphMask,
    key: GlyphMaskBlurKey,
): BlurredA8GlyphMask {
    require(source.width >= 0 && source.height >= 0) {
        "A8 glyph mask dimensions must be non-negative"
    }
    require(source.rowBytes >= source.width) {
        "A8 glyph mask rowBytes must cover its width"
    }
    require(
        source.pixels.size.toLong() ==
            source.rowBytes.toLong() * source.height.toLong(),
    ) {
        "A8 glyph mask pixels must exactly match rowBytes * height"
    }
    require(source.pixels.all { sample -> sample in 0..255 }) {
        "A8 glyph mask pixels must be unsigned bytes"
    }

    val scaledSigma =
        key.sigma.toDouble() * max(abs(key.rasterScaleX), abs(key.rasterScaleY)).toDouble()
    if (scaledSigma == 0.0) {
        return BlurredA8GlyphMask(mask = source, paddingPx = 0)
    }
    val paddingValue = ceil(3.0 * scaledSigma)
    require(paddingValue.isFinite() && paddingValue in 0.0..Int.MAX_VALUE.toDouble()) {
        "Glyph blur padding exceeds Int range"
    }
    val padding = paddingValue.toInt()
    val outputLeft = checkedBlurBearing(source.left, padding)
    val outputTop = checkedBlurBearing(source.top, padding)
    if (source.width == 0 || source.height == 0) {
        checkedBlurFarEdge(outputLeft, source.width)
        checkedBlurFarEdge(outputTop, source.height)
        return BlurredA8GlyphMask(
            mask = source.copy(
                left = outputLeft,
                top = outputTop,
            ),
            paddingPx = padding,
        )
    }
    val outputWidth = checkedBlurExtent(source.width, padding)
    val outputHeight = checkedBlurExtent(source.height, padding)
    checkedBlurFarEdge(outputLeft, outputWidth)
    checkedBlurFarEdge(outputTop, outputHeight)
    val outputSize = outputWidth.toLong() * outputHeight.toLong()
    require(outputSize <= Int.MAX_VALUE.toLong()) {
        "Blurred A8 glyph mask exceeds addressable pixel count"
    }

    val expandedSource = IntArray(outputSize.toInt())
    for (row in 0 until source.height) {
        for (column in 0 until source.width) {
            expandedSource[(row + padding) * outputWidth + column + padding] =
                source.pixels[row * source.rowBytes + column]
        }
    }
    val kernel = gaussianKernel(scaledSigma, padding)
    val horizontal = DoubleArray(outputSize.toInt())
    for (row in 0 until outputHeight) {
        for (column in 0 until outputWidth) {
            var value = 0.0
            for (offset in -padding..padding) {
                val sampleX = column + offset
                if (sampleX in 0 until outputWidth) {
                    value += expandedSource[row * outputWidth + sampleX] *
                        kernel[offset + padding]
                }
            }
            horizontal[row * outputWidth + column] = value
        }
    }
    val blurred = IntArray(outputSize.toInt())
    for (row in 0 until outputHeight) {
        for (column in 0 until outputWidth) {
            var value = 0.0
            for (offset in -padding..padding) {
                val sampleY = row + offset
                if (sampleY in 0 until outputHeight) {
                    value += horizontal[sampleY * outputWidth + column] *
                        kernel[offset + padding]
                }
            }
            blurred[row * outputWidth + column] = value.roundToInt().coerceIn(0, 255)
        }
    }
    val output = List(outputSize.toInt()) { index ->
        val sourceAlpha = expandedSource[index]
        val blurAlpha = blurred[index]
        when (key.style) {
            GlyphMaskBlurStyle.NORMAL -> blurAlpha
            GlyphMaskBlurStyle.SOLID ->
                sourceAlpha + roundedAlphaProduct(255 - sourceAlpha, blurAlpha)
            GlyphMaskBlurStyle.OUTER ->
                roundedAlphaProduct(255 - sourceAlpha, blurAlpha)
            GlyphMaskBlurStyle.INNER ->
                roundedAlphaProduct(sourceAlpha, blurAlpha)
        }.coerceIn(0, 255)
    }

    return BlurredA8GlyphMask(
        mask = A8GlyphMask(
            glyphId = source.glyphId,
            width = outputWidth,
            height = outputHeight,
            left = outputLeft,
            top = outputTop,
            rowBytes = outputWidth,
            pixels = output,
            diagnostics = source.diagnostics,
            sourceOutlineSha256 = source.sourceOutlineSha256,
        ),
        paddingPx = padding,
    )
}

private fun checkedBlurBearing(sourceBearing: Int, padding: Int): Int {
    val bearing = sourceBearing.toLong() - padding.toLong()
    require(bearing in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Blurred A8 glyph mask bearing exceeds Int range"
    }
    return bearing.toInt()
}

private fun checkedBlurFarEdge(outputBearing: Int, outputExtent: Int) {
    val farEdge = outputBearing.toLong() + outputExtent.toLong()
    require(farEdge in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Blurred A8 glyph mask far edge exceeds Int range"
    }
}

private fun checkedBlurExtent(sourceExtent: Int, padding: Int): Int {
    val extent = sourceExtent.toLong() + padding.toLong() * 2L
    require(extent in 0..Int.MAX_VALUE.toLong()) {
        "Blurred A8 glyph mask extent exceeds Int range"
    }
    return extent.toInt()
}

private fun gaussianKernel(sigma: Double, radius: Int): DoubleArray {
    if (radius == 0 || sigma == 0.0) return doubleArrayOf(1.0)
    val kernel = DoubleArray(radius * 2 + 1) { index ->
        val distance = (index - radius).toDouble()
        exp(-(distance * distance) / (2.0 * sigma * sigma))
    }
    val sum = kernel.sum()
    require(sum.isFinite() && sum > 0.0) {
        "Glyph blur kernel must have a finite positive normalization"
    }
    for (index in kernel.indices) {
        kernel[index] /= sum
    }
    return kernel
}

private fun roundedAlphaProduct(left: Int, right: Int): Int =
    (left * right + 127) / 255
