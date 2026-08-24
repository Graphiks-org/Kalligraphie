package io.ygdrasil.kalligraphie.font.colr

import java.security.MessageDigest
import kotlin.math.abs

internal const val U16_SIZE_BYTES = 2
internal const val U24_SIZE_BYTES = 3
internal const val U32_SIZE_BYTES = 4

internal const val MAX_COLOR_PALETTES = 256
internal const val MAX_COLOR_PALETTE_ENTRIES = 4096
internal const val MAX_COLOR_RECORDS = 4096
internal const val MAX_EXPANDED_COLOR_RECORDS = 65536L
internal const val MAX_COLOR_BASE_GLYPHS = 8192
internal const val MAX_COLOR_LAYERS = 16384
internal const val MAX_LAYERS_PER_COLOR_GLYPH = 256
internal const val MAX_COLOR_STOPS = 4096
internal const val MAX_EXPANDED_COLOR_LAYERS = 65536L
internal const val MAX_COLOR_PAINT_DEPTH = 32
internal const val MAX_COLR_V1_EXPANDED_PAINTS = 65536

const val COLR_FOREGROUND_PALETTE_INDEX: Int = 0xFFFF

internal const val ColorGlyphFloatEpsilon: Float = 0.0001f
internal const val COLRV1TransformDeterminantEpsilon: Float = 0.0001f

/**
 * Bounds-checked big-endian reader for raw OpenType color table bytes.
 */
internal class ColorTableReader(
    private val bytes: ByteArray,
) {
    val size: Int
        get() = bytes.size

    fun fits(offset: Int, length: Long): Boolean {
        if (offset < 0 || length < 0L) return false
        val start = offset.toLong()
        val end = start + length
        return start <= bytes.size.toLong() && end >= start && end <= bytes.size.toLong()
    }

    fun u8(offset: Int): Int? =
        if (fits(offset, 1L)) bytes[offset].toInt() and 0xFF else null

    fun u16(offset: Int): Int? {
        if (!fits(offset, U16_SIZE_BYTES.toLong())) return null
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    fun i16(offset: Int): Int? =
        u16(offset)?.toShort()?.toInt()

    fun u24(offset: Int): Int? {
        if (!fits(offset, U24_SIZE_BYTES.toLong())) return null
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    fun u32(offset: Int): Long? {
        if (!fits(offset, U32_SIZE_BYTES.toLong())) return null
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    fun f2Dot14(offset: Int): Float? =
        i16(offset)?.let { value -> value / 16384f }

    fun fixed16Dot16(offset: Int): Float? {
        if (!fits(offset, U32_SIZE_BYTES.toLong())) return null
        val raw = ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
        return raw / 65536f
    }
}

internal fun Long.toIntOrNull(): Int? =
    if (this <= Int.MAX_VALUE.toLong()) toInt() else null

internal fun absoluteTableOffset(baseOffset: Int, relativeOffset: Int, tableSize: Int): Int? {
    val absolute = baseOffset.toLong() + relativeOffset.toLong()
    if (absolute < 0L || absolute >= tableSize.toLong() || absolute > Int.MAX_VALUE.toLong()) return null
    return absolute.toInt()
}

internal fun packArgb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    ((alpha and 0xFF) shl 24) or
        ((red and 0xFF) shl 16) or
        ((green and 0xFF) shl 8) or
        (blue and 0xFF)

fun colorGlyphFloatToken(value: Float): String {
    require(value.isFinite()) { "Color glyph float values must be finite." }
    val token = value.toString()
    return if (token.endsWith(".0") && 'E' !in token && 'e' !in token) {
        token.dropLast(2)
    } else {
        token
    }
}

internal fun approxColorGlyphFloat(left: Float, right: Float): Boolean =
    abs(left - right) <= ColorGlyphFloatEpsilon

fun colorGlyphNullableString(value: String?): String =
    value?.let(::colorGlyphJsonString) ?: "null"

fun colorGlyphJsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

fun colorGlyphSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }

fun colorGlyphArgbHex(color: Int): String {
    val unsigned = color.toLong() and 0xFFFF_FFFFL
    return "#%08X".format(unsigned)
}

fun colorGlyphFloatMapJson(values: Map<String, Float>): String = buildString {
    append("{")
    append(
        values.entries
            .sortedBy { entry -> entry.key }
            .joinToString(", ") { entry ->
                "${colorGlyphJsonString(entry.key)}: ${colorGlyphFloatToken(entry.value)}"
            },
    )
    append("}")
}

fun resolvePaletteColorArgb(
    palette: CPALPalette,
    paletteIndex: Int,
    alpha: Float,
): String {
    if (paletteIndex == COLR_FOREGROUND_PALETTE_INDEX) {
        return "#%02X000000".format((255f * alpha).toInt().coerceIn(0, 255))
    }
    val baseColor = palette.colors.getOrNull(paletteIndex)
        ?: error("Palette index $paletteIndex is unavailable for resolved color output.")
    val baseAlpha = (baseColor ushr 24) and 0xFF
    val resolvedAlpha = (baseAlpha.toFloat() * alpha).toInt().coerceIn(0, 255)
    val rgb = baseColor and 0x00FF_FFFF
    return colorGlyphArgbHex((resolvedAlpha shl 24) or rgb)
}

internal fun List<Int>.toARGBByteArray(): ByteArray {
    val bytes = ByteArray(size * 4)
    forEachIndexed { index, pixel ->
        val offset = index * 4
        bytes[offset] = (pixel ushr 24).toByte()
        bytes[offset + 1] = (pixel ushr 16).toByte()
        bytes[offset + 2] = (pixel ushr 8).toByte()
        bytes[offset + 3] = pixel.toByte()
    }
    return bytes
}
