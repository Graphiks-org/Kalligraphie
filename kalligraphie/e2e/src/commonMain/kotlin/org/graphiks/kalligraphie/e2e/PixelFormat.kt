package org.graphiks.kalligraphie.e2e

/** Pixel layout of a canonical golden image. */
public enum class PixelFormat(
    /** Bytes stored per pixel in the canonical serialization. */
    public val bytesPerPixel: Int,
) {
    /** One eight-bit coverage sample per pixel, row-major. */
    ALPHA_8(1),

    /** Four straight (non-premultiplied) channels per pixel in R, G, B, A order. */
    RGBA_8888(4),
}
