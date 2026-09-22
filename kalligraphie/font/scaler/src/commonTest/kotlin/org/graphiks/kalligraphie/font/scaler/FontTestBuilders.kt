package org.graphiks.kalligraphie.font.scaler

internal fun minimalTrueTypeFont(
    glyphCount: Int,
    indexToLocFormat: Int = 0,
    maxPoints: Int = 128,
    maxContours: Int = 16,
    maxCompositePoints: Int = 128,
    maxCompositeContours: Int = 16,
    maxComponentElements: Int = 8,
    maxComponentDepth: Int = 8,
    tables: Map<String, ByteArray>,
    extraTables: Map<String, ByteArray> = emptyMap(),
): ByteArray {
    val requiredTables = linkedMapOf(
        "head" to headTable(unitsPerEm = 2048, indexToLocFormat = indexToLocFormat),
        "maxp" to maxpTable(
            glyphCount = glyphCount,
            maxPoints = maxPoints,
            maxContours = maxContours,
            maxCompositePoints = maxCompositePoints,
            maxCompositeContours = maxCompositeContours,
            maxComponentElements = maxComponentElements,
            maxComponentDepth = maxComponentDepth,
        ),
        "name" to nameTable(),
        "cmap" to byteArrayOf(0, 0, 0, 0),
        "hhea" to ByteArray(36).also { it.writeUInt16(34, maxOf(glyphCount, 1)) },
        "hmtx" to ByteArray(maxOf(glyphCount, 1) * 4),
        "loca" to tables.getValue("loca"),
        "glyf" to tables.getValue("glyf"),
    )
    requiredTables.putAll(extraTables)
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

internal fun locaFormat0(vararg offsets: Int): ByteArray =
    ByteArray(offsets.size * 2).also { bytes ->
        offsets.forEachIndexed { index, offset -> bytes.writeUInt16(index * 2, offset / 2) }
    }

internal fun hhea(
    numberOfHMetrics: Int,
    ascender: Int = 1160,
    descender: Int = -288,
    lineGap: Int = 0,
): ByteArray =
    ByteArray(36).also { bytes ->
        bytes.writeInt16(4, ascender)
        bytes.writeInt16(6, descender)
        bytes.writeInt16(8, lineGap)
        bytes.writeUInt16(34, numberOfHMetrics)
    }

internal fun hmtx(longMetrics: List<Pair<Int, Int>>): ByteArray =
    ByteArray(longMetrics.size * 4).also { bytes ->
        longMetrics.forEachIndexed { index, (advance, bearing) ->
            bytes.writeUInt16(index * 4, advance)
            bytes.writeInt16(index * 4 + 2, bearing)
        }
    }

internal fun vhea(numberOfLongVerMetrics: Int): ByteArray =
    ByteArray(36).also { bytes -> bytes.writeUInt16(34, numberOfLongVerMetrics) }

internal fun vmtx(longMetrics: List<Pair<Int, Int>>): ByteArray =
    ByteArray(longMetrics.size * 4).also { bytes ->
        longMetrics.forEachIndexed { index, (advance, bearing) ->
            bytes.writeUInt16(index * 4, advance)
            bytes.writeInt16(index * 4 + 2, bearing)
        }
    }

/**
 * A one-component composite whose single component sets `USE_MY_METRICS`, so a metrics consumer
 * must redirect the composite's `hmtx` entry (and its varied metric) to [componentGlyphId].
 *
 * The flags are `ARG_1_AND_2_ARE_WORDS` (0x0001) | `ARGS_ARE_XY_VALUES` (0x0002) |
 * `USE_MY_METRICS` (0x0200). `ARG_1_AND_2_ARE_WORDS` is required because the offsets below are
 * written as int16.
 */
internal fun compositeGlyphWithMetricsSource(componentGlyphId: Int): ByteArray =
    ByteArray(18).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0001 or 0x0002 or 0x0200)
        bytes.writeUInt16(12, componentGlyphId)
        bytes.writeInt16(14, 0)
        bytes.writeInt16(16, 0)
    }

internal fun singlePointGlyph(x: Int, y: Int): ByteArray =
    ByteArray(20).also { bytes ->
        bytes.writeInt16(0, 1)
        bytes.writeInt16(2, x)
        bytes.writeInt16(4, y)
        bytes.writeInt16(6, x)
        bytes.writeInt16(8, y)
        bytes.writeUInt16(10, 0)
        bytes.writeUInt16(12, 0)
        bytes[14] = 0x01
        bytes.writeInt16(15, x)
        bytes.writeInt16(17, y)
    }

internal fun singleAxisFvarTable(): ByteArray =
    ByteArray(36).also { bytes ->
        bytes.writeUInt16(0, 1)
        bytes.writeUInt16(2, 0)
        bytes.writeUInt16(4, 16)
        bytes.writeUInt16(6, 2)
        bytes.writeUInt16(8, 1)
        bytes.writeUInt16(10, 20)
        bytes.writeUInt16(12, 0)
        bytes.writeUInt16(14, 0)
        bytes.writeTag(16, "wght")
        bytes.writeInt32Fixed(20, 100f)
        bytes.writeInt32Fixed(24, 100f)
        bytes.writeInt32Fixed(28, 900f)
        bytes.writeUInt16(32, 0)
        bytes.writeUInt16(34, 1)
    }

