package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticData
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

/**
 * Resolves Unicode code points through validated TrueType `cmap` tables.
 *
 * All methods are read-only with respect to the supplied bytes. Malformed
 * tables and invalid scalar values are returned as typed failures; no partial
 * glyph mapping is published.
 */
internal object CmapReader {
    /**
     * Resolves [codePoint] using the maximum representable glyph range.
     *
     * This convenience method validates and decodes the table for the call.
     * Repeated callers should use [readUnicodeCmap] once and then query the
     * returned immutable lookup.
     */
    internal fun resolveGlyphId(cmapTable: ByteArray, codePoint: Int): FontOperationResult<GlyphLookupResult> =
        resolveGlyphId(cmapTable, codePoint, MAX_GLYPH_ID_EXCLUSIVE)

    /**
     * Resolves a code point while validating all published glyph identifiers against [numGlyphs].
     */
    internal fun resolveGlyphId(
        cmapTable: ByteArray,
        codePoint: Int,
        numGlyphs: Int,
    ): FontOperationResult<GlyphLookupResult> {
        if (!codePoint.isUnicodeScalar()) {
            return failure(FontError.InvalidFontData("Code point is outside the Unicode scalar range.", CMAP_LOCATION))
        }
        val lookup = when (val result = readUnicodeCmap(cmapTable, numGlyphs)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return lookup.resolveGlyphId(codePoint)
    }

    /**
     * Selects and validates the Unicode mapping once for repeated glyph lookups.
     *
     * The returned lookup owns its decoded mapping and is safe for concurrent
     * read-only queries. A failure identifies invalid table data or a glyph ID
     * outside [numGlyphs]; the input byte array is never modified.
     */
    internal fun readUnicodeCmap(
        cmapTable: ByteArray,
        numGlyphs: Int,
    ): FontOperationResult<UnicodeCmapLookup> {
        if (numGlyphs !in 1..MAX_GLYPH_ID_EXCLUSIVE) {
            return failure(
                FontError.InvalidFontData("maxp.numGlyphs is invalid for cmap validation.", CMAP_LOCATION),
                FontDiagnosticData(observedValue = numGlyphs.toLong(), limit = MAX_GLYPH_ID_EXCLUSIVE.toLong()),
            )
        }

        val subtable = when (val result = selectUnicodeSubtable(cmapTable)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }

        val mapping = when (subtable.format) {
            4 -> decodeFormat4(subtable.bytes, numGlyphs)
            12 -> decodeFormat12(subtable.bytes, numGlyphs)
            else -> failure(FontError.InvalidFontData("Unsupported cmap format ${subtable.format}.", CMAP_LOCATION))
        }

        return when (mapping) {
            is FontOperationResult.Success -> FontOperationResult.Success(UnicodeCmapLookup(mapping.value))
            is FontOperationResult.Failure -> mapping
            is FontOperationResult.Cancelled -> mapping
        }
    }

    private fun selectUnicodeSubtable(cmapTable: ByteArray): FontOperationResult<SelectedSubtable> {
        val version = readUInt16(cmapTable, 0)?.toInt()
            ?: return failure(FontError.InvalidFontData("cmap header is truncated.", CMAP_LOCATION))
        if (version != 0) {
            return failure(
                FontError.InvalidFontData("cmap version must be zero.", CMAP_LOCATION),
                FontDiagnosticData(observedValue = version.toLong(), limit = 0L),
            )
        }
        val numTables = readUInt16(cmapTable, 2)?.toInt()
            ?: return failure(FontError.InvalidFontData("cmap header is truncated.", CMAP_LOCATION))
        val directoryLength = numTables.toLong() * 8L
        if (checkedRangeEnd(4L, directoryLength, cmapTable.size) == null) {
            return failure(
                FontError.InvalidFontData("cmap encoding records are truncated.", CMAP_LOCATION),
                FontDiagnosticData(
                    offset = 4L,
                    length = directoryLength,
                    observedValue = 4L + directoryLength,
                    limit = cmapTable.size.toLong(),
                ),
            )
        }
        var best: SelectedSubtable? = null
        var offset = 4
        repeat(numTables) {
            val platformId = readUInt16(cmapTable, offset)?.toInt()
                ?: return failure(FontError.InvalidFontData("cmap platform ID is truncated.", CMAP_LOCATION))
            val encodingId = readUInt16(cmapTable, offset + 2)?.toInt()
                ?: return failure(FontError.InvalidFontData("cmap encoding ID is truncated.", CMAP_LOCATION))
            if (isRelevantUnicodeRecord(platformId, encodingId)) {
                val subtableOffset = readUInt32(cmapTable, offset + 4)?.toLong()
                    ?: return failure(FontError.InvalidFontData("cmap subtable offset is truncated.", CMAP_LOCATION))
                val subtableOffsetInt = checkedRangeEnd(subtableOffset, 2L, cmapTable.size)
                    ?.let { subtableOffset.toInt() }
                    ?: return failure(
                        FontError.InvalidFontData("cmap subtable offset is out of bounds.", CMAP_LOCATION),
                        FontDiagnosticData(
                            offset = subtableOffset,
                            length = 2L,
                            observedValue = subtableOffset + 2L,
                            limit = cmapTable.size.toLong(),
                        ),
                    )
                val format = readUInt16(cmapTable, subtableOffsetInt)?.toInt()
                    ?: return failure(FontError.InvalidFontData("cmap subtable format is truncated.", CMAP_LOCATION))
                val priority = subtablePriority(platformId, encodingId, format) ?: run {
                    offset += 8
                    return@repeat
                }
                val subtable = when (val result = subtableSlice(cmapTable, subtableOffset, format)) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
                val candidate = SelectedSubtable(priority, subtableOffset, format, subtable)
                if (best == null || candidate < best) {
                    best = candidate
                }
            }
            offset += 8
        }
        return best?.let { FontOperationResult.Success(it) }
            ?: failure(FontError.InvalidFontData("No supported Unicode cmap subtable was found.", CMAP_LOCATION))
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

    private fun subtableSlice(
        cmapTable: ByteArray,
        subtableOffset: Long,
        format: Int,
    ): FontOperationResult<ByteArray> {
        val offset = checkedRangeEnd(subtableOffset, 2L, cmapTable.size)
            ?.let { subtableOffset.toInt() }
            ?: return rangeFailure("cmap subtable offset is out of bounds.", subtableOffset, 2L, cmapTable.size)
        val length = when (format) {
            4 -> checkedRangeEnd(subtableOffset, 4L, cmapTable.size)
                ?.let { readUInt16(cmapTable, (subtableOffset + 2L).toInt())?.toLong() }
            12 -> checkedRangeEnd(subtableOffset, 8L, cmapTable.size)
                ?.let { readUInt32(cmapTable, (subtableOffset + 4L).toInt())?.toLong() }
            else -> null
        } ?: return failure(FontError.InvalidFontData("cmap subtable length is truncated.", CMAP_LOCATION))
        val minimumLength = if (format == 4) 16L else 16L
        if (length < minimumLength) {
            return failure(
                FontError.InvalidFontData("cmap subtable length is too small.", CMAP_LOCATION),
                FontDiagnosticData(length = length, observedValue = length, limit = minimumLength),
            )
        }
        val end = checkedRangeEnd(subtableOffset, length, cmapTable.size)
            ?: return rangeFailure("cmap subtable range is out of bounds.", subtableOffset, length, cmapTable.size)
        return FontOperationResult.Success(cmapTable.copyOfRange(offset, end))
    }

    private fun decodeFormat4(
        subtable: ByteArray,
        glyphLimit: Int,
    ): FontOperationResult<CmapMapping> {
        val segCountX2 = readUInt16(subtable, 6)?.toInt()
            ?: return failure(FontError.InvalidFontData("Format 4 cmap header is truncated.", CMAP_LOCATION))
        if (segCountX2 == 0 || segCountX2 % 2 != 0) {
            return failure(FontError.InvalidFontData("Format 4 cmap segment count is invalid.", CMAP_LOCATION))
        }
        val segCount = segCountX2 / 2
        val endCodesOffset = 14L
        val reservedPadOffset = endCodesOffset + segCount.toLong() * 2L
        val startCodesOffset = reservedPadOffset + 2L
        val idDeltasOffset = startCodesOffset + segCount.toLong() * 2L
        val idRangeOffsetsOffset = idDeltasOffset + segCount.toLong() * 2L
        val glyphArrayOffset = idRangeOffsetsOffset + segCount.toLong() * 2L
        if (checkedRangeEnd(idRangeOffsetsOffset, segCount.toLong() * 2L, subtable.size) == null) {
            return failure(FontError.InvalidFontData("Format 4 cmap arrays are truncated.", CMAP_LOCATION))
        }
        if (readUInt16(subtable, reservedPadOffset.toInt()) != 0u) {
            return failure(FontError.InvalidFontData("Format 4 reservedPad must be zero.", CMAP_LOCATION))
        }

        var previousEnd = -1
        val segments = ArrayList<Format4Segment>(segCount)
        for (segmentIndex in 0 until segCount) {
            val endCodeOffset = endCodesOffset + segmentIndex.toLong() * 2L
            val startCodeOffset = startCodesOffset + segmentIndex.toLong() * 2L
            val idDeltaOffset = idDeltasOffset + segmentIndex.toLong() * 2L
            val idRangeOffsetWordOffset = idRangeOffsetsOffset + segmentIndex.toLong() * 2L
            val endCode = readUInt16(subtable, endCodeOffset.toInt())?.toInt()
                ?: return failure(FontError.InvalidFontData("Format 4 endCode is truncated.", CMAP_LOCATION))
            val startCode = readUInt16(subtable, startCodeOffset.toInt())?.toInt()
                ?: return failure(FontError.InvalidFontData("Format 4 startCode is truncated.", CMAP_LOCATION))
            if (startCode > endCode || startCode <= previousEnd) {
                return failure(FontError.InvalidFontData("Format 4 cmap segments are unsorted or overlapping.", CMAP_LOCATION))
            }
            previousEnd = endCode
            val idDelta = readInt16(subtable, idDeltaOffset.toInt())
                ?: return failure(FontError.InvalidFontData("Format 4 idDelta is truncated.", CMAP_LOCATION))
            val idRangeOffset = readUInt16(subtable, idRangeOffsetWordOffset.toInt())?.toInt()
                ?: return failure(FontError.InvalidFontData("Format 4 idRangeOffset is truncated.", CMAP_LOCATION))
            val firstGlyphWordOffset = idRangeOffsetWordOffset + idRangeOffset.toLong()
            if (idRangeOffset != 0) {
                val lastGlyphWordOffset = firstGlyphWordOffset + (endCode - startCode).toLong() * 2L
                if (
                    firstGlyphWordOffset < glyphArrayOffset ||
                    checkedRangeEnd(lastGlyphWordOffset, 2L, subtable.size) == null
                ) {
                    return rangeFailure(
                        "Format 4 glyphIdArray access is out of bounds.",
                        firstGlyphWordOffset,
                        (endCode - startCode + 1).toLong() * 2L,
                        subtable.size,
                    )
                }
            }

            val glyphIds = if (idRangeOffset == 0) {
                null
            } else {
                IntArray(endCode - startCode + 1) { codePointOffset ->
                    val mappedCodePoint = startCode + codePointOffset
                    val glyphWordOffset = firstGlyphWordOffset + (mappedCodePoint - startCode).toLong() * 2L
                    val glyphIndex = readUInt16(subtable, glyphWordOffset.toInt())?.toInt()
                        ?: return failure(FontError.InvalidFontData("Format 4 glyphIdArray is truncated.", CMAP_LOCATION))
                    if (glyphIndex == 0) 0 else mapWithDelta(glyphIndex, idDelta)
                }
            }
            for (mappedCodePoint in startCode..endCode) {
                val mappedGlyphId = glyphIds?.get(mappedCodePoint - startCode)
                    ?: mapWithDelta(mappedCodePoint, idDelta)
                if (mappedGlyphId != 0 && mappedGlyphId >= glyphLimit) {
                    return invalidGlyphId(mappedGlyphId.toLong(), glyphLimit)
                }
            }
            segments += Format4Segment(startCode, endCode, idDelta, glyphIds)
        }
        if (previousEnd != 0xFFFF) {
            return failure(FontError.InvalidFontData("Format 4 cmap is missing its terminal segment.", CMAP_LOCATION))
        }
        return FontOperationResult.Success(Format4Mapping(segments))
    }

    private fun decodeFormat12(
        subtable: ByteArray,
        glyphLimit: Int,
    ): FontOperationResult<CmapMapping> {
        val numGroups = readUInt32(subtable, 12)?.toLong()
            ?: return failure(FontError.InvalidFontData("Format 12 cmap header is truncated.", CMAP_LOCATION))
        val groupsLength = numGroups * 12L
        if (checkedRangeEnd(16L, groupsLength, subtable.size) == null) {
            return failure(FontError.InvalidFontData("Format 12 cmap groups are truncated.", CMAP_LOCATION))
        }

        var previousEnd = -1L
        var offset = 16L
        val groups = ArrayList<Format12Group>()
        var groupIndex = 0L
        while (groupIndex < numGroups) {
            val startCharCode = readUInt32(subtable, offset.toInt())?.toLong()
                ?: return failure(FontError.InvalidFontData("Format 12 startCharCode is truncated.", CMAP_LOCATION))
            val endCharCode = readUInt32(subtable, (offset + 4L).toInt())?.toLong()
                ?: return failure(FontError.InvalidFontData("Format 12 endCharCode is truncated.", CMAP_LOCATION))
            val startGlyphId = readUInt32(subtable, (offset + 8L).toInt())?.toLong()
                ?: return failure(FontError.InvalidFontData("Format 12 startGlyphId is truncated.", CMAP_LOCATION))
            if (startCharCode > endCharCode || startCharCode <= previousEnd || endCharCode > 0x10FFFFL) {
                return failure(FontError.InvalidFontData("Format 12 cmap groups are unsorted or overlapping.", CMAP_LOCATION))
            }
            previousEnd = endCharCode
            val finalGlyphId = startGlyphId + (endCharCode - startCharCode)
            if (startGlyphId > 0xFFFFL || finalGlyphId > 0xFFFFL || finalGlyphId >= glyphLimit.toLong()) {
                return invalidGlyphId(finalGlyphId, glyphLimit)
            }
            groups += Format12Group(startCharCode, endCharCode, startGlyphId)
            offset += 12
            groupIndex += 1
        }
        return FontOperationResult.Success(Format12Mapping(groups))
    }

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())

    private fun failure(error: FontError, data: FontDiagnosticData): FontOperationResult.Failure =
        failure(error, listOf(error.toDiagnostic(data)))

    private fun rangeFailure(
        message: String,
        offset: Long,
        length: Long,
        sourceSize: Int,
    ): FontOperationResult.Failure =
        failure(
            FontError.InvalidFontData(message, CMAP_LOCATION),
            FontDiagnosticData(
                offset = offset.takeIf { it >= 0L },
                length = length.takeIf { it >= 0L },
                observedValue = if (offset <= Long.MAX_VALUE - length) offset + length else Long.MAX_VALUE,
                limit = sourceSize.toLong(),
            ),
        )

    private fun invalidGlyphId(glyphId: Long, glyphLimit: Int): FontOperationResult.Failure =
        failure(
            FontError.InvalidFontData("cmap publishes a glyph ID outside maxp.numGlyphs.", CMAP_LOCATION),
            FontDiagnosticData(observedValue = glyphId, limit = glyphLimit.toLong()),
        )

    private data class SelectedSubtable(
        val priority: Int,
        val offset: Long,
        val format: Int,
        val bytes: ByteArray,
    ) : Comparable<SelectedSubtable> {
        override fun compareTo(other: SelectedSubtable): Int =
            compareValuesBy(this, other, SelectedSubtable::priority, SelectedSubtable::offset)
    }

}

/**
 * Immutable selected and validated Unicode `cmap` view for repeated lookups.
 *
 * Queries are deterministic, allocation-light binary searches over the
 * decoded mapping and are safe to perform concurrently.
 */
internal class UnicodeCmapLookup internal constructor(
    private val mapping: CmapMapping,
) {
    /** Resolves one Unicode scalar value without rescanning or revalidating the `cmap` table. */
    internal fun resolveGlyphId(codePoint: Int): FontOperationResult<GlyphLookupResult> {
        if (!codePoint.isUnicodeScalar()) {
            return failure(FontError.InvalidFontData("Code point is outside the Unicode scalar range.", CMAP_LOCATION))
        }
        val glyphId = mapping.glyphIdFor(codePoint)
        return if (glyphId == 0) {
            FontOperationResult.Success(
                GlyphLookupResult(GlyphId(0)),
                listOf(glyphNotFoundDiagnostic(codePoint)),
            )
        } else {
            FontOperationResult.Success(GlyphLookupResult(GlyphId(glyphId)))
        }
    }
}

internal interface CmapMapping {
    fun glyphIdFor(codePoint: Int): Int
}

private class Format4Mapping(
    private val segments: List<Format4Segment>,
) : CmapMapping {
    override fun glyphIdFor(codePoint: Int): Int {
        if (codePoint > 0xFFFF) return 0
        var low = 0
        var high = segments.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val segment = segments[middle]
            when {
                codePoint < segment.startCode -> high = middle - 1
                codePoint > segment.endCode -> low = middle + 1
                else -> return segment.glyphIdFor(codePoint)
            }
        }
        return 0
    }
}

