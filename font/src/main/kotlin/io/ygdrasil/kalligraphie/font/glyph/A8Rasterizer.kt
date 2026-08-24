package io.ygdrasil.kalligraphie.font.glyph

import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.font.scaler.OutlineCommand
import io.ygdrasil.kalligraphie.font.scaler.ScaledGlyph
import io.ygdrasil.kalligraphie.glyph.GlyphMaskGenerator
import io.ygdrasil.kalligraphie.glyph.GlyphStrikeKey
import io.ygdrasil.kalligraphie.glyph.OutlineGlyphRepresentation
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

data class A8Bitmap(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width >= 0) { "A8 bitmap width must be non-negative, but was $width." }
        require(height >= 0) { "A8 bitmap height must be non-negative, but was $height." }

        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= Int.MAX_VALUE.toLong()) {
            "A8 bitmap pixel count $pixelCount exceeds the maximum representable buffer size."
        }
        require(pixels.size == pixelCount.toInt()) {
            "A8 bitmap pixels must exactly match width * height."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is A8Bitmap) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

fun A8Bitmap?.occupancySize(): Long {
    if (this == null) return 0L
    return width.toLong() * height.toLong()
}

class A8Rasterizer(
    @Suppress("unused")
    private val flatness: Double = 0.25,
) {
    fun rasterize(glyph: ScaledGlyph): A8Bitmap? {
        if (glyph.commands.isEmpty()) return null

        val pathCommands = glyph.commands.map { command ->
            formatOutlineCommand(command)
        }

        val outline = OutlineGlyphRepresentation(
            glyphId = glyph.glyphId,
            pathCommands = pathCommands,
            windingRule = "nonZero",
        )

        val strikeKey = GlyphStrikeKey(
            typefaceId = TypefaceID(Uuid.NIL),
            glyphId = glyph.glyphId,
            sizePx = glyph.size,
            scaleX = 1f,
            scaleY = 1f,
            subpixelX = 0f,
            subpixelY = 0f,
        )

        val generator = object : GlyphMaskGenerator {}

        val mask = try {
            generator.generate(outline, strikeKey)
        } catch (_: Exception) {
            return null
        }

        if (mask.width <= 0 || mask.height <= 0) return null

        val pixelCount = mask.width * mask.height
        val pixels = ByteArray(pixelCount)
        for (row in 0 until mask.height) {
            for (col in 0 until mask.width) {
                val value = mask.pixels[row * mask.rowBytes + col]
                pixels[row * mask.width + col] = value.toByte()
            }
        }

        return A8Bitmap(mask.width, mask.height, pixels)
    }

    private fun formatOutlineCommand(command: OutlineCommand): String = when (command) {
        is OutlineCommand.MoveTo -> "M ${tidy(command.x)} ${tidy(command.y)}"
        is OutlineCommand.LineTo -> "L ${tidy(command.x)} ${tidy(command.y)}"
        is OutlineCommand.QuadraticTo ->
            "Q ${tidy(command.controlX)} ${tidy(command.controlY)} ${tidy(command.x)} ${tidy(command.y)}"
        is OutlineCommand.CubicTo ->
            "C ${tidy(command.controlX1)} ${tidy(command.controlY1)} ${tidy(command.controlX2)} ${tidy(command.controlY2)} ${tidy(command.x)} ${tidy(command.y)}"
        is OutlineCommand.Close -> "Z"
    }
}

private fun tidy(value: Double): String {
    if (value.isInfinite() || value.isNaN()) error("Non-finite coordinate in outline command.")
    val rounded = (value * 1_000_000.0).roundToLong() / 1_000_000.0
    if (rounded == rounded.toLong().toDouble()) {
        return rounded.toLong().toString()
    }
    val formatted = String.format(Locale.ROOT, "%.6f", rounded)
    return formatted.replace(Regex("0*$"), "").replace(Regex("\\.$"), "")
}
