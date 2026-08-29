package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.sfnt.checkedRangeEnd
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32

public object CmapReader {
    public fun resolveGlyphId(cmapTable: ByteArray, codePoint: Int): FontOperationResult<GlyphLookupResult> {
        if (!codePoint.isUnicodeScalar()) {
            return failure(FontError.InvalidFontData("Code point is outside the Unicode scalar range.", CMAP_LOCATION))
        }

        val subtable = selectUnicodeSubtable(cmapTable) ?: return failure(
            FontError.InvalidFontData("No supported Unicode cmap subtable was found.", CMAP_LOCATION),
        )

        val glyphId = when (subtable.format) {
            4 -> lookupFormat4(subtable.bytes, codePoint)
            12 -> lookupFormat12(subtable.bytes, codePoint)
            else -> failure(FontError.InvalidFontData("Unsupported cmap format ${subtable.format}.", CMAP_LOCATION))
        }

        return when (glyphId) {
            is FontOperationResult.Success -> {
                val value = glyphId.value
                if (value == 0) {
                    FontOperationResult.Success(
                        GlyphLookupResult(GlyphId(0)),
                        listOf(glyphNotFoundDiagnostic(codePoint)),
                    )
                } else {
                    FontOperationResult.Success(GlyphLookupResult(GlyphId(value)))
                }
            }
            is FontOperationResult.Failure -> glyphId
            is FontOperationResult.Cancelled -> glyphId
        }
    }

    private fun selectUnicodeSubtable(cmapTable: ByteArray): SelectedSubtable? {
        val numTables = readUInt16(cmapTable, 2)?.toInt() ?: return null
        var best: SelectedSubtable? = null
        var offset = 4
        repeat(numTables) {
            val platformId = readUInt16(cmapTable, offset)?.toInt() ?: return null
            val encodingId = readUInt16(cmapTable, offset + 2)?.toInt() ?: return null
            if (isRelevantUnicodeRecord(platformId, encodingId)) {
                val subtableOffset = readUInt32(cmapTable, offset + 4)?.toInt() ?: return null
                val format = readUInt16(cmapTable, subtableOffset)?.toInt() ?: return null
                val priority = subtablePriority(platformId, encodingId, format) ?: run {
                    offset += 8
                    return@repeat
                }
                val subtable = subtableSlice(cmapTable, subtableOffset, format) ?: return null
                val candidate = SelectedSubtable(priority, subtableOffset, format, subtable)
                if (best == null || candidate < best) {
                    best = candidate
                }
            }
            offset += 8
        }
        return best
    }

    private fun subtablePriority(platformId: Int, encodingId: Int, format: Int): Int? =
        when {
            platformId == 3 && encodingId == 10 && format == 12 -> 0
            platformId == 0 && format == 12 -> 1
            platformId == 3 && encodingId == 1 && format == 4 -> 2
            platformId == 0 && format == 4 -> 3
            else -> null
        }

    private fun isRelevantUnicodeRecord(platformId: Int, encodingId: Int): Boolean =
        when (platformId) {
            0 -> true
            3 -> encodingId == 1 || encodingId == 10
            else -> false
        }

    private fun subtableSlice(cmapTable: ByteArray, subtableOffset: Int, format: Int): ByteArray? {
        val length = when (format) {
            4 -> readUInt16(cmapTable, subtableOffset + 2)?.toInt()
            12 -> readUInt32(cmapTable, subtableOffset + 4)?.toInt()
            else -> null
        } ?: return null
        val end = checkedRangeEnd(subtableOffset, length, cmapTable.size) ?: return null
        return cmapTable.copyOfRange(subtableOffset, end)
    }

