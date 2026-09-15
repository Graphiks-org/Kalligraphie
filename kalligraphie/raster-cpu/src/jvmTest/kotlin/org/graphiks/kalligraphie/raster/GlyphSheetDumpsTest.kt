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
            name = "sheet-test.pgm",
            fontPath = "/fonts/liberation/LiberationSans-Regular.ttf",
            codepoints = listOf(0x41, 0x42),
            pixelsPerEm = 32.0,
        )
        assertTrue(first.bytes.size > 13, "a sheet must contain pixel data")
        assertEquals("P5\n", first.bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1))
        val second = GlyphSheetDumps.outlineSheet(
            name = "sheet-test.pgm",
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
                name = "sheet-test.pgm",
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
            name = "sheet-emoji-test.ppm",
            fontPath = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf",
            codepoints = listOf(0x1F600, 0x1F601),
            pixelsPerEm = 64.0,
            paletteIndex = 0,
        )
        assertEquals("P6\n", dump.bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1))
        assertTrue(dump.bytes.size > 13)
    }
}
