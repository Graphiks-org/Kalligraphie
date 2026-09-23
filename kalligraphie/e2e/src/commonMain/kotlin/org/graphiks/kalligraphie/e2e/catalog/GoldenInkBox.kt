package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.PixelFormat

/** Tight box of ink pixels inside a canonical image, in inclusive image coordinates. */
public data class InkBox(
    /** Leftmost column holding ink. */
    public val minX: Int,
    /** Topmost row holding ink. */
    public val minY: Int,
    /** Rightmost column holding ink. */
    public val maxX: Int,
    /** Bottommost row holding ink. */
    public val maxY: Int,
) {
    /** Number of columns the box spans. */
    public val width: Int get() = maxX - minX + 1

    /** Number of rows the box spans. */
    public val height: Int get() = maxY - minY + 1
}

/**
 * Measures the ink of a canonical image. A pixel carries ink when its alpha is non-zero, so an
 * opaque black pixel and a transparent white one are told apart by coverage alone — the same rule
 * for [PixelFormat.ALPHA_8] and [PixelFormat.RGBA_8888].
 */
public object GoldenInkBox {
    /** Returns the tight ink box of [image], or `null` when no pixel carries coverage. */
    public fun of(image: GoldenImage): InkBox? {
        val bytes = image.copyCanonicalBytes()
        val bytesPerPixel = image.format.bytesPerPixel
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alphaIndex = (y * image.width + x) * bytesPerPixel + (bytesPerPixel - 1)
                if (bytes[alphaIndex].toInt() == 0) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else InkBox(minX, minY, maxX, maxY)
    }
}
