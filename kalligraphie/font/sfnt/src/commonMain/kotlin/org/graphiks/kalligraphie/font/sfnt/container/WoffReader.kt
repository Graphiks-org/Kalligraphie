@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.InflateOutcome
import org.graphiks.kalligraphie.font.sfnt.checkedRangeEnd
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag
import org.graphiks.kalligraphie.font.sfnt.platformInflateSupport
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32

/**
 * Decodes one WOFF 1.0 container into a standalone SFNT under [WoffDecodeLimits].
 *
 * The complete declared file extent is validated before any table is produced: the header field
 * map, `reserved`, `numTables`, the normative `totalSfntSize`, the table/metadata/private extents
 * (aligned, non-overlapping, with absent optional blocks zeroed) and each record's
 * `compLength <= origLength`. Tables with `compLength == origLength` are copied raw; a shorter
 * `compLength` is inflated as a **zlib-wrapped** stream to exactly `origLength`, bounded by
 * `limits.maxDecodedFontBytes`. `flavor == 'ttcf'` is refused as a collection. Decoded tables are
 * handed to [SfntReassembler] with each record's `origChecksum`.
 */
internal object WoffReader {
    private const val SIGNATURE: String = "wOFF"
    private const val COLLECTION_FLAVOR: UInt = 0x74746366u
    private const val WOFF_HEADER_BYTES: Int = 44
    private const val WOFF_RECORD_BYTES: Int = 20
    private const val SFNT_HEADER_BYTES: Int = 12
    private const val SFNT_RECORD_BYTES: Int = 16

    /** Decodes [bytes], or returns a typed rejection. */
    fun decode(bytes: ByteArray, limits: WoffDecodeLimits): FontOperationResult<ByteArray> {
        if (bytes.size < WOFF_HEADER_BYTES) return invalidHeader("The WOFF header is truncated.")
        if (bytes.decodeAsciiTag(0) != SIGNATURE) return invalidHeader("The WOFF signature is missing.")

        val flavor = readUInt32(bytes, 4)!!
        val declaredLength = readUInt32(bytes, 8)!!.toLong()
        val numTables = readUInt16(bytes, 12)!!.toInt()
        val reserved = readUInt16(bytes, 14)!!.toInt()
        val totalSfntSize = readUInt32(bytes, 16)!!.toLong()
        val metaOffset = readUInt32(bytes, 24)!!.toLong()
        val metaLength = readUInt32(bytes, 28)!!.toLong()
        val metaOrigLength = readUInt32(bytes, 32)!!.toLong()
        val privateOffset = readUInt32(bytes, 36)!!.toLong()
        val privateLength = readUInt32(bytes, 40)!!.toLong()

        if (reserved != 0) return invalidHeader("The WOFF reserved field must be zero.")
        if (numTables <= 0) return invalidHeader("The WOFF table directory is empty.")
        if (declaredLength != bytes.size.toLong()) {
            return invalidHeader("The WOFF declared length does not describe the available bytes.")
        }
        val directoryEnd = WOFF_HEADER_BYTES.toLong() + WOFF_RECORD_BYTES.toLong() * numTables
        if (directoryEnd > bytes.size.toLong()) return invalidHeader("The WOFF table directory is truncated.")
        if (flavor == COLLECTION_FLAVOR) {
            return FontOperationResult.Failure(
                FontError.UnsupportedContainer("WOFF collections are not supported.", FontDiagnosticLocation.Source),
            )
        }

        val records = ArrayList<TableRecord>(numTables)
        val seenTags = HashSet<String>(numTables)
        var expectedSfntSize = SFNT_HEADER_BYTES.toLong() + SFNT_RECORD_BYTES.toLong() * numTables
        for (index in 0 until numTables) {
            val base = WOFF_HEADER_BYTES + index * WOFF_RECORD_BYTES
            val tag = bytes.decodeAsciiTag(base)
            val offset = readUInt32(bytes, base + 4)!!.toLong()
            val compressedLength = readUInt32(bytes, base + 8)!!.toLong()
            val originalLength = readUInt32(bytes, base + 12)!!.toLong()
            val checksum = readUInt32(bytes, base + 16)!!
            if (!seenTags.add(tag)) return invalidDirectory("The WOFF table directory repeats the tag $tag.")
            if (compressedLength > originalLength) {
                return invalidDirectory("WOFF table $tag compresses larger than its original size.")
            }
            if (offset % 4L != 0L) return invalidDirectory("WOFF table $tag is not four-byte aligned.")
            if (offset < directoryEnd) return invalidDirectory("WOFF table $tag overlaps the header or directory.")
            checkedRangeEnd(offset, compressedLength, bytes.size)
                ?: return invalidDirectory("WOFF table $tag is out of bounds.")
            if (align4(offset + compressedLength) > bytes.size.toLong()) {
                return invalidDirectory("WOFF table $tag padding is out of bounds.")
            }
            if (originalLength > limits.maxDecodedFontBytes) {
                return limit(
                    "WOFF table $tag declares $originalLength bytes, over the " +
                        "${limits.maxDecodedFontBytes}-byte decoding limit.",
                )
            }
            expectedSfntSize += align4(originalLength)
            records += TableRecord(tag, offset, compressedLength, originalLength, checksum)
        }
        if (totalSfntSize != expectedSfntSize) {
            return invalidHeader("The WOFF totalSfntSize does not match the table directory.")
        }

        val metadataAbsent = metaOffset == 0L && metaLength == 0L && metaOrigLength == 0L
        val metadataPresent = metaOffset != 0L && metaLength != 0L && metaOrigLength != 0L
        if (!metadataAbsent && !metadataPresent) {
            return invalidHeader("The WOFF metadata offset, length and original length must agree.")
        }
        val privateAbsent = privateOffset == 0L && privateLength == 0L
        val privatePresent = privateOffset != 0L && privateLength != 0L
        if (!privateAbsent && !privatePresent) {
            return invalidHeader("The WOFF private-data offset and length must agree.")
        }
        if (metadataPresent) {
            optionalBlockFailure(metaOffset, metaLength, directoryEnd, bytes.size, "metadata")?.let { return it }
        }
        if (privatePresent) {
            optionalBlockFailure(privateOffset, privateLength, directoryEnd, bytes.size, "private data")?.let { return it }
        }

        val extents = ArrayList<Extent>(records.size + 2)
        for (record in records) {
            extents += Extent(record.offset, align4(record.offset + record.compressedLength), isTable = true)
        }
        if (metadataPresent) {
            extents += Extent(metaOffset, align4(metaOffset + metaLength), isTable = false)
        }
        if (privatePresent) {
            extents += Extent(privateOffset, align4(privateOffset + privateLength), isTable = false)
        }
        extents.sortBy { it.start }
        for (index in 1 until extents.size) {
            val previous = extents[index - 1]
            val current = extents[index]
            if (current.start < previous.end) {
                return if (previous.isTable || current.isTable) {
                    invalidDirectory("The WOFF table, metadata and private-data extents overlap.")
                } else {
                    invalidHeader("The WOFF metadata and private-data extents overlap.")
                }
            }
        }

        val tables = ArrayList<SfntTable>(numTables)
        for (record in records) {
            val data = if (record.compressedLength == record.originalLength) {
                bytes.copyOfRange(record.offset.toInt(), (record.offset + record.compressedLength).toInt())
            } else {
                when (val inflated = inflate(bytes, record)) {
                    is FontOperationResult.Success -> inflated.value
                    is FontOperationResult.Failure -> return inflated
                    is FontOperationResult.Cancelled -> return inflated
                }
            }
            tables += SfntTable(record.tag, data, record.checksum)
        }
        return SfntReassembler.assemble(flavor, tables, limits.maxDecodedFontBytes)
    }

