@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.brotli.BrotliDecoder
import org.graphiks.kalligraphie.font.sfnt.checkedRangeEnd
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32

/**
 * Decodes one WOFF 2.0 container into a standalone SFNT under [WoffDecodeLimits].
 *
 * Header metadata is not a format gate: `reserved` may be non-zero (W3C WOFF2 §3.2 forbids
 * rejecting on it), `majorVersion`/`minorVersion` are file metadata, and `totalSfntSize` is
 * advisory (a correctly reconstructed font must not be rejected for disagreeing with it). Only the
 * signature, the declared `length`, a non-zero `numTables`, the directory encoding, the transform
 * matrix and the single Brotli font-data stream are enforced.
 *
 * The table directory maps `flags` bits 0-5 to [Woff2KnownTags] or a following four-byte tag, and
 * bits 6-7 to a transform version. `transformLength` is read exactly when the entry names a
 * non-null transform (`glyf`/`loca` version 0; `hmtx` version 1); an unknown transform version is
 * rejected as `font.woff2.unknown-transform`. `UIntBase128` rejects a leading `0x80`, a run longer
 * than five bytes, and a value above 2^32-1.
 *
 * The whole font-data block is the single `totalCompressedSize` slice immediately after the
 * directory. It is decompressed in one [BrotliDecoder.decode] call bounded by
 * `limits.maxDecodedFontBytes`/`limits.maxWorkingBytes`; the decoded length must equal the
 * directory sum (a malformed stream already failed as `font.woff2.brotli-failed`), then it is
 * split across the entries in directory order and handed to [SfntReassembler]. Per-table checksums
 * are recomputed because WOFF2 carries none.
 */
internal object Woff2Reader {
    private const val SIGNATURE: String = "wOF2"
    private const val COLLECTION_FLAVOR: UInt = 0x74746366u
    private const val HEADER_BYTES: Int = 48

    private const val INVALID_HEADER: String = "font.woff2.invalid-header"
    private const val INVALID_TABLE_DIRECTORY: String = "font.woff2.invalid-table-directory"
    private const val UNKNOWN_TRANSFORM: String = "font.woff2.unknown-transform"
    private const val INVALID_FONT_DATA_SIZE: String = "font.woff2.invalid-font-data-size"
    private const val TRANSFORM_FAILED: String = "font.woff2.transform-failed"

