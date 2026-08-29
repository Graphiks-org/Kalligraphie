package org.graphiks.kalligraphie

internal fun minimalTrueTypeFont(
    glyphCount: Int,
    indexToLocFormat: Int = 0,
    tables: Map<String, ByteArray>,
): ByteArray {
    val requiredTables = linkedMapOf(
        "head" to headTable(unitsPerEm = 2048, indexToLocFormat = indexToLocFormat),
        "maxp" to maxpTable(glyphCount = glyphCount),
        "name" to nameTable(),
        "cmap" to byteArrayOf(0, 0, 0, 0),
        "hhea" to hheaTable(numberOfHMetrics = maxOf(glyphCount, 1)),
        "hmtx" to hmtxTable(glyphCount = maxOf(glyphCount, 1)),
        "loca" to (tables["loca"] ?: byteArrayOf(0, 0, 0, 0)),
        "glyf" to (tables["glyf"] ?: byteArrayOf(0, 0, 0, 0)),
    )
    for ((tag, bytes) in tables) {
        requiredTables[tag] = bytes
    }

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
    fontBytes.writeUInt16(6, 0)
    fontBytes.writeUInt16(8, 0)
    fontBytes.writeUInt16(10, 0)

    var directoryOffset = 12
    for (tag in tableTags) {
        val table = requiredTables.getValue(tag)
        val tableOffset = offsets.getValue(tag)
        fontBytes.writeTag(directoryOffset, tag)
        fontBytes.writeUInt32(directoryOffset + 4, 0)
        fontBytes.writeUInt32(directoryOffset + 8, tableOffset)
        fontBytes.writeUInt32(directoryOffset + 12, table.size)
        table.copyInto(fontBytes, destinationOffset = tableOffset)
        directoryOffset += 16
    }
    return fontBytes
}

internal fun locaFormat0(vararg offsets: Int): ByteArray =
    ByteArray(offsets.size * 2).also { bytes ->
        offsets.forEachIndexed { index, offset -> bytes.writeUInt16(index * 2, offset / 2) }
    }

internal fun locaFormat1(vararg offsets: Int): ByteArray =
    ByteArray(offsets.size * 4).also { bytes ->
        offsets.forEachIndexed { index, offset -> bytes.writeUInt32(index * 4, offset) }
    }

internal fun compositeGlyphSelfCycle(): ByteArray =
    ByteArray(18).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeInt16(2, 0)
        bytes.writeInt16(4, 0)
        bytes.writeInt16(6, 0)
        bytes.writeInt16(8, 0)
        bytes.writeUInt16(10, 0x0003)
        bytes.writeUInt16(12, 0)
        bytes.writeInt16(14, 0)
        bytes.writeInt16(16, 0)
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
        bytes.writeUInt16(30, 8)
    }

private fun hheaTable(numberOfHMetrics: Int): ByteArray =
    ByteArray(36).also { bytes ->
        bytes.writeUInt16(34, numberOfHMetrics)
    }

private fun hmtxTable(glyphCount: Int): ByteArray =
    ByteArray(glyphCount * 4).also { bytes ->
        repeat(glyphCount) { index ->
            bytes.writeUInt16(index * 4, 1000)
            bytes.writeInt16(index * 4 + 2, 0)
        }
    }

private fun nameTable(): ByteArray {
    val family = "Test Family".encodeUtf16Be()
    val style = "Regular".encodeUtf16Be()
    val stringOffset = 30
    return ByteArray(stringOffset + family.size + style.size).also { bytes ->
        bytes.writeUInt16(0, 0)
        bytes.writeUInt16(2, 2)
        bytes.writeUInt16(4, stringOffset)
        bytes.writeNameRecord(6, nameId = 1, length = family.size, textOffset = 0)
        bytes.writeNameRecord(18, nameId = 2, length = style.size, textOffset = family.size)
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
            val value = char.code
            bytes[index * 2] = (value ushr 8).toByte()
            bytes[index * 2 + 1] = value.toByte()
        }
    }

private fun ByteArray.writeTag(offset: Int, tag: String) {
    tag.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
}

internal fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.writeInt16(offset: Int, value: Int) {
    writeUInt16(offset, value and 0xFFFF)
}

internal fun ByteArray.writeUInt32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
