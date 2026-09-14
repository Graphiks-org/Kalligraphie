@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColor

/** Shared bounded palette decoder; version-zero callers retain their original diagnostics. */
internal object CpalPaletteReader {
    fun read(
        table: ByteArray,
        limits: ColrCpalV0Limits,
        allowVersionOne: Boolean = true,
    ): FontOperationResult<List<List<GlyphColor>>> {
        if (table.size < CPAL_V0_HEADER_LENGTH) return invalid("font.cpal.truncated", "CPAL version 0 header is truncated.", "CPAL")
        val version = readUInt16(table, 0)?.toInt() ?: return invalid("font.cpal.truncated", "CPAL version is truncated.", "CPAL")
        if (version != 0 && !(allowVersionOne && version == 1)) return invalid("font.cpal.unsupported-version", "Only CPAL version 0 is supported.", "CPAL")
        val entryCount = readUInt16(table, 2)?.toInt() ?: return invalid("font.cpal.truncated", "CPAL entry count is truncated.", "CPAL")
        val paletteCount = readUInt16(table, 4)?.toInt() ?: return invalid("font.cpal.truncated", "CPAL palette count is truncated.", "CPAL")
        val colorRecordCount = readUInt16(table, 6)?.toInt() ?: return invalid("font.cpal.truncated", "CPAL color-record count is truncated.", "CPAL")
        val colorRecordsOffset = readUInt32(table, 8)?.toLong() ?: return invalid("font.cpal.truncated", "CPAL color-record offset is truncated.", "CPAL")
        if (entryCount == 0 || paletteCount == 0) return invalid("font.cpal.invalid-table", "CPAL must contain at least one non-empty palette.", "CPAL")
        limit(entryCount, limits.maxPaletteEntries, "CPAL palette entry limit exceeded.", "CPAL")?.let { return it }
        limit(paletteCount, limits.maxPalettes, "CPAL palette limit exceeded.", "CPAL")?.let { return it }
        limit(colorRecordCount, limits.maxColorRecords, "CPAL color-record limit exceeded.", "CPAL")?.let { return it }
        val decodedPaletteBytes = paletteCount.toLong() * entryCount.toLong() * COLOR_RECORD_LENGTH
        if (decodedPaletteBytes > limits.maxDecodedPaletteBytes.toLong()) {
            return FontOperationResult.Failure(
                FontError.ResourceLimitExceeded("CPAL decoded-palette byte limit exceeded.", FontDiagnosticLocation.Table("CPAL")),
            )
        }

        val paletteIndicesEnd = checkedRangeEnd(CPAL_V0_HEADER_LENGTH, paletteCount * 2, table.size)
            ?: return invalid("font.cpal.truncated", "CPAL palette indices are truncated.", "CPAL")
        val colorRecordsEnd = checkedRangeEnd(colorRecordsOffset, colorRecordCount.toLong() * COLOR_RECORD_LENGTH, table.size)
            ?: return invalid("font.cpal.truncated", "CPAL color records are truncated.", "CPAL")
        if (colorRecordsEnd < paletteIndicesEnd) return invalid("font.cpal.invalid-table", "CPAL color records overlap the palette-index header.", "CPAL")

        if (version == 1 && !validMetadata(table, paletteCount, entryCount)) {
            return invalid("font.cpal.truncated", "CPAL version 1 metadata arrays are truncated.", "CPAL")
        }
        val palettes = ArrayList<List<GlyphColor>>(paletteCount)
        repeat(paletteCount) { paletteIndex ->
            val firstColorRecord = readUInt16(table, CPAL_V0_HEADER_LENGTH + paletteIndex * 2)?.toInt()
                ?: return invalid("font.cpal.truncated", "CPAL palette index is truncated.", "CPAL")
            if (firstColorRecord > colorRecordCount || entryCount > colorRecordCount - firstColorRecord) {
                return invalid("font.cpal.invalid-palette-index", "CPAL palette references unavailable color records.", "CPAL")
            }
            val colors = ArrayList<GlyphColor>(entryCount)
            repeat(entryCount) { entryIndex ->
                val offset = colorRecordsOffset + (firstColorRecord + entryIndex).toLong() * COLOR_RECORD_LENGTH
                colors += GlyphColor(
                    red = table[offset.toInt() + 2].toInt() and 0xFF,
                    green = table[offset.toInt() + 1].toInt() and 0xFF,
                    blue = table[offset.toInt()].toInt() and 0xFF,
                    alpha = table[offset.toInt() + 3].toInt() and 0xFF,
                )
            }
            palettes += colors
        }
        return FontOperationResult.Success(palettes)
    }

    fun validateStructure(table: ByteArray, allowVersionOne: Boolean = true): Int? {
        if (table.size < CPAL_V0_HEADER_LENGTH) return null
        val version = readUInt16(table, 0)?.toInt() ?: return null
        if (version != 0 && !(allowVersionOne && version == 1)) return null
        val entryCount = readUInt16(table, 2)?.toInt() ?: return null
        val paletteCount = readUInt16(table, 4)?.toInt() ?: return null
        val colorRecordCount = readUInt16(table, 6)?.toInt() ?: return null
        val colorRecordsOffset = readUInt32(table, 8)?.toLong() ?: return null
        if (entryCount == 0 || paletteCount == 0) return null
        val paletteIndicesEnd = checkedRangeEnd(CPAL_V0_HEADER_LENGTH, paletteCount * 2, table.size) ?: return null
        val colorRecordsEnd = checkedRangeEnd(colorRecordsOffset, colorRecordCount.toLong() * COLOR_RECORD_LENGTH, table.size) ?: return null
        if (colorRecordsEnd < paletteIndicesEnd) return null
        repeat(paletteCount) { paletteIndex ->
            val firstColorRecord = readUInt16(table, CPAL_V0_HEADER_LENGTH + paletteIndex * 2)?.toInt() ?: return null
            if (firstColorRecord > colorRecordCount || entryCount > colorRecordCount - firstColorRecord) return null
        }
        if (version == 1 && !validMetadata(table, paletteCount, entryCount)) return null
        return entryCount
    }

    private fun validMetadata(table: ByteArray, paletteCount: Int, entryCount: Int): Boolean {
        val metadata = CPAL_V0_HEADER_LENGTH + paletteCount * 2
        checkedRangeEnd(metadata, 12, table.size) ?: return false
        for (index in 0..2) {
            val offset = readUInt32(table, metadata + index * 4)?.toLong() ?: return false
            val count = if (index == 2) entryCount else paletteCount
            val width = if (index == 0) 4L else 2L
            if (offset != 0L && checkedRangeEnd(offset, count.toLong() * width, table.size) == null) return false
        }
        return true
    }

    private fun limit(observed: Int, maximum: Int, message: String, tag: String): FontOperationResult.Failure? =
        if (observed > maximum) {
            FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Table(tag)))
        } else {
            null
        }

    private fun invalid(code: String, message: String, tag: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table(tag)))
}

private const val CPAL_V0_HEADER_LENGTH = 12
private const val COLOR_RECORD_LENGTH = 4L
