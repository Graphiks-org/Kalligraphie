@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32

/**
 * Test-only WOFF 1.0 fixtures.
 *
 * The base SFNT carries the two tables the round-trip tests require: a `cmap`, so the reassembled
 * directory's first record is `cmap`, and an all-zero `head`, so the reassembler's checksum
 * sequencing produces the `0xB1B0AFBA` whole-font checksum. Table data is deliberately not a
 * multiple of four bytes long so padding is exercised.
 */
internal object WoffTestFonts {
    private const val SFNT_FLAVOR: UInt = 0x00010000u
    private const val WOFF_HEADER_BYTES: Int = 44
    private const val WOFF_RECORD_BYTES: Int = 20
    private const val SFNT_HEADER_BYTES: Int = 12
    private const val SFNT_RECORD_BYTES: Int = 16
    private const val HEAD_ADJUSTMENT_OFFSET: Int = 8

    /** Minimal valid SFNT carrying a `cmap` and a `head`. */
    fun singleTableSfnt(): ByteArray {
        val tables = listOf(
            SfntTable("cmap", byteArrayOf(0, 0, 0, 1, 4)),
            SfntTable("head", ByteArray(54)),
        )
        return (SfntReassembler.assemble(SFNT_FLAVOR, tables, 1L shl 20) as FontOperationResult.Success).value
    }

    /** Wraps [font] as a WOFF file whose tables are stored uncompressed. */
    fun wrapUncompressed(font: ByteArray = singleTableSfnt(), flavor: UInt = SFNT_FLAVOR): ByteArray =
        wrap(font, flavor, compress = false)

    /** Wraps [font] as a WOFF file, zlib-deflating every table that shrinks. */
    fun wrapDeflated(font: ByteArray = singleTableSfnt(), flavor: UInt = SFNT_FLAVOR): ByteArray =
        wrap(font, flavor, compress = true)

    /** An otherwise valid WOFF whose header `totalSfntSize` (bytes 16-19) is inconsistent. */
    fun withWrongTotalSfntSize(): ByteArray {
        val woff = wrapUncompressed()
        woff[16] = 0x7F
        woff[17] = 0
        woff[18] = 0
        woff[19] = 0
        return woff
    }

    /** An otherwise valid WOFF whose second directory record duplicates the first record's tag. */
    fun wrapWithDuplicateTag(): ByteArray {
        val woff = wrapUncompressed()
        val secondRecord = WOFF_HEADER_BYTES + WOFF_RECORD_BYTES
        "cmap".forEachIndexed { index, character -> woff[secondRecord + index] = character.code.toByte() }
        return woff
    }

    /** An otherwise valid WOFF whose header `reserved` field (bytes 14-15) is non-zero. */
    fun withNonZeroReserved(): ByteArray = wrapUncompressed().also {
        it[15] = 0x01
    }

    /** An otherwise valid WOFF whose second record's extent starts where the first record's does. */
    fun withOverlappingExtents(): ByteArray {
        val woff = wrapUncompressed()
        val firstOffset = readUInt32(woff, WOFF_HEADER_BYTES + 4)!!
        writeUInt32(woff, WOFF_HEADER_BYTES + WOFF_RECORD_BYTES + 4, firstOffset)
        return woff
    }

    /** An otherwise valid WOFF whose first record's `compLength` exceeds its `origLength`. */
    fun withCompressedLargerThanOriginal(): ByteArray {
        val woff = wrapUncompressed()
        val originalLength = readUInt32(woff, WOFF_HEADER_BYTES + 12)!!
        writeUInt32(woff, WOFF_HEADER_BYTES + 8, originalLength + 1u)
        return woff
    }

    /** An otherwise valid deflated WOFF whose first compressed table has a malformed zlib header. */
    fun withMalformedDeflate(): ByteArray {
        val woff = wrapDeflated()
        for (index in 0 until readUInt16(woff, 12)!!.toInt()) {
            val base = WOFF_HEADER_BYTES + index * WOFF_RECORD_BYTES
            val compressedLength = readUInt32(woff, base + 8)!!.toLong()
            val originalLength = readUInt32(woff, base + 12)!!.toLong()
            if (compressedLength < originalLength) {
                val offset = readUInt32(woff, base + 4)!!.toInt()
                woff[offset] = 0x00
                woff[offset + 1] = 0x00
                return woff
            }
        }
        return woff
    }

