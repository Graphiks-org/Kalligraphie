@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32

/**
 * Test-only, independently written TrueType `glyf`/`loca` parser used to compare a reconstruction
 * with a reference outline at the semantic level (contours, points, on-curve flags, coordinates and
 * composite structure). It deliberately reimplements the OpenType `glyf` decoding rules instead of
 * sharing any production code, so a matching comparison is meaningful evidence.
 */
internal object GlyfOutlineParser {
    /** Parses every glyph of [glyf] using [loca] under [indexFormat]. */
    fun parse(glyf: ByteArray, loca: ByteArray, indexFormat: Int): List<GlyphSemantics> {
        val offsets = parseLoca(loca, indexFormat)
        val numGlyphs = offsets.size - 1
        return List(numGlyphs) { index -> parseGlyph(glyf, offsets[index], offsets[index + 1]) }
    }

    private fun parseLoca(loca: ByteArray, indexFormat: Int): LongArray {
        val entrySize = if (indexFormat == 0) 2 else 4
        val count = loca.size / entrySize
        return LongArray(count) { index ->
            if (indexFormat == 0) {
                readUInt16(loca, index * 2)!!.toLong() * 2L
            } else {
                readUInt32(loca, index * 4)!!.toLong()
            }
        }
    }

    private fun parseGlyph(glyf: ByteArray, start: Long, end: Long): GlyphSemantics {
        if (start == end) return EmptyGlyph
        val base = start.toInt()
        val numberOfContours = readInt16(glyf, base)!!
        val xMin = readInt16(glyf, base + 2)!!
        val yMin = readInt16(glyf, base + 4)!!
        val xMax = readInt16(glyf, base + 6)!!
        val yMax = readInt16(glyf, base + 8)!!
        return if (numberOfContours >= 0) {
            parseSimple(glyf, base, numberOfContours, xMin, yMin, xMax, yMax)
        } else {
            parseComposite(glyf, base, xMin, yMin, xMax, yMax)
        }
    }

    private fun parseSimple(
        glyf: ByteArray,
        base: Int,
        numberOfContours: Int,
        xMin: Int,
        yMin: Int,
        xMax: Int,
        yMax: Int,
    ): SimpleGlyph {
        val endPts = IntArray(numberOfContours) { readUInt16(glyf, base + 10 + it * 2)!!.toInt() }
        var cursor = base + 10 + numberOfContours * 2
        val instructionLength = readUInt16(glyf, cursor)!!.toInt()
        cursor += 2
        val instructions = glyf.copyOfRange(cursor, cursor + instructionLength)
        cursor += instructionLength

        val pointCount = if (endPts.isEmpty()) 0 else endPts.last() + 1
        val rawFlags = ArrayList<Int>(pointCount)
        while (rawFlags.size < pointCount) {
            val flag = glyf[cursor].toInt() and 0xFF
            cursor++
            rawFlags += flag
            if (flag and 0x08 != 0) {
                val repeat = glyf[cursor].toInt() and 0xFF
                cursor++
                repeat(repeat) { rawFlags += flag }
            }
        }
        val flags = rawFlags.toIntArray()

        val xs = IntArray(pointCount)
        var x = 0
        for (index in 0 until pointCount) {
            val flag = flags[index]
            val delta = when {
                flag and 0x02 != 0 -> {
                    val magnitude = glyf[cursor].toInt() and 0xFF
                    cursor++
                    if (flag and 0x10 != 0) magnitude else -magnitude
                }
                flag and 0x10 != 0 -> 0
                else -> {
                    val value = readInt16(glyf, cursor)!!
                    cursor += 2
                    value
                }
            }
            x += delta
            xs[index] = x
        }

        val ys = IntArray(pointCount)
        var y = 0
        for (index in 0 until pointCount) {
            val flag = flags[index]
            val delta = when {
                flag and 0x04 != 0 -> {
                    val magnitude = glyf[cursor].toInt() and 0xFF
                    cursor++
                    if (flag and 0x20 != 0) magnitude else -magnitude
                }
                flag and 0x20 != 0 -> 0
                else -> {
                    val value = readInt16(glyf, cursor)!!
                    cursor += 2
                    value
                }
            }
            y += delta
            ys[index] = y
        }

        val points = List(pointCount) { Point(xs[it], ys[it], flags[it] and 0x01 != 0) }
        return SimpleGlyph(points, endPts.toList(), instructions.toList(), xMin, yMin, xMax, yMax)
    }