    /** Decodes [bytes], or returns a typed rejection. */
    fun decode(bytes: ByteArray, limits: WoffDecodeLimits): FontOperationResult<ByteArray> {
        if (bytes.size < HEADER_BYTES) return invalidHeader("The WOFF2 header is truncated.")
        if (bytes.decodeAsciiTag(0) != SIGNATURE) return invalidHeader("The WOFF2 signature is missing.")

        val flavor = readUInt32(bytes, 4)!!
        if (flavor == COLLECTION_FLAVOR) {
            return FontOperationResult.Failure(
                FontError.UnsupportedContainer("WOFF2 collections are not supported.", FontDiagnosticLocation.Source),
            )
        }
        val declaredLength = readUInt32(bytes, 8)!!.toLong()
        val numTables = readUInt16(bytes, 12)!!.toInt()
        // `reserved` (14), `totalSfntSize` (16), `majorVersion`/`minorVersion` (24/26) and the
        // metadata/private offsets are deliberately unread: none is a format gate (§6.3).
        val totalCompressedSize = readUInt32(bytes, 20)!!.toLong()

        if (numTables <= 0) return invalidHeader("The WOFF2 table directory is empty.")
        if (declaredLength != bytes.size.toLong()) {
            return invalidHeader("The WOFF2 declared length does not describe the available bytes.")
        }

        val entries = ArrayList<Entry>(numTables)
        val seenTags = HashSet<String>(numTables)
        var cursor = HEADER_BYTES
        for (index in 0 until numTables) {
            if (cursor >= bytes.size) return invalidDirectory("The WOFF2 table directory is truncated.")
            val flags = bytes[cursor].toInt() and 0xFF
            cursor++
            val tagIndex = flags and 0x3F
            val transformVersion = (flags ushr 6) and 0x03
            val tag = if (tagIndex == 0x3F) {
                if (cursor + 4 > bytes.size) return invalidDirectory("A WOFF2 custom tag is truncated.")
                val custom = bytes.decodeAsciiTag(cursor)
                cursor += 4
                custom
            } else {
                Woff2KnownTags.tagsByIndex[tagIndex]
            }
            if (!seenTags.add(tag)) return invalidDirectory("The WOFF2 table directory repeats the tag $tag.")

            val nonNullTransform = when (tag) {
                "glyf", "loca" -> when (transformVersion) {
                    0 -> true
                    3 -> false
                    else -> return unknownTransform(tag, transformVersion)
                }
                "hmtx" -> when (transformVersion) {
                    0 -> false
                    1 -> true
                    else -> return unknownTransform(tag, transformVersion)
                }
                else -> if (transformVersion == 0) false else return unknownTransform(tag, transformVersion)
            }

            val readOrigLength = readUIntBase128(bytes, cursor)
                ?: return invalidDirectory("WOFF2 table $tag has a malformed origLength.")
            cursor = readOrigLength.next
            var transformLength: Long? = null
            if (nonNullTransform) {
                val readTransformLength = readUIntBase128(bytes, cursor)
                    ?: return invalidDirectory("WOFF2 table $tag has a malformed transformLength.")
                transformLength = readTransformLength.value
                cursor = readTransformLength.next
                // A transformed `loca` is a placeholder: it consumes no font-data bytes. The
                // remaining loca consistency rules (original size vs `(numGlyphs+1) * entrySize`,
                // transform mode agreeing with `glyf`) need the reconstructed tables and land with
                // Task 8.
                if (tag == "loca" && transformLength != 0L) {
                    return invalidDirectory("A transformed WOFF2 loca table must declare a zero transformLength.")
                }
            }
            entries += Entry(tag, readOrigLength.value, nonNullTransform, transformLength)
        }

        // Task 8 (glyf/loca) and Task 9 (hmtx) implement transform reconstruction. Until then,
        // refuse a non-null transform rather than pass transformed bytes through as if they were
        // the original table. The rewritten `loca` consistency rules also land with Task 8.
        for (entry in entries) {
            if (entry.nonNullTransform) {
                return failure(
                    TRANSFORM_FAILED,
                    "WOFF2 table ${entry.tag} uses a transform that is not yet supported.",
                )
            }
        }

        var directorySum = 0L
        for (entry in entries) {
            directorySum += entry.transformLength ?: entry.origLength
        }

        val compressedEnd = checkedRangeEnd(cursor.toLong(), totalCompressedSize, bytes.size)
            ?: return invalidDirectory("The WOFF2 font-data block is out of bounds.")
        val block = bytes.copyOfRange(cursor, compressedEnd)
        val decompressed = when (
            val result = BrotliDecoder.decode(block, limits.maxDecodedFontBytes, limits.maxWorkingBytes)
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (decompressed.size.toLong() != directorySum) {
            return failure(
                INVALID_FONT_DATA_SIZE,
                "The WOFF2 font-data block is ${decompressed.size} bytes but the directory declares $directorySum.",
            )
        }

        val tables = ArrayList<SfntTable>(numTables)
        var offset = 0
        for (entry in entries) {
            val length = (entry.transformLength ?: entry.origLength).toInt()
            tables += SfntTable(entry.tag, decompressed.copyOfRange(offset, offset + length), null)
            offset += length
        }
        return SfntReassembler.assemble(flavor, tables, limits.maxDecodedFontBytes)
    }

    /**
     * Reads one W3C `UIntBase128` value starting at [start].
     *
     * Returns `null` for a leading `0x80` (leading zero), a run longer than five bytes, a value
     * that would exceed 2^32-1, or a truncated source.
     */
    private fun readUIntBase128(bytes: ByteArray, start: Int): Base128? {
        var accum = 0L
        var offset = start
        for (index in 0 until 5) {
            if (offset >= bytes.size) return null
            val dataByte = bytes[offset].toInt() and 0xFF
            offset++
            if (index == 0 && dataByte == 0x80) return null
            if (accum and 0xFE000000L != 0L) return null
            accum = (accum shl 7) or (dataByte and 0x7F).toLong()
            if (dataByte and 0x80 == 0) return Base128(accum, offset)
        }
        return null
    }

    private fun invalidHeader(message: String): FontOperationResult.Failure =
        failure(INVALID_HEADER, message)

    private fun invalidDirectory(message: String): FontOperationResult.Failure =
        failure(INVALID_TABLE_DIRECTORY, message)

    private fun unknownTransform(tag: String, version: Int): FontOperationResult.Failure =
        failure(UNKNOWN_TRANSFORM, "WOFF2 table $tag declares unknown transform version $version.")

    private fun failure(code: String, message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Source))

    private class Entry(
        val tag: String,
        val origLength: Long,
        val nonNullTransform: Boolean,
        val transformLength: Long?,
    )

    private class Base128(val value: Long, val next: Int)
}

/**
 * The W3C WOFF2 §4.1 "Known Table Tags" table, indexed by the `flags` bits 0-5.
 *
 * Index 63 is the sentinel for a four-byte tag that follows the flags byte and is therefore not
 * present here. Tags shorter than four characters keep their trailing space (`cvt `, `CFF `,
 * `SVG `), and `feat` (45) and `Feat` (61) are distinct entries.
 */
internal object Woff2KnownTags {
    val tagsByIndex: List<String> = listOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post",
        "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT",
        "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea",
        "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH",
        "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar",
        "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar",
        "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop",
        "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
    )
}
