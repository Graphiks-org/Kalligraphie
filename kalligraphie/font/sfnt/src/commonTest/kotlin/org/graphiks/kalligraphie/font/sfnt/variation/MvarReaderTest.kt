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

    /**
     * A `valueRecordSize` wider than the 8-byte record must stride forward by the declared size and
     * ignore the extra bytes, or a later tag is read out of phase.
     */
    @Test
    fun readsValueRecordsWiderThanTheMinimalRecord() {
        val data = success(
            MvarReader.read(mvarTable("hasc" to 10, "hdsc" to 20, valueRecordSize = 10), expectedAxisCount = 1),
        )

        assertTrue(data.hasValueRecord("hasc"))
        assertTrue(data.hasValueRecord("hdsc"))
        assertEquals(10.0, data.delta("hasc", listOf(1.0)))
        assertEquals(20.0, data.delta("hdsc", listOf(1.0)))
    }

    @Test
    fun rejectsADuplicateValueTag() {
        val failure = assertIs<FontOperationResult.Failure>(
            MvarReader.read(mvarTable("hasc" to 1, "hasc" to 2), expectedAxisCount = 1),
        )
        assertEquals("font.variation.invalid-mvar", failure.error.code)
    }

    /** An inner index past the store's delta rows has no row and yields `0.0`. */
    @Test
    fun returnsZeroWhenTheValueRecordInnerIndexExceedsTheStoreRows() {
        val data = success(MvarReader.read(mvarTable("hasc" to 25, innerIndexes = listOf(5)), expectedAxisCount = 1))

        assertEquals(1, data.axisCount)
        assertEquals(0.0, data.delta("hasc", listOf(1.0)))
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

/** Builds an MVAR 1.0 table whose value records all point at a single-region delta row. */
private fun mvarTable(
    vararg records: Pair<String, Int>,
    valueRecordSize: Int = 8,
    innerIndexes: List<Int>? = null,
): ByteArray {
    val store = itemVariationStore(itemDeltas = records.map { intArrayOf(it.second) })
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
        u16(innerIndexes?.get(index) ?: index)
        repeat(valueRecordSize - 8) { u8(0) }
    }
    store.forEach { u8(it.toInt() and 0xFF) }
    return out.toByteArray()
}

/** Builds an MVAR 1.0 header with no value records and no store. */
private fun mvarHeaderOnly(): ByteArray {
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    u16(1); u16(0); u16(0)
    u16(8)
    u16(0)
    u16(0)
    return out.toByteArray()
}