    private fun parseComposite(
        glyf: ByteArray,
        base: Int,
        xMin: Int,
        yMin: Int,
        xMax: Int,
        yMax: Int,
    ): CompositeGlyph {
        var cursor = base + 10
        val components = ArrayList<CompositeComponent>()
        var flags: Int
        do {
            flags = readUInt16(glyf, cursor)!!.toInt()
            val glyphIndex = readUInt16(glyf, cursor + 2)!!.toInt()
            cursor += 4
            val xyValues = flags and 0x0002 != 0
            val words = flags and 0x0001 != 0
            var arg1 = 0
            var arg2 = 0
            if (xyValues) {
                if (words) {
                    arg1 = readInt16(glyf, cursor)!!
                    arg2 = readInt16(glyf, cursor + 2)!!
                    cursor += 4
                } else {
                    arg1 = glyf[cursor].toInt()
                    arg2 = glyf[cursor + 1].toInt()
                    cursor += 2
                }
            } else {
                cursor += if (words) 4 else 2
            }
            val transform = IntArray(4)
            when {
                flags and 0x0008 != 0 -> {
                    transform[0] = readInt16(glyf, cursor)!!
                    transform[3] = transform[0]
                    cursor += 2
                }
                flags and 0x0040 != 0 -> {
                    transform[0] = readInt16(glyf, cursor)!!
                    transform[3] = readInt16(glyf, cursor + 2)!!
                    cursor += 4
                }
                flags and 0x0080 != 0 -> {
                    for (index in 0 until 4) transform[index] = readInt16(glyf, cursor + index * 2)!!
                    cursor += 8
                }
            }
            components += CompositeComponent(
                glyphIndex = glyphIndex,
                x = arg1,
                y = arg2,
                xyValues = xyValues,
                semanticFlags = flags and COMPONENT_SEMANTIC_MASK,
                transform = transform.toList(),
            )
        } while (flags and 0x0020 != 0)
        return CompositeGlyph(components, xMin, yMin, xMax, yMax)
    }

    /** Reads one big-endian tagged table from a reassembled SFNT, or `null` when absent. */
    fun sfntTable(font: ByteArray, tag: String): ByteArray? {
        val numTables = readUInt16(font, 4)!!.toInt()
        for (index in 0 until numTables) {
            val record = 12 + 16 * index
            if (font.decodeAsciiTag(record) == tag) {
                val offset = readUInt32(font, record + 8)!!.toInt()
                val length = readUInt32(font, record + 12)!!.toInt()
                return font.copyOfRange(offset, offset + length)
            }
        }
        return null
    }

    /** Component flag bits that carry meaning after reconstruction; excludes byte-width encodings. */
    private const val COMPONENT_SEMANTIC_MASK: Int = 0x0002 or 0x0004 or 0x0008 or 0x0040 or 0x0080 or
        0x0200 or 0x0400 or 0x0800 or 0x1000

    private inline fun repeat(times: Int, action: () -> Unit) {
        for (index in 0 until times) action()
    }
}

/** Semantic form of one decoded glyph, comparable by data-class equality. */
internal sealed interface GlyphSemantics

internal data object EmptyGlyph : GlyphSemantics

internal data class Point(val x: Int, val y: Int, val onCurve: Boolean)

internal data class SimpleGlyph(
    val points: List<Point>,
    val endPtsOfContours: List<Int>,
    val instructions: List<Byte>,
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
) : GlyphSemantics

internal data class CompositeComponent(
    val glyphIndex: Int,
    val x: Int,
    val y: Int,
    val xyValues: Boolean,
    val semanticFlags: Int,
    val transform: List<Int>,
)

internal data class CompositeGlyph(
    val components: List<CompositeComponent>,
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
) : GlyphSemantics
