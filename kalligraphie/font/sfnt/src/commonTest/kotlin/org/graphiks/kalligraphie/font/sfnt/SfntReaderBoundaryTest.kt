package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SfntReaderBoundaryTest {
    @Test
    fun rejectsUnsupportedContainersWithTypedFailure() {
        val bytes = byteArrayOf(
            't'.code.toByte(),
            't'.code.toByte(),
            'c'.code.toByte(),
            'f'.code.toByte(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("unsupported.ttcf")))

        assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.UnsupportedContainer>(result.error)
    }

    @Test
    fun rejectsOutOfBoundsRequiredTablesWithTypedFailure() {
        val bytes = minimalTrueTypeFont(
            overrides = mapOf(
                "glyf" to TableBytes(bytes = byteArrayOf(1, 2, 3, 4), forcedOffset = 4096),
            ),
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("out-of-bounds.ttf")))

        assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.OutOfBounds>(result.error)
        assertEquals(FontDiagnosticLocation.Table("glyf"), result.error.location)
    }

    @Test
    fun ignoresNonUnicodeWindowsNameRecordsWhenChoosingEnglishNames() {
        val bytes = minimalTrueTypeFont(
            nameRecords = listOf(
                nameRecord(platformId = 3, encodingId = 0, languageId = 0x0409, nameId = 1, text = "Wrong Family"),
                nameRecord(platformId = 3, encodingId = 0, languageId = 0x0409, nameId = 2, text = "Wrong Style"),
                nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = "Right Family"),
                nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 2, text = "Right Style"),
            ),
        )

        val result = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("unicode-names.ttf"))),
        )

        assertEquals("Right Family", result.value.metadata.familyName)
        assertEquals("Right Style", result.value.metadata.styleName)
    }
}

private data class NameRecord(
    val platformId: Int,
    val encodingId: Int,
    val languageId: Int,
    val nameId: Int,
    val text: String,
)

private data class TableBytes(
    val bytes: ByteArray,
    val forcedOffset: Int? = null,
)

private fun minimalTrueTypeFont(
    nameRecords: List<NameRecord> = listOf(
        nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 1, text = "Test Family"),
        nameRecord(platformId = 3, encodingId = 1, languageId = 0x0409, nameId = 2, text = "Regular"),
    ),
    overrides: Map<String, TableBytes> = emptyMap(),
): ByteArray {
    val tables = linkedMapOf(
        "head" to TableBytes(headTable(unitsPerEm = 2048, indexToLocFormat = 0)),
        "maxp" to TableBytes(maxpTable(glyphCount = 3)),
        "name" to TableBytes(nameTable(nameRecords)),
        "cmap" to TableBytes(byteArrayOf(0, 0, 0, 0)),
        "hhea" to TableBytes(byteArrayOf(0, 0, 0, 0)),
        "hmtx" to TableBytes(byteArrayOf(0, 0, 0, 0)),
        "loca" to TableBytes(byteArrayOf(0, 0, 0, 0)),
        "glyf" to TableBytes(byteArrayOf(0, 0, 0, 0)),
    )
    for ((tag, table) in overrides) {
        tables[tag] = table
    }

    val orderedTags = tables.keys.toList()
    val directorySize = 12 + orderedTags.size * 16
    val assignedOffsets = LinkedHashMap<String, Int>()
    var nextOffset = directorySize
    for (tag in orderedTags) {
        val override = tables.getValue(tag).forcedOffset
        if (override != null) {
            assignedOffsets[tag] = override
        } else {
            assignedOffsets[tag] = nextOffset
            nextOffset += tables.getValue(tag).bytes.size
        }
    }

    val totalSize = assignedOffsets.entries.fold(directorySize) { acc, (tag, offset) ->
        val bytes = tables.getValue(tag).bytes
        if (tables.getValue(tag).forcedOffset == null) {
            maxOf(acc, offset + bytes.size)
        } else {
            acc
        }
    }
    val fontBytes = ByteArray(totalSize)
    fontBytes.writeUInt32(0, 0x00010000)
    fontBytes.writeUInt16(4, orderedTags.size)
    fontBytes.writeUInt16(6, 0)
    fontBytes.writeUInt16(8, 0)
    fontBytes.writeUInt16(10, 0)

    var directoryOffset = 12
    for (tag in orderedTags) {
        val table = tables.getValue(tag).bytes
        val tableOffset = assignedOffsets.getValue(tag)
        fontBytes.writeTag(directoryOffset, tag)
        fontBytes.writeUInt32(directoryOffset + 4, 0)
        fontBytes.writeUInt32(directoryOffset + 8, tableOffset)
        fontBytes.writeUInt32(directoryOffset + 12, table.size)
        if (tableOffset + table.size <= fontBytes.size) {
            table.copyInto(fontBytes, destinationOffset = tableOffset)
        }
        directoryOffset += 16
    }
    return fontBytes
}

private fun headTable(unitsPerEm: Int, indexToLocFormat: Int): ByteArray =
    ByteArray(54).also { bytes ->
        bytes.writeUInt16(18, unitsPerEm)
        bytes.writeInt16(50, indexToLocFormat)
    }

private fun maxpTable(glyphCount: Int): ByteArray =
    ByteArray(6).also { bytes ->
        bytes.writeUInt16(4, glyphCount)
    }

private fun nameTable(records: List<NameRecord>): ByteArray {
    val encodedStrings = records.map { it.text.encodeUtf16Be() }
    val stringOffset = 6 + records.size * 12
    val totalStringBytes = encodedStrings.sumOf { it.size }
    val bytes = ByteArray(stringOffset + totalStringBytes)
    bytes.writeUInt16(0, 0)
    bytes.writeUInt16(2, records.size)
    bytes.writeUInt16(4, stringOffset)

    var recordOffset = 6
    var textOffset = 0
    for ((index, record) in records.withIndex()) {
        val encoded = encodedStrings[index]
        bytes.writeUInt16(recordOffset, record.platformId)
        bytes.writeUInt16(recordOffset + 2, record.encodingId)
        bytes.writeUInt16(recordOffset + 4, record.languageId)
        bytes.writeUInt16(recordOffset + 6, record.nameId)
        bytes.writeUInt16(recordOffset + 8, encoded.size)
        bytes.writeUInt16(recordOffset + 10, textOffset)
        encoded.copyInto(bytes, destinationOffset = stringOffset + textOffset)
        textOffset += encoded.size
        recordOffset += 12
    }
    return bytes
}

private fun nameRecord(
    platformId: Int,
    encodingId: Int,
    languageId: Int,
    nameId: Int,
    text: String,
): NameRecord = NameRecord(platformId, encodingId, languageId, nameId, text)

private fun String.encodeUtf16Be(): ByteArray =
    ByteArray(length * 2).also { bytes ->
        forEachIndexed { index, char ->
            val value = char.code
            bytes[index * 2] = (value ushr 8).toByte()
            bytes[index * 2 + 1] = value.toByte()
        }
    }

private fun ByteArray.writeTag(offset: Int, tag: String) {
    tag.forEachIndexed { index, char ->
        this[offset + index] = char.code.toByte()
    }
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
