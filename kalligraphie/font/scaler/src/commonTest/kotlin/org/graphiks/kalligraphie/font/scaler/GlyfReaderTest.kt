package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GlyfReaderTest {
    @Test
    fun rejectsLocaOffsetPastGlyfLength() {
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, 8),
                    "glyf" to ByteArray(4),
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.loca.out-of-range", failure.error.code)
    }

    @Test
    fun rejectsTruncatedGlyphHeaderInsideValidLocaRange() {
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, 8),
                    "glyf" to ByteArray(8),
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.glyf.truncated", failure.error.code)
    }

    @Test
    fun rejectsCompositeThatReentersTheActiveGlyphPath() {
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, 18),
                    "glyf" to compositeGlyphSelfCycle(),
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.glyf.composite-cycle", failure.error.code)
    }

    private fun parseFont(bytes: ByteArray): ParsedFont =
        ParsedFont(
            bytes = bytes,
            font = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
                SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("glyf-test.ttf"))),
            ).value,
        )

    private fun outlineProfile(): OutlineProfile =
        OutlineProfile(
            maxBytes = 4096,
            maxContours = 32,
            maxPoints = 256,
            maxCompositeDepth = 4,
            maxCompositeComponents = 16,
        )
}

private data class ParsedFont(
    val bytes: ByteArray,
    val font: ParsedTrueTypeFont,
)

private fun minimalTrueTypeFont(
    glyphCount: Int,
    indexToLocFormat: Int = 0,
    tables: Map<String, ByteArray>,
): ByteArray {
    val requiredTables = linkedMapOf(
        "head" to headTable(unitsPerEm = 2048, indexToLocFormat = indexToLocFormat),
        "maxp" to maxpTable(glyphCount = glyphCount),
        "name" to nameTable(),
        "cmap" to byteArrayOf(0, 0, 0, 0),
        "hhea" to ByteArray(36).also { it.writeUInt16(34, maxOf(glyphCount, 1)) },
        "hmtx" to ByteArray(maxOf(glyphCount, 1) * 4),
        "loca" to tables.getValue("loca"),
        "glyf" to tables.getValue("glyf"),
    )
    val tableTags = requiredTables.keys.toList()
    val directorySize = 12 + tableTags.size * 16
    var nextOffset = directorySize
    val offsets = LinkedHashMap<String, Int>()
    for (tag in tableTags) {
        offsets[tag] = nextOffset
        nextOffset += requiredTables.getValue(tag).size
    }
    val fontBytes = ByteArray(nextOffset)
    fontBytes.writeUInt32(0, 0x00010000)
    fontBytes.writeUInt16(4, tableTags.size)
    var directoryOffset = 12
    for (tag in tableTags) {
        val table = requiredTables.getValue(tag)
        val tableOffset = offsets.getValue(tag)
        fontBytes.writeTag(directoryOffset, tag)
        fontBytes.writeUInt32(directoryOffset + 8, tableOffset)
        fontBytes.writeUInt32(directoryOffset + 12, table.size)
        table.copyInto(fontBytes, destinationOffset = tableOffset)
        directoryOffset += 16
    }
    return fontBytes
}

private fun locaFormat0(vararg offsets: Int): ByteArray =
    ByteArray(offsets.size * 2).also { bytes ->
        offsets.forEachIndexed { index, offset -> bytes.writeUInt16(index * 2, offset / 2) }
    }

private fun compositeGlyphSelfCycle(): ByteArray =
    ByteArray(18).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0003)
        bytes.writeUInt16(12, 0)
    }

private fun headTable(unitsPerEm: Int, indexToLocFormat: Int): ByteArray =
    ByteArray(54).also { bytes ->
        bytes.writeUInt16(18, unitsPerEm)
        bytes.writeInt16(50, indexToLocFormat)
    }

private fun maxpTable(glyphCount: Int): ByteArray =
    ByteArray(32).also { bytes ->
        bytes.writeUInt32(0, 0x00010000)
        bytes.writeUInt16(4, glyphCount)
        bytes.writeUInt16(6, 128)
        bytes.writeUInt16(8, 16)
        bytes.writeUInt16(10, 128)
        bytes.writeUInt16(12, 16)
        bytes.writeUInt16(14, 2)
        bytes.writeUInt16(28, 8)
    }

private fun nameTable(): ByteArray {
    val family = "Test".encodeUtf16Be()
    val style = "Regular".encodeUtf16Be()
    val stringOffset = 30
    return ByteArray(stringOffset + family.size + style.size).also { bytes ->
        bytes.writeUInt16(2, 2)
        bytes.writeUInt16(4, stringOffset)
        bytes.writeNameRecord(6, 1, family.size, 0)
        bytes.writeNameRecord(18, 2, style.size, family.size)
        family.copyInto(bytes, destinationOffset = stringOffset)
        style.copyInto(bytes, destinationOffset = stringOffset + family.size)
    }
}

private fun ByteArray.writeNameRecord(recordOffset: Int, nameId: Int, length: Int, textOffset: Int) {
    writeUInt16(recordOffset, 3)
    writeUInt16(recordOffset + 2, 1)
    writeUInt16(recordOffset + 4, 0x0409)
    writeUInt16(recordOffset + 6, nameId)
    writeUInt16(recordOffset + 8, length)
    writeUInt16(recordOffset + 10, textOffset)
}

private fun String.encodeUtf16Be(): ByteArray =
    ByteArray(length * 2).also { bytes ->
        forEachIndexed { index, char ->
            bytes[index * 2] = (char.code ushr 8).toByte()
            bytes[index * 2 + 1] = char.code.toByte()
        }
    }

private fun ByteArray.writeTag(offset: Int, tag: String) {
    tag.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
}

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeInt16(offset: Int, value: Int) {
    writeUInt16(offset, value and 0xFFFF)
}

private fun ByteArray.writeUInt32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
