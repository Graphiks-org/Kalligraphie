@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontOperationResult

class MvarReaderTest {
    @Test
    fun readsAValueRecordAndInterpolatesItsDelta() {
        val data = success(MvarReader.read(mvarTable("hasc" to 200), expectedAxisCount = 1))

        assertTrue(data.hasValueRecord("hasc"))
        assertFalse(data.hasValueRecord("hdsc"))
        assertEquals(0.0, data.delta("hasc", listOf(0.0)))
        assertEquals(100.0, data.delta("hasc", listOf(0.5)))
        assertEquals(200.0, data.delta("hasc", listOf(1.0)))
    }

    @Test
    fun returnsZeroForAnUnknownTag() {
        val data = success(MvarReader.read(mvarTable("hasc" to 200), expectedAxisCount = 1))

        assertEquals(0.0, data.delta("xhgt", listOf(1.0)))
    }

    @Test
    fun acceptsAnMvarWithoutValueRecords() {
        val data = success(MvarReader.read(mvarHeaderOnly(), expectedAxisCount = 1))

        assertEquals(0, data.valueRecordCount)
        assertEquals(0.0, data.delta("hasc", listOf(1.0)))
    }

    @Test
    fun rejectsUnsortedValueTags() {
        val failure = assertIs<FontOperationResult.Failure>(
            MvarReader.read(mvarTable("xhgt" to 1, "hasc" to 2), expectedAxisCount = 1),
        )
        assertEquals("font.variation.invalid-mvar", failure.error.code)
    }

    @Test
    fun rejectsAnUnsupportedVersion() {
        val table = mvarTable("hasc" to 1).also { it[1] = 3 }

        val failure = assertIs<FontOperationResult.Failure>(MvarReader.read(table, expectedAxisCount = 1))
        assertEquals("font.variation.unsupported-mvar-version", failure.error.code)
    }

    @Test
    fun rejectsAnUndersizedValueRecord() {
        // valueRecordSize is the uint16 at offset 6; force it to 4, below the 8-byte minimum.
        val table = mvarTable("hasc" to 1).also { bytes ->
            bytes[6] = 0
            bytes[7] = 4
        }

        val failure = assertIs<FontOperationResult.Failure>(MvarReader.read(table, expectedAxisCount = 1))
        assertEquals("font.variation.invalid-mvar", failure.error.code)
    }

    @Test
    fun rejectsTooManyValueRecords() {
        val failure = assertIs<FontOperationResult.Failure>(
            MvarReader.read(
                mvarTable("hasc" to 1),
                expectedAxisCount = 1,
                limits = MetricVariationLimits(maxValueRecords = 0),
            ),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun rejectsAnAxisCountMismatch() {
        val failure = assertIs<FontOperationResult.Failure>(
            MvarReader.read(mvarTable("hasc" to 1), expectedAxisCount = 2),
        )
        assertEquals("font.variation.invalid-mvar", failure.error.code)
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

/** Builds an MVAR 1.0 table whose value records all point at a single-region delta row. */
internal fun mvarTable(vararg records: Pair<String, Int>): ByteArray {
    val store = itemVariationStore(itemDeltas = records.map { intArrayOf(it.second) })
    val valueRecordSize = 8
    val headerSize = 12
    val storeOffset = headerSize + records.size * valueRecordSize
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    u16(1); u16(0); u16(0)
    u16(valueRecordSize)
    u16(records.size)
    u16(storeOffset)
    records.forEachIndexed { index, (tag, _) ->
        tag.forEach { u8(it.code) }
        u16(0)
        u16(index)
    }
    store.forEach { u8(it.toInt() and 0xFF) }
    return out.toByteArray()
}

/** Builds an MVAR 1.0 header with no value records and no store. */
internal fun mvarHeaderOnly(): ByteArray {
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    u16(1); u16(0); u16(0)
    u16(8)
    u16(0)
    u16(0)
    return out.toByteArray()
}
