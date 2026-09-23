package org.graphiks.kalligraphie.e2e

/**
 * Immutable canonical image whose bytes are the golden fingerprint subject.
 *
 * Pixels are stored row-major with no padding, in the orientation its producer declared: see
 * [orientation] and [GoldenOrientation]. Nothing here flips, premultiplies, or reorders channels
 * behind the caller's back, so the same logical image always serializes to the same bytes; a reader
 * that needs image orientation asks for it through [toImageOrientation].
 */
public class GoldenImage private constructor(
    /** Image width in pixels. */
    public val width: Int,
    /** Image height in pixels. */
    public val height: Int,
    /** Pixel layout. */
    public val format: PixelFormat,
    /** Row order of the canonical bytes, as declared by the producer. */
    public val orientation: GoldenOrientation,
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

    /**
     * Returns this image with row zero at the visual top.
     *
     * An [GoldenOrientation.IMAGE] image is returned as it is, so a caller that already holds image
     * orientation pays for no copy and risks no second reversal. A [GoldenOrientation.DESIGN] image
     * is reversed row by row, the pixels of one row staying together: the dimensions and the pixel
     * format are untouched, only the reading order changes.
     */
    public fun toImageOrientation(): GoldenImage {
        if (orientation == GoldenOrientation.IMAGE) return this
        val rowBytes = width * format.bytesPerPixel
        val reversed = ByteArray(captured.size)
        for (row in 0 until height) {
            val source = row * rowBytes
            val target = (height - 1 - row) * rowBytes
            captured.copyInto(reversed, destinationOffset = target, startIndex = source, endIndex = source + rowBytes)
        }
        return GoldenImage(width, height, format, GoldenOrientation.IMAGE, reversed)
    }

    override fun equals(other: Any?): Boolean =
        other is GoldenImage && width == other.width && height == other.height &&
            format == other.format && orientation == other.orientation &&
            captured.contentEquals(other.captured)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + format.hashCode()
        result = 31 * result + orientation.hashCode()
        return 31 * result + captured.contentHashCode()
    }

    override fun toString(): String =
        "GoldenImage(width=$width, height=$height, format=$format, orientation=$orientation, bytes=${captured.size})"

    public companion object {
        /** Wraps eight-bit coverage samples (one byte per pixel, row-major). */
        public fun alpha8(
            width: Int,
            height: Int,
            pixels: ByteArray,
            orientation: GoldenOrientation = GoldenOrientation.IMAGE,
        ): GoldenImage = GoldenImage(width, height, PixelFormat.ALPHA_8, orientation, pixels)

        /** Wraps straight RGBA samples (four bytes per pixel in R, G, B, A order, row-major). */
        public fun rgba8(
            width: Int,
            height: Int,
            pixels: ByteArray,
            orientation: GoldenOrientation = GoldenOrientation.IMAGE,
        ): GoldenImage = GoldenImage(width, height, PixelFormat.RGBA_8888, orientation, pixels)
    }
}
