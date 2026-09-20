@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult

class AvarReaderTest {
    @Test
    fun readsOneSegmentMapPerAxis() {
        val result = AvarReader.read(avarTable(axisCount = 2), expectedAxisCount = 2)
        assertIs<FontOperationResult.Success<AvarData>>(result)
        assertEquals(2, result.value.axisSegmentMaps.size)
        val first = result.value.axisSegmentMaps[0]
        assertEquals(-1f, first.first().from)
        assertEquals(1f, first.last().from)
    }

    @Test
    fun decodesNonIdentitySegmentsOnBothChannels() {
        val result = AvarReader.read(
            avarTableWithSegments(listOf(-1f to -1f, 0f to -0.5f, 1f to 1f)),
            expectedAxisCount = 1,
        )
        assertIs<FontOperationResult.Success<AvarData>>(result)
        val map = result.value.axisSegmentMaps[0]
        assertEquals(0f, map[1].from)
        assertEquals(-0.5f, map[1].to)
    }

    @Test
    fun rejectsAxisCountMismatch() {
        val result = AvarReader.read(avarTable(axisCount = 2), expectedAxisCount = 1)
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.invalid-avar", result.error.code)
    }

    @Test
    fun rejectsNonIncreasingFromCoordinates() {
        val result = AvarReader.read(avarTableWithBrokenSegments(), expectedAxisCount = 1)
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.invalid-avar", result.error.code)
    }

    @Test
    fun rejectsVersionTwoUntilSupported() {
        val result = AvarReader.read(avarTable(axisCount = 1, major = 2), expectedAxisCount = 1)
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.unsupported-avar-version", result.error.code)
    }

    @Test
    fun rejectsNonZeroReservedField() {
        val result = AvarReader.read(avarTable(axisCount = 1, reserved = 2), expectedAxisCount = 1)
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.invalid-avar", result.error.code)
    }

    @Test
    fun rejectsMissingZeroAnchor() {
        val result = AvarReader.read(
            avarTableWithSegments(listOf(-1f to -1f, 0.5f to 0.5f, 1f to 1f)),
            expectedAxisCount = 1,
        )
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.invalid-avar", result.error.code)
    }

    @Test
    fun rejectsMissingNegativeOneAnchor() {
        val result = AvarReader.read(
            avarTableWithSegments(listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f)),
            expectedAxisCount = 1,
        )
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.invalid-avar", result.error.code)
    }

    @Test
    fun rejectsMissingPositiveOneAnchor() {
        val result = AvarReader.read(
            avarTableWithSegments(listOf(-1f to -1f, 0f to 0f, 0.5f to 0.5f)),
            expectedAxisCount = 1,
        )
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.invalid-avar", result.error.code)
    }

    @Test
    fun rejectsNonMonotonicToCoordinates() {
        val result = AvarReader.read(
            avarTableWithSegments(listOf(-1f to -1f, 0f to 0.5f, 1f to 0.0f)),
            expectedAxisCount = 1,
        )
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.invalid-avar", result.error.code)
    }

    @Test
    fun acceptsEmptySegmentMapAsIdentityAxis() {
        val result = AvarReader.read(avarTableWithSegments(emptyList()), expectedAxisCount = 1)
        assertIs<FontOperationResult.Success<AvarData>>(result)
        assertEquals(1, result.value.axisSegmentMaps.size)
        assertEquals(0, result.value.axisSegmentMaps[0].size)
    }

    @Test
    fun rejectsTruncatedSegmentMapBody() {
        val result = AvarReader.read(avarTableWithTruncatedBody(), expectedAxisCount = 1)
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.invalid-avar", result.error.code)
    }

    @Test
    fun rejectsSourceBytesAboveLimit() {
        val result = AvarReader.read(avarTable(axisCount = 1), expectedAxisCount = 1, limits = VariationLimits(maxSourceBytes = 4))
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.resource-limit-exceeded", result.error.code)
    }

    private fun avarTable(axisCount: Int, major: Int = 1, reserved: Int = 0): ByteArray {
        val headerSize = 8
        val perAxis = 2 + 3 * 4
        val bytes = ByteArray(headerSize + axisCount * perAxis)
        writeU16(bytes, 0, major); writeU16(bytes, 2, 0); writeU16(bytes, 4, reserved); writeU16(bytes, 6, axisCount)
        var offset = headerSize
        repeat(axisCount) {
            writeU16(bytes, offset, 3); offset += 2
            writeF2Dot14(bytes, offset, -1f); writeF2Dot14(bytes, offset + 2, -1f); offset += 4
            writeF2Dot14(bytes, offset, 0f); writeF2Dot14(bytes, offset + 2, 0f); offset += 4
            writeF2Dot14(bytes, offset, 1f); writeF2Dot14(bytes, offset + 2, 1f); offset += 4
        }
        return bytes
    }

    private fun avarTableWithBrokenSegments(): ByteArray {
        val bytes = ByteArray(8 + 2 + 3 * 4)
        writeU16(bytes, 0, 1); writeU16(bytes, 2, 0); writeU16(bytes, 4, 0); writeU16(bytes, 6, 1)
        writeU16(bytes, 8, 3)
        writeF2Dot14(bytes, 10, 0f); writeF2Dot14(bytes, 12, 0f)
        writeF2Dot14(bytes, 14, -1f); writeF2Dot14(bytes, 16, -1f)
        writeF2Dot14(bytes, 18, 1f); writeF2Dot14(bytes, 20, 1f)
        return bytes
    }

    private fun avarTableWithSegments(segments: List<Pair<Float, Float>>, reserved: Int = 0): ByteArray {
        val bytes = ByteArray(8 + 2 + segments.size * 4)
        writeU16(bytes, 0, 1); writeU16(bytes, 2, 0); writeU16(bytes, 4, reserved); writeU16(bytes, 6, 1)
        writeU16(bytes, 8, segments.size)
        var offset = 10
        for ((from, to) in segments) {
            writeF2Dot14(bytes, offset, from)
            writeF2Dot14(bytes, offset + 2, to)
            offset += 4
        }
        return bytes
    }

    private fun avarTableWithTruncatedBody(): ByteArray {
        val bytes = ByteArray(8 + 2 + 4)
        writeU16(bytes, 0, 1); writeU16(bytes, 2, 0); writeU16(bytes, 4, 0); writeU16(bytes, 6, 1)
        writeU16(bytes, 8, 3)
        writeF2Dot14(bytes, 10, -1f); writeF2Dot14(bytes, 12, -1f)
        return bytes
    }

    private fun writeF2Dot14(bytes: ByteArray, offset: Int, value: Float) {
        val raw = (value * 16384f).toInt() and 0xFFFF
        bytes[offset] = ((raw ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (raw and 0xFF).toByte()
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }
}
