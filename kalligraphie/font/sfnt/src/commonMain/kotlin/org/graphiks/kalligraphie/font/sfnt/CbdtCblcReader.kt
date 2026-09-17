@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapGlyphMetrics
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapResourceLimit
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId

/**
 * Fully validated CBLC version 2.0 / 3.0 and CBDT version 2.0 / 3.0 colour bitmap route.
 *
 * This value supports only CBLC index-subtable format 1 with CBDT image formats 17 and 18
 * (embedded PNG). Uncompressed 32-bit BGRA records, image format 19, composite records, and
 * other index formats are rejected while opening the route. It retains only the selected strike's
 * validated source records, never decoded pixels, and never exposes raw CBDT bytes to a consumer.
 *
 * Placement follows the EBLC/EBDT convention: [BitmapGlyphIR.originX] is the left side bearing
 * and [BitmapGlyphIR.originY] is the distance from the horizontal origin to the top edge of the
 * bitmap.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class CbdtCblcData internal constructor(
    private val strike: BitmapStrike,
    private val glyphCount: Int,
    private val limits: BitmapLimits,
    private val records: Map<GlyphId, CbdtCblcRecord>,
) {
    /**
     * Decodes one validated glyph into a complete immutable colour bitmap.
     *
     * Cancellation is observed at entry and again after PNG decoding, before any pixels are
     * published.
     *
     * @return the decoded bitmap, a typed failure when [glyphId] is outside the face or the
     * selected strike has no record for it, or cancellation without partial pixels. An absent
     * strike record is not evidence that the glyph has no ink.
     */
    public fun decode(
        glyphId: GlyphId,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<BitmapGlyphIR> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (glyphId.value !in 0 until glyphCount) {
            return FontOperationResult.Failure(FontError.GlyphOutOfRange(glyphId.value, location = FontDiagnosticLocation.Glyph(glyphId.value)))
        }
        val record = records[glyphId] ?: return FontOperationResult.Failure(
            FontError.GlyphRepresentationUnavailable(
                glyphId = glyphId.value,
                message = "The selected CBDT strike $strike has no bitmap for glyph ${glyphId.value}.",
            ),
        )
        val decoded = when (val result = PngDecoder.decode(record.pngBytes, limits, "CBDT")) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (decoded.width != record.width || decoded.height != record.height) return dimensionMismatch()
        return FontOperationResult.Success(
            BitmapGlyphIR(
                glyphId = glyphId,
                strike = strike,
                width = record.width,
                height = record.height,
                originX = record.originX,
                originY = record.originY,
                metrics = record.metrics,
                pixelFormat = BitmapPixelFormat.RGBA_8888,
                colorSpace = GlyphColorSpace.SRGB,
                decodedPixels = decoded.copyPixels(),
            ),
        )
    }
}

