@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.variation

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.TupleVariationScalars
import org.graphiks.kalligraphie.font.sfnt.VariationRegionAxisFactor

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
    fun treatsAnInvalidBoundOrderingAsNoEffect() {
        val startAfterPeak = success(
            VariationStoreEvaluator.read(storeBytes(listOf(intArrayOf(0x4000, 0x2000, 0x4000))), 0, "CFF2"),
        )
        val peakAfterEnd = success(
            VariationStoreEvaluator.read(storeBytes(listOf(intArrayOf(0x0000, 0x4000, 0x2000))), 0, "CFF2"),
        )

        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(startAfterPeak, 0, listOf(-1.0)))
        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(startAfterPeak, 0, listOf(0.0)))
        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(startAfterPeak, 0, listOf(1.0)))
        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(peakAfterEnd, 0, listOf(0.0)))
        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(peakAfterEnd, 0, listOf(0.5)))
    }

    @Test
    fun sharesTheRegionRuleWithTheSharedFactorAndTheTupleStores() {
        val regions = listOf(
            Triple(0.0, 0.5, 1.0),
            Triple(-1.0, 1.0, 1.0),
            Triple(0.0, 1.0, 1.0),
            Triple(1.0, 0.5, 1.0),
            Triple(0.0, 1.0, 0.5),
        )
        val coordinates = listOf(-1.0, -0.5, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5)
        for ((start, peak, end) in regions) {
            val store = success(
                VariationStoreEvaluator.read(
                    storeBytes(listOf(intArrayOf(f2dot14(start), f2dot14(peak), f2dot14(end)))),
                    0,
                    "CFF2",
                ),
            )
            for (coordinate in coordinates) {
                val shared = VariationRegionAxisFactor.factor(start, peak, end, coordinate)
                assertEquals(
                    shared,
                    TupleVariationScalars.scalar(
                        listOf(coordinate),
                        doubleArrayOf(peak),
                        doubleArrayOf(start),
                        doubleArrayOf(end),
                    ),
                )
                assertContentEquals(doubleArrayOf(shared), VariationStoreEvaluator.scalars(store, 0, listOf(coordinate)))
            }
        }
    }

    @Test
    fun treatsAPeakOfZeroAsNoEffect() {
        val store = success(
            VariationStoreEvaluator.read(storeBytes(listOf(intArrayOf(0x0000, 0x0000, 0x0000))), 0, "CFF2"),
        )

        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(store, 0, listOf(0.25)))
    }

    @Test
    fun multipliesScalarsAcrossAxes() {
        val store = success(
            VariationStoreEvaluator.read(
                storeBytes(listOf(intArrayOf(0x0000, 0x4000, 0x4000, 0x0000, 0x4000, 0x4000))),
                0,
                "CFF2",
            ),
        )

        assertContentEquals(doubleArrayOf(0.0), VariationStoreEvaluator.scalars(store, 0, listOf(0.0, 0.0)))
        assertContentEquals(doubleArrayOf(0.25), VariationStoreEvaluator.scalars(store, 0, listOf(0.5, 0.5)))
        assertContentEquals(doubleArrayOf(0.5), VariationStoreEvaluator.scalars(store, 0, listOf(1.0, 0.5)))
    }

    @Test
    fun evaluatesRegionStartPeakAndEndExactly() {
        val store = success(
            VariationStoreEvaluator.read(storeBytes(listOf(intArrayOf(0x1000, 0x2000, 0x4000))), 0, "CFF2"),
        )

        assertContentEquals(doubleArrayOf(0.0), VariationStoreEvaluator.scalars(store, 0, listOf(0.25)))
        assertContentEquals(doubleArrayOf(0.5), VariationStoreEvaluator.scalars(store, 0, listOf(0.375)))
        assertContentEquals(doubleArrayOf(1.0), VariationStoreEvaluator.scalars(store, 0, listOf(0.5)))
        assertContentEquals(doubleArrayOf(0.5), VariationStoreEvaluator.scalars(store, 0, listOf(0.75)))
        assertContentEquals(doubleArrayOf(0.0), VariationStoreEvaluator.scalars(store, 0, listOf(1.0)))
    }

    @Test
    fun returnsEmptyScalarsForAnUnknownItemData() {
        val store = success(VariationStoreEvaluator.read(storeBytes(), 0, "CFF2"))

        assertContentEquals(DoubleArray(0), VariationStoreEvaluator.scalars(store, 7, listOf(0.0)))
    }

    @Test
    fun returnsEmptyScalarsForANegativeItemData() {
        val store = success(VariationStoreEvaluator.read(storeBytes(), 0, "CFF2"))

        assertContentEquals(DoubleArray(0), VariationStoreEvaluator.scalars(store, -1, listOf(0.0)))
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
    fun rejectsAnOutOfRangeRegionIndex() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(
                storeBytes(itemDataRegionIndexes = listOf(intArrayOf(3))),
                0,
                "CFF2",
            ),
        )
        assertEquals("font.variation.invalid-store", failure.error.code)
        assertEquals(
            "Item variation data references region index 3 but the store declares 1 region(s).",
            failure.error.message,
        )
    }

    @Test
    fun rejectsTooManyRegions() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(storeBytes(), 0, "CFF2", VariationStoreLimits(maxRegions = 0)),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun rejectsAnItemDataLargerThanTheRegionLimit() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(
                storeBytes(itemDataRegionIndexes = listOf(intArrayOf(0, 0))),
                0,
                "CFF2",
                VariationStoreLimits(maxRegions = 1),
            ),
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

    @Test
    fun interpolatesAWordDeltaRowAtALocation() {
        val store = success(
            VariationStoreEvaluator.read(
                storeWithDeltas(deltas = listOf(intArrayOf(100)), longWords = false, wordCount = 1),
                0,
                "HVAR",
                includeDeltas = true,
            ),
        )

        assertEquals(0.0, VariationStoreEvaluator.delta(store, 0, 0, listOf(0.0)))
        assertEquals(50.0, VariationStoreEvaluator.delta(store, 0, 0, listOf(0.5)))
        assertEquals(100.0, VariationStoreEvaluator.delta(store, 0, 0, listOf(1.0)))
    }

    @Test
    fun interpolatesALongWordDeltaRow() {
        val store = success(
            VariationStoreEvaluator.read(
                storeWithDeltas(deltas = listOf(intArrayOf(70_000)), longWords = true, wordCount = 1),
                0,
                "HVAR",
                includeDeltas = true,
            ),
        )

        assertEquals(70_000.0, VariationStoreEvaluator.delta(store, 0, 0, listOf(1.0)))
    }

    @Test
    fun signExtendsTheTrailingByteDeltas() {
        val store = success(
            VariationStoreEvaluator.read(
                storeWithDeltas(
                    regions = listOf(intArrayOf(0x0000, 0x4000, 0x4000), intArrayOf(0x0000, 0x4000, 0x4000)),
                    regionIndexes = intArrayOf(0, 1),
                    deltas = listOf(intArrayOf(5, -7)),
                    longWords = false,
                    wordCount = 1,
                ),
                0,
                "HVAR",
                includeDeltas = true,
            ),
        )

        assertEquals(-2.0, VariationStoreEvaluator.delta(store, 0, 0, listOf(1.0)))
    }

    @Test
    fun returnsTheRawDeltaRowAndItemCount() {
        val store = success(
            VariationStoreEvaluator.read(
                storeWithDeltas(deltas = listOf(intArrayOf(11), intArrayOf(-4)), longWords = false, wordCount = 1),
                0,
                "MVAR",
                includeDeltas = true,
            ),
        )

        assertEquals(2, store.itemCountAt(0))
        assertContentEquals(intArrayOf(11), store.deltaRow(0, 0))
        assertContentEquals(intArrayOf(-4), store.deltaRow(0, 1))
        assertNull(store.deltaRow(0, 2))
    }

    @Test
    fun returnsZeroForAnAbsentDeltaRow() {
        val store = success(
            VariationStoreEvaluator.read(
                storeWithDeltas(deltas = listOf(intArrayOf(100)), longWords = false, wordCount = 1),
                0,
                "HVAR",
                includeDeltas = true,
            ),
        )

        assertEquals(0.0, VariationStoreEvaluator.delta(store, 0, 7, listOf(1.0)))
        assertEquals(0.0, VariationStoreEvaluator.delta(store, 4, 0, listOf(1.0)))
    }

    @Test
    fun doesNotRequireDeltaBytesWhenDeltasAreNotRequested() {
        val store = success(VariationStoreEvaluator.read(storeBytes(), 0, "CFF2"))

        assertEquals(1, store.itemCountAt(0))
        assertNull(store.deltaRow(0, 0))
        assertEquals(0.0, VariationStoreEvaluator.delta(store, 0, 0, listOf(1.0)))
    }

    @Test
    fun rejectsAnItemCountAboveTheLimit() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(
                storeWithDeltas(deltas = listOf(intArrayOf(1), intArrayOf(2)), longWords = false, wordCount = 1),
                0,
                "HVAR",
                VariationStoreLimits(maxItemCount = 1),
                includeDeltas = true,
            ),
        )
        assertEquals("font.resource-limit-exceeded", failure.error.code)
    }

    @Test
    fun rejectsMissingDeltaBytesWhenDeltasAreRequested() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(storeBytes(), 0, "HVAR", includeDeltas = true),
        )
        assertEquals("font.variation.truncated-store", failure.error.code)
    }

    @Test
    fun rejectsAWordCountAboveTheRegionIndexCount() {
        val failure = assertIs<FontOperationResult.Failure>(
            VariationStoreEvaluator.read(
                storeWithDeltas(deltas = listOf(intArrayOf(1, 2, 3)), wordCount = 3),
                0,
                "HVAR",
                includeDeltas = true,
            ),
        )
        assertEquals("font.variation.invalid-store", failure.error.code)
    }

    @Test
    fun acceptsAnEmptyDeltaListWhenDeltasAreRequested() {
        val store = success(
            VariationStoreEvaluator.read(
                storeWithDeltas(deltas = emptyList(), longWords = false, wordCount = 1),
                0,
                "HVAR",
                includeDeltas = true,
            ),
        )

        assertEquals(0, store.itemCountAt(0))
        assertNull(store.deltaRow(0, 0))
        assertEquals(0.0, VariationStoreEvaluator.delta(store, 0, 0, listOf(1.0)))
    }

    @Test
    fun signExtendsANegativeLongWordDeltaBelowInt16Range() {
        val store = success(
            VariationStoreEvaluator.read(
                storeWithDeltas(deltas = listOf(intArrayOf(-70_000)), longWords = true, wordCount = 1),
                0,
                "HVAR",
                includeDeltas = true,
            ),
        )

        assertContentEquals(intArrayOf(-70_000), store.deltaRow(0, 0))
        assertEquals(-70_000.0, VariationStoreEvaluator.delta(store, 0, 0, listOf(1.0)))
    }

    private fun f2dot14(value: Double): Int = (value * 16_384.0).toInt()

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

private fun storeWithDeltas(
    regions: List<IntArray> = listOf(intArrayOf(0x0000, 0x4000, 0x4000)),
    regionIndexes: IntArray = intArrayOf(0),
    deltas: List<IntArray>,
    longWords: Boolean = false,
    wordCount: Int,
): ByteArray {
    val axisCount = regions.first().size / 3
    val regionListOffset = 12
    val regionListSize = 4 + regions.size * axisCount * 6
    val itemDataOffset = regionListOffset + regionListSize
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
    u16(1)
    u32(regionListOffset)
    u16(1)
    u32(itemDataOffset)
    u16(axisCount)
    u16(regions.size)
    for (region in regions) for (value in region) u16(value)
    u16(deltas.size)
    u16(if (longWords) 0x8000 or wordCount else wordCount)
    u16(regionIndexes.size)
    regionIndexes.forEach { u16(it) }
    for (row in deltas) {
        for (position in 0 until wordCount) {
            if (longWords) u32(row[position]) else u16(row[position] and 0xFFFF)
        }
        for (position in wordCount until row.size) u8(row[position] and 0xFF)
    }
    return out.toByteArray()
}
