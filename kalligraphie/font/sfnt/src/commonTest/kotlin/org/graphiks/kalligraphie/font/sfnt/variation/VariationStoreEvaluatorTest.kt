@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult

class VariationStoreEvaluatorTest {
    @Test
    fun evaluatesAPositiveRegionAcrossItsRange() {
        val store = success(VariationStoreEvaluator.read(storeBytes(), 0, "CFF2"))

        assertContentEquals(doubleArrayOf(0.0), VariationStoreEvaluator.scalars(store, 0, listOf(0.0)))
        assertContentEquals(doubleArrayOf(0.5), VariationStoreEvaluator.scalars(store, 0, listOf(0.5)))
        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(store, 0, listOf(1.0)))
    }

    @Test
    fun evaluatesANegativeRegionAcrossItsRange() {
        val store = success(
            VariationStoreEvaluator.read(storeBytes(listOf(intArrayOf(0xC000, 0xC000, 0x0000))), 0, "CFF2"),
        )

        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(store, 0, listOf(-1.0)))
        assertContentEquals(doubleArrayOf(0.5), VariationStoreEvaluator.scalars(store, 0, listOf(-0.5)))
        assertContentEquals(doubleArrayOf(0.0), VariationStoreEvaluator.scalars(store, 0, listOf(0.0)))
    }

    @Test
    fun ignoresAZeroCrossingRegion() {
        val store = success(
            VariationStoreEvaluator.read(storeBytes(listOf(intArrayOf(0xC000, 0x4000, 0x4000))), 0, "CFF2"),
        )

        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(store, 0, listOf(-1.0)))
        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(store, 0, listOf(0.0)))
        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(store, 0, listOf(1.0)))
    }

    @Test
    fun treatsAPeakOfZeroAsNoEffect() {
        val store = success(
            VariationStoreEvaluator.read(storeBytes(listOf(intArrayOf(0x0000, 0x0000, 0x0000))), 0, "CFF2"),
        )

        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(store, 0, listOf(0.25)))
    }

    @Test
    fun returnsEmptyScalarsForAnUnknownItemData() {
        val store = success(VariationStoreEvaluator.read(storeBytes(), 0, "CFF2"))

        assertContentEquals(DoubleArray(0), VariationStoreEvaluator.scalars(store, 7, listOf(0.0)))
    }

    @Test
    fun rejectsANonFormatOneStore() {
        val bytes = storeBytes().also { it[1] = 2 }

        val failure = assertIs<FontOperationResult.Failure>(VariationStoreEvaluator.read(bytes, 0, "CFF2"))
        assertEquals("font.variation.unsupported-store-format", failure.error.code)
    }

    @Test
    fun rejectsATruncatedStore() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(storeBytes().copyOf(4), 0, "CFF2"),
        )
        assertEquals("font.variation.truncated-store", failure.error.code)
    }

    @Test
    fun rejectsTooManyRegions() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(storeBytes(), 0, "CFF2", VariationStoreLimits(maxRegions = 0)),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun rejectsAStoreLargerThanTheByteLimit() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(storeBytes(), 0, "CFF2", VariationStoreLimits(maxSourceBytes = 8)),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun honoursCancellation() {
        val result = VariationStoreEvaluator.read(storeBytes(), 0, "CFF2", cancellationToken = CancellationToken.cancelled)

        assertIs<FontOperationResult.Cancelled>(result)
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value

    private fun storeBytes(
        regions: List<IntArray> = listOf(intArrayOf(0x0000, 0x4000, 0x4000)),
        itemDataRegionIndexes: List<IntArray> = listOf(intArrayOf(0)),
    ): ByteArray {
        val axisCount = regions.first().size / 3
        val regionListOffset = 8 + itemDataRegionIndexes.size * 4
        val regionListSize = 4 + regions.size * axisCount * 6
        val itemDataOffsets = ArrayList<Int>()
        var cursor = regionListOffset + regionListSize
        for (indexes in itemDataRegionIndexes) {
            itemDataOffsets.add(cursor)
            cursor += 6 + indexes.size * 2
        }
        val out = ArrayList<Byte>()
        fun u8(value: Int) { out += (value and 0xFF).toByte() }
        fun u16(value: Int) { u8(value shr 8); u8(value) }
        fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
        u16(1)
        u32(regionListOffset)
        u16(itemDataRegionIndexes.size)
        itemDataOffsets.forEach { u32(it) }
        u16(axisCount)
        u16(regions.size)
        for (region in regions) for (value in region) u16(value)
        for (indexes in itemDataRegionIndexes) {
            u16(1)
            u16(0)
            u16(indexes.size)
            for (index in indexes) u16(index)
        }
        return out.toByteArray()
    }
}