/** Reads the declared CBDT colour-bitmap route into portable bounded records. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object CbdtCblcReader {
    /**
     * Reports whether both bitmap tables declare a version this route can open.
     *
     * This inexpensive check validates the minimum header length and that the CBLC and CBDT
     * versions are each 2.0 or 3.0. The two versions are accepted independently, so a mixed pair
     * is tolerated deliberately: the supported subset (index format 1 with image formats 17 and
     * 18) has identical record layouts in both versions. Call [read] with an exact
     * [BitmapProfile] to validate a strike, index subtables, image records, codecs, and resource
     * limits before issuing a certificate.
     */
    public fun hasSupportedVersions(
        cblcTable: ByteArray,
        cbdtTable: ByteArray,
    ): Boolean {
        if (cblcTable.size < CBLC_HEADER_LENGTH || cbdtTable.size < CBDT_HEADER_LENGTH) return false
        val cblcVersion = readUInt32(cblcTable, 0) ?: return false
        val cbdtVersion = readUInt32(cbdtTable, 0) ?: return false
        return isSupportedVersion(cblcVersion) && isSupportedVersion(cbdtVersion)
    }

    /**
     * Reports whether every declared strike can use this reader's exact colour route.
     *
     * The predicate validates headers, strike envelopes, reserved and horizontal strike flags,
     * 32-bit depth, subtable boundaries, glyph ranges, image formats, offsets, and image records
     * with conservative implementation limits. It publishes no data and is intended only for a
     * face capability prefilter. Call [read] again with the consumer's exact [BitmapProfile]
     * before issuing a certificate.
     */
    public fun hasStructurallyValidTables(
        cblcTable: ByteArray,
        cbdtTable: ByteArray,
        glyphCount: Int,
    ): Boolean {
        if (!hasSupportedVersions(cblcTable, cbdtTable) || glyphCount <= 0) return false
        if (cblcTable.size > MAX_CAPABILITY_TABLE_BYTES || cbdtTable.size > MAX_CAPABILITY_TABLE_BYTES) return false
        val strikeCount = readUInt32(cblcTable, 4)?.toLong() ?: return false
        if (strikeCount !in 1L..MAX_CAPABILITY_STRIKES.toLong()) return false
        if (checkedRangeEnd(CBLC_HEADER_LENGTH.toLong(), strikeCount * BITMAP_SIZE_TABLE_LENGTH, cblcTable.size) == null) return false
        repeat(strikeCount.toInt()) { index ->
            val size = when (val parsed = readCbdtBitmapSizeTable(cblcTable, CBLC_HEADER_LENGTH + index * BITMAP_SIZE_TABLE_LENGTH)) {
                is FontOperationResult.Success -> parsed.value
                else -> return false
            }
            if (size.bitDepth != 32) return false
            if (size.flags and RESERVED_FLAGS_MASK != 0 || size.flags and HORIZONTAL_METRICS_FLAG == 0) return false
            if (size.startGlyphId !in 0 until glyphCount || size.endGlyphId !in size.startGlyphId until glyphCount) {
                return false
            }
            val profile = BitmapProfile(
                strike = size.strike,
                acceptedPixelFormats = listOf(BitmapPixelFormat.RGBA_8888),
                acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
                limits = BitmapLimits(
                    maxStrikes = MAX_CAPABILITY_STRIKES,
                    maxIndexSubtables = MAX_CAPABILITY_INDEX_SUBTABLES,
                    maxRecordCount = MAX_CAPABILITY_RECORDS,
                    maxIndexTableBytes = MAX_CAPABILITY_TABLE_BYTES,
                    maxSourceTableBytes = MAX_CAPABILITY_TABLE_BYTES,
                    maxWidth = MAX_CAPABILITY_DIMENSION,
                    maxHeight = MAX_CAPABILITY_DIMENSION,
                    maxPixels = MAX_CAPABILITY_PIXELS,
                    maxCompressedBytes = MAX_CAPABILITY_TABLE_BYTES,
                    maxTotalCompressedBytes = MAX_CAPABILITY_TABLE_BYTES,
                    maxDecodedBytes = MAX_CAPABILITY_DECODED_BYTES,
                    maxTotalDecodedBytes = MAX_CAPABILITY_DECODED_BYTES,
                ),
                schemaVersion = 2,
            )
            if (read(cblcTable, cbdtTable, glyphCount, profile) !is FontOperationResult.Success) return false
        }
        return true
    }

    /**
     * Parses the exact strike requested by [profile].
     *
     * The operation validates every index subtable and every image record in the selected strike
     * before it returns. A different strike, another bit depth, unsupported flags or codecs,
     * truncated data, a dimension mismatch, or a limit breach is returned as a typed failure
     * rather than deferred to a renderer.
     *
     * @param cblcTable exact bytes of the OpenType `CBLC` table.
     * @param cbdtTable exact bytes of the OpenType `CBDT` table.
     * @param glyphCount number of glyph identifiers declared by the face.
     * @param profile exact strike, format, color-space, and resource requirements.
     */
    public fun read(
        cblcTable: ByteArray,
        cbdtTable: ByteArray,
        glyphCount: Int,
        profile: BitmapProfile,
    ): FontOperationResult<CbdtCblcData> {
        if (glyphCount <= 0) return invalid("font.cbdt.invalid-glyph-count", "CBDT requires a positive face glyph count.", "CBLC")
        if (profile.schemaVersion != 2 ||
            profile.strike.bitDepth != 32 ||
            BitmapPixelFormat.RGBA_8888 !in profile.acceptedPixelFormats ||
            GlyphColorSpace.SRGB !in profile.acceptedColorSpaces
        ) {
            return unsupported("Only schema version 2 CBDT colour bitmap pixels are supported.")
        }
        if (cblcTable.size > profile.limits.maxIndexTableBytes) {
            return limit(BitmapResourceLimit.INDEX_TABLE_BYTES, cblcTable.size.toLong(), profile.limits.maxIndexTableBytes, "CBLC")
        }
        if (cbdtTable.size > profile.limits.maxSourceTableBytes) {
            return limit(BitmapResourceLimit.SOURCE_TABLE_BYTES, cbdtTable.size.toLong(), profile.limits.maxSourceTableBytes, "CBDT")
        }
        if (cblcTable.size < CBLC_HEADER_LENGTH) return invalid("font.cblc.truncated", "CBLC header is truncated.", "CBLC")
        if (!isSupportedVersion(readUInt32(cblcTable, 0) ?: return invalid("font.cblc.truncated", "CBLC version is truncated.", "CBLC"))) {
            return unsupported("Only CBLC version 2.0 or 3.0 is supported.")
        }
        if (cbdtTable.size < CBDT_HEADER_LENGTH) return invalid("font.cbdt.truncated", "CBDT header is truncated.", "CBDT")
        if (!isSupportedVersion(readUInt32(cbdtTable, 0) ?: return invalid("font.cbdt.truncated", "CBDT version is truncated.", "CBDT"))) {
            return unsupported("Only CBDT version 2.0 or 3.0 is supported.")
        }
        val strikeCount = readUInt32(cblcTable, 4)?.toLong()
            ?: return invalid("font.cblc.truncated", "CBLC strike count is truncated.", "CBLC")
        if (strikeCount > profile.limits.maxStrikes.toLong()) {
            return limit(BitmapResourceLimit.STRIKES, strikeCount, profile.limits.maxStrikes, "CBLC")
        }
        checkedRangeEnd(CBLC_HEADER_LENGTH.toLong(), strikeCount * BITMAP_SIZE_TABLE_LENGTH, cblcTable.size)
            ?: return invalid("font.cblc.truncated", "CBLC bitmap size tables are truncated.", "CBLC")

        var selected: CbdtBitmapSizeTable? = null
        repeat(strikeCount.toInt()) { index ->
            val table = when (val parsed = readCbdtBitmapSizeTable(cblcTable, CBLC_HEADER_LENGTH + index * BITMAP_SIZE_TABLE_LENGTH)) {
                is FontOperationResult.Success -> parsed.value
                is FontOperationResult.Failure -> return parsed
                is FontOperationResult.Cancelled -> return parsed
            }
            if (table.strike == profile.strike) {
                if (selected != null) return invalid("font.cblc.duplicate-strike", "CBLC declares the requested strike more than once.", "CBLC")
                selected = table
            }
        }
        val size = selected ?: return unsupported("The exact requested bitmap strike is unavailable.")
        if (size.flags and RESERVED_FLAGS_MASK != 0) {
            return invalid("font.cblc.invalid-flags", "CBLC strike flags use reserved bits.", "CBLC")
        }
        if (size.flags and HORIZONTAL_METRICS_FLAG == 0) {
            return unsupported("Only horizontally-metric CBDT strikes are supported.")
        }
        if (size.startGlyphId !in 0 until glyphCount || size.endGlyphId !in size.startGlyphId until glyphCount) {
            return invalid("font.cblc.invalid-glyph-range", "CBLC strike glyph range is outside the face.", "CBLC")
        }
        return readStrike(cblcTable, cbdtTable, glyphCount, size, profile)
    }

    private fun readStrike(
        cblc: ByteArray,
        cbdt: ByteArray,
        glyphCount: Int,
        size: CbdtBitmapSizeTable,
        profile: BitmapProfile,
    ): FontOperationResult<CbdtCblcData> {
        if (size.numberOfIndexSubTables > profile.limits.maxIndexSubtables) {
            return limit(BitmapResourceLimit.INDEX_SUBTABLES, size.numberOfIndexSubTables.toLong(), profile.limits.maxIndexSubtables, "CBLC")
        }
        val indexTablesEnd = checkedRangeEnd(size.indexSubTableArrayOffset, size.indexTablesSize, cblc.size)
            ?: return invalid("font.cblc.invalid-index-tables-range", "CBLC index tables exceed their declared strike region.", "CBLC")
        checkedRangeEnd(
            size.indexSubTableArrayOffset,
            size.numberOfIndexSubTables.toLong() * INDEX_SUBTABLE_ARRAY_ENTRY_LENGTH,
            indexTablesEnd,
        )
            ?: return invalid("font.cblc.invalid-index-tables-range", "CBLC index-subtable array exceeds its declared strike region.", "CBLC")
        val records = LinkedHashMap<GlyphId, CbdtCblcRecord>()
        var recordCount = 0
        var totalCompressedBytes = 0L
        var totalDecodedBytes = 0L
        repeat(size.numberOfIndexSubTables) { index ->
            val entryOffset = size.indexSubTableArrayOffset.toInt() + index * INDEX_SUBTABLE_ARRAY_ENTRY_LENGTH
            val firstGlyph = readUInt16(cblc, entryOffset)?.toInt()
                ?: return invalid("font.cblc.truncated", "CBLC subtable glyph range is truncated.", "CBLC")
            val lastGlyph = readUInt16(cblc, entryOffset + 2)?.toInt()
                ?: return invalid("font.cblc.truncated", "CBLC subtable glyph range is truncated.", "CBLC")
            val additionalOffset = readUInt32(cblc, entryOffset + 4)?.toLong()
                ?: return invalid("font.cblc.truncated", "CBLC subtable offset is truncated.", "CBLC")
            if (firstGlyph !in size.startGlyphId..size.endGlyphId || lastGlyph !in firstGlyph..size.endGlyphId) {
                return invalid("font.cblc.invalid-strike-glyph-range", "CBLC subtable range is outside its selected strike.", "CBLC")
            }
            val subtableOffset = size.indexSubTableArrayOffset + additionalOffset
            checkedRangeEnd(subtableOffset, INDEX_SUBTABLE_HEADER_LENGTH, indexTablesEnd)
                ?: return invalid("font.cblc.invalid-index-tables-range", "CBLC index subtable exceeds its declared strike region.", "CBLC")
            val indexFormat = readUInt16(cblc, subtableOffset.toInt())?.toInt()
                ?: return invalid("font.cblc.truncated", "CBLC index subtable is truncated.", "CBLC")
            val imageFormat = readUInt16(cblc, subtableOffset.toInt() + 2)?.toInt()
                ?: return invalid("font.cblc.truncated", "CBLC image format is truncated.", "CBLC")
            val imageDataOffset = readUInt32(cblc, subtableOffset.toInt() + 4)?.toLong()
                ?: return invalid("font.cblc.truncated", "CBLC image-data offset is truncated.", "CBLC")
            if (indexFormat != INDEX_FORMAT_ONE || (imageFormat != IMAGE_FORMAT_SMALL_METRICS && imageFormat != IMAGE_FORMAT_BIG_METRICS)) {
                return unsupported("Only CBLC index format 1 and CBDT image formats 17 and 18 are supported.")
            }
            val glyphsInSubtable = lastGlyph - firstGlyph + 1
            if (glyphsInSubtable > profile.limits.maxRecordCount - recordCount) {
                return limit(BitmapResourceLimit.RECORD_COUNT, recordCount.toLong() + glyphsInSubtable, profile.limits.maxRecordCount, "CBLC")
            }
            recordCount += glyphsInSubtable
            val offsetArrayStart = subtableOffset + INDEX_SUBTABLE_HEADER_LENGTH
            checkedRangeEnd(offsetArrayStart, (glyphsInSubtable + 1).toLong() * 4L, indexTablesEnd)
                ?: return invalid("font.cblc.invalid-index-tables-range", "CBLC image-offset array exceeds its declared strike region.", "CBLC")
            var previousOffset = -1L
            val offsets = LongArray(glyphsInSubtable + 1)
            repeat(glyphsInSubtable + 1) { offsetIndex ->
                val value = readUInt32(cblc, offsetArrayStart.toInt() + offsetIndex * 4)?.toLong()
                    ?: return invalid("font.cblc.truncated", "CBLC image offset is truncated.", "CBLC")
                if (value < previousOffset) return invalid("font.cblc.invalid-offset-order", "CBLC image offsets are not monotonic.", "CBLC")
                offsets[offsetIndex] = value
                previousOffset = value
            }
            repeat(glyphsInSubtable) { glyphOffset ->
                val length = offsets[glyphOffset + 1] - offsets[glyphOffset]
                if (length == 0L) return@repeat
                val dataOffset = imageDataOffset + offsets[glyphOffset]
                val dataEnd = checkedRangeEnd(dataOffset, length, cbdt.size)
                    ?: return invalid("font.cbdt.truncated", "CBDT image data is truncated.", "CBDT")
                val parsed = when (val parsed = readImageRecord(cbdt, dataOffset.toInt(), dataEnd, imageFormat, profile)) {
                    is FontOperationResult.Success -> parsed.value
                    is FontOperationResult.Failure -> return parsed
                    is FontOperationResult.Cancelled -> return parsed
                }
                if (exceedsCumulativeLimit(totalCompressedBytes, parsed.compressedByteCount, profile.limits.maxTotalCompressedBytes)) {
                    return limit(BitmapResourceLimit.TOTAL_COMPRESSED_BYTES, totalCompressedBytes + parsed.compressedByteCount, profile.limits.maxTotalCompressedBytes, "CBDT")
                }
                if (exceedsCumulativeLimit(totalDecodedBytes, parsed.decodedByteCount, profile.limits.maxTotalDecodedBytes)) {
                    return limit(BitmapResourceLimit.TOTAL_DECODED_BYTES, totalDecodedBytes + parsed.decodedByteCount, profile.limits.maxTotalDecodedBytes, "CBDT")
                }
                val glyphId = GlyphId(firstGlyph + glyphOffset)
                if (records.put(glyphId, parsed.record) != null) return invalid("font.cblc.duplicate-glyph", "CBLC strike has overlapping glyph records.", "CBLC")
                totalCompressedBytes += parsed.compressedByteCount
                totalDecodedBytes += parsed.decodedByteCount
            }
        }
        return FontOperationResult.Success(CbdtCblcData(size.strike, glyphCount, profile.limits, records))
    }

    private fun readImageRecord(
        data: ByteArray,
        start: Int,
        end: Int,
        imageFormat: Int,
        profile: BitmapProfile,
    ): FontOperationResult<ParsedCbdtRecord> {
        val metricsLength = if (imageFormat == IMAGE_FORMAT_BIG_METRICS) BIG_GLYPH_METRICS_LENGTH else SMALL_GLYPH_METRICS_LENGTH
        val headerLength = metricsLength + DATA_LENGTH_FIELD_LENGTH
        if (end - start < headerLength) {
            return invalid("font.cbdt.truncated", "CBDT image format $imageFormat header is truncated.", "CBDT")
        }
        val height = data[start].toInt() and 0xFF
        val width = data[start + 1].toInt() and 0xFF
        val dataLength = readUInt32(data, start + metricsLength)?.toLong()
            ?: return invalid("font.cbdt.truncated", "CBDT image data length is truncated.", "CBDT")
        if ((end - start).toLong() != headerLength.toLong() + dataLength) {
            return invalid("font.cbdt.invalid-image", "CBDT image record length does not match its declared data length.", "CBDT")
        }
        if (dataLength > profile.limits.maxCompressedBytes.toLong()) {
            return limit(BitmapResourceLimit.COMPRESSED_BYTES, dataLength, profile.limits.maxCompressedBytes, "CBDT")
        }
        val encoded = data.copyOfRange(start + headerLength, end)
        val header = when (val inspected = PngDecoder.inspectHeader(encoded, profile.limits, "CBDT")) {
            is FontOperationResult.Success -> inspected.value
            is FontOperationResult.Failure -> return inspected
            is FontOperationResult.Cancelled -> return inspected
        }
        if (header.width != width || header.height != height) return dimensionMismatch()
        return FontOperationResult.Success(
            ParsedCbdtRecord(
                record = CbdtCblcRecord(
                    width = width,
                    height = height,
                    originX = data[start + 2].toInt(),
                    originY = data[start + 3].toInt(),
                    metrics = if (imageFormat == IMAGE_FORMAT_BIG_METRICS) {
                        BitmapGlyphMetrics(
                            advanceX = data[start + 4].toInt() and 0xFF,
                            advanceY = data[start + 7].toInt() and 0xFF,
                        )
                    } else {
                        BitmapGlyphMetrics(advanceX = data[start + 4].toInt() and 0xFF, advanceY = 0)
                    },
                    pngBytes = encoded,
                ),
                compressedByteCount = dataLength,
                decodedByteCount = width.toLong() * height.toLong() * RGBA_BYTES_PER_PIXEL.toLong(),
            ),
        )
    }

    private fun readCbdtBitmapSizeTable(bytes: ByteArray, offset: Int): FontOperationResult<CbdtBitmapSizeTable> {
        if (checkedRangeEnd(offset, BITMAP_SIZE_TABLE_LENGTH, bytes.size) == null) {
            return invalid("font.cblc.truncated", "CBLC bitmap size table is truncated.", "CBLC")
        }
        val indexSubTableArrayOffset = readUInt32(bytes, offset)?.toLong()
            ?: return invalid("font.cblc.truncated", "CBLC bitmap size table is truncated.", "CBLC")
        val indexTablesSize = readUInt32(bytes, offset + 4)?.toLong()
            ?: return invalid("font.cblc.truncated", "CBLC bitmap size table is truncated.", "CBLC")
        val numberOfIndexSubTables = readUInt32(bytes, offset + 8)?.toLong()
            ?: return invalid("font.cblc.truncated", "CBLC bitmap size table is truncated.", "CBLC")
        if (numberOfIndexSubTables > Int.MAX_VALUE) {
            return invalid("font.cblc.invalid-subtable-count", "CBLC subtable count is invalid.", "CBLC")
        }
        val startGlyphId = readUInt16(bytes, offset + 40)?.toInt()
            ?: return invalid("font.cblc.truncated", "CBLC bitmap size table is truncated.", "CBLC")
        val endGlyphId = readUInt16(bytes, offset + 42)?.toInt()
            ?: return invalid("font.cblc.truncated", "CBLC bitmap size table is truncated.", "CBLC")
        val ppemX = bytes[offset + 44].toInt() and 0xFF
        val ppemY = bytes[offset + 45].toInt() and 0xFF
        if (ppemX == 0 || ppemY == 0) {
            return invalid("font.cblc.invalid-strike", "CBLC strike ppem values must be positive.", "CBLC")
        }
        val bitDepth = bytes[offset + 46].toInt() and 0xFF
        if (bitDepth !in 1..32) {
            return invalid("font.cblc.invalid-strike", "CBLC strike bit depth must be between 1 and 32.", "CBLC")
        }
        return FontOperationResult.Success(
            CbdtBitmapSizeTable(
                indexSubTableArrayOffset = indexSubTableArrayOffset,
                indexTablesSize = indexTablesSize,
                numberOfIndexSubTables = numberOfIndexSubTables.toInt(),
                startGlyphId = startGlyphId,
                endGlyphId = endGlyphId,
                strike = BitmapStrike(ppemX, ppemY, bitDepth),
                bitDepth = bitDepth,
                flags = bytes[offset + 47].toInt() and 0xFF,
            ),
        )
    }

    private fun invalid(code: String, message: String, table: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table(table)))

    private fun limit(
        dimension: BitmapResourceLimit,
        observed: Long,
        maximum: Int,
        table: String,
    ): FontOperationResult.Failure =
        FontOperationResult.Failure(
            FontError.BitmapResourceLimitExceeded(
                limit = dimension,
                observed = observed,
                maximum = maximum.toLong(),
                location = FontDiagnosticLocation.Table(table),
            ),
        )

    private fun unsupported(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile(message, FontDiagnosticLocation.Table("CBLC")))
}

