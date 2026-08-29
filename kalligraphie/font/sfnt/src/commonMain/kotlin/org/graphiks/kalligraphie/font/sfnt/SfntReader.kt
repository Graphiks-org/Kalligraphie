package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic

public object SfntReader {
    private val requiredTables = setOf("head", "maxp", "name", "cmap", "hhea", "hmtx", "loca", "glyf")

    public fun readMetadata(source: FontSource): FontOperationResult<ParsedTrueTypeFont> {
        val bytes = source.copyBytes()
        if (bytes.size < 12) {
            return failure(FontError.InvalidFontData("SFNT header is truncated."))
        }

        val scalerType = readUInt32(bytes, 0) ?: return failure(FontError.OutOfBounds("Could not read scaler type.", FontDiagnosticLocation.Source))
        val containerTag = bytes.decodeAsciiTag(0)
        if (scalerType != 0x00010000u && containerTag != "true") {
            val message = when (containerTag) {
                "ttcf", "OTTO", "typ1" -> "Unsupported SFNT container: $containerTag"
                else -> "Unsupported SFNT container."
            }
            return failure(FontError.UnsupportedContainer(message))
        }

        val numTables = readUInt16(bytes, 4) ?: return failure(FontError.OutOfBounds("Could not read table count.", FontDiagnosticLocation.Source))
        val directoryBytes = checkedRangeEnd(12, numTables.toInt() * 16, bytes.size)
            ?: return failure(FontError.OutOfBounds("SFNT directory exceeds source length.", FontDiagnosticLocation.Source))

        val records = LinkedHashMap<String, TableRecord>(numTables.toInt())
        val diagnostics = mutableListOf<FontDiagnostic>()
        var offset = 12
        repeat(numTables.toInt()) {
            val tag = bytes.decodeAsciiTag(offset)
            val tableOffset = readUInt32(bytes, offset + 8)?.toInt()
                ?: return failure(FontError.OutOfBounds("Could not read table offset for $tag.", FontDiagnosticLocation.Table(tag)))
            val tableLength = readUInt32(bytes, offset + 12)?.toInt()
                ?: return failure(FontError.OutOfBounds("Could not read table length for $tag.", FontDiagnosticLocation.Table(tag)))

            if (tag in requiredTables && records.containsKey(tag)) {
                diagnostics += FontDiagnostic(
                    code = "font.sfnt.duplicate-table",
                    severity = FontDiagnosticSeverity.ERROR,
                    location = FontDiagnosticLocation.Table(tag),
                    message = "Duplicate required table: $tag",
                )
            } else {
                records[tag] = TableRecord(tag, tableOffset, tableLength)
            }
            offset += 16
        }
        if (diagnostics.isNotEmpty()) {
            return failure(FontError.InvalidFontData("Duplicate required tables detected."), diagnostics)
        }
        if (directoryBytes > bytes.size) {
            return failure(FontError.OutOfBounds("SFNT directory exceeds source length.", FontDiagnosticLocation.Source))
        }

        for (tag in requiredTables.sorted()) {
            val record = records[tag] ?: return failure(FontError.MissingRequiredTable(tag))
            if (record.length == 0) {
                return failure(FontError.MissingRequiredTable(tag, "Required table $tag has zero length."))
            }
            if (checkedRangeEnd(record.offset, record.length, bytes.size) == null) {
                return failure(
                    FontError.OutOfBounds(
                        message = "Table $tag exceeds source length.",
                        location = FontDiagnosticLocation.Table(tag),
                    ),
                )
            }
        }

        val unitsPerEm = parseUnitsPerEm(bytes, records.getValue("head")) ?: return failure(
            FontError.InvalidFontData("head table is truncated.", FontDiagnosticLocation.Table("head")),
        )
        val indexToLocFormat = parseIndexToLocFormat(bytes, records.getValue("head")) ?: return failure(
            FontError.InvalidFontData("head table is truncated.", FontDiagnosticLocation.Table("head")),
        )
        val glyphCount = parseGlyphCount(bytes, records.getValue("maxp")) ?: return failure(
            FontError.InvalidFontData("maxp table is truncated.", FontDiagnosticLocation.Table("maxp")),
        )
        val names = parseNameTable(bytes, records.getValue("name")) ?: return failure(
            FontError.InvalidFontData("name table is invalid.", FontDiagnosticLocation.Table("name")),
        )

        return FontOperationResult.Success(
            ParsedTrueTypeFont(
                tableRecords = records,
                metadata = FontFaceMetadata(
                    familyName = names.familyName,
                    styleName = names.styleName,
                    unitsPerEm = unitsPerEm,
                    glyphCount = glyphCount,
                ),
                indexToLocFormat = indexToLocFormat,
            ),
        )
    }

    private fun parseUnitsPerEm(bytes: ByteArray, head: TableRecord): Int? {
        val table = slice(bytes, head) ?: return null
        return readUInt16(table, 18)?.toInt()
    }

    private fun parseIndexToLocFormat(bytes: ByteArray, head: TableRecord): Int? {
        val table = slice(bytes, head) ?: return null
        return readInt16(table, 50)
    }

