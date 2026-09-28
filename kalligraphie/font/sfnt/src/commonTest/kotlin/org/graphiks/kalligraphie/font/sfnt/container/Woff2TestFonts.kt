@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.io.encoding.Base64

/**
 * Test-only WOFF 2.0 fixtures.
 *
 * The canonical blob carries two tables: an untransformed `glyf` (transform version 3, the WOFF2
 * null transform) and an all-zero `head` (transform version 0). The `head` table is what lets
 * [SfntReassembler] patch `head.checkSumAdjustment`, so the reassembled font's whole-font checksum
 * is the `0xB1B0AFBA` magic. The directory order is `glyf` then `head`, and the concatenated table
 * bytes (`5 + 54 = 59` bytes) are the single Brotli font-data stream.
 *
 * Brotli compression is not available in `commonTest`, so the one valid compressed stream is
 * embedded as a base64 constant. It was produced with the Brotli CLI **1.2.0**
 * (`/opt/homebrew/bin/brotli`):
 *
 * ```
 * python3 -c "import sys; sys.stdout.buffer.write(bytes([0, 0, 0, 1, 4]) + bytes(54))" \
 *     | brotli -q 11 -c | base64
 * ```
 *
 * Every variant mutates the canonical blob in place; where a variant changes a declared length or
 * a `UIntBase128`, the mutation keeps the same byte width so the header `length` stays valid.
 */
internal object Woff2TestFonts {
    private const val WOFF2_FLAVOR: UInt = 0x00010000u
    private const val COLLECTION_FLAVOR: UInt = 0x74746366u
    private const val HEADER_BYTES: Int = 48

    /** Known-tag indices from W3C WOFF2 §4.1; `glyf` is 10 and `head` is 1. */
    private const val GLYF_INDEX: Int = 10
    private const val HEAD_INDEX: Int = 1

    /**
     * `glyf` (5 bytes, transform version 3) followed by `head` (54 bytes, transform version 0),
     * Brotli-compressed at quality 11. It decodes to exactly `0000000104` + 54 zero bytes.
     */
    private const val FONT_DATA: String = "HzoA+CcBAgjCjwAgowE="

    private val GLYF_BYTES: ByteArray = byteArrayOf(0, 0, 0, 1, 4)
    private val HEAD_BYTES: ByteArray = ByteArray(54)

    /** Offsets of the two mutating fields of the canonical directory. */
    private const val GLYF_FLAGS_OFFSET: Int = HEADER_BYTES
    private const val GLYF_ORIG_LENGTH_OFFSET: Int = HEADER_BYTES + 1

    /** A valid WOFF2 whose only content table, `glyf`, uses the null transform (version 3). */
    fun singleTableUntransformed(): ByteArray {
        val directory = ArrayList<Byte>()
        directory += ((3 shl 6) or GLYF_INDEX).toByte()
        writeBase128(directory, GLYF_BYTES.size)
        directory += ((0 shl 6) or HEAD_INDEX).toByte()
        writeBase128(directory, HEAD_BYTES.size)

        val compressed = Base64.decode(FONT_DATA)
        val length = HEADER_BYTES + directory.size + compressed.size
        val out = ByteArray(length)
        writeTag(out, 0, "wOF2")
        writeUInt32(out, 4, WOFF2_FLAVOR)
        writeUInt32(out, 8, length.toUInt())
        writeUInt16(out, 12, 2)
        writeUInt16(out, 14, 0)
        // The sfnt size is advisory; this value matches but the reader must not rely on it.
        writeUInt32(out, 16, (12 + 16 * 2 + align4(GLYF_BYTES.size) + align4(HEAD_BYTES.size)).toUInt())
        writeUInt32(out, 20, compressed.size.toUInt())
        writeUInt16(out, 24, 1)
        writeUInt16(out, 26, 0)
        directory.toByteArray().copyInto(out, HEADER_BYTES)
        compressed.copyInto(out, HEADER_BYTES + directory.size)
        return out
    }

    /** The canonical blob with a `totalSfntSize` that disagrees with the tables. */
    fun withWrongTotalSfntSize(): ByteArray = singleTableUntransformed().also {
        writeUInt32(it, 16, 0x7F000000u)
    }

    /** The canonical blob with a non-zero `reserved` field, which must not cause rejection. */
    fun withNonZeroReserved(): ByteArray = singleTableUntransformed().also {
        writeUInt16(it, 14, 0x1234)
    }

    /** The canonical blob with `flavor == 'ttcf'`, a collection that must be refused. */
    fun withCollectionFlavor(): ByteArray = singleTableUntransformed().also {
        writeUInt32(it, 4, COLLECTION_FLAVOR)
    }

    /** The canonical blob whose `glyf` entry declares an unknown transform version `1`. */
    fun withUnknownTransform(): ByteArray = singleTableUntransformed().also {
        it[GLYF_FLAGS_OFFSET] = ((1 shl 6) or GLYF_INDEX).toByte()
    }

    /** The canonical blob whose `glyf` `origLength` starts with the rejected `0x80` byte. */
    fun withBadUIntBase128(): ByteArray = singleTableUntransformed().also {
        it[GLYF_ORIG_LENGTH_OFFSET] = 0x80.toByte()
    }

    /**
     * The canonical blob whose `glyf` `origLength` is one byte too large, so the directory sum
     * (`60`) disagrees with the valid stream's decoded size (`59`) without being a Brotli failure.
     */
    fun withWrongDirectoryLength(): ByteArray = singleTableUntransformed().also {
        it[GLYF_ORIG_LENGTH_OFFSET] = (GLYF_BYTES.size + 1).toByte()
    }

    private fun writeBase128(target: MutableList<Byte>, value: Int) {
        var current = value
        val encoded = ArrayList<Int>(5)
        encoded += current and 0x7F
        current = current ushr 7
        while (current != 0) {
            encoded += (current and 0x7F) or 0x80
            current = current ushr 7
        }
        for (index in encoded.indices.reversed()) target += encoded[index].toByte()
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
}