private fun exceedsCumulativeLimit(total: Long, increment: Long, maximum: Int): Boolean =
    increment > maximum.toLong() || total > maximum.toLong() - increment

private fun isSupportedVersion(version: UInt): Boolean =
    version == VERSION_2 || version == VERSION_3

private fun dimensionMismatch(): FontOperationResult.Failure =
    FontOperationResult.Failure(
        FontError.FontDataFailure(
            code = "font.cbdt.dimension-mismatch",
            message = "CBDT PNG dimensions do not match the glyph metrics.",
            location = FontDiagnosticLocation.Table("CBDT"),
        ),
    )

private data class CbdtBitmapSizeTable(
    val indexSubTableArrayOffset: Long,
    val indexTablesSize: Long,
    val numberOfIndexSubTables: Int,
    val startGlyphId: Int,
    val endGlyphId: Int,
    val strike: BitmapStrike,
    val bitDepth: Int,
    val flags: Int,
)

internal data class CbdtCblcRecord(
    val width: Int,
    val height: Int,
    val originX: Int,
    val originY: Int,
    val metrics: BitmapGlyphMetrics,
    val pngBytes: ByteArray,
)

private data class ParsedCbdtRecord(
    val record: CbdtCblcRecord,
    val compressedByteCount: Long,
    val decodedByteCount: Long,
)

