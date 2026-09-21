package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.PixelFormat

class GlyphSheetScenesTest {
    @Test
    fun rendersATwoGlyphLatinSheet() {
        val first = GlyphSheetScenes.outlineSheet(LIBERATION_SANS, listOf(0x41, 0x42), 32.0)
        assertEquals(PixelFormat.ALPHA_8, first.format)
        assertTrue(first.width > 0 && first.height > 0, "a sheet must contain pixel data")
        val second = GlyphSheetScenes.outlineSheet(LIBERATION_SANS, listOf(0x41, 0x42), 32.0)
        assertContentEquals(first.copyCanonicalBytes(), second.copyCanonicalBytes())
    }

    @Test
    fun failsWhenACodepointHasNoGlyph() {
        val failure = assertFailsWith<IllegalStateException> {
            GlyphSheetScenes.outlineSheet(LIBERATION_SANS, listOf(0x10FFFF), 32.0)
        }
        assertTrue(failure.message.orEmpty().contains("U+10FFFF"), "the failing codepoint must be named")
    }

    @Test
    fun rendersAnEmojiSheetFromThePaintRoute() {
        val image = GlyphSheetScenes.paintSheet(EMOJI_TWO_COLR_V0, listOf(0x1F600, 0x1F601), 64.0, 0)
        assertEquals(PixelFormat.RGBA_8888, image.format)
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun wrapsAcrossRows() {
        val oneRow = GlyphSheetScenes.outlineSheet(LIBERATION_SANS, (0x41..0x50).toList(), 32.0)
        val twoRows = GlyphSheetScenes.outlineSheet(LIBERATION_SANS, (0x41..0x50).toList() + 0x52, 32.0)
        assertTrue(oneRow.width > 0 && oneRow.height > 0, "a sheet must be non-empty")
        assertEquals(oneRow.width, twoRows.width, "16 glyphs fill one row; the 17th must wrap instead of widening it")
        assertEquals(
            2 * oneRow.height,
            twoRows.height,
            "the 17th glyph must keep the shared cell envelope so the sheet is exactly two cell rows tall",
        )
    }

    @Test
    fun paintSheetIsDeterministic() {
        val first = GlyphSheetScenes.paintSheet(EMOJI_TWO_COLR_V0, listOf(0x1F600, 0x1F601), 64.0, 0)
        val second = GlyphSheetScenes.paintSheet(EMOJI_TWO_COLR_V0, listOf(0x1F600, 0x1F601), 64.0, 0)
        assertContentEquals(first.copyCanonicalBytes(), second.copyCanonicalBytes())
    }

    private companion object {
        const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"
        const val EMOJI_TWO_COLR_V0 = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"
    }
}
