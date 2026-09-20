package org.graphiks.kalligraphie.platform.ios

/** One OpenType table copied out of a CoreText font. */
internal data class SfntTable(val tag: String, val bytes: ByteArray)

/** Packs a four-character OpenType tag into its big-endian 32-bit value. */
internal fun tagToUInt(tag: String): UInt {
    require(tag.length == 4) { "An OpenType tag has four characters: $tag" }
    return (tag[0].code.toUInt() shl 24) or
        (tag[1].code.toUInt() shl 16) or
        (tag[2].code.toUInt() shl 8) or
        tag[3].code.toUInt()
}

/**
 * Rebuilds a standalone SFNT container from CoreText-copied tables.
 *
 * iOS sandboxes apps away from system font files, but CoreText exposes each
 * table's bytes; this writes a valid table directory (sorted by tag, four-byte
 * padded, per-table checksums and a recomputed `head.checkSumAdjustment`) so the
 * captured faces are portable content. A table the platform does not supply is
 * simply absent; `DSIG` becomes stale once the container is rewritten.
 */
internal fun assembleSfnt(tables: List<SfntTable>): ByteArray {
    require(tables.isNotEmpty()) { "At least one table is required to assemble a font" }
    val sorted = tables.sortedBy { tagToUInt(it.tag) }
    val numTables = sorted.size
    var entrySelector = 0
    while ((1 shl (entrySelector + 1)) <= numTables) entrySelector++
    val searchRange = (1 shl entrySelector) * 16
    val rangeShift = numTables * 16 - searchRange
    val hasCff = sorted.any { it.tag == "CFF " || it.tag == "CFF2" }
    val version = if (hasCff) OTTO_VERSION else TRUETYPE_VERSION

    val headerSize = 12 + numTables * 16
    val offsets = IntArray(numTables)
    var cursor = headerSize
    for (index in 0 until numTables) {
        offsets[index] = cursor
        cursor += padded4(sorted[index].bytes.size)
    }
    val out = ByteArray(cursor)
    out.putU32(0, version)
    out.putU16(4, numTables)
    out.putU16(6, searchRange)
    out.putU16(8, entrySelector)
    out.putU16(10, rangeShift)
    for (index in 0 until numTables) {
        val table = sorted[index]
        val record = 12 + index * 16
        out.putU32(record, tagToUInt(table.tag))
        out.putU32(record + 4, checksum(table.bytes))
        out.putU32(record + 8, offsets[index].toUInt())
        out.putU32(record + 12, table.bytes.size.toUInt())
        table.bytes.copyInto(out, offsets[index])
    }
    val headIndex = sorted.indexOfFirst { it.tag == "head" }
    if (headIndex >= 0 && sorted[headIndex].bytes.size >= HEAD_CHECKSUM_OFFSET + 4) {
        val headOffset = offsets[headIndex]
        out.putU32(headOffset + HEAD_CHECKSUM_OFFSET, 0u)
        out.putU32(headOffset + HEAD_CHECKSUM_OFFSET, CHECKSUM_MAGIC - checksum(out))
    }
    return out
}

private const val TRUETYPE_VERSION = 0x00010000u
private const val OTTO_VERSION = 0x4F54544Fu
private const val CHECKSUM_MAGIC = 0xB1B0AFBAu
private const val HEAD_CHECKSUM_OFFSET = 8

private fun padded4(size: Int): Int = (size + 3) and 3.inv()

/** The OpenType table checksum: the sum of the table's big-endian 32-bit words, zero-padded. */
private fun checksum(bytes: ByteArray): UInt {
    var sum = 0u
    var index = 0
    while (index < bytes.size) {
        val word = (byteAt(bytes, index) shl 24) or
            (byteAt(bytes, index + 1) shl 16) or
            (byteAt(bytes, index + 2) shl 8) or
            byteAt(bytes, index + 3)
        sum += word.toUInt()
        index += 4
    }
    return sum
}

private fun byteAt(bytes: ByteArray, index: Int): Int =
    if (index < bytes.size) bytes[index].toInt() and 0xFF else 0

private fun ByteArray.putU32(position: Int, value: UInt) {
    this[position] = (value shr 24).toByte()
    this[position + 1] = (value shr 16).toByte()
    this[position + 2] = (value shr 8).toByte()
    this[position + 3] = value.toByte()
}

private fun ByteArray.putU16(position: Int, value: Int) {
    this[position] = (value shr 8).toByte()
    this[position + 1] = value.toByte()
}
