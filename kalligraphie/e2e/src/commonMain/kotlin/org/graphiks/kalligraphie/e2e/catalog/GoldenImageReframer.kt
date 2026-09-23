package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenImage

/**
 * Places a rendered image inside a larger canonical frame without touching its pixels. Clipping is
 * refused rather than cropping: silently dropping ink would hide the very regression a golden scene
 * exists to catch.
 */
public object GoldenImageReframer {
    /** Copies [image] into a [width] x [height] frame at ([offsetX], [offsetY]). */
    public fun reframe(image: GoldenImage, width: Int, height: Int, offsetX: Int, offsetY: Int): GoldenImage {
        require(width > 0 && height > 0) { "A reframed image must be positive." }
        require(offsetX >= 0 && offsetY >= 0) { "A reframe offset must not be negative." }
        require(offsetX + image.width <= width && offsetY + image.height <= height) {
            "A reframe must not clip the source image."
        }
        val bytesPerPixel = image.format.bytesPerPixel
        val source = image.copyCanonicalBytes()
        val target = ByteArray(width * height * bytesPerPixel)
        for (y in 0 until image.height) {
            val sourceRow = y * image.width * bytesPerPixel
            val targetRow = ((y + offsetY) * width + offsetX) * bytesPerPixel
            source.copyInto(target, destinationOffset = targetRow, startIndex = sourceRow, endIndex = sourceRow + image.width * bytesPerPixel)
        }
        return when (image.format) {
            org.graphiks.kalligraphie.e2e.PixelFormat.ALPHA_8 -> GoldenImage.alpha8(width, height, target)
            org.graphiks.kalligraphie.e2e.PixelFormat.RGBA_8888 -> GoldenImage.rgba8(width, height, target)
        }
    }
}
