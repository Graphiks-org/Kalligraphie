package org.graphiks.kalligraphie.e2e

/**
 * Immutable canonical image whose bytes are the golden fingerprint subject.
 *
 * Pixels are stored row-major with no padding, in the source orientation of the
 * rasterizer. Nothing here flips, premultiplies, or reorders channels: the same
 * logical image always serializes to the same bytes.
 */
public class GoldenImage private constructor(
    /** Image width in pixels. */
    public val width: Int,
    /** Image height in pixels. */
    public val height: Int,
    /** Pixel layout. */
    public val format: PixelFormat,
    canonicalBytes: ByteArray,
) {
    private val captured: ByteArray = canonicalBytes.copyOf()

    init {
        require(width >= 0) { "A golden image width must be non-negative." }
        require(height >= 0) { "A golden image height must be non-negative." }
        val pixels = width.toLong() * height.toLong()
        require(pixels <= (Int.MAX_VALUE / format.bytesPerPixel).toLong()) {
            "Canonical byte count exceeds the maximum buffer size."
        }
        val expected = pixels * format.bytesPerPixel.toLong()
        require(captured.size == expected.toInt()) { "Canonical byte count does not match the image dimensions." }
    }

    /** Returns a caller-owned copy of the canonical bytes. */
    public fun copyCanonicalBytes(): ByteArray = captured.copyOf()

    override fun equals(other: Any?): Boolean =
        other is GoldenImage && width == other.width && height == other.height &&
            format == other.format && captured.contentEquals(other.captured)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + format.hashCode()
        return 31 * result + captured.contentHashCode()
    }

    override fun toString(): String =
        "GoldenImage(width=$width, height=$height, format=$format, bytes=${captured.size})"

    public companion object {
        /** Wraps eight-bit coverage samples (one byte per pixel, row-major). */
        public fun alpha8(width: Int, height: Int, pixels: ByteArray): GoldenImage =
            GoldenImage(width, height, PixelFormat.ALPHA_8, pixels)

        /** Wraps straight RGBA samples (four bytes per pixel in R, G, B, A order). */
        public fun rgba8(width: Int, height: Int, pixels: ByteArray): GoldenImage =
            GoldenImage(width, height, PixelFormat.RGBA_8888, pixels)
    }
}