/**
 * A minimal `VARC` 1.0 header: major/minor version followed by the five offset fields
 * (`Coverage`, `MultiVarStore`, `ConditionList`, `AxisIndicesList`, `VarCompositeGlyphs`).
 *
 * The portable outline route never decodes the table — it detects the tag and reports the typed
 * `font.variation.varc-unsupported` failure — so the offsets are deliberately left at zero.
 */
internal fun varcTable(): ByteArray =
    ByteArray(24).also { bytes ->
        bytes.writeUInt16(0, 1)
        bytes.writeUInt16(2, 0)
    }

internal fun gvarTable(axisCount: Int, glyphRecords: List<ByteArray>): ByteArray {
    val headerSize = 20
    val offsetsSize = (glyphRecords.size + 1) * 2
    val glyphDataOffset = headerSize + offsetsSize
    fun evenSize(byteCount: Int): Int = (byteCount + 1) / 2 * 2
    val glyphDataSize = glyphRecords.sumOf { evenSize(it.size) }
    val bytes = ByteArray(glyphDataOffset + glyphDataSize)
    bytes.writeUInt16(0, 1)
    bytes.writeUInt16(2, 0)
    bytes.writeUInt16(4, axisCount)
    bytes.writeUInt16(6, 0)
    bytes.writeUInt32(8, 0)
    bytes.writeUInt16(12, glyphRecords.size)
    bytes.writeUInt16(14, 0)
    bytes.writeUInt32(16, glyphDataOffset)
    var cursor = 0
    glyphRecords.forEachIndexed { index, record ->
        bytes.writeUInt16(headerSize + index * 2, cursor / 2)
        record.copyInto(bytes, glyphDataOffset + cursor)
        cursor += evenSize(record.size)
    }
    bytes.writeUInt16(headerSize + glyphRecords.size * 2, cursor / 2)
    return bytes
}

internal fun gvarGlyphRecord(peak: List<Double>, xDeltas: IntArray, yDeltas: IntArray): ByteArray {
    require(peak.isNotEmpty()) { "gvar peak must declare at least one axis." }
    require(xDeltas.size == yDeltas.size) { "gvar x and y delta counts must match." }
    val axisCount = peak.size
    val headerSize = 4 + 4 + axisCount * 2
    val data = packedGvarDeltas(xDeltas) + packedGvarDeltas(yDeltas)
    val record = ByteArray((headerSize + data.size + 1) / 2 * 2)
    record.writeUInt16(0, 1)
    record.writeUInt16(2, headerSize)
    record.writeUInt16(4, data.size)
    record.writeUInt16(6, 0x8000)
    peak.forEachIndexed { axis, value ->
        record.writeInt16(8 + axis * 2, (value * 16_384.0).toInt())
    }
    data.copyInto(record, headerSize)
    return record
}

internal fun packedGvarDeltas(values: IntArray): ByteArray {
    val body = ArrayList<Byte>(values.size * 3)
    for (value in values) {
        when {
            value == 0 -> body += 0x80.toByte()
            value in -128..127 -> {
                body += 0x00
                body += value.toByte()
            }

            else -> {
                body += 0x40.toByte()
                body += (value ushr 8).toByte()
                body += value.toByte()
            }
        }
    }
    return body.toByteArray()
}

private fun headTable(unitsPerEm: Int, indexToLocFormat: Int): ByteArray =
    ByteArray(54).also { bytes ->
        bytes.writeUInt16(18, unitsPerEm)
        bytes.writeInt16(50, indexToLocFormat)
    }

private fun maxpTable(
    glyphCount: Int,
    maxPoints: Int,
    maxContours: Int,
    maxCompositePoints: Int,
    maxCompositeContours: Int,
    maxComponentElements: Int,
    maxComponentDepth: Int,
): ByteArray =
    ByteArray(32).also { bytes ->
        bytes.writeUInt32(0, 0x00010000)
        bytes.writeUInt16(4, glyphCount)
        bytes.writeUInt16(6, maxPoints)
        bytes.writeUInt16(8, maxContours)
        bytes.writeUInt16(10, maxCompositePoints)
        bytes.writeUInt16(12, maxCompositeContours)
        bytes.writeUInt16(14, 2)
        bytes.writeUInt16(28, maxComponentElements)
        bytes.writeUInt16(30, maxComponentDepth)
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

/**
 * The big-endian writers below stay file-private rather than `internal` because `GlyfReaderTest`
 * and `CmapReaderTest` each declare private writers with the same names; an `internal` declaration
 * here would collide with those siblings. The table builders above are `internal` because their
 * names are unique across the test sources.
 */
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

private fun ByteArray.writeInt32Fixed(offset: Int, value: Float) {
    writeUInt32(offset, (value * 65_536f).toInt())
}