private data class Format4Segment(
    val startCode: Int,
    val endCode: Int,
    val idDelta: Int,
    private val glyphIds: IntArray?,
) {
    fun glyphIdFor(codePoint: Int): Int =
        glyphIds?.get(codePoint - startCode) ?: mapWithDelta(codePoint, idDelta)
}

private class Format12Mapping(
    private val groups: List<Format12Group>,
) : CmapMapping {
    override fun glyphIdFor(codePoint: Int): Int {
        val value = codePoint.toLong()
        var low = 0
        var high = groups.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val group = groups[middle]
            when {
                value < group.startCharCode -> high = middle - 1
                value > group.endCharCode -> low = middle + 1
                else -> return (group.startGlyphId + value - group.startCharCode).toInt()
            }
        }
        return 0
    }
}

private data class Format12Group(
    val startCharCode: Long,
    val endCharCode: Long,
    val startGlyphId: Long,
)

/** Result of resolving a Unicode code point through a validated `cmap` table. */
internal data class GlyphLookupResult(
    /** Resolved glyph identifier; zero denotes the missing-glyph glyph. */
    public val glyphId: GlyphId,
)

private val CMAP_LOCATION: FontDiagnosticLocation = FontDiagnosticLocation.Table("cmap")

private const val MAX_GLYPH_ID_EXCLUSIVE = 0x10000

private fun mapWithDelta(baseValue: Int, idDelta: Int): Int = (baseValue + idDelta) and 0xFFFF

private fun Int.isUnicodeScalar(): Boolean = this in 0..0x10FFFF && this !in 0xD800..0xDFFF

private fun glyphNotFoundDiagnostic(codePoint: Int): FontDiagnostic =
    FontDiagnostic(
        code = "font.cmap.glyph-not-found",
        severity = FontDiagnosticSeverity.INFO,
        location = CMAP_LOCATION,
        message = "No glyph mapping exists for U+${codePoint.toString(16).uppercase().padStart(4, '0')}.",
    )
