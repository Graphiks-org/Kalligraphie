package org.graphiks.kalligraphie.font.sfnt.variation

import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.assertIs

/** DeltaSetIndexMap format 0 with `mapCount` identical `(outer, inner)` entries. */
internal fun deltaSetIndexMap0(outer: Int, inner: Int, mapCount: Int): ByteArray {
    val innerBitCount = 8
    val entryFormat = 0x00 or (innerBitCount - 1)
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    u8(0)
    u8(entryFormat)
    u16(mapCount)
    repeat(mapCount) { u8((outer shl innerBitCount) or inner) }
    return out.toByteArray()
}

/**
 * Builds a one-axis format-1 ItemVariationStore with region `(0, 1, 1)` and one item variation data
 * subtable. [itemDeltas] is the list of rows; each row is one int16 delta per region.
 */
internal fun itemVariationStore(itemDeltas: List<IntArray>): ByteArray {
    val regionListOffset = 12
    val regionListSize = 4 + 6
    val itemDataOffset = regionListOffset + regionListSize
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
    u16(1)
    u32(regionListOffset)
    u16(1)
    u32(itemDataOffset)
    u16(1)
    u16(1)
    u16(0x0000); u16(0x4000); u16(0x4000)
    u16(itemDeltas.size)
    u16(itemDeltas.firstOrNull()?.size ?: 0)
    u16(1)
    u16(0)
    itemDeltas.forEach { row -> row.forEach { u16(it and 0xFFFF) } }
    return out.toByteArray()
}

/** Writes the big-endian 16-bit [value] at [offset]. */
internal fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
}

/** Writes the big-endian 24-bit [value] at [offset]. */
internal fun writeUInt24(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 16).toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = value.toByte()
}

/** Writes the big-endian 32-bit [value] at [offset]. */
internal fun writeUInt32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

/** Unwraps a successful [FontOperationResult] or fails the test with the typed failure. */
internal fun <T> success(result: FontOperationResult<T>): T = assertIs<FontOperationResult.Success<T>>(result).value
