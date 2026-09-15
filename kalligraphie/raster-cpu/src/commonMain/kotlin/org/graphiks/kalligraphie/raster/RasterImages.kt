package org.graphiks.kalligraphie.raster

/**
 * Immutable eight-bit coverage image.
 *
 * Pixels are copied at construction and read row-major: index `y * width + x`,
 * with row zero corresponding to [top]. [left] and [top] are whole-pixel bearings relative to
 * the coordinate origin used by the request.
 */
public class A8Image(
    /** Image width in pixels. */
    public val width: Int,
    /** Image height in pixels. */
    public val height: Int,
    /** Horizontal bearing of the first column. */
    public val left: Int,
    /** Vertical bearing of the first row. */
    public val top: Int,
    pixels: ByteArray,
) {
    private val captured: ByteArray = pixels.copyOf()

    init {
        require(width >= 0) { "width must be non-negative." }
        require(height >= 0) { "height must be non-negative." }
        val expected = width.toLong() * height.toLong()
        require(expected <= Int.MAX_VALUE.toLong()) { "pixel count exceeds the maximum buffer size." }
        require(captured.size == expected.toInt()) { "pixel count does not match the image dimensions." }
    }

    /** Returns a caller-owned copy of the coverage samples. */
    public fun copyPixels(): ByteArray = captured.copyOf()

    /** Returns the coverage sample at [x], [y] in the inclusive range `0..255`. */
    public operator fun get(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "pixel ($x, $y) is outside the image." }
        return captured[y * width + x].toInt() and 0xFF
    }

    override fun equals(other: Any?): Boolean =
        other is A8Image && width == other.width && height == other.height &&
            left == other.left && top == other.top && captured.contentEquals(other.captured)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + left
        result = 31 * result + top
        return 31 * result + captured.contentHashCode()
    }

    override fun toString(): String =
        "A8Image(width=$width, height=$height, left=$left, top=$top, bytes=${captured.size})"
}

/**
 * Immutable non-premultiplied RGBA image with eight bits per channel.
 *
 * Pixels are copied at construction and stored row-major in R, G, B, A byte
 * order. [get] returns the packed non-premultiplied value `0xAARRGGBB`.
 */
public class Rgba8Image(
    /** Image width in pixels. */
    public val width: Int,
    /** Image height in pixels. */
    public val height: Int,
    /** Horizontal bearing of the first column. */
    public val left: Int,
    /** Vertical bearing of the first row. */
    public val top: Int,
    pixels: ByteArray,
) {
    private val captured: ByteArray = pixels.copyOf()

    init {
        require(width >= 0) { "width must be non-negative." }
        require(height >= 0) { "height must be non-negative." }
        val pixels = width.toLong() * height.toLong()
        require(pixels <= Int.MAX_VALUE.toLong() / 4L) { "pixel count exceeds the maximum buffer size." }
        val expected = pixels * 4L
        require(captured.size == expected.toInt()) { "pixel count does not match the image dimensions." }
    }

    /** Returns a caller-owned copy of the pixels in R, G, B, A order. */
    public fun copyPixels(): ByteArray = captured.copyOf()

    /** Returns the packed non-premultiplied pixel `0xAARRGGBB` at [x], [y]. */
    public operator fun get(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "pixel ($x, $y) is outside the image." }
        val index = (y * width + x) * 4
        val red = captured[index].toInt() and 0xFF
        val green = captured[index + 1].toInt() and 0xFF
        val blue = captured[index + 2].toInt() and 0xFF
        val alpha = captured[index + 3].toInt() and 0xFF
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    override fun equals(other: Any?): Boolean =
        other is Rgba8Image && width == other.width && height == other.height &&
            left == other.left && top == other.top && captured.contentEquals(other.captured)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + left
        result = 31 * result + top
        return 31 * result + captured.contentHashCode()
    }

    override fun toString(): String =
        "Rgba8Image(width=$width, height=$height, left=$left, top=$top, bytes=${captured.size})"
}