    /**
     * Validates one declared optional block. Returns `null` when [offset]/[length] describe an
     * aligned in-bounds extent after the directory.
     */
    private fun optionalBlockFailure(
        offset: Long,
        length: Long,
        directoryEnd: Long,
        sourceSize: Int,
        label: String,
    ): FontOperationResult.Failure? {
        if (offset % 4L != 0L) return invalidHeader("The WOFF $label is not four-byte aligned.")
        if (offset < directoryEnd) return invalidHeader("The WOFF $label overlaps the header or directory.")
        checkedRangeEnd(offset, length, sourceSize)
            ?: return invalidHeader("The WOFF $label is out of bounds.")
        if (align4(offset + length) > sourceSize.toLong()) {
            return invalidHeader("The WOFF $label padding is out of bounds.")
        }
        return null
    }

    /** Inflates one zlib-wrapped table to exactly its declared original length. */
    private fun inflate(
        bytes: ByteArray,
        record: TableRecord,
    ): FontOperationResult<ByteArray> {
        val start = record.offset.toInt()
        val compressed = bytes.copyOfRange(start, start + record.compressedLength.toInt())
        // The portable seam, not Okio directly: Okio's `InflaterSource` exists on the JVM and native
        // targets alone, and the web target inflates through its own synchronous DEFLATE.
        return when (val outcome = platformInflateSupport().inflateZlib(compressed, record.originalLength)) {
            is InflateOutcome.Success ->
                if (outcome.bytes.size.toLong() == record.originalLength) {
                    FontOperationResult.Success(outcome.bytes)
                } else {
                    invalidDeflate("WOFF table ${record.tag} inflates to the wrong length.")
                }

            is InflateOutcome.Malformed ->
                invalidDeflate("WOFF table ${record.tag} has a malformed zlib stream.")

            is InflateOutcome.LimitExceeded ->
                invalidDeflate("WOFF table ${record.tag} inflates beyond its declared length.")
        }
    }

    private fun invalidHeader(message: String): FontOperationResult.Failure =
        failure("font.woff.invalid-header", message)

    private fun invalidDirectory(message: String): FontOperationResult.Failure =
        failure("font.woff.invalid-table-directory", message)

    private fun invalidDeflate(message: String): FontOperationResult.Failure =
        failure("font.woff.invalid-deflate", message)

    private fun limit(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Source))

    private fun failure(code: String, message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Source))

    private fun align4(value: Long): Long = (value + 3L) / 4L * 4L

    private class TableRecord(
        val tag: String,
        val offset: Long,
        val compressedLength: Long,
        val originalLength: Long,
        val checksum: UInt,
    )

    private class Extent(val start: Long, val end: Long, val isTable: Boolean)
}