    private fun parseGlyphCount(bytes: ByteArray, maxp: TableRecord): Int? {
        val table = slice(bytes, maxp) ?: return null
        return readUInt16(table, 4)?.toInt()
    }

    private fun parseNameTable(bytes: ByteArray, name: TableRecord): ParsedNames? {
        val table = slice(bytes, name) ?: return null
        val count = readUInt16(table, 2)?.toInt() ?: return null
        val stringOffset = readUInt16(table, 4)?.toInt() ?: return null
        val recordsEnd = checkedRangeEnd(6, count * 12, table.size) ?: return null
        if (stringOffset !in recordsEnd..table.size) {
            return null
        }

        var familyEnglish: String? = null
        var styleEnglish: String? = null
        var familyFallback: String? = null
        var styleFallback: String? = null

        var cursor = 6
        repeat(count) {
            val platformId = readUInt16(table, cursor)?.toInt() ?: return null
            val encodingId = readUInt16(table, cursor + 2)?.toInt() ?: return null
            val languageId = readUInt16(table, cursor + 4)?.toInt() ?: return null
            val nameId = readUInt16(table, cursor + 6)?.toInt() ?: return null
            val length = readUInt16(table, cursor + 8)?.toInt() ?: return null
            val offset = readUInt16(table, cursor + 10)?.toInt() ?: return null
            if (isUnicodeNameRecord(platformId, encodingId)) {
                val bytesStart = stringOffset + offset
                val bytesEnd = checkedRangeEnd(bytesStart, length, table.size) ?: return@repeat
                val value = decodeUtf16Be(table, bytesStart, bytesEnd) ?: return@repeat
                if (nameId == 1) {
                    if (isEnglishUnicodeNameRecord(platformId, languageId)) {
                        familyEnglish = familyEnglish ?: value
                    }
                    familyFallback = familyFallback ?: value
                }
                if (nameId == 2) {
                    if (isEnglishUnicodeNameRecord(platformId, languageId)) {
                        styleEnglish = styleEnglish ?: value
                    }
                    styleFallback = styleFallback ?: value
                }
            }
            cursor += 12
        }

        val familyName = familyEnglish ?: familyFallback ?: return null
        val styleName = styleEnglish ?: styleFallback ?: return null
        return ParsedNames(familyName, styleName)
    }

    private fun decodeUtf16Be(bytes: ByteArray, start: Int, end: Int): String? {
        if (((end - start) and 1) != 0) {
            return null
        }
        val chars = CharArray((end - start) / 2)
        var sourceIndex = start
        var targetIndex = 0
        while (sourceIndex < end) {
            val codeUnit = ((bytes[sourceIndex].toInt() and 0xFF) shl 8) or (bytes[sourceIndex + 1].toInt() and 0xFF)
            chars[targetIndex++] = codeUnit.toChar()
            sourceIndex += 2
        }
        return chars.concatToString()
    }

    private fun isUnicodeNameRecord(platformId: Int, encodingId: Int): Boolean =
        when (platformId) {
            0 -> encodingId in 0..6
            3 -> encodingId == 1 || encodingId == 10
            else -> false
        }

    private fun isEnglishUnicodeNameRecord(platformId: Int, languageId: Int): Boolean =
        when (platformId) {
            0 -> languageId == 0
            3 -> languageId == 0x0409
            else -> false
        }

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
}

public data class ParsedTrueTypeFont(
    val tableRecords: Map<String, TableRecord>,
    val metadata: FontFaceMetadata,
    val indexToLocFormat: Int,
)

public data class TableRecord(
    val tag: String,
    val offset: Int,
    val length: Int,
)

private data class ParsedNames(
    val familyName: String,
    val styleName: String,
)

public fun slice(bytes: ByteArray, record: TableRecord): ByteArray? {
    val end = checkedRangeEnd(record.offset, record.length, bytes.size) ?: return null
    return bytes.copyOfRange(record.offset, end)
}

public fun checkedRangeEnd(offset: Int, length: Int, sourceSize: Int): Int? {
    if (offset < 0 || length < 0 || offset > sourceSize) {
        return null
    }
    val end = offset.toLong() + length.toLong()
    if (end > sourceSize.toLong()) {
        return null
    }
    return end.toInt()
}

public fun readUInt16(bytes: ByteArray, offset: Int): UInt? {
    if (offset < 0 || offset + 1 >= bytes.size) {
        return null
    }
    return (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toUInt()
}

public fun readInt16(bytes: ByteArray, offset: Int): Int? = readUInt16(bytes, offset)?.toShort()?.toInt()

public fun readUInt32(bytes: ByteArray, offset: Int): UInt? {
    if (offset < 0 || offset + 3 >= bytes.size) {
        return null
    }
    return (((bytes[offset].toUInt() and 0xFFu) shl 24) or
        ((bytes[offset + 1].toUInt() and 0xFFu) shl 16) or
        ((bytes[offset + 2].toUInt() and 0xFFu) shl 8) or
        (bytes[offset + 3].toUInt() and 0xFFu))
}

public fun ByteArray.decodeAsciiTag(offset: Int): String {
    if (offset < 0 || offset + 3 >= size) {
        return ""
    }
    return CharArray(4) { index -> (this[offset + index].toInt() and 0xFF).toChar() }.concatToString()
}
