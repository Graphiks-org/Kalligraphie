@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontVariationCoordinate

class FvarReaderTest {
    @Test
    fun readsAxesAndInstancesInDesignCoordinates() {
        val result = FvarReader.read(fvarTable())
        assertIs<FontOperationResult.Success<FvarData>>(result)
        val data = result.value
        assertEquals(listOf("opsz", "wght"), data.axes.map { it.tag })
        assertEquals(8f, data.axes[0].minValue)
        assertEquals(14f, data.axes[0].defaultValue)
        assertEquals(144f, data.axes[0].maxValue)
        assertEquals(100f, data.axes[1].minValue)
        assertEquals(400f, data.axes[1].defaultValue)
        assertEquals(900f, data.axes[1].maxValue)
        val instance = data.instances.single()
        assertEquals(258, instance.subfamilyNameId)
        assertEquals(
            listOf(FontVariationCoordinate("opsz", 14f), FontVariationCoordinate("wght", 700f)),
            instance.coordinates,
        )
    }

    @Test
    fun rejectsTruncatedHeader() {
        assertIs<FontOperationResult.Failure>(FvarReader.read(ByteArray(8)))
    }

    @Test
    fun rejectsUnsupportedMajorVersion() {
        val table = fvarTable()
        table[0] = 0
        table[1] = 2
        val result = FvarReader.read(table)
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.variation.unsupported-fvar-version", result.error.code)
    }

    @Test
    fun rejectsAxisCountAboveLimit() {
        val result = FvarReader.read(fvarTable(), VariationLimits(maxAxes = 1))
        assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.resource-limit-exceeded", result.error.code)
    }

    private fun fvarTable(): ByteArray {
        val axesOffset = 16
        val axisCount = 2
        val axisSize = 20
        val instanceCount = 1
        val instanceSize = axisCount * 4 + 4
        val bytes = ByteArray(axesOffset + axisCount * axisSize + instanceCount * instanceSize)
        writeU16(bytes, 0, 1)
        writeU16(bytes, 2, 0)
        writeU16(bytes, 4, axesOffset)
        writeU16(bytes, 6, 2)
        writeU16(bytes, 8, axisCount)
        writeU16(bytes, 10, axisSize)
        writeU16(bytes, 12, instanceCount)
        writeU16(bytes, 14, instanceSize)
        var offset = axesOffset
        writeTag(bytes, offset, "opsz"); writeFixed(bytes, offset + 4, 8f); writeFixed(bytes, offset + 8, 14f)
        writeFixed(bytes, offset + 12, 144f); writeU16(bytes, offset + 16, 0); writeU16(bytes, offset + 18, 256)
        offset += axisSize
        writeTag(bytes, offset, "wght"); writeFixed(bytes, offset + 4, 100f); writeFixed(bytes, offset + 8, 400f)
        writeFixed(bytes, offset + 12, 900f); writeU16(bytes, offset + 16, 1); writeU16(bytes, offset + 18, 257)
        offset += axisSize
        writeU16(bytes, offset, 258)
        writeU16(bytes, offset + 2, 0)
        writeFixed(bytes, offset + 4, 14f)
        writeFixed(bytes, offset + 8, 700f)
        return bytes
    }

    private fun writeTag(bytes: ByteArray, offset: Int, tag: String) {
        for (index in 0 until 4) bytes[offset + index] = tag[index].code.toByte()
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeFixed(bytes: ByteArray, offset: Int, value: Float) {
        val raw = (value * 65536f).toInt()
        bytes[offset] = ((raw ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((raw ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((raw ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (raw and 0xFF).toByte()
    }
}