    private fun lookupFormat4(subtable: ByteArray, codePoint: Int): FontOperationResult<Int> {
        if (codePoint > 0xFFFF) {
            return FontOperationResult.Success(0)
        }
        val segCountX2 = readUInt16(subtable, 6)?.toInt()
            ?: return failure(FontError.InvalidFontData("Format 4 cmap header is truncated.", CMAP_LOCATION))
        if (segCountX2 == 0 || segCountX2 % 2 != 0) {
            return failure(FontError.InvalidFontData("Format 4 cmap segment count is invalid.", CMAP_LOCATION))
        }
        val segCount = segCountX2 / 2
        val endCodesOffset = 14
        val startCodesOffset = endCodesOffset + segCount * 2 + 2
        val idDeltasOffset = startCodesOffset + segCount * 2
        val idRangeOffsetsOffset = idDeltasOffset + segCount * 2
        val glyphArrayOffset = idRangeOffsetsOffset + segCount * 2
        if (checkedRangeEnd(idRangeOffsetsOffset, segCount * 2, subtable.size) == null) {
            return failure(FontError.InvalidFontData("Format 4 cmap arrays are truncated.", CMAP_LOCATION))
        }

        var previousEnd = -1
        for (segmentIndex in 0 until segCount) {
            val endCode = readUInt16(subtable, endCodesOffset + segmentIndex * 2)?.toInt()
                ?: return failure(FontError.InvalidFontData("Format 4 endCode is truncated.", CMAP_LOCATION))
            val startCode = readUInt16(subtable, startCodesOffset + segmentIndex * 2)?.toInt()
                ?: return failure(FontError.InvalidFontData("Format 4 startCode is truncated.", CMAP_LOCATION))
            if (startCode > endCode || startCode <= previousEnd) {
                return failure(FontError.InvalidFontData("Format 4 cmap segments are unsorted or overlapping.", CMAP_LOCATION))
            }
            previousEnd = endCode
            if (codePoint !in startCode..endCode) {
                continue
            }

            val idDelta = readInt16(subtable, idDeltasOffset + segmentIndex * 2)
                ?: return failure(FontError.InvalidFontData("Format 4 idDelta is truncated.", CMAP_LOCATION))
            val idRangeOffsetWordOffset = idRangeOffsetsOffset + segmentIndex * 2
            val idRangeOffset = readUInt16(subtable, idRangeOffsetWordOffset)?.toInt()
                ?: return failure(FontError.InvalidFontData("Format 4 idRangeOffset is truncated.", CMAP_LOCATION))
            if (idRangeOffset == 0) {
                return mapWithDelta(codePoint, idDelta)
            }

            val glyphWordOffset = idRangeOffsetWordOffset + idRangeOffset + (codePoint - startCode) * 2
            if (glyphWordOffset < glyphArrayOffset || checkedRangeEnd(glyphWordOffset, 2, subtable.size) == null) {
                return failure(FontError.InvalidFontData("Format 4 glyphIdArray access is out of bounds.", CMAP_LOCATION))
            }
            val glyphIndex = readUInt16(subtable, glyphWordOffset)?.toInt()
                ?: return failure(FontError.InvalidFontData("Format 4 glyphIdArray is truncated.", CMAP_LOCATION))
            if (glyphIndex == 0) {
                return FontOperationResult.Success(0)
            }
            return mapWithDelta(glyphIndex, idDelta)
        }

        return FontOperationResult.Success(0)
    }

    private fun mapWithDelta(baseValue: Int, idDelta: Int): FontOperationResult<Int> {
        val glyphId = baseValue + idDelta
        return if (glyphId in 0..0xFFFF) {
            FontOperationResult.Success(glyphId)
        } else {
            failure(FontError.InvalidFontData("Format 4 glyph mapping overflowed its valid range.", CMAP_LOCATION))
        }
    }

    private fun lookupFormat12(subtable: ByteArray, codePoint: Int): FontOperationResult<Int> {
        val numGroups = readUInt32(subtable, 12)?.toInt()
            ?: return failure(FontError.InvalidFontData("Format 12 cmap header is truncated.", CMAP_LOCATION))
        if (checkedRangeEnd(16, numGroups * 12, subtable.size) == null) {
            return failure(FontError.InvalidFontData("Format 12 cmap groups are truncated.", CMAP_LOCATION))
        }

        var previousEnd = -1L
        var offset = 16
        repeat(numGroups) {
            val startCharCode = readUInt32(subtable, offset)?.toLong()
                ?: return failure(FontError.InvalidFontData("Format 12 startCharCode is truncated.", CMAP_LOCATION))
            val endCharCode = readUInt32(subtable, offset + 4)?.toLong()
                ?: return failure(FontError.InvalidFontData("Format 12 endCharCode is truncated.", CMAP_LOCATION))
            val startGlyphId = readUInt32(subtable, offset + 8)?.toLong()
                ?: return failure(FontError.InvalidFontData("Format 12 startGlyphId is truncated.", CMAP_LOCATION))
            if (startCharCode > endCharCode || startCharCode <= previousEnd) {
                return failure(FontError.InvalidFontData("Format 12 cmap groups are unsorted or overlapping.", CMAP_LOCATION))
            }
            previousEnd = endCharCode
            if (codePoint.toLong() in startCharCode..endCharCode) {
                val glyphId = startGlyphId + (codePoint.toLong() - startCharCode)
                return if (glyphId in 0L..0xFFFFL) {
                    FontOperationResult.Success(glyphId.toInt())
                } else {
                    failure(FontError.InvalidFontData("Format 12 glyph mapping overflowed its valid range.", CMAP_LOCATION))
                }
            }
            offset += 12
        }

        return FontOperationResult.Success(0)
    }

    private fun glyphNotFoundDiagnostic(codePoint: Int): FontDiagnostic =
        FontDiagnostic(
            code = "font.cmap.glyph-not-found",
            severity = FontDiagnosticSeverity.INFO,
            location = CMAP_LOCATION,
            message = "No glyph mapping exists for U+${codePoint.toString(16).uppercase().padStart(4, '0')}.",
        )

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())

    private fun Int.isUnicodeScalar(): Boolean = this in 0..0x10FFFF && this !in 0xD800..0xDFFF

    private data class SelectedSubtable(
        val priority: Int,
        val offset: Int,
        val format: Int,
        val bytes: ByteArray,
    ) : Comparable<SelectedSubtable> {
        override fun compareTo(other: SelectedSubtable): Int =
            compareValuesBy(this, other, SelectedSubtable::priority, SelectedSubtable::offset)
    }

}

public data class GlyphLookupResult(
    public val glyphId: GlyphId,
)

private val CMAP_LOCATION: FontDiagnosticLocation = FontDiagnosticLocation.Table("cmap")
