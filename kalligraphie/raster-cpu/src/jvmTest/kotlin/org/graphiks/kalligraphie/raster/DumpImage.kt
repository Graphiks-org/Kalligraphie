package org.graphiks.kalligraphie.raster

/**
 * Encodes one eight-bit coverage image as a raw P5 PGM without flipping.
 *
 * This preserves the rasterizer's source orientation and is used for the raw
 * single-glyph reference dumps.
 */
internal fun pgm(image: A8Image): ByteArray {
    val header = "P5\n${image.width} ${image.height}\n255\n".toByteArray(Charsets.US_ASCII)
    return header + image.copyPixels()
}

/**
 * Encodes one non-premultiplied RGBA image as a P6 PPM composited over white
 * without flipping (same integer formula as the canvases).
 */
internal fun ppm(image: Rgba8Image): ByteArray {
    val header = "P6\n${image.width} ${image.height}\n255\n".toByteArray(Charsets.US_ASCII)
    val pixels = image.copyPixels()
    val composited = ByteArray(image.width * image.height * 3)
    for (index in 0 until image.width * image.height) {
        val alpha = pixels[index * 4 + 3].toInt() and 0xFF
        for (channel in 0 until 3) {
            val color = pixels[index * 4 + channel].toInt() and 0xFF
            composited[index * 3 + channel] = ((color * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
        }
    }
    return header + composited
}

/**
 * Black-backed eight-bit canvas for readable demonstration sheets.
 *
 * Coverage images are drawn on their glyph baseline with a vertical flip so the
 * font's y-up design space reads correctly in the y-down image format. Pixels
 * outside the canvas are skipped deterministically.
 */
internal class A8Canvas(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "canvas dimensions must be positive." }
    }

    private val pixels = ByteArray(width * height)

    /** Returns the canvas sample at [x], [y] in the inclusive range `0..255`. */
    fun sample(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "pixel ($x, $y) is outside the canvas." }
        return pixels[y * width + x].toInt() and 0xFF
    }

    /**
     * Draws [image] at [penX] with its baseline at [baselineY], flipped vertically.
     *
     * Coverage samples are scaled by [ink] (the eight-bit ink value, default 255).
     */
    fun drawCoverage(image: A8Image, penX: Int, baselineY: Int, ink: Int = 255) {
        require(ink in 0..255) { "ink must be in 0..255." }
        val x0 = penX + image.left
        val y0 = baselineY - (image.top + image.height)
        for (row in 0 until image.height) {
            val designRow = image.height - 1 - row
            val canvasY = y0 + row
            if (canvasY !in 0 until height) continue
            for (column in 0 until image.width) {
                val canvasX = x0 + column
                if (canvasX !in 0 until width) continue
                val coverage = image[column, designRow]
                if (coverage == 0) continue
                pixels[canvasY * width + canvasX] = ((coverage * ink + 127) / 255).toByte()
            }
        }
    }

    /** Encodes the canvas as a raw P5 PGM. */
    fun toPgm(): ByteArray {
        val header = "P5\n$width $height\n255\n".toByteArray(Charsets.US_ASCII)
        return header + pixels.copyOf()
    }
}

/**
 * White-backed non-premultiplied RGBA canvas for readable color dumps.
 *
 * Color glyphs are drawn on their baseline with a vertical flip; normalized
 * bitmap strikes are drawn without a flip because they already use image
 * orientation.
 */
internal class RgbaCanvas(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "canvas dimensions must be positive." }
    }

    private val pixels = ByteArray(width * height * 4).apply { fill(-1) }

    /** Returns the packed non-premultiplied pixel `0xAARRGGBB` at [x], [y]. */
    fun pixel(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "pixel ($x, $y) is outside the canvas." }
        val base = (y * width + x) * 4
        val red = pixels[base].toInt() and 0xFF
        val green = pixels[base + 1].toInt() and 0xFF
        val blue = pixels[base + 2].toInt() and 0xFF
        val alpha = pixels[base + 3].toInt() and 0xFF
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /** Draws [image] at [penX] with its baseline at [baselineY], flipped vertically. */
    fun drawColor(image: Rgba8Image, penX: Int, baselineY: Int) {
        val x0 = penX + image.left
        val y0 = baselineY - (image.top + image.height)
        for (row in 0 until image.height) {
            val designRow = image.height - 1 - row
            val canvasY = y0 + row
            if (canvasY !in 0 until height) continue
            for (column in 0 until image.width) {
                val canvasX = x0 + column
                if (canvasX !in 0 until width) continue
                blend(image[column, designRow], canvasX, canvasY)
            }
        }
    }

    /** Draws [image] top-left at [x], [y] without flipping. */
    fun drawBitmap(image: Rgba8Image, x: Int, y: Int) {
        for (row in 0 until image.height) {
            val canvasY = y + row
            if (canvasY !in 0 until height) continue
            for (column in 0 until image.width) {
                val canvasX = x + column
                if (canvasX !in 0 until width) continue
                blend(image[column, row], canvasX, canvasY)
            }
        }
    }

    private fun blend(source: Int, x: Int, y: Int) {
        val alpha = (source ushr 24) and 0xFF
        if (alpha == 0) return
        val base = (y * width + x) * 4
        for (channel in 0 until 3) {
            val sourceChannel = (source ushr (16 - channel * 8)) and 0xFF
            val destination = pixels[base + channel].toInt() and 0xFF
            pixels[base + channel] =
                ((sourceChannel * alpha + destination * (255 - alpha) + 127) / 255).toByte()
        }
    }

    /** Encodes the canvas as a raw P6 PPM (the canvas is already opaque). */
    fun toPpm(): ByteArray {
        val header = "P6\n$width $height\n255\n".toByteArray(Charsets.US_ASCII)
        val rgb = ByteArray(width * height * 3)
        for (index in 0 until width * height) {
            val base = index * 4
            rgb[index * 3] = pixels[base]
            rgb[index * 3 + 1] = pixels[base + 1]
            rgb[index * 3 + 2] = pixels[base + 2]
        }
        return header + rgb
    }
}

/** One rendered demonstration artifact: the encoded bytes plus a human-readable note. */
internal class Dump(
    val bytes: ByteArray,
    val note: String = "",
)
