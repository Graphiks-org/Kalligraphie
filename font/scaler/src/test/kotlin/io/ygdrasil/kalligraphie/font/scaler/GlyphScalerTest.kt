package io.ygdrasil.kalligraphie.font.scaler

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GlyphScalerTest {

    @Test
    fun `scaler produces deterministic glyph outline for Liberation Sans 'A' at 32px`() {
        val fontBytes = javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")!!
            .readBytes()
        val scaler = GlyphScaler.fromBytes(fontBytes)

        val codepoint = 'A'.code
        val glyph = scaler.scaleGlyph(
            glyphId = scaler.glyphIdForCodepoint(codepoint)!!,
            size = 32.0f,
            sourceCodepoint = codepoint,
        )

        assertNotNull(glyph)
        assertEquals(codepoint, glyph.sourceCodepoint)
        assertEquals(32.0f, glyph.size)
        assertTrue(glyph.advanceWidth > 0f)
        assertTrue(glyph.bounds.width > 0f && glyph.bounds.height > 0f)
        // Deterministic: same input -> same output (hash-stable)
        val secondRun = scaler.scaleGlyph(scaler.glyphIdForCodepoint(codepoint)!!, 32.0f, sourceCodepoint = codepoint)
        assertEquals(glyph.checksum(), secondRun.checksum())
    }

    @Test
    fun `scaler refuses unknown glyph id with stable diagnostic`() {
        val fontBytes = javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")!!
            .readBytes()
        val scaler = GlyphScaler.fromBytes(fontBytes)

        val result = scaler.scaleGlyphOrDiagnostic(glyphId = 99999, size = 32.0f)

        assertTrue(result is GlyphScaleResult.Unsupported)
        assertEquals("font.scaler.glyph_id_out_of_range", (result as GlyphScaleResult.Unsupported).code)
    }

    @Test
    fun `scaler reads COLRv0 CPAL header offsets from Skia fixture`() {
        val fontBytes = javaClass.getResourceAsStream("/fonts/skia/colr.ttf")!!.readBytes()
        val scaler = GlyphScaler.fromBytes(fontBytes)

        assertTrue(scaler.hasAnyColorTable)
        val colorLayers = scaler.getGlyphRepresentation(glyphId = 2, fontSize = 96f)
            as? GlyphRepresentation.ColorLayers
        assertNotNull(colorLayers)
        assertEquals(
            listOf(
                ColorLayerEntry(glyphId = 7, paletteColorArgb = 0xFFFF2A2A.toInt()),
                ColorLayerEntry(glyphId = 8, paletteColorArgb = 0xFF000000.toInt()),
            ),
            colorLayers!!.layers,
        )
        assertEquals(0xFFFF2A2A.toInt(), scaler.resolveCpalColor(0))
    }

    @Test
    fun `scaler honors a non-zero CPAL color record index for palette zero`() {
        val fontBytes = javaClass.getResourceAsStream("/fonts/skia/colr.ttf")!!.readBytes()
        val cpalOffset = sfntTableOffset(fontBytes, "CPAL")
        val colorRecordsOffset = readU32(fontBytes, cpalOffset + 8)

        // Turn the fixture into a CPAL v0 table with colorRecordIndices[0] == 1.
        // It keeps two palette entries so the three existing color records remain in bounds.
        writeU16(fontBytes, cpalOffset + 2, 2)
        writeU16(fontBytes, cpalOffset + 12, 1)
        val expectedFirstColor = readBgraArgb(fontBytes, cpalOffset + colorRecordsOffset + 4)

        val scaler = GlyphScaler.fromBytes(fontBytes)

        assertEquals(expectedFirstColor, scaler.resolveCpalColor(0))
        val colorLayers = scaler.getGlyphRepresentation(glyphId = 2, fontSize = 96f)
            as? GlyphRepresentation.ColorLayers
        assertNotNull(colorLayers)
        assertEquals(expectedFirstColor, colorLayers!!.layers.first().paletteColorArgb)
    }

    private fun sfntTableOffset(bytes: ByteArray, expectedTag: String): Int {
        val tableCount = readU16(bytes, 4)
        repeat(tableCount) { index ->
            val offset = 12 + index * 16
            val tag = String(bytes, offset, 4, Charsets.ISO_8859_1)
            if (tag == expectedTag) return readU32(bytes, offset + 8)
        }
        error("Missing $expectedTag table")
    }

    private fun readBgraArgb(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 3].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset].toInt() and 0xFF)

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