private const val CBLC_HEADER_LENGTH = 8
private const val CBDT_HEADER_LENGTH = 4
private const val BITMAP_SIZE_TABLE_LENGTH = 48
private const val INDEX_SUBTABLE_ARRAY_ENTRY_LENGTH = 8
private const val INDEX_SUBTABLE_HEADER_LENGTH = 8L
private const val SMALL_GLYPH_METRICS_LENGTH = 5
private const val BIG_GLYPH_METRICS_LENGTH = 8
private const val DATA_LENGTH_FIELD_LENGTH = 4
private const val INDEX_FORMAT_ONE = 1
private const val IMAGE_FORMAT_SMALL_METRICS = 17
private const val IMAGE_FORMAT_BIG_METRICS = 18
private const val HORIZONTAL_METRICS_FLAG = 0x01
private const val RESERVED_FLAGS_MASK = 0xFC
private const val RGBA_BYTES_PER_PIXEL = 4
private const val VERSION_2: UInt = 0x00020000u
private const val VERSION_3: UInt = 0x00030000u
private const val MAX_CAPABILITY_TABLE_BYTES = 16 * 1024 * 1024
private const val MAX_CAPABILITY_STRIKES = 64
private const val MAX_CAPABILITY_INDEX_SUBTABLES = 4_096
private const val MAX_CAPABILITY_RECORDS = 65_536
private const val MAX_CAPABILITY_DIMENSION = 255
private const val MAX_CAPABILITY_PIXELS = MAX_CAPABILITY_DIMENSION * MAX_CAPABILITY_DIMENSION
private const val MAX_CAPABILITY_DECODED_BYTES = 64 * 1024 * 1024
