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

    @Test
    fun rejectsGlyphCountMismatch() {
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(ByteArray(0)))
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 2),
        )
        assertEquals("font.variation.invalid-gvar", failure.error.code)
    }

    @Test
    fun rejectsNonMonotonicGlyphOffsets() {
        // Two two-byte records produce long offsets 0, 2, 4; swap the first two to descend.
        val gvar = gvarTable(1, 2, true, emptyList(), listOf(ByteArray(2), ByteArray(2)))
        gvar.writeUInt32(20, 4)
        gvar.writeUInt32(24, 2)
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 2),
        )
        assertEquals("font.variation.invalid-gvar", failure.error.code)
    }

    @Test
    fun rejectsGlyphDataOutOfRange() {
        // The final short offset is stored halved at headerSize + glyphCount * 2; 100 decodes to 200,
        // which overshoots the empty glyph-data region at the end of the table.
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(ByteArray(0)))
        gvar.writeUInt16(22, 100)
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 1),
        )
        assertEquals("font.variation.invalid-gvar", failure.error.code)
    }

    @Test
    fun rejectsSharedTupleCountAboveLimit() {
        val gvar = gvarTable(
            1,
            1,
            false,
            listOf(doubleArrayOf(1.0), doubleArrayOf(0.5)),
            listOf(ByteArray(0)),
        )
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 1, limits = GvarLimits(maxSharedTuples = 1)),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun rejectsSourceBytesAboveLimit() {
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(ByteArray(0)))
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 1, limits = GvarLimits(maxSourceBytes = 1)),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun rejectsSharedTuplesOffsetInsideHeader() {
        val gvar = gvarTable(1, 1, false, listOf(doubleArrayOf(1.0)), listOf(ByteArray(0)))
        // Shared-tuples pointer (header field at byte 8) collides with the 20-byte header.
        gvar.writeUInt32(8, 4)
        val failure = assertIs<FontOperationResult.Failure>(
            GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 1),
        )
        assertEquals("font.variation.invalid-gvar", failure.error.code)
    }

    @Test
    fun readsDifferentlySizedShortOffsetRecordsWithMultipleGlyphs() {
        // Records of 3 and 1 bytes pad to 4 and 2; short offsets are stored as 0, 2, 3. A wrong
        // entry size (4 instead of 2) or over-scaled offset would overshoot and fail.
        val gvar = gvarTable(1, 2, false, emptyList(), listOf(ByteArray(3), ByteArray(1)))
        val data = success(GvarReader.read(gvar, expectedAxisCount = 1, expectedGlyphCount = 2))
        assertEquals(1, data.axisCount)
        assertEquals(2, data.glyphCount)
    }

    @Test
    fun returnsNullForGlyphWithoutVariationData() {
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(ByteArray(0)))
        val data = success(GvarReader.read(gvar, 1, 1))
        val deltas = success(data.glyphDeltas(0, listOf(0), listOf(100.0), listOf(200.0), listOf(1.0)))
        assertEquals(null, deltas)
    }

    @Test
    fun appliesCompletePointSetDeltasAtEmbeddedPeak() {
        val record = gvarGlyphRecord(
            sharedPointNumbers = null,
            tuples = listOf(
                gvarTuple(
                    peak = listOf(1.0),
                    data = packedDeltas(intArrayOf(10, 0, 0, 0, 0)) + packedDeltas(intArrayOf(0, 0, 0, 0, 0)),
                ),
            ),
        )
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(record))
        val data = success(GvarReader.read(gvar, 1, 1))
        val deltas = success(data.glyphDeltas(0, listOf(0), listOf(100.0), listOf(200.0), listOf(1.0)))
        assertEquals(10.0, deltas!!.xDelta(0))
        assertEquals(0.0, deltas.yDelta(0))
    }

    @Test
    fun returnsZeroDeltasAtDefaultCoordinates() {
        val record = gvarGlyphRecord(
            sharedPointNumbers = null,
            tuples = listOf(
                gvarTuple(
                    peak = listOf(1.0),
                    data = packedDeltas(intArrayOf(10, 0, 0, 0, 0)) + packedDeltas(intArrayOf(0, 0, 0, 0, 0)),
                ),
            ),
        )
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(record))
        val data = success(GvarReader.read(gvar, 1, 1))
        val deltas = success(data.glyphDeltas(0, listOf(0), listOf(100.0), listOf(200.0), listOf(0.0)))
        assertEquals(0.0, deltas!!.xDelta(0))
    }

    @Test
    fun usesSharedTuplePeakWhenNotEmbedded() {
        val record = gvarGlyphRecord(
            sharedPointNumbers = null,
            tuples = listOf(
                gvarTuple(
                    peak = listOf(1.0),
                    embeddedPeak = false,
                    sharedTupleIndex = 0,
                    data = packedDeltas(intArrayOf(7, 0, 0, 0, 0)) + packedDeltas(intArrayOf(0, 0, 0, 0, 0)),
                ),
            ),
        )
        val gvar = gvarTable(1, 1, false, listOf(doubleArrayOf(1.0)), listOf(record))
        val data = success(GvarReader.read(gvar, 1, 1))
        val deltas = success(data.glyphDeltas(0, listOf(0), listOf(100.0), listOf(200.0), listOf(1.0)))
        assertEquals(7.0, deltas!!.xDelta(0))
    }

    @Test
    fun interpolatesUntouchedPointsWithPrivatePointNumbers() {
        val record = gvarGlyphRecord(
            sharedPointNumbers = null,
            tuples = listOf(
                gvarTuple(
                    peak = listOf(1.0),
                    privatePointNumbers = true,
                    data = packedPoints(intArrayOf(0, 2)) +
                        packedDeltas(intArrayOf(0, 100)) +
                        packedDeltas(intArrayOf(0, 0)),
                ),
            ),
        )
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(record))
        val data = success(GvarReader.read(gvar, 1, 1))
        val deltas = success(
            data.glyphDeltas(0, listOf(2), listOf(0.0, 50.0, 100.0), listOf(0.0, 0.0, 0.0), listOf(1.0)),
        )
        assertEquals(50.0, deltas!!.xDelta(1))
    }

    @Test
    fun scalesDeltasByIntermediateRegionFactor() {
        val record = gvarGlyphRecord(
            sharedPointNumbers = null,
            tuples = listOf(
                gvarTuple(
                    peak = listOf(1.0),
                    intermediate = true,
                    start = listOf(0.0),
                    end = listOf(1.5),
                    data = packedDeltas(intArrayOf(20, 0, 0, 0, 0)) + packedDeltas(intArrayOf(0, 0, 0, 0, 0)),
                ),
            ),
        )
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(record))
        val data = success(GvarReader.read(gvar, 1, 1))
        val deltas = success(data.glyphDeltas(0, listOf(0), listOf(100.0), listOf(200.0), listOf(1.25)))
        assertEquals(10.0, deltas!!.xDelta(0))
    }

    @Test
    fun returnsNullForGlyphIdOutOfRange() {
        val gvar = gvarTable(1, 1, false, emptyList(), listOf(ByteArray(0)))
        val data = success(GvarReader.read(gvar, 1, 1))
        assertEquals(null, success(data.glyphDeltas(4, listOf(0), listOf(0.0), listOf(0.0), listOf(1.0))))
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

private class GvarTupleBuilder(
    val peak: List<Double>,
    val embeddedPeak: Boolean,
    val intermediate: Boolean,
    val start: List<Double>,
    val end: List<Double>,
    val sharedTupleIndex: Int,
    val privatePointNumbers: Boolean,
    val data: ByteArray,
)

private fun gvarTuple(
    peak: List<Double>,
    embeddedPeak: Boolean = true,
    intermediate: Boolean = false,
    start: List<Double> = emptyList(),
    end: List<Double> = emptyList(),
    sharedTupleIndex: Int = 0,
    privatePointNumbers: Boolean = false,
    data: ByteArray,
): GvarTupleBuilder = GvarTupleBuilder(peak, embeddedPeak, intermediate, start, end, sharedTupleIndex, privatePointNumbers, data)

private fun gvarGlyphRecord(sharedPointNumbers: IntArray?, tuples: List<GvarTupleBuilder>): ByteArray {
    val axisCount = tuples.firstOrNull()?.peak?.size ?: 0
    var headerSize = 4
    for (tuple in tuples) {
        headerSize += 4
        if (tuple.embeddedPeak) headerSize += axisCount * 2
        if (tuple.intermediate) headerSize += axisCount * 4
    }
    val serialized = ArrayList<ByteArray>()
    if (sharedPointNumbers != null) serialized += packedPoints(sharedPointNumbers)
    tuples.forEach { serialized += it.data }
    val serializedSize = serialized.sumOf { it.size }
    val record = ByteArray(headerSize + serializedSize)
    var tupleBits = tuples.size
    if (sharedPointNumbers != null) tupleBits = tupleBits or GVAR_SHARED_POINT_NUMBERS
    record.writeUInt16(0, tupleBits)
    record.writeUInt16(2, headerSize)
    var headerOffset = 4
    for (tuple in tuples) {
        record.writeUInt16(headerOffset, tuple.data.size)
        var tupleIndex = if (tuple.embeddedPeak) GVAR_EMBEDDED_PEAK_TUPLE else tuple.sharedTupleIndex and GVAR_TUPLE_INDEX_MASK
        if (tuple.intermediate) tupleIndex = tupleIndex or GVAR_INTERMEDIATE_REGION
        if (tuple.privatePointNumbers) tupleIndex = tupleIndex or GVAR_PRIVATE_POINT_NUMBERS
        record.writeUInt16(headerOffset + 2, tupleIndex)
        headerOffset += 4
        if (tuple.embeddedPeak) {
            tuple.peak.forEachIndexed { axis, value ->
                record.writeInt16(headerOffset + axis * 2, (value * 16_384.0).toInt())
            }
            headerOffset += axisCount * 2
        }
        if (tuple.intermediate) {
            tuple.start.forEachIndexed { axis, value ->
                record.writeInt16(headerOffset + axis * 2, (value * 16_384.0).toInt())
            }
            headerOffset += axisCount * 2
            tuple.end.forEachIndexed { axis, value ->
                record.writeInt16(headerOffset + axis * 2, (value * 16_384.0).toInt())
            }
            headerOffset += axisCount * 2
        }
    }
    var dataOffset = headerSize
    serialized.forEach { block ->
        block.copyInto(record, dataOffset)
        dataOffset += block.size
    }
    return record
}

private fun packedPoints(points: IntArray): ByteArray {
    val count = points.size
    val header = if (count < 0x80) byteArrayOf(count.toByte()) else byteArrayOf((0x80 or (count ushr 8)).toByte(), count.toByte())
    val body = ArrayList<Byte>(count * 3)
    var previous = 0
    for (point in points) {
        val delta = point - previous
        previous = point
        if (delta <= 0x7F) {
            body += 0x00
            body += delta.toByte()
        } else {
            body += 0x80.toByte()
            body += (delta ushr 8).toByte()
            body += delta.toByte()
        }
    }
    return header + body.toByteArray()
}

private fun packedDeltas(values: IntArray): ByteArray {
    val body = ArrayList<Byte>(values.size * 3)
    for (value in values) {
        when {
            value == 0 -> body += 0x80.toByte()
            value in -128..127 -> {
                body += 0x00
                body += value.toByte()
            }
            else -> {
                body += 0x40.toByte()
                body += (value ushr 8).toByte()
                body += value.toByte()
            }
        }
    }
    return body.toByteArray()
}
