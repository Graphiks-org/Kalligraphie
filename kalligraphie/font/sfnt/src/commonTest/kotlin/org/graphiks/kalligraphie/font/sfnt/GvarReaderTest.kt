@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult

class GvarReaderTest {
    @Test
    fun readsHeaderSharedTuplesAndShortOffsets() {
        val gvar = gvarTable(
            axisCount = 1,
            glyphCount = 1,
            longOffsets = false,
            sharedTuples = listOf(doubleArrayOf(1.0)),
            glyphRecords = listOf(ByteArray(0)),
        )
        val data = success(GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 1))
        assertEquals(1, data.axisCount)
        assertEquals(1, data.glyphCount)
    }

    @Test
    fun readsLongGlyphOffsets() {
        val gvar = gvarTable(
            axisCount = 1,
            glyphCount = 2,
            longOffsets = true,
            sharedTuples = emptyList(),
            glyphRecords = listOf(ByteArray(0), ByteArray(0)),
        )
        val data = success(GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 2))
        assertEquals(2, data.glyphCount)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(ByteArray(0)))
        gvar.writeUInt16(0, 2)
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 1),
        )
        assertEquals("font.variation.unsupported-gvar-version", failure.error.code)
    }

    @Test
    fun rejectsAxisCountMismatch() {
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(ByteArray(0)))
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(gvar, expectedAxisCount = 2, expectedGlyphCount = 1),
        )
        assertEquals("font.variation.invalid-gvar", failure.error.code)
    }

    @Test
    fun rejectsTruncatedHeader() {
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(ByteArray(12), expectedAxisCount = 1, expectedGlyphCount = 1),
        )
        assertEquals("font.variation.invalid-gvar", failure.error.code)
    }

    private fun <T> success(result: FontOperationResult<T>): T = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Unexpected failure: ${result.error}")
        is FontOperationResult.Cancelled -> error("Unexpected cancellation")
    }
}

private fun gvarTable(
    axisCount: Int,
    glyphCount: Int,
    longOffsets: Boolean,
    sharedTuples: List<DoubleArray>,
    glyphRecords: List<ByteArray>,
): ByteArray {
    require(glyphRecords.size == glyphCount)
    val headerSize = 20
    val entrySize = if (longOffsets) 4 else 2
    val offsetsSize = (glyphCount + 1) * entrySize
    val sharedTuplesOffset = if (sharedTuples.isEmpty()) 0 else headerSize + offsetsSize
    val sharedTuplesSize = sharedTuples.size * axisCount * 2
    val glyphDataOffset = headerSize + offsetsSize + sharedTuplesSize
    // Short gvar offsets are uint16 byte-offsets divided by two, so every glyph variation record
    // must start and end on an even byte. Pad each record to an even length (the tuple parser stops
    // at its own declared variationDataSize, so trailing zero padding is ignored).
    fun evenSize(byteCount: Int): Int = ((byteCount + 1) / 2) * 2
    val glyphDataSize = glyphRecords.sumOf { evenSize(it.size) }
    val bytes = ByteArray(glyphDataOffset + glyphDataSize)
    bytes.writeUInt16(0, 1)
    bytes.writeUInt16(2, 0)
    bytes.writeUInt16(4, axisCount)
    bytes.writeUInt16(6, sharedTuples.size)
    bytes.writeUInt32(8, sharedTuplesOffset)
    bytes.writeUInt16(12, glyphCount)
    bytes.writeUInt16(14, if (longOffsets) GVAR_LONG_OFFSETS else 0)
    bytes.writeUInt32(16, glyphDataOffset)
    // Directory entries are offsets RELATIVE to glyphDataOffset, matching GvarReader.read
    // (start = glyphDataStart + glyphOffsets[glyphId]). Short offsets are stored divided by two.
    var cursor = 0
    glyphRecords.forEachIndexed { index, record ->
        val recordOffset = glyphDataOffset + cursor
        if (longOffsets) bytes.writeUInt32(headerSize + index * 4, cursor) else bytes.writeUInt16(headerSize + index * 2, cursor / 2)
        record.copyInto(bytes, recordOffset)
        cursor += evenSize(record.size)
    }
    if (longOffsets) bytes.writeUInt32(headerSize + glyphCount * 4, cursor)
    else bytes.writeUInt16(headerSize + glyphCount * 2, cursor / 2)
    sharedTuples.forEachIndexed { tupleIndex, tuple ->
        tuple.forEachIndexed { axis, value ->
            bytes.writeInt16(sharedTuplesOffset + (tupleIndex * axisCount + axis) * 2, (value * 16_384.0).toInt())
        }
    }
    return bytes
}

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeInt16(offset: Int, value: Int) {
    writeUInt16(offset, value and 0xFFFF)
}

private fun ByteArray.writeUInt32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