    private fun wrap(font: ByteArray, flavor: UInt, compress: Boolean): ByteArray {
        val records = sfntRecords(font)
        val directoryBytes = WOFF_HEADER_BYTES + WOFF_RECORD_BYTES * records.size
        val entries = ArrayList<Entry>(records.size)
        val stored = ArrayList<ByteArray>(records.size)
        var cursor = directoryBytes
        for (record in records) {
            val raw = font.copyOfRange(record.offset, record.offset + record.length)
            val original = if (record.tag == "head" && record.length > HEAD_ADJUSTMENT_OFFSET) {
                zeroHeadAdjustment(raw)
            } else {
                raw
            }
            val compressed = if (compress) ZlibEncoder.encode(original) else original
            val data = if (compress && compressed.size < original.size) compressed else original
            entries += Entry(record.tag, cursor, data.size, original.size, record.checksum)
            stored += data
            cursor += align4(data.size)
        }
        return build(flavor, entries, stored)
    }

    private fun build(flavor: UInt, entries: List<Entry>, stored: List<ByteArray>): ByteArray {
        val length = WOFF_HEADER_BYTES + WOFF_RECORD_BYTES * entries.size + stored.sumOf { align4(it.size) }
        val sfntSize = SFNT_HEADER_BYTES + SFNT_RECORD_BYTES * entries.size +
            entries.sumOf { align4(it.originalLength) }
        val out = ByteArray(length)
        writeTag(out, 0, "wOFF")
        writeUInt32(out, 4, flavor)
        writeUInt32(out, 8, length.toUInt())
        writeUInt16(out, 12, entries.size)
        writeUInt16(out, 14, 0)
        writeUInt32(out, 16, sfntSize.toUInt())
        writeUInt16(out, 20, 1)
        writeUInt16(out, 22, 0)
        for ((index, entry) in entries.withIndex()) {
            val base = WOFF_HEADER_BYTES + index * WOFF_RECORD_BYTES
            writeTag(out, base, entry.tag)
            writeUInt32(out, base + 4, entry.offset.toUInt())
            writeUInt32(out, base + 8, entry.compressedLength.toUInt())
            writeUInt32(out, base + 12, entry.originalLength.toUInt())
            writeUInt32(out, base + 16, entry.checksum)
            stored[index].copyInto(out, entry.offset)
        }
        return out
    }

    private fun sfntRecords(font: ByteArray): List<Record> {
        val numTables = readUInt16(font, 4)!!.toInt()
        return (0 until numTables).map { index ->
            val base = SFNT_HEADER_BYTES + index * SFNT_RECORD_BYTES
            Record(
                tag = font.decodeAsciiTag(base),
                checksum = readUInt32(font, base + 4)!!,
                offset = readUInt32(font, base + 8)!!.toInt(),
                length = readUInt32(font, base + 12)!!.toInt(),
            )
        }
    }

    private fun zeroHeadAdjustment(data: ByteArray): ByteArray = data.copyOf().also {
        for (index in HEAD_ADJUSTMENT_OFFSET until HEAD_ADJUSTMENT_OFFSET + 4) it[index] = 0
    }

    private fun align4(value: Int): Int = (value + 3) / 4 * 4

    private fun writeUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8 and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt32(target: ByteArray, offset: Int, value: UInt) {
        target[offset] = (value shr 24 and 0xFFu).toByte()
        target[offset + 1] = (value shr 16 and 0xFFu).toByte()
        target[offset + 2] = (value shr 8 and 0xFFu).toByte()
        target[offset + 3] = (value and 0xFFu).toByte()
    }

    private fun writeTag(target: ByteArray, offset: Int, tag: String) {
        for (index in 0 until 4) {
            target[offset + index] = if (index < tag.length) tag[index].code.toByte() else 0
        }
    }

    private class Record(val tag: String, val checksum: UInt, val offset: Int, val length: Int)

    private class Entry(
        val tag: String,
        val offset: Int,
        val compressedLength: Int,
        val originalLength: Int,
        val checksum: UInt,
    )
}
