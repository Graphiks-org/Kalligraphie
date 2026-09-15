package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlyphSheetDumpsTest {
    @Test
    fun rendersATwoGlyphLatinSheet() {
        val first = GlyphSheetDumps.outlineSheet(
            fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
            codepoints = listOf(0x41, 0x42),
            pixelsPerEm = 32.0,
        )
        assertTrue(first.bytes.size > 13, "a sheet must contain pixel data")
        assertEquals("P5\n", first.bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1))
        val second = GlyphSheetDumps.outlineSheet(
            fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
            codepoints = listOf(0x41, 0x42),
            pixelsPerEm = 32.0,
        )
        assertContentEquals(first.bytes, second.bytes)
    }

    @Test
    fun failsWhenACodepointHasNoGlyph() {
        val failure = assertFailsWith<IllegalStateException> {
            GlyphSheetDumps.outlineSheet(
                fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
                codepoints = listOf(0x10FFFF),
                pixelsPerEm = 32.0,
            )
        }
        assertTrue(failure.message.orEmpty().contains("U+10FFFF"), "the failing codepoint must be named")
    }

    @Test
    fun rendersAnEmojiSheetFromThePaintRoute() {
        val dump = GlyphSheetDumps.paintSheet(
            fontPath = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf",
            codepoints = listOf(0x1F600, 0x1F601),
            pixelsPerEm = 64.0,
            paletteIndex = 0,
        )
        assertEquals("P6\n", dump.bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1))
        assertTrue(dump.bytes.size > 13)
    }

    @Test
    fun wrapsAcrossRows() {
        val oneRow = GlyphSheetDumps.outlineSheet(
            fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
            codepoints = (0x41..0x50).toList(),
            pixelsPerEm = 32.0,
        )
        val twoRows = GlyphSheetDumps.outlineSheet(
            fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
            codepoints = (0x41..0x50).toList() + 0x52,
            pixelsPerEm = 32.0,
        )
        val (oneRowWidth, oneRowHeight) = pgmSize(oneRow.bytes)
        val (twoRowsWidth, twoRowsHeight) = pgmSize(twoRows.bytes)
        assertTrue(oneRowWidth > 0 && oneRowHeight > 0, "a sheet must be non-empty")
        assertEquals(oneRowWidth, twoRowsWidth, "16 glyphs fill one row; the 17th must wrap instead of widening it")
        assertEquals(
            2 * oneRowHeight,
            twoRowsHeight,
            "the 17th glyph must keep the shared cell envelope so the sheet is exactly two cell rows tall",
        )
    }

    @Test
    fun paintSheetIsDeterministic() {
        val first = GlyphSheetDumps.paintSheet(
            fontPath = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf",
            codepoints = listOf(0x1F600, 0x1F601),
            pixelsPerEm = 64.0,
            paletteIndex = 0,
        )
        val second = GlyphSheetDumps.paintSheet(
            fontPath = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf",
            codepoints = listOf(0x1F600, 0x1F601),
            pixelsPerEm = 64.0,
            paletteIndex = 0,
        )
        assertContentEquals(first.bytes, second.bytes)
    }

    @Test
    fun rendersTheBitmapStrike() {
        val dump = GlyphSheetDumps.bitmapDump(
            fontPath = "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf",
            codepoint = 0x1F600,
        )
        assertEquals("P6\n", dump.bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1))
        assertTrue(dump.bytes.size > 13)
    }

    private fun pgmSize(bytes: ByteArray): Pair<Int, Int> {
        val header = bytes.copyOfRange(0, minOf(64, bytes.size)).toString(Charsets.ISO_8859_1)
        val match = checkNotNull(Regex("^P5\\n(\\d+) (\\d+)\\n").find(header)) {
            "the sheet must start with a P5 header"
        }
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }
}
